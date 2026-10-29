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

        /** 绑定重试次数：远端进程刚被回收时，第二次基本都能成 */
        private const val MAX_BIND_ATTEMPTS = 2
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

    private val permissionListeners =
        HashMap<(requestCode: Int, grantResult: Int) -> Unit, Shizuku.OnRequestPermissionResultListener>()

    fun addPermissionResultListener(listener: ((requestCode: Int, grantResult: Int) -> Unit)) {
        runCatching {
            // Shizuku 的移除按对象身份匹配：这里必须保住注册时实际挂上去的实例，
            // 否则配对的 remove 拿着原始 lambda 永远移除不掉，每次进页泄漏一个监听
            val registered = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                listener(requestCode, grantResult)
            }
            Shizuku.addRequestPermissionResultListener(registered)
            synchronized(permissionListeners) { permissionListeners[listener] = registered }
        }
    }

    /** 与 [addPermissionResultListener] 配对：按注册时的原始引用移除监听 */
    fun removePermissionResultListener(listener: (requestCode: Int, grantResult: Int) -> Unit) {
        val registered = synchronized(permissionListeners) { permissionListeners.remove(listener) }
            ?: return
        runCatching { Shizuku.removeRequestPermissionResultListener(registered) }
    }

    /**
     * 丢弃当前代理（远端进程死亡后必须重建，否则会一直抛 DeadObjectException）。
     * 只清引用，不解绑：Shizuku 会自行处理进程回收。
     */
    fun invalidate() {
        service = null
    }

    /**
     * 彻底重置通道：丢弃缓存代理 + 松开等待中的绑定。
     *
     * 用户点「重试」或 Shizuku 服务重启后调用；不清 `pending` 的话，
     * 上一次超时的等待会一直被复用，重试也永远等不到结果。
     */
    fun reset() {
        release()
    }

    private fun alive(svc: IBlyyShellService?): Boolean =
        svc != null && runCatching { svc.asBinder().isBinderAlive }.getOrDefault(false)

    /**
     * 绑定并返回服务；不可用时抛出可读异常。
     *
     * 绑定成功 ≠ 服务可用：Shizuku 在**独立进程**里反射实例化服务实现类，
     * 若该类被裁剪/混淆掉构造器（release 包常见），`bindUserService` 照样返回成功，
     * 但等待回调永远不来。因此这里拿到代理后先做一次真实 `ping()` 往返，
     * 把它当成「通道健康」的唯一判据。
     */
    suspend fun requireService(): IBlyyShellService = withContext(Dispatchers.IO) {
        // 远端进程可能在「大目录列举撑爆 Binder」后死亡：缓存代理必须先验证存活
        service?.let { if (alive(it)) return@withContext it else service = null }
        if (!isAvailable()) throw IOException("Shizuku 未运行，请先启动 Shizuku 并授权本应用")
        if (!isPermissionGranted()) throw SecurityException("尚未授予 Shizuku 权限")

        var failure: Throwable? = null
        for (attempt in 1..MAX_BIND_ATTEMPTS) {
            try {
                val svc = bindOnce()
                val reply = svc.ping()
                if (reply.isBlank()) throw IOException("Shizuku 服务端无应答")
                Log.i(TAG, "Shizuku 通道可用：$reply（第 $attempt 次绑定）")
                return@withContext svc
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                failure = t
                Log.w(TAG, "绑定 Shizuku 服务失败（第 $attempt 次）：${t.javaClass.simpleName} ${t.message}")
                // 远端可能刚崩 / 上一次绑定半途而废：解绑后再从头绑一次
                release()
            }
        }
        throw IOException(describeFailure(failure))
    }

    /** 把底层异常翻译成用户能看懂、能行动的一句话 */
    private fun describeFailure(t: Throwable?): String {
        val raw = t?.message?.takeIf { it.isNotBlank() } ?: t?.javaClass?.simpleName ?: "未知原因"
        return when (t) {
            null -> "连接 Shizuku 服务失败"
            is NoSuchMethodError, is NoClassDefFoundError, is AbstractMethodError ->
                "Shizuku 服务实现类不完整（安装包被裁剪或二次精简），请安装完整版安装包：$raw"
            else -> "连接 Shizuku 服务失败：$raw"
        }
    }

    /** 单次绑定：并发调用共享同一个等待中的 deferred */
    private suspend fun bindOnce(): IBlyyShellService {
        // 并发调用（四类目录同时列举）必须共享同一个等待中的绑定：
        // 各自 new 一个 deferred 会互相覆盖，先到的那个永远等不到结果，只能超时。
        val deferred: CompletableDeferred<IBlyyShellService?> = synchronized(lock) {
            pending ?: CompletableDeferred<IBlyyShellService?>().also { created ->
                pending = created
                if (!binding) {
                    binding = true
                    val result = runCatching { Shizuku.bindUserService(args, connection) }
                    if (result.isFailure) {
                        binding = false
                        pending = null
                        throw IOException(
                            "无法绑定 Shizuku 服务：${result.exceptionOrNull()?.message ?: "未知原因"}"
                        )
                    }
                }
            }
        }
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            ?: throw IOException(
                if (deferred.isCompleted) {
                    // 回调以 null 完成（服务端断开 / 通道被重置），不是超时，别误导用户
                    "Shizuku 服务连接中断（服务被回收或被重置），请点重试"
                } else {
                    "连接 Shizuku 服务超时（可能被系统回收或服务端无法实例化，请点重试）"
                }
            )
    }

    /**
     * 解绑（退出扫描页 / 重试前调用，避免常驻进程与「半绑定」状态）。
     *
     * 即使 `service` 为空也要尝试解绑：绑定失败的重试路径里，代理可能还没回来，
     * 但远端已经登记过这次绑定，不解绑会让下一次 `bindUserService` 撞上重复绑定。
     *
     * `pending` 必须一并 complete 掉：否则重试路径（`requireService` 失败后 release
     * 再绑）会复用同一个永不完成的 deferred，两次重试都只能等满超时。
     */
    fun release() {
        service = null
        synchronized(lock) {
            pending?.complete(null)
            pending = null
            binding = false
        }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
}
