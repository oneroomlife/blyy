package com.example.blyy.data.repository

import android.net.Uri
import android.util.Log
import com.example.blyy.data.local.ShipDao
import com.example.blyy.data.model.Ship
import com.example.blyy.data.model.ShipCharacterInfo
import com.example.blyy.data.model.ShipGallery
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.util.NetworkHelper
import com.example.blyy.viewmodel.ArchiveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import javax.inject.Inject

private const val TAG = "ShipRepository"

class ShipRepository @Inject constructor(
    private val shipDao: ShipDao,
    private val networkHelper: NetworkHelper
) {
    val allShips = shipDao.getAllShips()
    val favoriteShips = shipDao.getFavoriteShips()

    suspend fun refreshShipsFromWiki(archiveType: ArchiveType = ArchiveType.DOCK) = withContext(Dispatchers.IO) {
        val url = "https://wiki.biligame.com/blhx/舰船图鉴"
        val result = networkHelper.fetchDocument(url, maxRetries = 3)
        
        result.fold(
            onSuccess = { doc ->
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
                Log.d(TAG, "Successfully refreshed ${ships.size} ships")
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to refresh ships", e)
                throw e
            }
        )
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

    private fun getOriginalImageUrl(url: String): String {
        if (url.isEmpty()) return ""
        var res = formatUrl(url)
        if (res.contains("/thumb/")) {
            val thumbIndex = res.indexOf("/thumb/")
            val lastSlashIndex = res.lastIndexOf("/")
            if (lastSlashIndex > thumbIndex + 7) {
                res = res.substring(0, thumbIndex) + "/" + res.substring(thumbIndex + 7, lastSlashIndex)
            }
        }
        return res
    }

    suspend fun fetchVoices(shipName: String): Triple<List<VoiceLine>, String, Map<String, String>> = withContext(Dispatchers.IO) {
        var processedName = shipName
        if (processedName.contains(".改")) {
            processedName = processedName.replace(".改", "")
        } else if (processedName.contains("改")) {
            processedName = processedName.replace("改", "")
        } else if (processedName.contains("Kai")) {
            processedName = processedName.replace("Kai", "")
        }
        
        val wikiNameMapping = mapOf(
            "DEAD" to "DEAD_MASTER",
            "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
        )
        
        val wikiName = wikiNameMapping[processedName] ?: processedName
        val encodedName = Uri.encode(wikiName)
        val url = "https://wiki.biligame.com/blhx/$encodedName"
        
        Log.d(TAG, "Fetching voices from: $url")
        
        val result = networkHelper.fetchDocument(url, maxRetries = 3)
        
        result.fold(
            onSuccess = { doc ->
                val skinFigureMap = extractSkinFigureMap(doc)
                val defaultFigureUrl = skinFigureMap.values.firstOrNull() ?: extractFigureUrl(doc)

                val voices = mutableListOf<VoiceLine>()
                val tables = doc.select(".table-ShipWordsTable")

                tables.forEach { table ->
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
                                blocks.forEach { block ->
                                    parseBlockToVoice(block, skinName, baseScene, voices)
                                }
                            } else {
                                parseBlockToVoice(td, skinName, baseScene, voices)
                            }
                        }
                    }
                }
                
                if (voices.isEmpty()) throw IllegalStateException("未找到语音数据")
                
                Log.d(TAG, "Found ${voices.size} voices, ${skinFigureMap.size} skin figures")
                
                Triple(voices, defaultFigureUrl, skinFigureMap)
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to fetch voices", e)
                throw e
            }
        )
    }

    private fun extractSkinFigureMap(doc: org.jsoup.nodes.Document): Map<String, String> {
        val skinFigureMap = mutableMapOf<String, String>()
        
        val tabLis = doc.select(".tab_li")
        val allImgs = doc.select("img[style*='position:absolute'][style*='bottom:0']")
        
        Log.d(TAG, "extractSkinFigureMap: Found ${tabLis.size} tab_li, ${allImgs.size} figure images")
        
        if (tabLis.isNotEmpty() && allImgs.isNotEmpty()) {
            val skinNames = tabLis.map { it.text().trim() }
            val figureUrls = allImgs.map { formatUrl(it.attr("src")) }
            
            figureUrls.forEachIndexed { index, figureUrl ->
                if (figureUrl.isNotEmpty()) {
                    val skinName = skinNames.getOrElse(index) { "皮肤${index + 1}" }
                    skinFigureMap[skinName] = figureUrl
                    Log.d(TAG, "Matched figure: $skinName -> $figureUrl")
                }
            }
            
            if (skinNames.size > figureUrls.size) {
                val lastFigureUrl = figureUrls.lastOrNull() ?: ""
                skinNames.drop(figureUrls.size).forEach { skinName ->
                    if (lastFigureUrl.isNotEmpty() && !skinFigureMap.containsKey(skinName)) {
                        skinFigureMap[skinName] = lastFigureUrl
                        Log.d(TAG, "Fallback figure: $skinName -> $lastFigureUrl")
                    }
                }
            }
        }
        
        if (skinFigureMap.isEmpty()) {
            val qcharFigure = doc.select(".qchar-figure img").firstOrNull()
            if (qcharFigure != null) {
                val defaultUrl = formatUrl(qcharFigure.attr("src"))
                skinFigureMap["通常"] = defaultUrl
                skinFigureMap["默认"] = defaultUrl
                skinFigureMap["默认装扮"] = defaultUrl
                Log.d(TAG, "Using qchar-figure as default: $defaultUrl")
            }
        }
        
        return skinFigureMap
    }

    private fun extractFigureUrl(doc: org.jsoup.nodes.Document): String {
        var figureUrl = ""
        
        val figureElement1 = doc.select(".qchar-figure img").firstOrNull()
        if (figureElement1 != null) {
            figureUrl = formatUrl(figureElement1.attr("src"))
            Log.d(TAG, "Found qchar-figure img: $figureUrl")
        }
        
        if (figureUrl.isEmpty()) {
            val figureElement2 = doc.select(".jntj-1 img").firstOrNull()
            if (figureElement2 != null) {
                figureUrl = formatUrl(figureElement2.attr("src"))
                Log.d(TAG, "Found jntj-1 img: $figureUrl")
            }
        }
        
        if (figureUrl.isEmpty()) {
            val figureElement3 = doc.select(".ship-avatar img").firstOrNull()
            if (figureElement3 != null) {
                figureUrl = formatUrl(figureElement3.attr("src"))
                Log.d(TAG, "Found ship-avatar img: $figureUrl")
            }
        }
        
        if (figureUrl.isEmpty()) {
            val figureElement4 = doc.select("[class*='char'] img, [class*='figure'] img").firstOrNull()
            if (figureElement4 != null) {
                figureUrl = formatUrl(figureElement4.attr("src"))
                Log.d(TAG, "Found char/figure img: $figureUrl")
            }
        }
        
        if (figureUrl.isEmpty()) {
            val scripts = doc.select("script")
            for (script in scripts) {
                val html = script.html()
                if (html.contains("qchar-figure") || html.contains("patchwiki.biligame.com/images/blhx")) {
                    val regex = Regex("""https://patchwiki\.biligame\.com/images/blhx/[a-zA-Z0-9/]+\.png""")
                    val match = regex.find(html)
                    if (match != null) {
                        figureUrl = match.value
                        Log.d(TAG, "Found figure in script: $figureUrl")
                        break
                    }
                }
            }
        }
        
        if (figureUrl.isEmpty()) {
            Log.w(TAG, "No figure URL found")
        }
        
        return figureUrl
    }

    suspend fun fetchShipGallery(shipName: String): ShipGallery = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchShipGallery START for: $shipName")
        
        var processedName = shipName
        if (processedName.contains(".改")) {
            processedName = processedName.replace(".改", "")
        } else if (processedName.contains("改")) {
            processedName = processedName.replace("改", "")
        } else if (processedName.contains("Kai")) {
            processedName = processedName.replace("Kai", "")
        }
        
        val wikiNameMapping = mapOf(
            "DEAD" to "DEAD_MASTER",
            "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
        )
        
        val wikiName = wikiNameMapping[processedName] ?: processedName
        val encodedName = Uri.encode(wikiName)
        val url = "https://wiki.biligame.com/blhx/$encodedName"
        
        Log.d(TAG, "Fetching gallery from: $url")
        
        val result = networkHelper.fetchDocument(url, maxRetries = 3)
        
        result.fold(
            onSuccess = { doc ->
                val illustrations = mutableListOf<Pair<String, String>>()

                // 1. 抓取大立绘
                doc.select(".wikitable.sv-portrait").forEach { table ->
                    val tabLis = table.select(".tab_li")
                    val tabCons = table.select(".tab_con")
                    tabCons.forEachIndexed { index, content ->
                        val skinName = tabLis.getOrNull(index)?.text()?.trim() ?: "通常"
                        val img = content.select("img").firstOrNull()
                        if (img != null) {
                            val imageUrl = getOriginalImageUrl(img.attr("src").ifEmpty { img.attr("data-src") })
                            if (imageUrl.isNotEmpty() && !imageUrl.contains("头像") && !imageUrl.contains("icon")) {
                                illustrations.add(Pair(skinName, imageUrl))
                            }
                        }
                    }
                }

                // 【关键修复】确保去重后的 Illustrations 作为最终参考，避免后方 mapping 错位
                val uniqueIllustrations = illustrations.distinctBy { it.second }

                // 2. 抓取隐藏图层里的所有 Q版小人 候选列表
                val chibiCandidates = mutableListOf<Pair<String, String>>()
                doc.select(".data-container img, .qchar-container img, .qchar-figure img").forEach { img ->
                    val alt = img.attr("alt").ifEmpty { img.attr("title") }.trim()
                    val src = getOriginalImageUrl(img.attr("src").ifEmpty { img.attr("data-src") })
                    if (src.endsWith(".png")) {
                        chibiCandidates.add(Pair(alt, src))
                    }
                }
                
                val defaultChibiUrl = chibiCandidates.firstOrNull()?.second ?: extractFigureUrl(doc)

                // 3. 严格复刻 js 的匹配逻辑，保证小人数据条目与大立绘 1:1 对等
                val finalFigures = uniqueIllustrations.map { (skinName, _) ->
                    var matchedUrl = defaultChibiUrl
                    
                    // 完全复刻碧蓝 Wiki 网页端前端代码中的硬编码处理
                    var searchText = skinName
                    if (searchText == "换装1") {
                        searchText = "换装"
                    }

                    if (skinName != "通常" && chibiCandidates.isNotEmpty()) {
                        // indexOf 包含查找匹配
                        val findMatch = chibiCandidates.find { it.first.contains(searchText) }
                        if (findMatch != null) {
                            matchedUrl = findMatch.second
                        } else {
                            // 移除括号后的兜底查找
                            val cleanSearch = cleanSkinName(searchText)
                            val fuzzyMatch = chibiCandidates.find { cleanSkinName(it.first) == cleanSearch }
                            if (fuzzyMatch != null) matchedUrl = fuzzyMatch.second
                        }
                    }
                    Pair(skinName, matchedUrl)
                }

                Log.d(TAG, "fetchShipGallery END: Found ${uniqueIllustrations.size} portraits and mapped ${finalFigures.size} chibis")

                ShipGallery(
                    shipName = shipName,
                    illustrations = uniqueIllustrations, // 将去重后的大图传入
                    figures = finalFigures               // 将精准匹配的小人传入
                )
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to fetch gallery", e)
                ShipGallery(shipName, emptyList(), emptyList())
            }
        )
    }

    private fun cleanSkinName(name: String): String {
        return name.lowercase().trim()
            .replace(Regex("""[\(（].*?[\)）]"""), "")
            .replace("立绘", "")
            .replace("皮肤", "")
            .trim()
    }

    private fun matchSkinNames(name1: String, name2: String): Boolean {
        if (name1 == name2) return true
        if (name1.contains(name2) || name2.contains(name1)) return true
        
        val equivalentNames = mapOf(
            "通常" to setOf("默认", "默认装扮"),
            "默认" to setOf("通常", "默认装扮"),
            "默认装扮" to setOf("通常", "默认")
        )
        
        return equivalentNames[name1]?.contains(name2) == true || equivalentNames[name2]?.contains(name1) == true
    }

    private fun parseBlockToVoice(element: Element, skinName: String, baseScene: String, list: MutableList<VoiceLine>) {
        val textElement = element.select(".ship_word_line").firstOrNull() ?: element
        val rawText = textElement.text().trim()
        
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

    suspend fun fetchCharacterInfo(shipName: String): ShipCharacterInfo = withContext(Dispatchers.IO) {
        var processedName = shipName
        if (processedName.contains(".改")) {
            processedName = processedName.replace(".改", "")
        } else if (processedName.contains("改")) {
            processedName = processedName.replace("改", "")
        } else if (processedName.contains("Kai")) {
            processedName = processedName.replace("Kai", "")
        }
        
        val wikiNameMapping = mapOf(
            "DEAD" to "DEAD_MASTER",
            "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
        )
        
        val wikiName = wikiNameMapping[processedName] ?: processedName
        val encodedName = Uri.encode(wikiName)
        val url = "https://wiki.biligame.com/blhx/$encodedName"
        
        Log.d(TAG, "Fetching character info from: $url")
        
        val result = networkHelper.fetchDocument(url, maxRetries = 3)
        
        result.fold(
            onSuccess = { doc ->
                var identity = ""
                var personality = ""
                var keywords = ""
                var possessions = ""
                var hairColor = ""
                var eyeColor = ""
                var moePoints = ""
                
                val tables = doc.select("table.wikitable, table.wikitable.sv-portrait")
                for (table in tables) {
                    val rows = table.select("tr")
                    var foundCharacterInfo = false
                    
                    for (row in rows) {
                        val tdCells = row.select("td")
                        val thCells = row.select("th")
                        
                        val allCells = if (thCells.isNotEmpty()) thCells else tdCells
                        
                        val firstCell = allCells.firstOrNull()
                        if (firstCell != null) {
                            val firstCellText = firstCell.text().trim()
                            
                            if (firstCellText.contains("角色信息")) {
                                foundCharacterInfo = true
                                continue
                            }
                            
                            if (firstCellText.contains("CV") || firstCellText.contains("画师")) {
                                if (foundCharacterInfo) break
                            }
                        }
                        
                        if (foundCharacterInfo && tdCells.size >= 2) {
                            val label = tdCells[0].text().trim().replace("：", "").replace(":", "")
                            val value = tdCells[1].text().trim()
                            
                            when {
                                label.contains("身份") -> identity = value
                                label.contains("性格") -> personality = value
                                label.contains("关键词") -> keywords = value
                                label.contains("持有物") -> possessions = value
                                label.contains("发色") -> hairColor = value
                                label.contains("瞳色") -> eyeColor = value
                                label.contains("萌点") -> moePoints = value
                            }
                        }
                        
                        if (foundCharacterInfo && thCells.size >= 2) {
                            val label = thCells[0].text().trim().replace("：", "").replace(":", "")
                            val value = thCells[1].text().trim()
                            
                            when {
                                label.contains("身份") && identity.isEmpty() -> identity = value
                                label.contains("性格") && personality.isEmpty() -> personality = value
                                label.contains("关键词") && keywords.isEmpty() -> keywords = value
                                label.contains("持有物") && possessions.isEmpty() -> possessions = value
                                label.contains("发色") && hairColor.isEmpty() -> hairColor = value
                                label.contains("瞳色") && eyeColor.isEmpty() -> eyeColor = value
                                label.contains("萌点") && moePoints.isEmpty() -> moePoints = value
                            }
                        }
                    }
                    
                    if (foundCharacterInfo) break
                }
                
                Log.d(TAG, "Character info for $shipName: identity=$identity, personality=$personality, keywords=$keywords")
                ShipCharacterInfo(shipName, identity, personality, keywords, possessions, hairColor, eyeColor, moePoints)
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to fetch character info", e)
                ShipCharacterInfo(shipName)
            }
        )
    }
}
