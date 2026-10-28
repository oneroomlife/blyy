package com.azurlane.blyy.util.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.azurlane.blyy.BuildConfig
import com.azurlane.blyy.shizuku.IBlyyShellService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 接入层：权限申请 + UserService 绑定 + 掉线重连。
 *
 * 为什么需要它：碧蓝航线把 Live2D 资源包放在
 * `/sdcard/Android/data/com.bilibili.azurlane/files/AssetBundles/…`，
 * Android 11+ 的普通应用无法直接读取；Shizuku 以 shell 身份运行，
 * 拥有与 `adb shell` 相同的文件视图，因此可以列出目录并把资源包交给本应用。
 */
@Singleton
class ShizukuBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ShizukuBridge"
        const val REQUEST_PERMISSION_CODE = 0x5348
        private const val BIND_TIMEOUT_MS = 8_000L
    }

    private val args: Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, BlyyShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("live2d_shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag("blyy-live2d-shell")

    @Volatile
    private var service: IBlyyShellService? = null

    @Volatile
    private var binding = false

    private var pending: CompletableDeferred<IBlyyShellService?>? = null

    private val lock = Any()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = binder?.let { IBlyyShellService.Stub.asInterface(it) }
            service = svc
            synchronized(lock) {
                binding = false
                pending?.complete(svc)
                pending = null
            }
            Log.i(TAG, "Shizuku UserService 已连接：${svc?.let { runCatching { it.ping() }.getOrNull() }}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            synchronized(lock) {
                binding = false
                pending?.complete(null)
                pending = null
            }
            Log.w(TAG, "Shizuku UserService 已断开")
        }
    }

    /** Shizuku 服务是否在运行（用户是否启动过 Shizuku / 是否已连上 ADB） */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已授予本应用 Shizuku 权限 */
    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 版本描述；不支持时返回 null */
    fun versionLabel(): String? = runCatching {
        if (Shizuku.isPreV11()) "v10（版本过低）" else "v${Shizuku.getVersion()}"
    }.getOrNull()

    /** 是否需要展示「为什么需要这个权限」 */
    fun requiresRationale(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    /** 拉起授权弹窗；结果通过 [addPermissionResultListener] 回调 */
    fun requestPermission(): Boolean = runCatching {
        Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        true
    }.getOrDefault(false)

    fun addPermissionResultListener(listener: ((requestCode: Int, grantResult: Int) -> Unit)) {
        runCatching {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                listener(requestCode, grantResult)
            }
        }
    }

    /**
     * 丢弃当前代理（远端进程死亡后必须重建，否则会一直抛 DeadObjectException）。
     * 只清引用，不解绑：Shizuku 会自行处理进程回收。
     */
    fun invalidate() {
        service = null
    }

    private fun alive(svc: IBlyyShellService?): Boolean =
        svc != null && runCatching { svc.asBinder().isBinderAlive }.getOrDefault(false)

    /** 绑定并返回服务；不可用时抛出可读异常 */
    suspend fun requireService(): IBlyyShellService = withContext(Dispatchers.IO) {
        // 远端进程可能在「大目录列举撑爆 Binder」后死亡：缓存代理必须先验证存活
        service?.let { if (alive(it)) return@withContext it else service = null }
        if (!isAvailable()) throw IOException("Shizuku 未运行，请先启动 Shizuku 并授权本应用")
        if (!isPermissionGranted()) throw SecurityException("尚未授予 Shizuku 权限")

        // 并发调用（四类目录同时列举）必须共享同一个等待中的绑定：
        // 各自 new 一个 deferred 会互相覆盖，先到的那个永远等不到结果，只能超时。
        val deferred: CompletableDeferred<IBlyyShellService?> = synchronized(lock) {
            pending ?: CompletableDeferred<IBlyyShellService?>().also { created ->
                pending = created
                if (!binding) {
                    binding = true
                    val ok = runCatching { Shizuku.bindUserService(args, connection) }.isSuccess
                    if (!ok) {
                        binding = false
                        pending = null
                        throw IOException("无法绑定 Shizuku 服务")
                    }
                }
            }
        }
        val svc = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        svc ?: throw IOException("连接 Shizuku 服务超时（可能被系统回收，请重试）")
    }

    /** 解绑（退出扫描页时调用，避免常驻进程） */
    fun release() {
        val svc = service
        service = null
        if (svc == null) return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        synchronized(lock) { binding = false }
    }
}
