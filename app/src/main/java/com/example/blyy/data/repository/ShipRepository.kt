package com.example.blyy.data.repository

import android.net.Uri
import com.example.blyy.data.local.ShipDao
import com.example.blyy.data.model.Ship
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.viewmodel.ArchiveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import javax.inject.Inject

class ShipRepository @Inject constructor(
    private val shipDao: ShipDao
) {
    val allShips = shipDao.getAllShips()
    val favoriteShips = shipDao.getFavoriteShips()

    suspend fun refreshShipsFromWiki(archiveType: ArchiveType = ArchiveType.DOCK) = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect("https://wiki.biligame.com/blhx/舰船图鉴")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(30000)
                .get()

            val elements = doc.select(".jntj-1.divsort")
            if (elements.isEmpty()) {
                throw IllegalStateException("未解析到任何舰船数据，可能遭到反爬虫拦截。")
            }

            val ships = elements.mapNotNull { el ->
                try {
                    val name = el.select(".jntj-4 a").text().trim().split(" ")[0]
                    val relativeLink = el.select(".jntj-3 a").attr("href")
                    val avatar = formatUrl(el.select(".jntj-2 img").attr("src"))
                    val border = formatUrl(el.select(".jntj-3 img").attr("src"))
                    val type = el.attr("data-param1").replace("，，", "·").replace(",,", "·").trim()
                    var rarity = el.attr("data-param2")
                    if (rarity.contains("海上传奇")) rarity = "海上传奇"
                    if (rarity.contains("决战方案")) rarity = "决战方案"
                    val faction = el.attr("data-param3")
                    val extra = el.attr("data-param4")

                    Ship(name, avatar, border, relativeLink, type, rarity, faction, extra, archiveType.name)
                } catch (e: Exception) { null }
            }
            shipDao.upsertShips(ships)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun formatUrl(url: String): String {
        if (url.isEmpty()) return ""
        var res = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://wiki.biligame.com$url"
            else -> url
        }
        if (res.contains("/thumb/")) {
            val thumbIndex = res.indexOf("/thumb/")
            val lastSlashIndex = res.lastIndexOf("/")
            if (lastSlashIndex > thumbIndex + 7) {
                res = res.substring(0, thumbIndex) + "/" + res.substring(thumbIndex + 7, lastSlashIndex)
            }
        }
        return res
    }

    suspend fun fetchVoices(shipName: String): Pair<List<VoiceLine>, String> = withContext(Dispatchers.IO) {
        try {
            // 处理改造船的名称，确保能够正确访问其网页
            var processedName = shipName
            // 移除改造标记，如 "改"、"Kai" 等
            if (processedName.contains(".改")) {
                processedName = processedName.replace(".改", "")
            } else if (processedName.contains("改")) {
                processedName = processedName.replace("改", "")
            } else if (processedName.contains("Kai")) {
                processedName = processedName.replace("Kai", "")
            }
            
            // 特殊舰娘名字到Wiki URL的映射
            val wikiNameMapping = mapOf(
                "DEAD" to "DEAD_MASTER",
                "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
            )
            
            val wikiName = wikiNameMapping[processedName] ?: processedName
            val encodedName = Uri.encode(wikiName)
            val url = "https://wiki.biligame.com/blhx/$encodedName"
            
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .referrer("https://wiki.biligame.com/blhx/%E9%A6%96%E9%A1%B5")
                .timeout(20000)
                .get()

            // 提取立绘小人URL - 尝试多个选择器
            var figureUrl = ""
            
            // 尝试选择器1: .qchar-figure img
            val figureElement1 = doc.select(".qchar-figure img").firstOrNull()
            if (figureElement1 != null) {
                figureUrl = formatUrl(figureElement1.attr("src"))
            }
            
            // 尝试选择器2: .jntj-1 img (头像区域可能有小人)
            if (figureUrl.isEmpty()) {
                val figureElement2 = doc.select(".jntj-1 img").firstOrNull()
                if (figureElement2 != null) {
                    figureUrl = formatUrl(figureElement2.attr("src"))
                }
            }
            
            // 尝试选择器3: .ship-avatar img
            if (figureUrl.isEmpty()) {
                val figureElement3 = doc.select(".ship-avatar img").firstOrNull()
                if (figureElement3 != null) {
                    figureUrl = formatUrl(figureElement3.attr("src"))
                }
            }
            
            // 尝试选择器4: [class*="char"] img
            if (figureUrl.isEmpty()) {
                val figureElement4 = doc.select("[class*='char'] img, [class*='figure'] img").firstOrNull()
                if (figureElement4 != null) {
                    figureUrl = formatUrl(figureElement4.attr("src"))
                }
            }
            
            // 尝试选择器5: 从页面脚本中提取
            if (figureUrl.isEmpty()) {
                val scripts = doc.select("script")
                for (script in scripts) {
                    val html = script.html()
                    if (html.contains("qchar-figure") || html.contains("patchwiki.biligame.com/images/blhx")) {
                        val regex = Regex("""https://patchwiki\.biligame\.com/images/blhx/[a-zA-Z0-9/]+\.png""")
                        val match = regex.find(html)
                        if (match != null) {
                            figureUrl = match.value
                            break
                        }
                    }
                }
            }

            val voices = mutableListOf<VoiceLine>()
            val tables = doc.select(".table-ShipWordsTable")

            tables.forEach { table ->
                // 1. 查找皮肤名称逻辑优化：先取 data-title，再向上找标题
                var skinName = table.attr("data-title")
                if (skinName.isEmpty()) {
                    var prev = table.previousElementSibling()
                    while (prev != null) {
                        if (prev.tagName().startsWith("h") || prev.hasClass("panel-heading")) {
                            skinName = prev.text().replace("[编辑]", "").replace("展开/折叠", "").trim()
                            break
                        }
                        prev = prev.previousElementSibling()
                    }
                }
                if (skinName.isEmpty() || skinName == "舰船台词") skinName = "默认装扮"

                val rows = table.select("tr")
                rows.forEach { row ->
                    val baseScene = row.select("th").firstOrNull()?.text()?.trim() ?: ""
                    if (baseScene.isNotEmpty() && !baseScene.contains("描述") && baseScene != "台词") {
                        
                        val td = row.select("td").firstOrNull() ?: return@forEach
                        val blocks = td.select(".ship_word_block")
                        
                        if (blocks.isNotEmpty()) {
                            // 处理 td 下有多个 block 的情况（如：普通+誓约）
                            blocks.forEach { block ->
                                parseBlockToVoice(block, skinName, baseScene, voices)
                            }
                        } else {
                            // 处理没有 block 直接写在 td 里的情况
                            parseBlockToVoice(td, skinName, baseScene, voices)
                        }
                    }
                }
            }
            
            if (voices.isEmpty()) throw IllegalStateException("未找到语音数据")
            Pair(voices, figureUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e 
        }
    }

    private fun parseBlockToVoice(element: org.jsoup.nodes.Element, skinName: String, baseScene: String, list: MutableList<VoiceLine>) {
        val textElement = element.select(".ship_word_line").firstOrNull() ?: element
        val rawText = textElement.text().trim()
        
        // 自动识别“誓约”变体
        val isOath = element.select("span[title*=誓约], span:contains(誓约)").isNotEmpty()
        val finalScene = if (isOath) "$baseScene(誓约)" else baseScene

        var audio = element.select(".sm-audio-src a").attr("href")
        if (audio.isEmpty()) {
            audio = element.select("a[href$=.mp3], a[href$=.ogg]").attr("href")
        }

        if (rawText.isNotEmpty() && audio.isNotEmpty()) {
            if (audio.startsWith("//")) audio = "https:$audio"
            else if (audio.startsWith("/")) audio = "https://wiki.biligame.com$audio"
            
            list.add(VoiceLine(skinName, finalScene, rawText, audio))
        }
    }

    suspend fun toggleFav(name: String, current: Boolean) {
        shipDao.updateFavorite(name, !current)
    }
}
