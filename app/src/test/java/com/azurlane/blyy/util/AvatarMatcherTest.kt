package com.azurlane.blyy.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 舰娘头像匹配算法全量回归测试。
 *
 * 数据来源：
 * - `ship_names.txt`：wiki 舰船图鉴（https://wiki.biligame.com/blhx/舰船图鉴）
 *   解析出的全量舰娘中文名（999 个，与 ShipRepository 抓取逻辑同源）。
 * - 资产文件清单优先读取真实 `src/main/assets/blhx_avatar/` 目录（始终与打包内容同步），
 *   读不到时回退到 `avatar_files_snapshot.txt` 快照。
 *
 * 断言含义：
 * 1. [matchAllShips]：名单中每个舰娘要么命中某个头像文件，要么在 [KNOWN_MISSING]
 *    白名单中（assets 确实没有该舰娘资源，运行时回退网络 URL）。
 * 2. [matchedFilesExist]：所有命中的文件必须真实存在于索引中（防索引/匹配错位）。
 */
class AvatarMatcherTest {

    companion object {
        /** 已确认 assets 中无对应资源的舰娘（运行时由网络 URL 兜底），新增失配需先核实再添加 */
        private val KNOWN_MISSING = setOf<String>(
            // 水星号（NO.759 风帆V，2026-10-22 实装）曾登记于此：assets 无 shuixinghao 资源，
            // 修复前经模糊子串跨字拼接误配"彗星"(s[huixing]hao)，音节边界闸门上线后正确失配。
            // 2026-10-23 shuixinghao.webp 已补充并同步快照，恢复 999/999 全量命中，条目移除。
        )

        private fun loadShipNames(): List<String> {
            val stream = AvatarMatcherTest::class.java.classLoader!!.getResourceAsStream("ship_names.txt")
                ?: error("ship_names.txt not found in test resources")
            return stream.bufferedReader(Charsets.UTF_8).readLines()
                .map { it.substringBefore('\t').trim() }
                .filter { it.isNotEmpty() }
        }

        /** 构建与 LocalAvatarResolver 相同规则的小写文件名索引 */
        private fun buildIndex(): Map<String, String> {
            val exts = listOf(".png", ".jpg", ".jpeg", ".webp")
            val files: List<String> = findRealAssetDir()?.listFiles()
                ?.map { it.name }
                ?: AvatarMatcherTest::class.java.classLoader!!.getResourceAsStream("avatar_files_snapshot.txt")!!
                    .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }

            val index = HashMap<String, String>(files.size)
            for (file in files) {
                val lower = file.lowercase()
                val ext = exts.firstOrNull { lower.endsWith(it) } ?: continue
                val key = file.dropLast(ext.length).lowercase()
                val existing = index[key]
                if (existing == null || exts.indexOf(lower.takeLast(ext.length)) < exts.indexOfFirst { existing.lowercase().endsWith(it) }) {
                    index[key] = file
                }
            }
            return index
        }

        private fun findRealAssetDir(): File? {
            val candidates = listOf(
                File("src/main/assets/blhx_avatar"),
                File("app/src/main/assets/blhx_avatar")
            )
            return candidates.firstOrNull { it.isDirectory }
        }
    }

    @Test
    fun matchAllShips() {
        val index = buildIndex()
        val names = loadShipNames()
        println("index size=${index.size}, ship names=${names.size}")

        val misses = mutableListOf<String>()
        val mapping = LinkedHashMap<String, String>()
        for (name in names) {
            val file = AvatarMatcher.match(name, index)
            if (file == null) {
                misses.add(name)
            } else {
                mapping[name] = file
            }
        }

        println("=== MATCHED ${mapping.size}/${names.size} (${ "%.1f".format(mapping.size * 100.0 / names.size) }%) ===")
        if (misses.isNotEmpty()) {
            println("=== MISSES (${misses.size}) ===")
            misses.forEach { println("MISS: $it") }
        }

        val unexpected = misses.filter { it !in KNOWN_MISSING }
        assertTrue("存在未声明的失配舰娘: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun matchedFilesExist() {
        val index = buildIndex()
        loadShipNames().forEach { name ->
            val file = AvatarMatcher.match(name, index) ?: return@forEach
            assertTrue("匹配结果 '$file' 不在索引中 (ship=$name)", index.containsValue(file))
        }
    }

    /**
     * 碰撞审计：同一头像文件被多个舰娘命中时，大概率存在误配
     * （如"英王乔治五世"与"鹰"同时命中 ying.webp）。发现的合法共用需加白名单。
     */
    @Test
    fun noFileCollisions() {
        val index = buildIndex()
        val fileToShips = HashMap<String, MutableList<String>>()
        loadShipNames().forEach { name ->
            AvatarMatcher.match(name, index)?.let { file ->
                fileToShips.getOrPut(file) { mutableListOf() }.add(name)
            }
        }
        val collisions = fileToShips.filter { it.value.size > 1 }
        collisions.forEach { (file, ships) -> println("COLLISION: $file <- $ships") }
        assertTrue("存在多舰命中同一文件: $collisions", collisions.isEmpty())
    }

    /**
     * 输出完整匹配映射，供人工审核误配（如模糊匹配命中他人皮肤文件）。
     * 运行 `gradlew test --tests "*AvatarMatcherTest*"` 后查看测试报告 stdout。
     */
    @Test
    fun dumpMapping() {
        val index = buildIndex()
        loadShipNames().forEach { name ->
            val file = AvatarMatcher.match(name, index) ?: return@forEach
            println("$name -> $file")
        }
    }

    /**
     * 打印所有「命中文件名 ≠ 舰名直接拼音」的匹配——
     * 这些走了变体/模糊/手动映射路径，是误配高风险区，需人工核对。
     */
    @Test
    fun dumpSuspiciousMatches() {
        val index = buildIndex()
        val stems = index.keys
        loadShipNames().forEach { name ->
            val file = AvatarMatcher.match(name, index) ?: return@forEach
            val stem = file.substringBeforeLast('.').lowercase()
            val pinyin = PinyinHelper.toPinyin(name)
            val direct = stem == pinyin ||
                stem == "${pinyin}_h" || stem == "${pinyin}_g" ||
                stem == name.lowercase() || stem == "${name.lowercase()}@dock"
            if (!direct) println("SUSPECT: $name (pinyin=$pinyin) -> $file")
        }
    }
}
