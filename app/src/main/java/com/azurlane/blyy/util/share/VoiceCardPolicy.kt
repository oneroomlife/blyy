package com.azurlane.blyy.util.share

/**
 * 台词语音卡的**纯策略层**：URL 可用性、缓存文件名、文案截断。
 *
 * 为什么单独抽出来：
 * - 合成器（[VoiceCardComposer] / [VoiceCardHtmlComposer]）与分享出口（[VoiceShareLauncher]）
 *   各自实现了同一套 `sanitize`，规则一旦漂移就会出现「同一句台词两个文件名」的缓存串味；
 * - 这几条规则全是**输入字符串 → 输出字符串**，不碰 Android 运行时，可以直接 JVM 单测
 *   （见 `VoiceCardPolicyTest`），比在设备上复现「第二次分享发出了上一次的日配语音」便宜得多。
 */
internal object VoiceCardPolicy {

    /** 文件名里单个字段的最大码点数（不是 char 数，见 [takeCodePoints]） */
    const val MAX_LABEL = 40

    /** 分享文案里引用的台词最大码点数（部分应用会把 EXTRA_TEXT 直接展开） */
    const val MAX_SHARE_EXCERPT = 200

    /**
     * 是否是可嵌入卡片的播放地址。
     *
     * 只认 `http` / `https`：
     * - 卡片会被别人在浏览器/聊天内置浏览器里打开，`file:`、`content:`、`javascript:` 一律不合法；
     * - wiki 侧解析出的字段来自网页，出现相对路径（`/audio/x.mp3`）时卡片播放器必然死掉，
     *   当作「无语音」降级比留一个点了没反应的按钮更诚实。
     */
    fun isHttpUrl(raw: String): Boolean {
        val url = raw.trim()
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val scheme = url.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return false
        // "http://" 之后必须还有主机名
        return url.length > colon + 3 && url[colon + 1] == '/' && url[colon + 2] == '/'
    }

    /**
     * 分享/缓存用的音频文件名。
     *
     * ⚠️ 必须带上 [urlHash]：同一句台词的中配与日配是**两个 URL**，
     * 旧实现只用「舰名_场景.mp3」，先分享中配再分享日配会把前一个文件原地覆盖
     * （下载失败时还会连带删掉已下好的那份），发出去的语音语言随机。
     */
    fun audioFileName(shipName: String, sceneName: String, url: String): String = buildString(64) {
        append(safeName(shipName))
        if (sceneName.isNotBlank()) {
            append('_')
            append(safeName(sceneName))
        }
        append('_')
        append(urlHash(url))
        append(".mp3")
    }

    /** 32 位 FNV-1a（按 UTF-8 字节），同一 URL 永远同一后缀，用于区分中配/日配同名台词 */
    fun urlHash(url: String): String {
        var hash = -0x7ee3623b // 0x811C9DC5
        for (byte in url.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }

    /**
     * 文件名净化：替换路径分隔符与非法字符，按**码点**截断。
     *
     * - 按码点而不是 `take(n)`：`take` 可能切断代理对，落盘后那个 emoji 变成 `?`，
     *   MediaStore 还会因此拒绝写入；
     * - 连续非法字符折叠成一个 `_`，且整串只剩占位符时回退到 [fallback]：
     *   否则「///:::」会变成「______」这种既难看又可能撞名的文件名。
     */
    fun safeName(raw: String, max: Int = MAX_LABEL, fallback: String = "voice"): String {
        val cleaned = StringBuilder(raw.length)
        var lastWasUnderscore = false
        for (ch in raw) {
            val illegal = ch == '\\' || ch == '/' || ch == ':' || ch == '*' || ch == '?' ||
                ch == '"' || ch == '<' || ch == '>' || ch == '|' ||
                ch.code < 0x20 || ch.code == 0x7F || ch.isWhitespace()
            if (illegal) {
                if (!lastWasUnderscore) cleaned.append('_')
                lastWasUnderscore = true
            } else {
                cleaned.append(ch)
                lastWasUnderscore = false
            }
        }
        val trimmed = cleaned.toString().trim().trim('_', '.')
        return takeCodePoints(trimmed, max).ifBlank { fallback }
    }

    /** 分享文案引用的台词：去首尾空白、按码点截断、不留下半截行 */
    fun excerpt(raw: String, max: Int = MAX_SHARE_EXCERPT): String =
        takeCodePoints(raw.trim(), max).trimEnd()

    /**
     * 按**码点**截断，绝不切断 UTF-16 代理对（emoji / 生僻字都在补充平面）。
     * 这是唯一允许的截断入口——直接 `substring` 会产出孤立代理项。
     */
    fun takeCodePoints(raw: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0 || raw.isEmpty()) return ""
        var index = 0
        var count = 0
        while (index < raw.length && count < maxCodePoints) {
            val wide = index + 1 < raw.length &&
                Character.isHighSurrogate(raw[index]) &&
                Character.isLowSurrogate(raw[index + 1])
            index += if (wide) 2 else 1
            count++
        }
        return raw.substring(0, index)
    }
}
