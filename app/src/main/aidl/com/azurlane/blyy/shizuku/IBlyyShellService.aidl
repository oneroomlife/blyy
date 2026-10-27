package com.azurlane.blyy.shizuku;

import android.os.ParcelFileDescriptor;

/**
 * 以 Shizuku（ADB/root 身份）运行的 shell 辅助服务。
 *
 * 用途：读取碧蓝航线放在 Android/data/com.bilibili.azurlane 下的 Live2D 资源包。
 * 普通应用无权限访问其它应用的 Android/data 目录，而 Shizuku 以 shell 身份运行，
 * 拥有与 `adb shell` 相同的读取能力。
 */
interface IBlyyShellService {

    /** 连通性探测，返回版本描述 */
    String ping();

    /**
     * 递归列举目录下的文件。
     *
     * @param root       起始目录
     * @param maxDepth   最大递归深度（1 = 只看当前层）
     * @param maxEntries 最多返回的条目数（防止超大目录拖垮 binder）
     * @return 每行一条：`类型\t路径\t大小\t修改时间`，类型 f=文件 d=目录
     */
    String listFiles(String root, int maxDepth, int maxEntries);

    /**
     * 分页列举目录（解决超大目录撑爆 Binder 事务的问题）。
     *
     * `AssetBundles/painting` 这类目录有 1 万+ 文件，一次性返回约 1.3MB，
     * 会直接触发 `TransactionTooLargeException`（表现为 DeadObjectException）。
     * 服务端把整棵目录树缓存成快照，按页返回小块，调用方循环取完。
     *
     * @param root      起始目录
     * @param maxDepth  最大递归深度
     * @param offset    起始条目下标（0 = 从头开始）
     * @param pageSize  本页最多条目数
     * @param maxBytes  本页文本字节预算（服务端仍会保证不超过 Binder 上限）
     * @param refresh   true = 丢弃快照并重建（每次扫描的第一页传 true）
     * @return 首行 `#total=<总条目数>`，其后每行同 [listFiles]
     */
    String listFilesPage(String root, int maxDepth, int offset, int pageSize, int maxBytes, boolean refresh);

    /** 判断路径是否存在 */
    boolean exists(String path);

    /** 读取文件片段，返回实际读取的字节数；失败返回 -1 */
    int readRange(String path, long offset, int length, inout byte[] buffer);

    /**
     * 把 source 拷贝到应用已经打开的 destination。
     * 目标 fd 由应用传入，因此服务端不需要对应用目录有写权限。
     *
     * @return 拷贝的字节数；失败返回 -1
     */
    long copyTo(String source, in ParcelFileDescriptor destination);

    /** 文件大小（不存在返回 -1） */
    long sizeOf(String path);

    /** 最后修改时间（毫秒；不存在返回 0） */
    long lastModified(String path);
}
