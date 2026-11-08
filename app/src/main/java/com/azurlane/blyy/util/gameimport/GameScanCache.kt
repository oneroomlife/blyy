package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 扫描缓存里的一条「远端资源包」物理信息。
 *
 * 刻意**不存**导入状态（NEW / IMPORTED / CHANGED / FAILED）：状态取决于索引与
 * 本地资源库，跨进程重启、刚导入完、刚删库都会变。存下来只会在
 * 「刚导入完再进页面却显示没导入」这种地方骗人，所以读取时按当前索引重算。
 */
data class CachedBundleEntry(
    val kind: GameAssetKind,
    val path: String,
    val size: Long,
    val modified: Long,
    val assetId: String,
    val companions: List<String>
)

/** 一次完整扫描的落盘快照 */
data class CachedScan(
    val roots: Map<GameAssetKind, String>,
    val entries: List<CachedBundleEntry>,
    val savedAt: Long
)

/**
 * 游戏资源目录扫描结果的本地快照。
 *
 * 存在的原因：`AssetBundles/painting` 有 1 万多个文件，每次进页面通过 Shizuku
 * 重新列举要 2 秒上下（实测「列举 1476ms + 附属包索引 309ms」）。用户看到的
 * 就是「每次点都要转圈等」。把上次的结果落盘后，进页面可以**先**把完整列表
 * 铺出来，再在后台重新扫描校正。
 *
 * 格式：**紧凑二进制**（`DataOutputStream`/`readUTF`），不是文本。理由：
 * 1. 6400 条里有 15700 个附属包路径、合计 130 万字符，写成文本单文件就有 2.1MB，
 *    在设备上一遍「读 2MB + 逐行 split」要 460~470ms（debug 构建实测）；
 * 2. 附属包路径高度重复（`commonui_atlas.ys` 在 4486 条里各出现一次），
 *    走**字符串表去重**后整份快照从 2.1MB 降到约 0.6MB，解析也退化成整数查表。
 * 3. 路径统一按「所有扫描根的共同父目录」记相对路径（同一段
 *    `/sdcard/Android/data/com.bilibili.azurlane/files/AssetBundles/` 只出现一次），
 *    相对路径不以 `/` 开头，绝对路径一定以 `/` 开头，读的时候据此还原。
 *
 * 布局：
 * ```
 * int  MAGIC ("BLYC")
 * int  VERSION
 * long savedAt
 * int  kindCount, then { UTF kindKey, UTF rootPath(可为空) } × kindCount
 * int  stringCount, then { UTF s } × stringCount        // s 相对 base，或绝对路径
 * int  entryCount, then { u8 kindIndex, int pathIndex, long size, long modified,
 *                         int assetIndex, int companionCount, int companionIndex × N }
 * ```
 *
 * 文件损坏/版本不符/体积异常时直接当作「没有缓存」：缓存只是加速手段，
 * 不能影响正确性。
 */
@Singleton
class GameScanCache @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "GameScanCache"
        private const val FILE_NAME = "game_scan_cache.bin"

        /** 旧版文本快照的文件名，升级后顺手删掉 */
        private const val LEGACY_FILE_NAME = "game_scan_cache.txt"
        private const val MAGIC = 0x424C5943 // "BLYC"
        private const val VERSION = 2

        /** 单次快照最多记录多少条（防御异常大的目录把文件写爆） */
        private const val MAX_ENTRIES = 60_000

        /** 单份快照字符串表上限（正常 1.7 万个） */
        private const val MAX_STRINGS = 400_000

        /** 超过这个体积的快照直接忽略（正常 6400 条约 0.6MB） */
        private const val MAX_BYTES = 32L * 1024 * 1024

        /** 剥前缀时要求的最短公共父目录，太短（如 "/"）就不剥 */
        private const val MIN_BASE_LENGTH = 8
    }

    private val mutex = Mutex()

    /** 进程内缓存：同一次会话里反复进出页面不必重复解析文件 */
    private var cached: CachedScan? = null
    private var loaded = false

    private fun file(): File = File(context.filesDir, FILE_NAME)

    /** 读取上次扫描快照；没有 / 损坏 / 版本不符都返回 null */
    suspend fun load(): CachedScan? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                val f = file()
                cached = if (f.isFile && f.length() in 1..MAX_BYTES) {
                    runCatching { decode(f) }
                        .onFailure { Log.w(TAG, "读取扫描缓存失败：${it.message}") }
                        .getOrNull()
                } else {
                    null
                }
                loaded = true
                if (cached != null) Log.i(TAG, "载入扫描缓存 ${cached?.entries?.size ?: 0} 条")
            }
            cached
        }
    }

    /** 扫描完成后覆盖写入；失败只记日志（不影响扫描结果本身） */
    suspend fun save(roots: Map<GameAssetKind, String>, entries: List<GameBundleEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val data = CachedScan(
                    roots = roots,
                    entries = entries.take(MAX_ENTRIES).map {
                        CachedBundleEntry(it.kind, it.path, it.size, it.modified, it.assetId, it.companions)
                    },
                    savedAt = System.currentTimeMillis()
                )
                cached = data
                loaded = true
                val target = file()
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                runCatching {
                    DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                        encode(data, out)
                    }
                    // 先写临时文件再改名：写到一半被杀掉也不会留下半个快照
                    if (target.exists() && !target.delete()) {
                        throw IllegalStateException("无法删除旧缓存 ${target.absolutePath}")
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    // 旧版文本快照已经没有用了，顺手清掉
                    runCatching { File(target.parentFile, LEGACY_FILE_NAME).delete() }
                }.onFailure {
                    Log.w(TAG, "写入扫描缓存失败：${it.message}")
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    /** 丢弃快照（清空索引 / 卸载游戏后调用） */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cached = null
            loaded = true
            runCatching { file().delete() }
            runCatching { File(context.filesDir, LEGACY_FILE_NAME).delete() }
            Unit
        }
    }

    // ---------------------------------------------------------------- 二进制编解码

    private fun encode(data: CachedScan, out: DataOutputStream) {
        // 所有扫描根的共同父目录：路径按它记相对形式，整份快照里那段最长的公共前缀只留一次
        val base = commonBase(data.roots.values)

        val kinds = LinkedHashSet<GameAssetKind>(8)
        kinds.addAll(data.roots.keys)
        for (e in data.entries) kinds.add(e.kind)
        val kindIndex = HashMap<GameAssetKind, Int>(kinds.size * 2)
        var k = 0
        for (kind in kinds) kindIndex[kind] = k++

        // 字符串表：路径 / assetId / 附属包都走它，重复的只存一份
        val strings = HashMap<String, Int>(data.entries.size * 4)
        val pool = ArrayList<String>(data.entries.size * 4)
        fun indexOf(value: String): Int {
            val key = relative(value, base)
            strings[key]?.let { return it }
            pool.add(key)
            val id = pool.size - 1
            strings[key] = id
            return id
        }

        val pathIds = IntArray(data.entries.size)
        val assetIds = IntArray(data.entries.size)
        val companionIds = arrayOfNulls<IntArray>(data.entries.size)
        for ((i, e) in data.entries.withIndex()) {
            pathIds[i] = indexOf(e.path)
            assetIds[i] = indexOf(e.assetId)
            companionIds[i] = IntArray(e.companions.size) { indexOf(e.companions[it]) }
        }

        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(data.savedAt)

        out.writeInt(kinds.size)
        for (kind in kinds) {
            out.writeUTF(kind.key)
            out.writeUTF(data.roots[kind].orEmpty())
        }

        out.writeInt(pool.size)
        for (s in pool) out.writeUTF(s)

        out.writeInt(data.entries.size)
        for ((i, e) in data.entries.withIndex()) {
            out.writeByte(kindIndex[e.kind] ?: 0)
            out.writeInt(pathIds[i])
            out.writeLong(e.size)
            out.writeLong(e.modified)
            out.writeInt(assetIds[i])
            val companions = companionIds[i] ?: IntArray(0)
            out.writeInt(companions.size)
            for (id in companions) out.writeInt(id)
        }
    }

    private fun decode(f: File): CachedScan? {
        DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != VERSION) return null
            val savedAt = input.readLong()

            val kindCount = input.readInt()
            if (kindCount !in 1..64) return null
            val kinds = ArrayList<GameAssetKind>(kindCount)
            val roots = LinkedHashMap<GameAssetKind, String>(kindCount * 2)
            repeat(kindCount) {
                val key = input.readUTF()
                val root = input.readUTF()
                val kind = GameAssetKind.fromKey(key) ?: return null
                kinds.add(kind)
                if (root.isNotEmpty()) roots[kind] = root
            }

            val base = commonBase(roots.values)
            val stringCount = input.readInt()
            if (stringCount !in 0..MAX_STRINGS) return null
            val pool = ArrayList<String>(stringCount)
            repeat(stringCount) {
                val value = input.readUTF()
                pool.add(
                    when {
                        value.startsWith("/") -> value
                        base.isEmpty() -> value
                        else -> "$base/$value"
                    }
                )
            }

            val entryCount = input.readInt()
            if (entryCount !in 1..MAX_ENTRIES) return null
            val entries = ArrayList<CachedBundleEntry>(entryCount)
            repeat(entryCount) {
                val kindOrdinal = input.readUnsignedByte()
                if (kindOrdinal >= kinds.size) return null
                val kind = kinds[kindOrdinal]
                val path = pool.getOrNull(input.readInt()) ?: return null
                val size = input.readLong()
                val modified = input.readLong()
                val assetId = pool.getOrNull(input.readInt()) ?: return null
                val companionCount = input.readInt()
                if (companionCount !in 0..512) return null
                val companions = if (companionCount == 0) {
                    emptyList()
                } else {
                    val list = ArrayList<String>(companionCount)
                    repeat(companionCount) {
                        list.add(pool.getOrNull(input.readInt()) ?: return null)
                    }
                    list
                }
                entries.add(CachedBundleEntry(kind, path, size, modified, assetId, companions))
            }
            if (entries.isEmpty()) return null
            return CachedScan(roots, entries, savedAt)
        }
    }

    /** 所有扫描根的共同父目录（`…/AssetBundles/painting` 与 `…/AssetBundles/char` → `…/AssetBundles`） */
    private fun commonBase(roots: Collection<String>): String {
        if (roots.isEmpty()) return ""
        var prefix: String? = null
        for (root in roots) {
            if (root.isEmpty()) continue
            prefix = prefix?.let { commonPrefix(it, root) } ?: root
        }
        val shared = prefix ?: return ""
        val base = shared.substringBeforeLast('/', "")
        return if (base.length >= MIN_BASE_LENGTH) base else ""
    }

    private fun commonPrefix(a: String, b: String): String {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return a.substring(0, i)
    }

    private fun relative(path: String, base: String): String {
        if (base.isEmpty() || !path.startsWith(base)) return path
        val cut = path.substring(base.length).removePrefix("/")
        return if (cut.isEmpty()) path else cut
    }
}
