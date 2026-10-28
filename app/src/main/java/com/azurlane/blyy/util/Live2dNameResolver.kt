package com.azurlane.blyy.util

import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.util.PinyinHelper
import com.azurlane.blyy.util.gameimport.GameAssetNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live2D 模型目录名（拼音）→ 中文舰名反查器。
 *
 * 与 [Live2dViewModel] / 查看器共用：模型目录普遍沿用拼音命名（aierdeliqi_4），
 * 依据舰船数据库（Room）做拼音前缀反查还原中文舰名。
 * 进程级单例，持续收集舰船表刷新映射；查不到时回退原目录名，绝不猜测造假。
 */
@Singleton
class Live2dNameResolver @Inject constructor(
    shipDao: ShipDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 真实皮肤名查询钩子（由 [Live2dSkinNameRegistry] 在构造时注册）。
     *
     * 目录名只能给出「换装N」这种序号名，wiki 台词表才能还原成「正月的牵手」
     * 这类真实皮肤名。把钩子挂在反查器上，所有用到 [displayNameFor] 的界面
     * （Live2D 库 / 查看器 / 游戏资源导入 / 立绘查看）都能自动升级，
     * 不必各自复制一份组合逻辑。未注册或序列未就绪时返回 null，回退「换装N」。
     */
    @Volatile
    var skinNameProvider: ((baseName: String, skinIndex: Int) -> String?)? = null

    /** 拼音 → 中文舰名（随舰船库刷新自动重建）；界面层可订阅其就绪时机 */
    val pinyinMap = MutableStateFlow(emptyMap<String, String>())

    init {
        // SD 资源库（含游戏导入的小人）复用同一套反查：目录名 → 中文舰名，
        // 秘书舰据此联动语音/立绘。挂在对象上避免 SD 模块再依赖 DI。
        SDResourceManager.shipNameProvider = { assetId -> baseShipName(assetId) }
        scope.launch {
            shipDao.getAllShips().collect { ships ->
                // 同时写入内置兜底表：舰船库通常覆盖全量，但首次启动、数据库尚未
                // 同步完成、或导入的是联动舰（2B / A2 / 涅普顿 等）时可能查不到，
                // 那样资源卡就只能显示拼音 id。兜底表保证这些名字也解析得出来。
                pinyinMap.value = FALLBACK_NAMES + ships.associate { PinyinHelper.toPinyin(it.name) to it.name }
            }
        }
    }

    companion object {
        /**
         * 内置兜底「拼音 → 舰名」表。
         *
         * 只收录**确定无误**且容易被舰船库漏掉的条目：纯字母数字舰名（资源名小写后
         * 与舰名书写形态不同）、联动舰、以及拼音与资源目录写法有差异的舰娘。
         * 舰船库里的数据优先级更高（同 key 会被覆盖）。
         */
        private val FALLBACK_NAMES: Map<String, String> = mapOf(
            // 纯字母数字舰名：资源目录为小写，显示要还原成官方写法
            "2b" to "2B",
            "a2" to "A2",
            "22" to "22",
            "33" to "33",
            // 常被拼音转换读错 / 目录拼写与拼音不一致
            "juejiang" to "倔强",
            "qianzao" to "如月千早",
            "qiaozhiwushi" to "英王乔治五世",
            "jiahezhanlie" to "加贺BB",
            "zhongjian" to "重剑",
            "qianlai" to "飞鸟川千濑",
            "pucimaosi" to "朴茨茅斯冒险号",
            "bolisi" to "印第安纳波利斯",
            "siwangzhuzai" to "DEAD",
            "zhixiao_doa" to "凪咲(DOA)",
            "bangfeng" to "滨风",
            "kubo" to "库珀",
            "heiyansheshou" to "BLACK★ROCK",
            "tiancheng_cv" to "天城CV",
            "buli_super" to "特装型布里MKIII",
            "kin" to "试作型布里MKII",
            "gin" to "泛用型布里"
        )
    }

    /**
     * 模型显示名：`aierdeliqi_4` → "埃尔德里奇 · 换装4"。
     *
     * 解析顺序（都查不到时保留原目录名）：
     * 1. 精确命中拼音表（`aersasi` → 阿尔萨斯）；
     * 2. 去掉换装序号 / 变体后缀后精确命中（`zhaohe_4_hx` → zhaohe → 肇和）；
     * 3. 前缀匹配（覆盖"阿达尔伯特亲王"→`adaerbote` 这类目录只取舰名前半的情况）；
     * 4. 纯字母数字舰名（`2b` / `a2` / `33`）大小写不敏感命中（`2B` / `A2`）。
     *
     * 换装序号与形态后缀都会拼进显示名，保证同一艘船的不同皮肤一眼能区分。
     */
    fun displayNameFor(id: String): String {
        val variant = GameAssetNaming.variantOf(id)
        val baseId = variant?.first ?: id
        val base = baseShipName(baseId)
            ?: return if (variant != null) "$baseId · ${variant.second}" else id
        val skinIndex = skinIndexOf(baseId)
        val realSkin = skinIndex?.takeIf { it >= 2 }?.let { skinNameProvider?.invoke(base, it) }
        val name = when {
            skinIndex == null || skinIndex < 2 -> base
            realSkin != null -> "$base · $realSkin"
            else -> "$base · 换装$skinIndex"
        }
        return variant?.let { "$name · ${it.second}" } ?: name
    }

    /** 基础中文舰名（无换装/变体后缀），供语音查询等按舰名检索的场景；查不到返回 null */
    fun baseShipName(id: String): String? {
        val base = stripSuffixes(id)
        // 统一小写后比对：资源目录可能保留原始大小写（`2B_2` / `A2` / `HDN101`），
        // 而映射表 key 由 toPinyin 生成、恒为小写。
        val pinyin = base.lowercase()
        if (pinyin.isEmpty()) return null
        val table = pinyinMap.value
        if (table.isEmpty()) return null
        // 1. 精确命中（2B / A2 / 33 这类纯字母数字舰名同样走这条）
        table[pinyin]?.let { return it }
        // 2. 大小写不敏感兜底（历史数据里 key 可能保留了大写）
        table.entries.firstOrNull { it.key.equals(pinyin, ignoreCase = true) }?.let { return it.value }
        // 3. 前缀匹配：目录名是舰名拼音的前缀（"阿达尔伯特亲王" → adaerbote）
        table.entries
            .filter { it.key.lowercase().startsWith(pinyin) && pinyin.length >= 4 }
            .minByOrNull { it.key.length }
            ?.let { return it.value }
        // 4. 反向匹配：目录名比舰名拼音更长（adaerboteqingwang）
        table.entries
            .filter { pinyin.startsWith(it.key.lowercase()) && it.key.length >= 6 }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }
        return null
    }

    /** 去掉换装序号与形态/变体后缀，得到基础资源基名（统一引擎拆解） */
    private fun stripSuffixes(id: String): String = ShipNameMatcher.keyParts(id).core

    /** `xxx_3` → 3；无序号返回 null */
    private fun skinIndexOf(id: String): Int? {
        val tail = id.substringAfterLast('_', "")
        if (tail.isEmpty() || tail.length > 2) return null
        return tail.toIntOrNull()
    }
}
