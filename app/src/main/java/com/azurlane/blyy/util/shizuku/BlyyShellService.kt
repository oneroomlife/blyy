package com.azurlane.blyy.util.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.azurlane.blyy.shizuku.IBlyyShellService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 运行在 Shizuku 提供的独立进程里的 shell 服务（UID = shell/root）。
 *
 * 关键点：
 * - 构造函数必须是 `(Context)`（Shizuku v13 优先用它），不能被混淆掉；
 * - 只做「文件系统视图」相关的事，不做任何 UI 或应用状态；
 * - 写入应用目录时通过调用方传入的 `ParcelFileDescriptor` 完成，
 *   服务端无需对应用私有目录有写权限。
 */
class BlyyShellService(@Suppress("UNUSED_PARAMETER") context: Context) :
    IBlyyShellService.Stub() {

    companion object {
        private const val TAG = "BlyyShellService"
        private const val COPY_BUFFER = 1 shl 20

        /** 单份快照最多条目数（防止异常目录把服务端内存打爆） */
        private const val MAX_SNAPSHOT_ENTRIES = 200_000

        /** 同时缓存几份目录快照（四类资源 + 余量） */
        private const val MAX_SNAPSHOTS = 8
    }

    /**
     * 目录列表快照：超大目录分页读时只走一次文件系统。
     *
     * 按 `root+depth` 缓存**多份**：客户端的四类目录（live2d / char / spinepainting /
     * painting）是并发列举的，单槽缓存会被互相顶掉，每次都重建整棵目录
     * （painting 有 1 万+ 文件，实测单次 0.5s）。
     */
    private val snapshots = object : LinkedHashMap<String, List<String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > MAX_SNAPSHOTS
    }

    private fun snapshotKey(root: String, depth: Int) = "$depth|$root"

    override fun ping(): String {
        val uid = android.os.Process.myUid()
        return "blyy-shell uid=$uid"
    }

    override fun listFiles(root: String, maxDepth: Int, maxEntries: Int): String {
        val lines = buildListing(root, maxDepth, maxEntries.coerceIn(1, MAX_SNAPSHOT_ENTRIES))
        val out = StringBuilder(lines.size * 80)
        lines.forEach { out.append(it).append('\n') }
        return out.toString()
    }

    /**
     * 分页列举。
     *
     * 返回首行 `#total=<n>` 便于调用方判断是否取完；随后是本页条目。
     * 快照按 `root + maxDepth` 复用，`refresh = true` 时重建。
     */
    override fun listFilesPage(
        root: String,
        maxDepth: Int,
        offset: Int,
        pageSize: Int,
        maxBytes: Int,
        refresh: Boolean
    ): String {
        val depth = maxDepth.coerceIn(1, 12)
        val page = pageSize.coerceIn(1, 4_000)
        val budget = maxBytes.coerceIn(8_000, 256_000)

        val key = snapshotKey(root, depth)
        // 只在取/存快照时加锁：构建过程（可能几百毫秒）不占锁，
        // 否则四类目录的并发列举会被这把锁重新串起来
        val cached = synchronized(snapshots) { if (refresh) null else snapshots[key] }
        val lines = cached ?: buildListing(root, depth, MAX_SNAPSHOT_ENTRIES).also { built ->
            synchronized(snapshots) { snapshots[key] = built }
        }

        val total = lines.size
        val start = offset.coerceIn(0, total)
        val out = StringBuilder(minOf(budget, 64_000))
        out.append("#total=").append(total).append('\n')
        var index = start
        var bytes = 0
        while (index < total && index - start < page) {
            val line = lines[index]
            val cost = line.length + 1
            if (bytes + cost > budget && index > start) break
            out.append(line).append('\n')
            bytes += cost
            index++
        }
        return out.toString()
    }

    /** 递归列举，返回 `类型\t路径\t大小\t修改时间` 行列表（广度优先） */
    private fun buildListing(root: String, maxDepth: Int, maxEntries: Int): List<String> {
        val started = android.os.SystemClock.elapsedRealtime()
        val dir = File(root)
        if (!dir.exists()) return emptyList()
        val depthLimit = maxDepth.coerceIn(1, 12)
        val limit = maxEntries.coerceIn(1, MAX_SNAPSHOT_ENTRIES)
        val out = ArrayList<String>(minOf(limit, 4_096))
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(dir to 0)
        while (queue.isNotEmpty() && out.size < limit) {
            val (current, depth) = queue.removeFirst()
            val children = try {
                current.listFiles()
            } catch (e: Exception) {
                Log.w(TAG, "列举失败：${current.absolutePath} ${e.message}")
                null
            } ?: continue
            for (child in children) {
                if (out.size >= limit) break
                val isDir = try {
                    child.isDirectory
                } catch (e: Exception) {
                    false
                }
                val size = if (isDir) 0L else runCatching { child.length() }.getOrDefault(0L)
                val mtime = runCatching { child.lastModified() }.getOrDefault(0L)
                out.add(
                    buildString(96) {
                        append(if (isDir) 'd' else 'f').append('\t')
                        append(child.absolutePath).append('\t')
                        append(size).append('\t')
                        append(mtime)
                    }
                )
                if (isDir && depth + 1 < depthLimit) queue.addLast(child to depth + 1)
            }
        }
        Log.d(TAG, "buildListing ${File(root).name} ${out.size} 项 ${android.os.SystemClock.elapsedRealtime() - started}ms")
        return out
    }

    override fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    override fun readRange(path: String, offset: Long, length: Int, buffer: ByteArray): Int {
        if (length <= 0 || buffer.isEmpty()) return 0
        return try {
            java.io.RandomAccessFile(path, "r").use { raf ->
                raf.seek(offset)
                val want = minOf(length, buffer.size)
                val n = raf.read(buffer, 0, want)
                if (n < 0) 0 else n
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取失败：$path +$offset/$length ${e.message}")
            -1
        }
    }

    override fun copyTo(source: String, destination: ParcelFileDescriptor): Long {
        val src = File(source)
        if (!src.isFile) return -1
        var total = 0L
        return try {
            FileInputStream(src).use { input ->
                // 注意：不要 close 这个流，否则会连带关闭调用方传入的 fd
                val output = FileOutputStream(destination.fileDescriptor)
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    total += n
                }
                output.flush()
            }
            total
        } catch (e: Exception) {
            Log.w(TAG, "拷贝失败：$source ${e.message}")
            -1
        }
    }

    override fun sizeOf(path: String): Long = runCatching {
        val f = File(path)
        if (f.isFile) f.length() else -1L
    }.getOrDefault(-1L)

    override fun lastModified(path: String): Long = runCatching { File(path).lastModified() }
        .getOrDefault(0L)
}
