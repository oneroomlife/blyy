package com.azurlane.blyy.util

import android.net.Uri

/**
 * 防盗链 Referer 解析器：根据资源 URL 域名返回对应的 Referer 头。
 *
 * 不同站点的图片/音频/视频资源有各自的防盗链策略，缺少或错误 Referer 会导致 HTTP 567/403：
 * - gamekee.com 系列 CDN → 需要 `https://www.gamekee.com/`
 * - biligame.com / hdslb.com 系列 CDN → 需要 `https://wiki.biligame.com/`
 * - 其他域名 → 通用 `https://www.google.com/`（部分 CDN 接受任意非空 Referer）
 *
 * 集中管理避免在多处重复 when 分支，新增站点只需修改此处。
 */
object RefererResolver {

    /** gamekee 站点 Referer */
    const val GAMEKEE_REFERER = "https://www.gamekee.com/"
    /** B站 wiki 站点 Referer */
    const val BILIGAME_REFERER = "https://wiki.biligame.com/"
    /** 通用 Referer 兜底 */
    const val DEFAULT_REFERER = "https://www.google.com/"

    /**
     * 根据主机名返回对应的 Referer。
     *
     * @param host URL 主机名（如 `patchwiki.biligame.com`），空字符串返回默认 Referer
     * @return 匹配的 Referer URL
     */
    fun getRefererByHost(host: String): String = when {
        host.contains("gamekee") -> GAMEKEE_REFERER
        host.contains("biligame") || host.contains("hdslb") -> BILIGAME_REFERER
        else -> DEFAULT_REFERER
    }

    /**
     * 根据完整 URL 返回对应的 Referer。
     *
     * @param url 完整资源 URL
     * @return 匹配的 Referer URL，URL 解析失败时返回默认 Referer
     */
    fun getRefererByUrl(url: String): String {
        val host = try {
            Uri.parse(url).host ?: return DEFAULT_REFERER
        } catch (e: Exception) {
            return DEFAULT_REFERER
        }
        return getRefererByHost(host)
    }
}
