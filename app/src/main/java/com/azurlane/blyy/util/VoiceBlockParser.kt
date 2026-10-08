package com.azurlane.blyy.util

import com.azurlane.blyy.data.model.VoiceLine
import org.jsoup.nodes.Element

/**
 * 解析 wiki 台词表中单个台词块(.ship_word_block,无块时为整个 td)为 [VoiceLine] 列表。
 *
 * wiki 现存两种块结构:
 * - 新结构(如圣地亚哥):一个 .ship_word_media_wrap 内并排 jp/zh 两个 .ship_word_line,
 *   共用一条音频,且块与 wrap 是同一个 div(class 同时含 ship_word_block 与 ship_word_media_wrap);
 *   一行台词一个块,多段台词(如主界面1/2/3)是 td 内多个平级块。
 * - 旧结构(如逸仙):块内每个语言各占一个 .ship_word_media_wrap,各带自己的音频。
 *
 * 旧实现把「块内 wrap 数量」当语言区分依据:新结构单 wrap 块直接取第一个 .ship_word_line,
 * 而新结构日文行永远在前,导致中文台词显示为日文。
 */
object VoiceBlockParser {

    private class ParsedWrap(val cnText: String, val jpText: String, val audio: String)

    fun parse(element: Element, skinName: String, baseScene: String, out: MutableList<VoiceLine>) {
        val isOath = element.select("span[title*=誓约], span:contains(誓约)").isNotEmpty()
        val scene = if (isOath) "$baseScene(誓约)" else baseScene

        val parsed = element.select(".ship_word_media_wrap")
            .map { parseWrap(it) }
            .filter { it.cnText.isNotEmpty() || it.jpText.isNotEmpty() }

        if (parsed.isEmpty()) {
            // 无 wrap 的旧式块:整块只有一段文本
            val textElement = element.select(".ship_word_line").firstOrNull() ?: element
            val text = textElement.text().trim()
            val audio = resolveAudio(element)
            if (text.isNotEmpty() && audio.isNotEmpty()) {
                if (isJapanese(text)) {
                    emit(out, skinName, scene, cnText = "", jpText = text, cnAudio = "", jpAudio = audio)
                } else {
                    emit(out, skinName, scene, cnText = text, jpText = "", cnAudio = audio, jpAudio = "")
                }
            }
            return
        }

        // 旧结构:各 wrap 单语言且中日齐备 → 按语言合并为一条台词(同块内多组变体取每组首个)
        val allSingleLang = parsed.all { it.cnText.isEmpty() || it.jpText.isEmpty() }
        val hasBothLangs = parsed.any { it.cnText.isNotEmpty() } && parsed.any { it.jpText.isNotEmpty() }
        if (parsed.size >= 2 && allSingleLang && hasBothLangs) {
            val cn = parsed.first { it.cnText.isNotEmpty() }
            val jp = parsed.first { it.jpText.isNotEmpty() }
            emit(out, skinName, scene, cn.cnText, jp.jpText, cn.audio, jp.audio)
            return
        }

        // 新结构:每个 wrap 是一条独立台词,jp/zh 行共用 wrap 内的音频
        parsed.forEach { w ->
            emit(out, skinName, scene, w.cnText, w.jpText, w.audio, w.audio)
        }
    }

    private fun parseWrap(wrap: Element): ParsedWrap {
        var cnText = ""
        var jpText = ""
        wrap.select(".ship_word_line").forEach { line ->
            val text = line.text().trim()
            if (text.isEmpty()) return@forEach
            when (line.attr("data-lang")) {
                "zh" -> if (cnText.isEmpty()) cnText = text
                "jp" -> if (jpText.isEmpty()) jpText = text
                else -> {
                    // 未标记语言的行按假名启发式判断
                    if (isJapanese(text)) {
                        if (jpText.isEmpty()) jpText = text
                    } else if (cnText.isEmpty()) {
                        cnText = text
                    }
                }
            }
        }
        return ParsedWrap(cnText, jpText, resolveAudio(wrap))
    }

    private fun emit(
        out: MutableList<VoiceLine>,
        skinName: String,
        scene: String,
        cnText: String,
        jpText: String,
        cnAudio: String,
        jpAudio: String
    ) {
        val dialogue = cnText.ifEmpty { jpText }
        val cnUrl = cnAudio.ifEmpty { jpAudio }
        val jpUrl = jpAudio.ifEmpty { cnAudio }
        if (dialogue.isNotEmpty() && (cnUrl.isNotEmpty() || jpUrl.isNotEmpty())) {
            out.add(VoiceLine(skinName, scene, dialogue, cnUrl, jpUrl))
        }
    }

    private fun resolveAudio(scope: Element): String {
        var audio = scope.select(".sm-audio-src a").attr("href")
        if (audio.isEmpty()) {
            audio = scope.select("a[href$=.mp3], a[href$=.ogg]").attr("href")
        }
        if (audio.startsWith("//")) audio = "https:$audio"
        else if (audio.startsWith("/")) audio = "https://wiki.biligame.com$audio"
        return audio
    }

    private fun isJapanese(text: String): Boolean {
        return text.any { c ->
            c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'
        }
    }
}
