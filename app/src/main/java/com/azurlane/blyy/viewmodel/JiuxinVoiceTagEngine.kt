package com.azurlane.blyy.viewmodel

import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.data.model.VoiceTagMapping
import kotlin.random.Random

/**
 * 啾信语音标签匹配引擎（从 JiuxinViewModel 提取的纯函数集合）。
 *
 * 职责：把用户聊天文本映射到舰娘语音台词标签（如"你好"→主界面/登录台词），
 * 并在语音列表中挑选最匹配的一条。无任何 ViewModel 状态依赖，可独立测试。
 */
internal object JiuxinVoiceTagEngine {

    /** 默认皮肤名集合（用于语音随机池优先匹配）— 统一定义，避免多处硬编码不一致 */
    val DEFAULT_SKIN_NAMES: Set<String> = setOf("默认装扮", "默认", "通常", "原装")

    // ── 语音标签映射优化 (日常口语对应专业台词标签) ──
    private val defaultVoiceTagMappings: List<VoiceTagMapping> = listOf(
        // 1. 登录界面 & 登录台词 (映射: 登录台词, 登录界面)
        VoiceTagMapping("你好", listOf("主界面", "登录台词", "登录界面"), priority = 5),
        VoiceTagMapping("早安", listOf("主界面", "登录台词"), priority = 5),
        VoiceTagMapping("午安", listOf("主界面", "登录台词"), priority = 5),
        VoiceTagMapping("晚安", listOf("主界面", "登录台词"), priority = 5),
        VoiceTagMapping("进游戏", listOf("登录界面", "登录台词"), priority = 7),
        VoiceTagMapping("启动", listOf("登录界面", "登录台词"), priority = 6),
        VoiceTagMapping("开服", listOf("登录界面"), priority = 8),
        VoiceTagMapping("登录台词", listOf("登录台词", "登录界面"), priority = 9),
        VoiceTagMapping("登录界面", listOf("登录界面", "登录台词"), priority = 9),

        // 2. 舰船型号 & 自我介绍 (映射: 舰船型号, 自我介绍)
        VoiceTagMapping("自我介绍", listOf("自我介绍", "舰船型号"), priority = 9),
        VoiceTagMapping("你是谁", listOf("自我介绍", "舰船型号"), priority = 7),
        VoiceTagMapping("叫什么名字", listOf("自我介绍", "舰船型号"), priority = 7),
        VoiceTagMapping("介绍一下", listOf("自我介绍", "舰船型号"), priority = 8),
        VoiceTagMapping("舰船型号", listOf("舰船型号", "自我介绍"), priority = 9),
        VoiceTagMapping("什么船", listOf("舰船型号"), priority = 7),
        VoiceTagMapping("什么舰种", listOf("舰船型号"), priority = 7),

        // 3. 获取台词 & 查看详情 (映射: 获取台词, 查看详情)
        VoiceTagMapping("获取台词", listOf("获取台词", "获得"), priority = 9),
        VoiceTagMapping("出了", listOf("获取台词", "获得"), priority = 7),
        VoiceTagMapping("捞到了", listOf("获取台词", "获得"), priority = 8),
        VoiceTagMapping("查看详情", listOf("查看详情", "获取台词"), priority = 9),
        VoiceTagMapping("详情", listOf("查看详情", "获取台词"), priority = 6),
        VoiceTagMapping("资料", listOf("查看详情", "获取台词"), priority = 7),
        VoiceTagMapping("属性", listOf("查看详情"), priority = 7),

        // 4. 主界面 (映射: 主界面)
        VoiceTagMapping("主界面", listOf("主界面"), priority = 9),
        VoiceTagMapping("主页", listOf("主界面"), priority = 7),
        VoiceTagMapping("看板娘", listOf("主界面"), priority = 8),
        VoiceTagMapping("在干嘛", listOf("主界面"), priority = 6),

        // 5. 触摸、特殊触摸、摸头 (映射: 触摸台词, 特殊触摸, 摸头台词)
        VoiceTagMapping("触摸台词", listOf("触摸台词"), priority = 9),
        VoiceTagMapping("碰你", listOf("触摸台词"), priority = 6),
        VoiceTagMapping("特殊触摸", listOf("特殊触摸"), priority = 9),
        VoiceTagMapping("别碰那里", listOf("特殊触摸"), priority = 8),
        VoiceTagMapping("色狼", listOf("特殊触摸"), priority = 8),
        VoiceTagMapping("变态", listOf("特殊触摸"), priority = 8),
        VoiceTagMapping("摸头台词", listOf("摸头台词", "触摸台词"), priority = 9),
        VoiceTagMapping("摸摸头", listOf("摸头台词", "触摸台词"), priority = 8),
        VoiceTagMapping("乖", listOf("摸头台词", "触摸台词"), priority = 7),

        // 6. 任务与邮件 (映射: 任务提醒, 任务完成, 鄌件提酲)
        VoiceTagMapping("任务提醒", listOf("任务提醒", "任务完成"), priority = 9),
        VoiceTagMapping("任务", listOf("任务提醒", "任务完成"), priority = 6),
        VoiceTagMapping("有任务吗", listOf("任务提醒"), priority = 7),
        VoiceTagMapping("任务完成", listOf("任务完成", "任务提醒"), priority = 9),
        VoiceTagMapping("做完了", listOf("任务完成", "任务提醒"), priority = 8),
        VoiceTagMapping("领奖励", listOf("任务完成", "任务提醒"), priority = 7),
        VoiceTagMapping("报酬", listOf("任务完成"), priority = 7),
        VoiceTagMapping("鄌件提酲", listOf("鄌件提酲", "邮件提醒"), priority = 9), // 兼容 typo 标签
        VoiceTagMapping("邮件提醒", listOf("邮件提醒", "鄌件提酲"), priority = 9),
        VoiceTagMapping("有信吗", listOf("邮件提醒", "鄌件提酲"), priority = 7),
        VoiceTagMapping("收邮件", listOf("邮件提醒", "鄌件提酲"), priority = 8),

        // 7. 回港 (映射: 回港台词)
        VoiceTagMapping("回港台词", listOf("回港台词", "登录台词"), priority = 9),
        VoiceTagMapping("我回来了", listOf("回港台词", "登录台词"), priority = 8),
        VoiceTagMapping("到家了", listOf("回港台词", "登录台词"), priority = 8),
        VoiceTagMapping("辛苦了", listOf("回港台词", "主界面"), priority = 7),

        // 8. 好感度与誓约 (映射: 好感度-友好, 好感度-喜欢, 好感度-爱, 誓约台词)
        VoiceTagMapping("好感度-友好", listOf("好感度-友好", "主界面"), priority = 9),
        VoiceTagMapping("友好", listOf("好感度-友好"), priority = 7),
        VoiceTagMapping("朋友", listOf("好感度-友好"), priority = 6),
        VoiceTagMapping("好感度-喜欢", listOf("好感度-喜欢", "好感度-爱"), priority = 9),
        VoiceTagMapping("喜欢", listOf("好感度-喜欢", "好感度-爱"), priority = 6),
        VoiceTagMapping("好感度-爱", listOf("好感度-爱", "誓约台词"), priority = 9, preferredSkinName = "誓约"),
        VoiceTagMapping("爱", listOf("好感度-爱", "誓约台词"), priority = 5, preferredSkinName = "誓约"),
        VoiceTagMapping("最喜欢了", listOf("好感度-爱", "誓约台词"), priority = 8, preferredSkinName = "誓约"),
        VoiceTagMapping("誓约台词", listOf("誓约台词", "好感度-爱"), priority = 9, preferredSkinName = "誓约"),
        VoiceTagMapping("结婚", listOf("誓约台词", "好感度-爱"), priority = 8, preferredSkinName = "誓约"),
        VoiceTagMapping("戒指", listOf("誓约台词", "好感度-爱"), priority = 8, preferredSkinName = "誓约"),
        VoiceTagMapping("嫁给我", listOf("誓约台词", "好感度-爱"), priority = 8, preferredSkinName = "誓约"),

        // 9. 强化与改造 (映射: 强化成功, 改造完成)
        VoiceTagMapping("强化", listOf("强化成功", "主界面"), priority = 8),
        VoiceTagMapping("升级", listOf("强化成功", "主界面"), priority = 7),
        VoiceTagMapping("改造", listOf("改造完成", "强化成功"), priority = 9),
        VoiceTagMapping("变强了", listOf("强化成功", "改造完成"), priority = 7),

        // 10. 战斗相关 (映射: 战斗台词, 技能触发, 胜利台词, 失败台词, 低血量)
        VoiceTagMapping("出击", listOf("战斗台词", "主界面"), priority = 9),
        VoiceTagMapping("开火", listOf("战斗台词", "技能触发"), priority = 7),
        VoiceTagMapping("技能", listOf("技能触发", "战斗台词"), priority = 9),
        VoiceTagMapping("胜利", listOf("胜利台词", "回港台词"), priority = 9),
        VoiceTagMapping("赢了", listOf("胜利台词", "回港台词"), priority = 8),
        VoiceTagMapping("失败", listOf("失败台词", "回港台词"), priority = 9),
        VoiceTagMapping("没血了", listOf("低血量", "主界面"), priority = 8),
        VoiceTagMapping("快不行了", listOf("低血量"), priority = 8),

        // 11. 委派与建造 (映射: 委派完成, 建造完成)
        VoiceTagMapping("委派", listOf("委派完成", "任务完成"), priority = 8),
        VoiceTagMapping("远征", listOf("委派完成", "任务完成"), priority = 8),
        VoiceTagMapping("建造", listOf("建造完成", "获取台词"), priority = 8),
        VoiceTagMapping("造好了", listOf("建造完成"), priority = 8),

        // 12. 状态与好感度保底 (映射: 旗舰台词, 失望, 陌生)
        VoiceTagMapping("旗舰", listOf("旗舰台词", "主界面"), priority = 8),
        VoiceTagMapping("失望", listOf("好感度-失望"), priority = 9),
        VoiceTagMapping("讨厌你", listOf("好感度-失望"), priority = 8),
        VoiceTagMapping("不认识", listOf("好感度-陌生"), priority = 8)
    )

    /**
     * 默认语音标签映射的关键词索引（O(1) 查找）。
     *
     * 用于 [buildVoiceTagMappings] 中判断用户自定义关键词是否已存在默认映射，
     * 替代 O(n) 线性扫描。
     */
    private val defaultVoiceTagByKeyword: Map<String, VoiceTagMapping> by lazy {
        defaultVoiceTagMappings.associateBy { it.keyword }
    }

    /**
     * 根据语音触发关键词列表构建完整的 voice tag mappings（去重 + 按优先级降序）。
     *
     * - 默认映射全部保留
     * - 用户自定义关键词若与默认重名则跳过（默认优先级更高，避免 distinctBy 后丢失默认映射）
     * - 用户自定义关键词若无对应默认映射，创建 fallback 映射：sceneTags=["主界面"]，priority=1
     *   （"主界面"是最通用的语音类别，确保用户自定义关键词能触发有意义的语音，
     *   而非 [findBestVoice] 的保底随机池；priority=1 确保默认关键词优先匹配）
     * - 按 priority 降序排列，确保 [findBestTagMatch] 在长度相同时优先取高优先级
     *
     * @param keywords 已分词的关键词列表（不含空串）
     */
    fun buildVoiceTagMappings(keywords: List<String>): List<VoiceTagMapping> {
        val userMappings = keywords.mapNotNull { kw ->
            if (defaultVoiceTagByKeyword.containsKey(kw)) null
            else VoiceTagMapping(keyword = kw, sceneTags = listOf("主界面"), priority = 1)
        }
        return (defaultVoiceTagMappings + userMappings)
            .distinctBy { it.keyword }
            .sortedByDescending { it.priority }
    }

    /**
     * 在用户文本中查找最佳匹配的语音标签映射
     * 优先匹配更长的关键词（避免"爱"误匹配"可爱"等），
     * 长度相同时取优先级更高的
     */
    fun findBestTagMatch(text: String, mappings: List<VoiceTagMapping>): VoiceTagMapping? =
        mappings
            .filter { text.contains(it.keyword, ignoreCase = true) }
            .maxWithOrNull(compareBy({ it.keyword.length }, { it.priority }))

    /**
     * 根据场景标签和偏好皮肤查找最佳语音
     * 解决"同一关键词可对应触发不同皮肤的同一标签语音"问题。
     */
    fun findBestVoice(
        voices: List<VoiceLine>,
        sceneTags: List<String>,
        preferredSkinName: String = ""
    ): VoiceLine {
        // 1. 策略：如果指定了偏好皮肤（如：好感度-爱强制要求"誓约"皮肤台词）
        if (preferredSkinName.isNotBlank()) {
            sceneTags.firstNotNullOfOrNull { tag ->
                voices.filter {
                    it.skinName.contains(preferredSkinName, ignoreCase = true) &&
                        it.scene.contains(tag, ignoreCase = true)
                }.randomOrNull() // 随机抽取该皮肤下的对应标签台词
            }?.let { return it }
        }

        // 2. 策略：跨皮肤随机池
        // 依次检查场景标签，收集所有皮肤中匹配该标签的台词，实现"同一标签、不同皮肤"的惊喜感
        sceneTags.forEach { tag ->
            val pool = voices.filter { it.scene.contains(tag, ignoreCase = true) }
            if (pool.isNotEmpty()) {
                // 优先考虑：默认皮肤或无皮肤台词（权重稍大），但包含所有其他换装皮肤
                val defaultPool = pool.filter { it.skinName in DEFAULT_SKIN_NAMES || it.skinName.isEmpty() }
                // 80% 概率触发默认/通常语音，20% 概率触发已拥有的其他皮肤语音
                return if (defaultPool.isNotEmpty() && Random.nextFloat() < 0.8f) {
                    defaultPool.random()
                } else {
                    pool.random()
                }
            }
        }

        // 3. 保底：优先从默认皮肤中随机选一条
        val defaultVoice = voices.filter { it.skinName in DEFAULT_SKIN_NAMES }.randomOrNull()
        return defaultVoice ?: voices.random()
    }
}
