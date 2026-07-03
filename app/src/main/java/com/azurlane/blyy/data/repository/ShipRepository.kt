package com.azurlane.blyy.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.ShipCharacterInfo
import com.azurlane.blyy.data.model.ShipGallery
import com.azurlane.blyy.data.model.StudentGallery
import com.azurlane.blyy.data.model.StudentGalleryImage
import com.azurlane.blyy.data.model.StudentGalleryTab
import com.azurlane.blyy.data.model.StudentGalleryVideo
import com.azurlane.blyy.data.model.StudentFilterData
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.util.NetworkHelper
import com.azurlane.blyy.util.WebViewHtmlFetcher
import com.azurlane.blyy.viewmodel.ArchiveType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ShipRepository"

class ShipRepository @Inject constructor(
    private val shipDao: ShipDao,
    private val networkHelper: NetworkHelper,
    @ApplicationContext private val context: Context
) {
    companion object {
        private val WIKI_NAME_MAPPING = mapOf(
            "DEAD" to "DEAD_MASTER",
            "BLACK★ROCK" to "BLACK★ROCK_SHOOTER"
        )

        private const val GAMEKEE_STUDENT_LIST_URL =
            "https://www.gamekee.com/ba/second/23941"
        private const val GAMEKEE_BASE_URL = "https://www.gamekee.com"

        /** 学生语音 tab id → 标签名 */
        private val VOICE_TAB_MAP = linkedMapOf(
            "tc" to "通常",
            "dtjkfg" to "大厅及咖啡厅",
            "hgd" to "好感度",
            "zd" to "战斗",
            "cz" to "成长",
            "sj" to "事件"
        )
    }

    val allShips = shipDao.getAllShips()
    val favoriteShips = shipDao.getFavoriteShips()

    /**
     * 学生语音内存缓存
     *
     * 多级缓存中的"内存缓存"层 — 以 studentLink 为 key 缓存语音解析结果。
     * 缓存有效期 1 小时，避免短时间内重复 WebView 渲染（耗时 6-8 秒）。
     */
    private data class VoiceCacheEntry(
        val voices: List<VoiceLine>,
        val avatarUrl: String,
        val skinFigureMap: Map<String, String>,
        val timestamp: Long
    )

    private val voiceCache = ConcurrentHashMap<String, VoiceCacheEntry>()
    private val VOICE_CACHE_EXPIRY_MS = 60 * 60 * 1000L // 1 小时

    /**
     * 获取学生语音（带内存缓存）
     *
     * 缓存策略：
     * 1. 先查内存缓存，命中且未过期则直接返回
     * 2. 未命中或过期则从网络拉取，成功后写入缓存
     *
     * @param studentLink 学生详情页链接
     * @param forceRefresh 是否强制刷新（忽略缓存）
     */
    suspend fun fetchStudentVoicesCached(studentLink: String, forceRefresh: Boolean = false): Triple<List<VoiceLine>, String, Map<String, String>> {
        // 检查内存缓存
        if (!forceRefresh) {
            val cached = voiceCache[studentLink]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < VOICE_CACHE_EXPIRY_MS) {
                Log.d(TAG, "Voice cache hit for $studentLink, age=${(System.currentTimeMillis() - cached.timestamp) / 1000}s")
                return Triple(cached.voices, cached.avatarUrl, cached.skinFigureMap)
            }
        }

        // 缓存未命中或强制刷新：从网络拉取
        val result = fetchStudentVoices(studentLink)

        // 写入缓存
        voiceCache[studentLink] = VoiceCacheEntry(
            voices = result.first,
            avatarUrl = result.second,
            skinFigureMap = result.third,
            timestamp = System.currentTimeMillis()
        )
        Log.d(TAG, "Voice cache updated for $studentLink, ${result.first.size} voices")

        return result
    }

    /** 清除指定学生的语音缓存 */
    fun clearVoiceCache(studentLink: String) {
        voiceCache.remove(studentLink)
    }

    /** 清除所有语音缓存 */
    fun clearAllVoiceCache() {
        voiceCache.clear()
    }

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
                        var faction = el.attr("data-param3").trim()
                        val extra = el.attr("data-param4").trim()
                        // META 舰船（余烬阵营）：data-param4 含 "META" 标签时统一归类到"余烬"阵营，
                        // 确保阵营筛选与卡片展示一致
                        if (extra.contains("META", ignoreCase = true)) {
                            faction = "META"
                        }

                        Ship(name, avatar, border, relativeLink, type, rarity, faction, extra, archiveType.name)
                    } catch (e: Exception) { null }
                }
                shipDao.upsertShips(ships)
                // 删除本地已不存在的舰船，保持数据库整洁（限定 archiveType 避免误删学生记录）
                shipDao.deleteOldShipsByArchiveType(archiveType.name, ships.map { it.name })
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

    private fun normalizeShipName(shipName: String): String {
        var processedName = shipName
        if (processedName.contains(".改")) {
            processedName = processedName.replace(".改", "")
        } else if (processedName.contains("改")) {
            processedName = processedName.replace("改", "")
        } else if (processedName.contains("Kai")) {
            processedName = processedName.replace("Kai", "")
        }
        return WIKI_NAME_MAPPING[processedName] ?: processedName
    }

    private fun buildWikiUrl(shipName: String): String {
        val wikiName = normalizeShipName(shipName)
        val encodedName = Uri.encode(wikiName)
        return "https://wiki.biligame.com/blhx/$encodedName"
    }



    suspend fun fetchVoices(shipName: String): Triple<List<VoiceLine>, String, Map<String, String>> = withContext(Dispatchers.IO) {
        val url = buildWikiUrl(shipName)
        
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
        
        val url = buildWikiUrl(shipName)
        
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
                            val imageUrl = formatUrl(img.attr("src").ifEmpty { img.attr("data-src") })
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
                    val src = formatUrl(img.attr("src").ifEmpty { img.attr("data-src") })
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
        val isOath = element.select("span[title*=誓约], span:contains(誓约)").isNotEmpty()
        val finalScene = if (isOath) "$baseScene(誓约)" else baseScene

        val mediaWraps = element.select(".ship_word_media_wrap")

        if (mediaWraps.size >= 2) {
            var cnText = ""
            var jpText = ""
            var cnAudio = ""
            var jpAudio = ""

            mediaWraps.forEach { wrap ->
                val lang = wrap.select(".ship_word_line").attr("data-lang")
                val text = wrap.select(".ship_word_line").text().trim()
                var audio = wrap.select(".sm-audio-src a").attr("href")
                if (audio.isEmpty()) {
                    audio = wrap.select("a[href$=.mp3], a[href$=.ogg]").attr("href")
                }
                if (audio.startsWith("//")) audio = "https:$audio"
                else if (audio.startsWith("/")) audio = "https://wiki.biligame.com$audio"

                when (lang) {
                    "zh" -> {
                        cnText = text
                        cnAudio = audio
                    }
                    "jp" -> {
                        jpText = text
                        jpAudio = audio
                    }
                    else -> {
                        val isJp = detectJapaneseByText(text)
                        if (isJp) {
                            Log.d(TAG, "检测到未标记lang的日文文本: scene=$finalScene, text=$text")
                            jpText = text
                            jpAudio = audio
                        } else if (cnAudio.isNotEmpty()) {
                            Log.d(TAG, "检测到未标记lang的文本，已有中配，归为日配: scene=$finalScene")
                            jpText = text
                            jpAudio = audio
                        } else if (jpAudio.isNotEmpty()) {
                            Log.d(TAG, "检测到未标记lang的文本，已有日配，归为中配: scene=$finalScene")
                            cnText = text
                            cnAudio = audio
                        } else {
                            Log.w(TAG, "无法判断未标记lang的文本语言，默认归为中配: scene=$finalScene, text=$text")
                            cnText = text
                            cnAudio = audio
                        }
                    }
                }
            }

            val dialogue = cnText.ifEmpty { jpText }
            val cnUrl = cnAudio.ifEmpty { jpAudio }
            val jpUrl = jpAudio.ifEmpty { cnAudio }

            if (dialogue.isNotEmpty() && (cnUrl.isNotEmpty() || jpUrl.isNotEmpty())) {
                list.add(VoiceLine(skinName, finalScene, dialogue, cnUrl, jpUrl))
            }
        } else {
            val textElement = element.select(".ship_word_line").firstOrNull() ?: element
            val rawText = textElement.text().trim()

            var audio = element.select(".sm-audio-src a").attr("href")
            if (audio.isEmpty()) {
                audio = element.select("a[href$=.mp3], a[href$=.ogg]").attr("href")
            }

            if (rawText.isNotEmpty() && audio.isNotEmpty()) {
                if (audio.startsWith("//")) audio = "https:$audio"
                else if (audio.startsWith("/")) audio = "https://wiki.biligame.com$audio"

                list.add(VoiceLine(skinName, finalScene, rawText, audio, audio))
            }
        }
    }

    private fun detectJapaneseByText(text: String): Boolean {
        return text.any { c ->
            c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'
        }
    }

    suspend fun toggleFav(name: String, archiveType: String, current: Boolean) {
        shipDao.updateFavorite(name, archiveType, !current)
    }

    suspend fun fetchCharacterInfo(shipName: String): ShipCharacterInfo = withContext(Dispatchers.IO) {
        val url = buildWikiUrl(shipName)
        
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

    // ==================== 学生档案（蔚蓝档案） ====================

    /**
     * 从 gamekee.com 刷新学生列表
     * 解析 https://www.gamekee.com/ba/second/23941 页面
     * 该页面为 Vue.js SPA，需要使用 WebView 渲染后才能解析
     */
    suspend fun refreshStudentsFromGamekee(): List<Ship> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val url = GAMEKEE_STUDENT_LIST_URL
        Log.d(TAG, "refreshStudentsFromGamekee START: url=$url")

        try {
            // gamekee.com 是 Vue.js SPA，静态 HTML 不含学生数据，需用 WebView 渲染
            // 使用带缓存+重试的版本，5 分钟内重复进入页面无需重新渲染
            val fetchStart = System.currentTimeMillis()
            val html = WebViewHtmlFetcher.fetchRenderedHtmlWithCache(context, url, waitMs = 8000L)
            val fetchDuration = System.currentTimeMillis() - fetchStart
            Log.d(TAG, "HTML fetch completed in ${fetchDuration}ms")

            val parseStart = System.currentTimeMillis()
            val doc = Jsoup.parse(html)

            val students = mutableListOf<Ship>()
            // 学生条目：<a class="item" href="/ba/tj/xxx.html">
            val items = doc.select("a.item[href*=/ba/tj/]")
            Log.d(TAG, "Found ${items.size} student items")

            val seenNames = mutableSetOf<String>()
            items.forEach { el ->
                try {
                    val name = el.select("span.name").text().trim()
                    if (name.isEmpty() || name in seenNames) return@forEach

                    val relativeLink = el.attr("href")
                    val link = if (relativeLink.startsWith("http")) relativeLink
                    else GAMEKEE_BASE_URL + relativeLink

                    // 头像：<img class="cover" src="..." data-src="...">
                    val coverImg = el.select("img.cover").firstOrNull()
                    var avatar = coverImg?.attr("data-src")?.ifEmpty { coverImg.attr("src") } ?: ""
                    if (avatar.isNotEmpty()) avatar = cleanGamekeeImageUrl(formatGamekeeUrl(avatar))

                    // 从链接中提取学生 ID（如 /ba/tj/59934.html → 59934）
                    val studentId = Regex("""/ba/tj/(\d+)\.html""").find(relativeLink)?.groupValues?.getOrNull(1) ?: ""

                    // 解析列表页可用的筛选数据（星级、学校等）
                    val filterData = parseStudentFilterFromListItem(el)

                    seenNames.add(name)
                    students.add(
                        Ship(
                            name = name,
                            avatarUrl = avatar,
                            borderUrl = null,
                            link = link,
                            type = filterData.attackType.ifEmpty { "学生" },
                            rarity = filterData.starRating.ifEmpty { "三星" },
                            faction = filterData.school.ifEmpty { "蔚蓝档案" },
                            extra = studentId,
                            archiveType = ArchiveType.STUDENT.name,
                            filterData = filterData.toJson()
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse student item: ${e.message}")
                }
            }
            val parseDuration = System.currentTimeMillis() - parseStart
            Log.d(TAG, "Parsed ${students.size} students in ${parseDuration}ms")

            if (students.isEmpty()) {
                throw IllegalStateException("未解析到任何学生数据，可能页面结构已变更。")
            }

            // 使用 upsertShips 保留已有收藏状态，避免删除后重新插入导致誓约丢失
            val dbStart = System.currentTimeMillis()
            shipDao.upsertShips(students)
            // 仅删除指定档案类型中不在新列表的记录
            shipDao.deleteOldShipsByArchiveType(ArchiveType.STUDENT.name, students.map { it.name })
            val dbDuration = System.currentTimeMillis() - dbStart
            Log.d(TAG, "DB upsert completed in ${dbDuration}ms, ${students.size} students")

            val totalDuration = System.currentTimeMillis() - startTime
            Log.d(TAG, "refreshStudentsFromGamekee SUCCESS: total=${totalDuration}ms, students=${students.size}")

            students
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "refreshStudentsFromGamekee FAILED: total=${totalDuration}ms, error=${e.message}", e)
            throw e
        }
    }

    /**
     * 从学生列表页项中解析可用的筛选数据
     *
     * gamekee 列表页的学生卡片可能包含：星级图标、学校标签等。
     * 使用多种选择器尝试提取，未找到的字段留空（后续由详情页补充）。
     */
    private fun parseStudentFilterFromListItem(el: Element): StudentFilterData {
        val fullText = el.text()

        // 星级：尝试从 data 属性、图标数量或文本中提取
        val starRating = extractStarRating(el, fullText)

        // 学校：尝试从标签/徽章中提取
        val school = extractSchool(el, fullText)

        // 攻击类型：尝试从标签中提取
        val attackType = extractAttackType(el, fullText)

        // 限定/常驻
        val limitedType = if (fullText.contains("限定")) "限定" else if (fullText.contains("常驻")) "常驻" else ""

        return StudentFilterData(
            starRating = starRating,
            school = school,
            attackType = attackType,
            limitedType = limitedType
        )
    }

    /** 从学生卡片中提取星级 */
    private fun extractStarRating(el: Element, fullText: String): String {
        // 尝试 data 属性
        val dataStar = el.attr("data-star")
        if (dataStar.isNotEmpty()) return "${dataStar}星"

        // 尝试文本匹配 "X星"
        val starMatch = Regex("""(\d)星""").find(fullText)
        if (starMatch != null) return starMatch.value

        // 尝试计数星星图标
        val starIcons = el.select(".star, .star-icon, [class*=star]")
        if (starIcons.isNotEmpty()) return "${starIcons.size}星"

        return ""
    }

    /** 从学生卡片中提取学校 */
    private fun extractSchool(el: Element, fullText: String): String {
        val schools = listOf("阿拜多斯", "格黑娜", "千禧年", "崔尼蒂", "歌赫娜",
            "赤冬", "百鬼夜行", "山海经", "瓦尔基里", "SRT", "阿里乌斯")
        schools.forEach { school ->
            if (fullText.contains(school) || el.attr("data-school").contains(school)) return school
        }
        return ""
    }

    /** 从学生卡片中提取攻击类型 */
    private fun extractAttackType(el: Element, fullText: String): String {
        val attackTypes = listOf("爆炸", "贯通", "神秘", "振击")
        attackTypes.forEach { type ->
            if (fullText.contains(type)) return type
        }
        return ""
    }

    /**
     * 从学生详情页补充完整筛选数据
     *
     * 列表页通常只有部分信息（星级、学校），详情页包含完整的 14 维筛选数据。
     * 此函数逐个获取详情页并更新数据库，使用 upsertShips 保留收藏状态。
     * 应在 viewModelScope 中调用，ViewModel 销毁时自动取消。
     */
    suspend fun enrichStudentFilterData(students: List<Ship>) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting enrichment for ${students.size} students")
        var enriched = 0
        students.forEach { student ->
            try {
                val detailUrl = student.link + "?tab=1"
                val filterData = parseStudentDetailForFilterData(detailUrl)
                if (filterData != null) {
                    // 合并已有数据（列表页解析的优先级低，详情页覆盖）
                    val merged = mergeFilterData(
                        StudentFilterData.fromJson(student.filterData) ?: StudentFilterData(),
                        filterData
                    )
                    val updated = student.copy(
                        filterData = merged.toJson(),
                        type = merged.attackType.ifEmpty { student.type },
                        rarity = merged.starRating.ifEmpty { student.rarity },
                        faction = merged.school.ifEmpty { student.faction }
                    )
                    // 使用 upsertShips 保留收藏状态
                    shipDao.upsertShips(listOf(updated))
                    enriched++
                }
                // 请求间隔，避免对 gamekee 服务器造成压力
                delay(500)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enrich ${student.name}: ${e.message}")
            }
        }
        Log.d(TAG, "Enrichment complete: $enriched/${students.size} students enriched")
    }

    /** 合并两个 StudentFilterData，后者（详情页）覆盖前者（列表页）的非空字段 */
    private fun mergeFilterData(base: StudentFilterData, detail: StudentFilterData): StudentFilterData {
        return StudentFilterData(
            starRating = detail.starRating.ifEmpty { base.starRating },
            limitedType = detail.limitedType.ifEmpty { base.limitedType },
            attackType = detail.attackType.ifEmpty { base.attackType },
            defenseType = detail.defenseType.ifEmpty { base.defenseType },
            formationPosition = detail.formationPosition.ifEmpty { base.formationPosition },
            combatRole = detail.combatRole.ifEmpty { base.combatRole },
            frontBackColor = detail.frontBackColor.ifEmpty { base.frontBackColor },
            weaponType = detail.weaponType.ifEmpty { base.weaponType },
            streetAdapt = detail.streetAdapt.ifEmpty { base.streetAdapt },
            outdoorAdapt = detail.outdoorAdapt.ifEmpty { base.outdoorAdapt },
            indoorAdapt = detail.indoorAdapt.ifEmpty { base.indoorAdapt },
            coverInteract = detail.coverInteract.ifEmpty { base.coverInteract },
            school = detail.school.ifEmpty { base.school },
            club = detail.club.ifEmpty { base.club }
        )
    }

    /**
     * 从学生详情页（tab=1）解析完整筛选数据
     *
     * gamekee 详情页通常包含学生属性表格/信息块，
     * 通过多种选择器尝试提取 14 个维度的筛选数据。
     *
     * @param detailUrl 详情页 URL（如 https://www.gamekee.com/ba/tj/59934.html?tab=1）
     * @return StudentFilterData 或 null（解析失败时）
     */
    suspend fun parseStudentDetailForFilterData(detailUrl: String): StudentFilterData? = withContext(Dispatchers.IO) {
        try {
            val html = WebViewHtmlFetcher.fetchRenderedHtmlWithCache(context, detailUrl, waitMs = 6000L)
            val doc = Jsoup.parse(html)
            val fullText = doc.text()

            // gamekee 详情页通常有属性表格或信息列表
            // 尝试从表格行、信息块、文本中提取各属性
            val tableRows = doc.select("tr, .info-item, .attr-item, .detail-item")
            val textMap = mutableMapOf<String, String>()

            tableRows.forEach { row ->
                val label = row.select("th, .label, .name, .title").text().trim()
                val value = row.select("td, .value, .content, .desc").text().trim()
                if (label.isNotEmpty() && value.isNotEmpty()) {
                    textMap[label] = value
                }
            }

            // 从文本中提取各属性
            fun findValue(vararg keys: String): String {
                keys.forEach { key ->
                    textMap.entries.forEach { (k, v) ->
                        if (k.contains(key)) return v
                    }
                    // 也尝试从全文中匹配 "key：value" 格式
                    val regex = Regex("""$key[：:]\s*(\S+)""")
                    regex.find(fullText)?.let { return it.groupValues[1] }
                }
                return ""
            }

            // 星级
            val starRating = findValue("星级", "稀有度").ifEmpty {
                Regex("""(\d)星""").find(fullText)?.value ?: ""
            }

            // 攻击类型
            val attackType = listOf("爆炸", "贯通", "神秘", "振击").find { fullText.contains(it) } ?: ""

            // 防御类型
            val defenseType = listOf("轻装甲", "重装甲", "特殊装甲", "弹力装甲").find { fullText.contains(it) } ?: ""

            // 编队位置
            val formationPosition = when {
                fullText.contains("前排") || fullText.contains("前锋") -> "前排"
                fullText.contains("后排") || fullText.contains("后方") -> "后排"
                else -> ""
            }

            // 战斗职责
            val combatRole = when {
                fullText.contains("输出") || fullText.contains("攻击") -> "输出"
                fullText.contains("支援") || fullText.contains("辅助") -> "支援"
                fullText.contains("坦克") || fullText.contains("防御") -> "坦克"
                else -> ""
            }

            // 站位前后
            val frontBackColor = findValue("站位", "位置").ifEmpty {
                when {
                    fullText.contains("前排") -> "前"
                    fullText.contains("后排") -> "后"
                    else -> ""
                }
            }

            // 武器种类：优先从表格提取，回退到全文单词边界匹配（避免 AR 匹配 URL 等）
            val weaponType = findValue("武器", "武器种类", "武器类型").ifEmpty {
                listOf("HG", "SMG", "AR", "SG", "SR", "MG", "RL", "GL", "FT").find {
                    Regex("""\b$it\b""").containsMatchIn(fullText)
                } ?: ""
            }

            // 适应等级（市街/室外/室内）
            val streetAdapt = findAdaptLevel(fullText, "市街", "市区")
            val outdoorAdapt = findAdaptLevel(fullText, "室外", "野外")
            val indoorAdapt = findAdaptLevel(fullText, "室内", "屋内")

            // 掩体互动
            val coverInteract = when {
                fullText.contains("掩体") -> findValue("掩体").ifEmpty {
                    when {
                        fullText.contains("高掩体") -> "高"
                        fullText.contains("中掩体") -> "中"
                        fullText.contains("低掩体") -> "低"
                        else -> ""
                    }
                }
                else -> ""
            }

            // 学校
            val school = listOf("阿拜多斯", "格黑娜", "千禧年", "崔尼蒂", "歌赫娜",
                "赤冬", "百鬼夜行", "山海经", "瓦尔基里", "SRT", "阿里乌斯"
            ).find { fullText.contains(it) } ?: ""

            // 社团
            val club = findValue("社团", "部活", "团队", "组织").ifEmpty {
                listOf("实现正义委员会", "风纪委员会", "游戏开发部", "圣三一自治区域",
                    "温讬科珀斯", "RABBIT小队"
                ).find { fullText.contains(it) } ?: ""
            }

            // 限定/常驻
            val limitedType = when {
                fullText.contains("限定") -> "限定"
                fullText.contains("常驻") -> "常驻"
                else -> ""
            }

            return@withContext StudentFilterData(
                starRating = starRating,
                limitedType = limitedType,
                attackType = attackType,
                defenseType = defenseType,
                formationPosition = formationPosition,
                combatRole = combatRole,
                frontBackColor = frontBackColor,
                weaponType = weaponType,
                streetAdapt = streetAdapt,
                outdoorAdapt = outdoorAdapt,
                indoorAdapt = indoorAdapt,
                coverInteract = coverInteract,
                school = school,
                club = club
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse student detail for filter data: ${e.message}")
            return@withContext null
        }
    }

    /** 从全文中提取适应等级（S/A/B/C/D） */
    private fun findAdaptLevel(fullText: String, vararg keys: String): String {
        keys.forEach { key ->
            // 尝试匹配 "key：S" 格式
            val regex = Regex("""$key[：:]\s*([SABCD])""")
            regex.find(fullText)?.let { return it.groupValues[1] }
        }
        return ""
    }

    /**
     * 获取学生语音数据
     * 需要使用 WebView 渲染 JS 动态页面
     *
     * 页面结构（JS 渲染后）：
     *   .list-group#{tabId} > .list-group-item > .item-title (场景名)
     *   .list-group-item > .dub > .dub-item > audio[id=voice_audio_{tabId}_{lang}_{idx}]
     *   .list-group-item > .voice-desc (对话文本)
     *   .list-group-item > .translate-box > .list > .text-name (日文/官翻标签) + 文本
     *
     * @param studentLink 学生详情页链接（如 https://www.gamekee.com/ba/tj/59934.html）
     * @return Triple<语音列表, 头像URL, 皮肤立绘Map>
     */
    suspend fun fetchStudentVoices(studentLink: String): Triple<List<VoiceLine>, String, Map<String, String>> =
        withContext(Dispatchers.IO) {
            val voiceUrl = studentLink + "?tab=2"
            Log.d(TAG, "Fetching student voices from: $voiceUrl")

            try {
                // 使用 WebView 渲染 JS 动态页面
                val html = WebViewHtmlFetcher.fetchRenderedHtmlWithCache(context, voiceUrl, waitMs = 8000L)
                val doc = Jsoup.parse(html)

                // 提取头像
                val avatarImg = doc.select(".voice-header .avatar img").firstOrNull()
                val rawAvatar = avatarImg?.attr("src")?.trim()?.trimEnd(',', ' ') ?: ""
                val avatarUrl = if (rawAvatar.isNotEmpty()) cleanGamekeeImageUrl(formatGamekeeUrl(rawAvatar)) else ""

                // 提取学生名
                val studentName = doc.select(".voice-header .name").text().trim()

                // 提取皮肤立绘（官方介绍图作为默认立绘）
                val skinFigureMap = mutableMapOf<String, String>()
                if (avatarUrl.isNotEmpty()) {
                    skinFigureMap["默认"] = avatarUrl
                }

                val voices = mutableListOf<VoiceLine>()

                // 遍历每个语音分类（通常、大厅及咖啡厅、好感度、战斗、成长、事件）
                VOICE_TAB_MAP.forEach { (tabId, tabName) ->
                    val listGroup = doc.select(".list-group#$tabId")
                    listGroup.select(".list-group-item").forEach { item ->
                        try {
                            // 场景名：.item-title（非 .item-header-title）
                            val scene = item.select(".item-title").text().trim()
                            if (scene.isEmpty()) return@forEach

                            // 提取音频：audio id 格式 voice_audio_{tabId}_{lang}_{index}
                            val dubItems = item.select(".dub-item")
                            var jpAudio = ""
                            var cnAudio = ""

                            dubItems.forEach { dubItem ->
                                val audioEl = dubItem.select("audio").firstOrNull() ?: return@forEach
                                val audio = audioEl.attr("src").let { formatGamekeeUrl(it) }
                                val audioId = audioEl.attr("id")

                                when {
                                    audioId.contains("_jp_") -> jpAudio = audio
                                    audioId.contains("_cn_") -> cnAudio = audio
                                }
                            }

                            // 提取对话文本
                            // .voice-desc 包含中文描述，.translate-box .list 包含日文/官翻翻译
                            var cnText = item.select(".voice-desc").text().trim()
                            var jpText = ""

                            // 解析翻译框：.translate-box .list，每个 .list 内有 .text-name 标签
                            item.select(".translate-box .list").forEach { listEl ->
                                val textName = listEl.select(".text-name").text().trim()
                                // 移除标签文本，获取纯内容
                                val fullText = listEl.text().trim()
                                val content = if (textName.isNotEmpty() && fullText.startsWith(textName)) {
                                    fullText.substring(textName.length).trim()
                                } else {
                                    fullText
                                }

                                when {
                                    textName.contains("日文") || textName.contains("日配") -> jpText = content
                                    textName.contains("官翻") || textName.contains("中文") || textName.contains("中配") -> {
                                        if (cnText.isEmpty()) cnText = content
                                    }
                                    else -> {
                                        // 未标记的文本
                                        if (cnText.isEmpty()) cnText = content
                                        else if (jpText.isEmpty()) jpText = content
                                    }
                                }
                            }

                            // 兜底：如果没有明确的中日区分，取第一个文本作为中文
                            val dialogue = cnText.ifEmpty { jpText }
                            val finalCnAudio = cnAudio.ifEmpty { jpAudio }
                            val finalJpAudio = jpAudio.ifEmpty { cnAudio }

                            if (dialogue.isNotEmpty() && (finalCnAudio.isNotEmpty() || finalJpAudio.isNotEmpty())) {
                                voices.add(VoiceLine(tabName, scene, dialogue, finalCnAudio, finalJpAudio))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse voice item: ${e.message}")
                        }
                    }
                }

                if (voices.isEmpty()) {
                    throw IllegalStateException("未找到学生语音数据，页面可能未完全加载。")
                }

                Log.d(TAG, "Found ${voices.size} student voices, avatar=$avatarUrl, name=$studentName")
                Triple(voices, avatarUrl, skinFigureMap)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch student voices", e)
                throw e
            }
        }

    /**
     * 获取学生立绘数据
     * 立绘页同样为 Vue.js SPA，需要使用 WebView 渲染后解析。
     *
     * 页面结构（JS 渲染后）：
     *   #gfjs.img-swiper — 官方介绍（轮播图）
     *   #hydt.base-content — 回忆大厅
     *   #sdj-jp.base-content — 设定集-日文
     *   #sdj-cn.base-content — 设定集-繁中
     *   #yhs.base-content — 本家画
     *   #gfys.base-content — 官方衍生
     *   #emoji.base-content — 表情
     *   #video.base-content — 视频
     *
     * @param studentLink 学生详情页链接
     * @return StudentGallery 包含 8 个分类标签的立绘数据
     */
    suspend fun fetchStudentGallery(studentLink: String): StudentGallery = withContext(Dispatchers.IO) {
        val galleryUrl = studentLink + "?tab=3"
        Log.d(TAG, "Fetching student gallery from: $galleryUrl")

        try {
            // 立绘页也是 Vue.js SPA，需要 WebView 渲染
            val html = WebViewHtmlFetcher.fetchRenderedHtmlWithCache(context, galleryUrl, waitMs = 8000L)
            val doc = Jsoup.parse(html)

            val tabs = mutableListOf<StudentGalleryTab>()
            val studentName = doc.select(".voice-header .name").text().ifEmpty { "学生" }

            StudentGallery.TAB_ID_MAP.forEach { (containerId, tabName) ->
                val images = mutableListOf<StudentGalleryImage>()
                val videos = mutableListOf<StudentGalleryVideo>()

                // 直接通过 ID 选择容器（class 可能是 base-content 或 img-swiper）
                val container = doc.select("#$containerId")
                if (container.isNotEmpty()) {
                    // 提取图片：过滤掉小图标（w_ < 200 的通常是 UI 图标）
                    container.select("img").forEach { img ->
                        val src = img.attr("src").ifEmpty { img.attr("data-src") }
                        if (src.isEmpty() || src.startsWith("data:")) return@forEach

                        // 通过 URL 中的 w_ 参数判断是否为内容图片（非图标）
                        val widthMatch = Regex("""w_(\d+)""").find(src)
                        val width = widthMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        if (width < 200) return@forEach // 跳过小图标

                        val url = cleanGamekeeImageUrl(formatGamekeeUrl(src))
                        val desc = img.attr("alt").ifEmpty { img.attr("title") }
                        images.add(StudentGalleryImage(url, desc))
                    }

                    // 视频标签特殊处理
                    if (containerId == "video") {
                        container.select("video").forEach { videoEl ->
                            // 优先从 src 属性获取视频地址
                            var videoSrc = videoEl.attr("src")
                            // 如果 src 为空，尝试从 <source> 子标签获取
                            if (videoSrc.isEmpty()) {
                                val sourceEl = videoEl.select("source").firstOrNull()
                                videoSrc = sourceEl?.attr("src") ?: ""
                            }
                            if (videoSrc.isNotEmpty()) {
                                val title = videoEl.attr("title")
                                    .ifEmpty { videoEl.attr("alt") }
                                    .ifEmpty { "视频" }
                                videos.add(StudentGalleryVideo(formatGamekeeUrl(videoSrc), title))
                            }
                        }
                    }
                }

                // 去重图片
                val uniqueImages = images.distinctBy { it.url }
                tabs.add(StudentGalleryTab(tabName, uniqueImages, videos))
            }

            val totalItems = tabs.sumOf { it.totalCount }
            Log.d(TAG, "Found student gallery: ${tabs.size} tabs, $totalItems items, name=$studentName")

            if (totalItems == 0) {
                Log.w(TAG, "Gallery has 0 items, page may not have fully loaded")
            }

            StudentGallery(studentName, tabs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch student gallery", e)
            StudentGallery("学生", emptyList())
        }
    }

    /** 格式化 gamekee URL — 补全协议前缀 */
    private fun formatGamekeeUrl(url: String): String {
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "https://www.gamekee.com$url"
            else -> url
        }
    }

    /**
     * 清理 gamekee 图片 URL — 去除重复的 x-image-process 参数，保留必要的格式转换。
     * 原始 URL 可能包含多个重复的 x-image-process 参数，导致请求异常。
     */
    private fun cleanGamekeeImageUrl(url: String): String {
        if (url.isEmpty()) return ""
        // 如果 URL 不含查询参数，直接返回
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return url

        val baseUrl = url.substring(0, qIndex)
        val query = url.substring(qIndex + 1)

        // 拆分参数，去重 x-image-process，只保留最后一个（通常是 format,webp）
        val params = query.split("&").toMutableList()
        val xImageIndices = params.indices.filter { params[it].startsWith("x-image-process=") }

        if (xImageIndices.size > 1) {
            // 保留最后一个 x-image-process（格式转换），移除其余的（resize 等）
            val lastIdx = xImageIndices.last()
            params.removeAll { it.startsWith("x-image-process=") && it != params[lastIdx] }
        }

        return "$baseUrl?${params.joinToString("&")}"
    }
}
