package com.azurlane.blyy.data.local

import android.os.Build
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.azurlane.blyy.data.backup.PrefBackup
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.viewmodel.PlayMode
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.azurlane.blyy.util.ChibiFrameRate
import com.azurlane.blyy.util.SECRETARY_SD_SCALE_MAX
import com.azurlane.blyy.util.SECRETARY_SD_SCALE_MIN
import com.azurlane.blyy.util.security.SecretCipher

/** 容错 JSON 实例：忽略未知字段，避免模型升级后反序列化崩溃导致数据"静默消失" */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "player_settings")

@Singleton
class PlayerSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PlayerSettingsDataStore"
        private val PLAY_MODE_KEY = stringPreferencesKey("play_mode")
        // 播放器产品化（v3.2）：倍速偏好 + 会话快照（杀进程后继续听）
        private val PLAYBACK_SPEED_KEY = floatPreferencesKey("playback_speed")
        private val PLAYER_SESSION_KEY = stringPreferencesKey("player_session")
        private val FAVORITES_KEY = stringPreferencesKey("favorites")
        private val PLAY_LATER_KEY = stringPreferencesKey("play_later_v2")
        private const val FIGURE_PREFIX = "fig_"

        // 今日秘书舰
        private val SECRETARY_SHIP_NAME_KEY = stringPreferencesKey("secretary_ship_name")
        private val SECRETARY_FIGURE_URL_KEY = stringPreferencesKey("secretary_figure_url")
        private val SECRETARY_AVATAR_URL_KEY = stringPreferencesKey("secretary_avatar_url")
        // wiki 网页 Q版小人回退图（冷启动零网络恢复悬浮小人，避免弱网/离线时悬浮窗空白）
        private val SECRETARY_WEB_CHIBI_URL_KEY = stringPreferencesKey("secretary_web_chibi_url")
        // 桌面小组件独立角色域：组件「换人」选出的舰娘与应用内秘书舰完全隔离，
        // 组件展示/语音播放只读写这两个键，绝不触碰 secretary_* 系列
        private val WIDGET_SHIP_NAME_KEY = stringPreferencesKey("widget_ship_name")
        private val WIDGET_SHIP_AVATAR_KEY = stringPreferencesKey("widget_ship_avatar_url")
        // 桌面小组件台词：换人/播放语音时捕获的一句真实台词（空=未捕获/已失效，
        // 小组件回退到内置时段问候语）；_at 为捕获时间戳，超期后小组组件不再使用
        private val SECRETARY_WIDGET_LINE_KEY = stringPreferencesKey("secretary_widget_line")
        private val SECRETARY_WIDGET_LINE_AT_KEY = longPreferencesKey("secretary_widget_line_at")
        // 桌面小组件语音表持久缓存（单 key：仅当前组件舰，JSON 含舰名/时间戳/台词表），
        // 换人/语音/进程重启后免二次 wiki 抓取，点击秒级响应
        private val WIDGET_VOICES_CACHE_KEY = stringPreferencesKey("widget_voices_cache")
        private val SECRETARY_AUTO_PLAY_ENABLED_KEY = booleanPreferencesKey("secretary_auto_play_enabled")
        private val SECRETARY_AUTO_PLAY_INTERVAL_KEY = intPreferencesKey("secretary_auto_play_interval")
        private val SECRETARY_DIALOGUE_ENABLED_KEY = booleanPreferencesKey("secretary_dialogue_enabled")
        private val SECRETARY_SD_SKIN_KEY = stringPreferencesKey("secretary_sd_skin")
        private val SECRETARY_SD_SCALE_KEY = floatPreferencesKey("secretary_sd_scale")
        // 自定义 SD 资源 ID（空=跟随舰名匹配，非空=直接按 ID 加载该资源）
        private val SECRETARY_SD_RESOURCE_ID_KEY = stringPreferencesKey("secretary_sd_resource_id")
        // SD 小人渲染帧率（30=省电档，60=默认档；见 util/ChibiFrameRate）
        private val SECRETARY_SD_FRAME_RATE_KEY = intPreferencesKey("secretary_sd_frame_rate")
        
        // 语音语言
        private val VOICE_LANGUAGE_KEY = stringPreferencesKey("voice_language")
        
        // 悬浮窗状态
        private val SECRETARY_OVERLAY_ENABLED_KEY = booleanPreferencesKey("secretary_overlay_enabled")
        // 悬浮窗触摸穿透（true=触摸事件穿透到下层应用，false=正常响应触摸交互）
        private val SECRETARY_OVERLAY_TOUCH_PASSTHROUGH_KEY = booleanPreferencesKey("secretary_overlay_touch_passthrough")
        // 悬浮窗主窗口位置（px，屏幕绝对坐标）。服务启动时恢复，拖动/缩放结束时写回，
        // 使小人"停留"在用户上次摆放的位置（不再每次重启都回到默认 100,300）
        private val SECRETARY_OVERLAY_POS_X_KEY = intPreferencesKey("secretary_overlay_pos_x")
        private val SECRETARY_OVERLAY_POS_Y_KEY = intPreferencesKey("secretary_overlay_pos_y")

        // 外观设置
        private val UI_STYLE_KEY = stringPreferencesKey("ui_style")
        private val FORCE_DARK_THEME_KEY = booleanPreferencesKey("force_dark_theme")
        private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
        private val HIDE_STATUS_BAR_KEY = booleanPreferencesKey("hide_status_bar")

        // 船坞顶栏「舰娘/学生」档案切换按钮开关（关闭后隐藏切换器，顶栏自动回收空间）
        private val SHOW_ARCHIVE_SWITCHER_KEY = booleanPreferencesKey("show_archive_switcher")

        // 游戏资源导入页排序方案（"name_asc" / "time_desc"，空 = 默认名称正序）
        private val GAME_IMPORT_SORT_KEY = stringPreferencesKey("game_import_sort")

        // 游戏资源导入页被「不再提示」关掉的设备提示 code（按提示身份记，不是单个布尔）
        private val GAME_IMPORT_DISMISSED_NOTICES_KEY =
            stringSetPreferencesKey("game_import_dismissed_notices")

        // Live2D 查看器互动语音开关
        private val LIVE2D_VOICE_ENABLED_KEY = booleanPreferencesKey("live2d_voice_enabled")

        // Live2D 查看器语音台词气泡开关
        private val LIVE2D_BUBBLE_ENABLED_KEY = booleanPreferencesKey("live2d_bubble_enabled")

        // Live2D 查看器陪伴模式：随机动作触发间隔（秒）/ 显示时钟日期 / 显示电量
        private val LIVE2D_COMPANION_INTERVAL_SEC_KEY = intPreferencesKey("live2d_companion_interval_sec")
        private val LIVE2D_COMPANION_SHOW_CLOCK_KEY = booleanPreferencesKey("live2d_companion_show_clock")
        private val LIVE2D_COMPANION_SHOW_BATTERY_KEY = booleanPreferencesKey("live2d_companion_show_battery")

        // Live2D 查看器陀螺仪注视/视差：开关 + 灵敏度（百分比）
        private val LIVE2D_GYRO_ENABLED_KEY = booleanPreferencesKey("live2d_gyro_enabled")
        private val LIVE2D_GYRO_GAIN_KEY = intPreferencesKey("live2d_gyro_gain_percent")

        // 自动检测更新
        private val AUTO_CHECK_UPDATE_ENABLED_KEY = booleanPreferencesKey("auto_check_update_enabled")
        private val SKIPPED_UPDATE_VERSION_KEY = stringPreferencesKey("skipped_update_version")

        // 网盘链接持久化缓存（跨重启兜底，避免 CDN 缓存或网络失败导致 App 拿不到链接）
        private val CACHED_DRIVE_LINK_JSON_KEY = stringPreferencesKey("cached_drive_link_json")
        private val CACHED_DRIVE_LINK_AT_KEY = androidx.datastore.preferences.core.longPreferencesKey("cached_drive_link_at")

        // SD 资源下载链接持久化缓存（与网盘链接缓存机制相同，独立存储避免互相覆盖）
        private val CACHED_SD_RESOURCE_LINK_JSON_KEY = stringPreferencesKey("cached_sd_resource_link_json")
        private val CACHED_SD_RESOURCE_LINK_AT_KEY = androidx.datastore.preferences.core.longPreferencesKey("cached_sd_resource_link_at")

        // Live2D 皮肤名序列持久化缓存（舰名 → 已解析的换装序列 JSON，含每条目的落库时间戳）
        private val LIVE2D_SKIN_SEQUENCES_JSON_KEY = stringPreferencesKey("live2d_skin_sequences_json")

        // 小助手配置
        private val ASSISTANT_DEFAULT_UID_KEY = stringPreferencesKey("assistant_default_uid")
        private val ASSISTANT_DEFAULT_SERVER_KEY = stringPreferencesKey("assistant_default_server")

        // 指挥官昵称：显示在「我的成绩榜」标题与战绩分享；仅本地使用，不上传。
        // 旧键名 leaderboard_nickname 属于已下线的全服排行榜，启动时由
        // [purgeLegacyLeaderboardData] 迁移到新键并删除旧键。
        private val COMMANDER_NICKNAME_KEY = stringPreferencesKey("commander_nickname")
        private const val LEGACY_COMMANDER_NICKNAME_KEY_NAME = "leaderboard_nickname"

        // AI 配置
        private val AI_API_KEY_KEY = stringPreferencesKey("ai_api_key")
        private val AI_CUSTOM_BASE_URL_KEY = stringPreferencesKey("ai_custom_base_url")
        private val AI_SYSTEM_PROMPT_KEY = stringPreferencesKey("ai_system_prompt")
        private val AI_NAME_KEY = stringPreferencesKey("ai_name")
        private val AI_AVATAR_URL_KEY = stringPreferencesKey("ai_avatar_url")
        private val AI_VOICE_ENABLED_KEY = booleanPreferencesKey("ai_voice_enabled")
        private val AI_VOICE_RANDOM_CHANCE_KEY = floatPreferencesKey("ai_voice_random_chance")
        private val AI_VOICE_KEYWORDS_KEY = stringPreferencesKey("ai_voice_keywords")
        private val AI_CHAT_HISTORY_KEY = stringPreferencesKey("ai_chat_history")
        private val AI_MODEL_KEY = stringPreferencesKey("ai_model")
        private val AI_VOICE_SHIP_NAME_KEY = stringPreferencesKey("ai_voice_ship_name")
        private val AI_VOICE_SHIP_AVATAR_KEY = stringPreferencesKey("ai_voice_ship_avatar")
        private val AI_STICKERS_ENABLED_KEY = booleanPreferencesKey("ai_stickers_enabled")
        private val AI_STICKER_CHANCE_KEY = floatPreferencesKey("ai_sticker_chance")

        // 历史对话会话管理
        private val AI_CHAT_SESSIONS_KEY = stringPreferencesKey("ai_chat_sessions")
        private val AI_CURRENT_SESSION_ID_KEY = stringPreferencesKey("ai_current_session_id")

        // 啾信预设配置列表（多套 API + 舰娘人格）
        private val AI_JIUXIN_PRESETS_KEY = stringPreferencesKey("ai_jiuxin_presets")
        // 会话列表自定义排序（用户长按拖动后的顺序，存储 session id 列表）
        private val AI_SESSION_ORDER_KEY = stringPreferencesKey("ai_session_order")
        // 聊天背景图片 URL（空字符串表示使用默认纯色背景）
        private val AI_CHAT_BACKGROUND_URL_KEY = stringPreferencesKey("ai_chat_background_url")
        // 多套 API 配置列表（独立于舰娘人格，可复用）
        private val AI_API_CONFIGS_KEY = stringPreferencesKey("ai_api_configs")
        // 多套舰娘人格配置列表（独立于 API 配置，可组合）
        private val AI_PERSONA_CONFIGS_KEY = stringPreferencesKey("ai_persona_configs")
        // 舰娘长期记忆（key = 舰娘身份 shipKey，value = PersonaMemory）
        private val AI_PERSONA_MEMORIES_KEY = stringPreferencesKey("ai_persona_memories")
        // 用户（指挥官）配置
        private val USER_NAME_KEY = stringPreferencesKey("user_name")
        private val USER_AVATAR_URL_KEY = stringPreferencesKey("user_avatar_url")

        // 旧版「全服排行榜」的本地缓存 key 已废弃（排行榜改为纯本地历史成绩榜，
        // 不再有远端数据）。历史遗留值由 [purgeLegacyLeaderboardData] 在启动时清除。

        // ── 密文自愈用的隔离区 ──
        // 若某段 JSON 是密文但当前 Keystore 解不开（换机恢复备份、清除应用密钥、
        // 签名/包名变更），直接覆盖写会**永久丢掉**用户的人格/配置。这里在覆盖前
        // 把解不开的原始密文挪到隔离 key，至少给后续人工/工具恢复留一份原料。
        private val AI_CHAT_SESSIONS_ORPHAN_KEY = stringPreferencesKey("ai_chat_sessions_orphan")
        private val AI_API_CONFIGS_ORPHAN_KEY = stringPreferencesKey("ai_api_configs_orphan")
        private val AI_JIUXIN_PRESETS_ORPHAN_KEY = stringPreferencesKey("ai_jiuxin_presets_orphan")

        // ── 备份 / 恢复 ──
        private val AUTO_BACKUP_ENABLED_KEY = booleanPreferencesKey("auto_backup_enabled")
        private val LAST_BACKUP_AT_KEY = longPreferencesKey("last_backup_at")
        private val LAST_RESTORE_AT_KEY = longPreferencesKey("last_restore_at")
        /** 恢复时舰娘表尚未建立 → 暂存待补的誓约名单，下次名录刷新后自动应用 */
        private val PENDING_OATH_RESTORE_KEY = stringPreferencesKey("pending_oath_restore")

        /**
         * 不进备份的 key 前缀（凭证 / 设备相关 / 可重建缓存）。
         *
         * 这份名单是**安全边界**而不是性能优化：
         * - ai_api_key / ai_api_configs / ai_chat_sessions / ai_jiuxin_presets 含凭证或凭证副本；
         * - assistant_default_uid/server 属隐私标识；
         * - secretary_overlay_pos_* 是屏幕像素坐标，换设备即失效；
         * - app_icon 与 app_custom_icon 指向本机文件路径；
         * - archive_cache_ts 与 widget_voices_cache 是可重建缓存；
         * - 备份自身状态（last_*）与待补标记不参与覆盖，避免恢复把"上次备份时间"弄乱。
         */
        private val BACKUP_EXCLUDED_PREFIXES = listOf(
            "ai_api_key", "ai_api_configs", "ai_chat_sessions", "ai_session_msgs_",
            "ai_current_session_id", "ai_jiuxin_presets",
            "assistant_default_",
            "secretary_overlay_pos_",
            "app_custom_icon", "app_icon_",
            "cached_drive_link", "cached_sd_resource_link",
            "archive_cache_ts", "widget_voices_cache",
            "last_backup_at", "last_restore_at", "pending_oath_restore", "auto_backup_enabled",
            "ai_chat_sessions_orphan", "ai_api_configs_orphan", "ai_jiuxin_presets_orphan"
        )

        /** 迁移/清理时识别旧排行榜遗留 key（含旧昵称，迁移后删除） */
        private const val LEGACY_LEADERBOARD_KEY_PREFIX = "leaderboard_"

        // 档案缓存时间戳（DOCK/STUDENT 各一份），用于多级缓存过期策略
        private val ARCHIVE_CACHE_TIMESTAMP_DOCK_KEY = androidx.datastore.preferences.core.longPreferencesKey("archive_cache_ts_dock")
        private val ARCHIVE_CACHE_TIMESTAMP_STUDENT_KEY = androidx.datastore.preferences.core.longPreferencesKey("archive_cache_ts_student")

        // ── 自定义 App 图标 ──
        /** 当前图标类型（DEFAULT / CLASSIC / BLUE / PURPLE / CUSTOM），对应 AppIconType 枚举 */
        private val APP_ICON_TYPE_KEY = stringPreferencesKey("app_icon_type")
        /** 自定义图标的内部存储路径（仅 CUSTOM 类型有效，空表示未设置） */
        private val APP_CUSTOM_ICON_PATH_KEY = stringPreferencesKey("app_custom_icon_path")
        /** 图标选择时间戳（用于升级后保持设置、排查切换问题） */
        private val APP_ICON_SELECTED_AT_KEY = androidx.datastore.preferences.core.longPreferencesKey("app_icon_selected_at")

        /** 档案缓存过期时间：5 分钟（毫秒） */
        const val ARCHIVE_CACHE_EXPIRY_MS = 5 * 60 * 1000L

        /** 播放倍速边界（播放器倍速选择器共用，避免 UI 与存储两套门槛） */
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 2.0f

        /** 陀螺仪灵敏度百分比边界与默认值 */
        const val GYRO_GAIN_MIN = 40
        const val GYRO_GAIN_MAX = 220
        const val GYRO_GAIN_DEFAULT = 100
    }

    /**
     * 带容错的数据流：捕获 DataStore IO 异常（文件损坏/权限/存储空间不足/并发写入冲突），
     * 发射空 Preferences 兜底，避免异常传播到 collect 导致 ViewModel 协程崩溃闪退。
     *
     * 背景：部分设备（尤其是定制 ROM 或低存储空间）首次启动或并发写入时，
     * DataStore 可能抛出 IOException。原代码直接使用 [context.dataStore.data]，
     * 异常会传播到 JiuxinViewModel.init 的 collector，导致 ViewModel 创建失败 → 闪退。
     *
     * 发射 emptyPreferences() 后，下游 map 会读到各 Flow 的默认兜底值（"" / emptyList()），
     * 与原有的 `?: ""` / `?: emptyList()` 逻辑配合，保证 UI 永不崩溃。
     *
     * 注意：仅捕获读取异常；写入异常（edit {} 内）仍由调用方的 try-catch 处理。
     *
     * 重试与自愈：Flow 抛出异常后上游即终止，catch 兜底发射 emptyPreferences 后整条数据流
     * 会**正常完成**——若不重启收集，所有派生 Flow（含 Eagerly 的三张配置表）将永久冻结在
     * 兜底空值，后续任何"读快照 → 全量覆盖写"都会基于空列表清掉已存数据，且 UI 不再收到
     * 更新。因此对瞬时 IO 异常（并发写冲突/低存储/ROM 抖动）先重试 3 次；重试耗尽后兜底
     * 发射一轮空值（保证本轮不崩溃），随后重启一轮上游收集——下次读取成功即自动恢复。
     */
    private val safeData: Flow<Preferences> = flow {
        while (currentCoroutineContext().isActive) {
            context.dataStore.data
                .retryWhen { cause, attempt ->
                    val shouldRetry = cause is IOException && attempt < 3
                    if (shouldRetry) {
                        Log.w(TAG, "DataStore read failed (attempt ${attempt + 1}), retrying", cause)
                        delay(100L * (attempt + 1))
                    }
                    shouldRetry
                }
                .catch { e ->
                    Log.e(TAG, "DataStore read failed, falling back to empty preferences", e)
                    emit(androidx.datastore.preferences.core.emptyPreferences())
                }
                .collect { emit(it) }
            // 走到这里说明本轮流已正常完成（重试耗尽后兜底），稍作退避后重启收集自愈
            delay(1000L)
        }
    }

    val playMode: Flow<PlayMode> = safeData
        .map { preferences: Preferences ->
            val modeName = preferences[PLAY_MODE_KEY] ?: PlayMode.PLAY_ONCE.name
            try {
                PlayMode.valueOf(modeName)
            } catch (e: Exception) {
                PlayMode.PLAY_ONCE
            }
        }

    val favorites: Flow<Set<String>> = safeData
        .map { preferences: Preferences ->
            preferences[FAVORITES_KEY]?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        }

    val playLaterList: Flow<List<PlayLaterItem>> = safeData
        .map { preferences: Preferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            try {
                Json.decodeFromString<List<PlayLaterItem>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }

    fun getSavedFigure(shipName: String): Flow<String?> = safeData
        .map { it[stringPreferencesKey(FIGURE_PREFIX + shipName)] }

    // 今日秘书舰
    val secretaryShipName: Flow<String> = safeData.map { it[SECRETARY_SHIP_NAME_KEY] ?: "" }
    val secretaryFigureUrl: Flow<String> = safeData.map { it[SECRETARY_FIGURE_URL_KEY] ?: "" }
    val secretaryAvatarUrl: Flow<String> = safeData.map { it[SECRETARY_AVATAR_URL_KEY] ?: "" }

    /** wiki 网页 Q版小人回退图 URL（本地 SD 资源未匹配时的悬浮小人显示图） */
    val secretaryWebChibiUrl: Flow<String> = safeData.map { it[SECRETARY_WEB_CHIBI_URL_KEY] ?: "" }

    /** 桌面小组件独立角色：舰名（空=从未抽选，组件显示引导文案） */
    val widgetShipName: Flow<String> = safeData.map { it[WIDGET_SHIP_NAME_KEY] ?: "" }

    /** 桌面小组件独立角色：头像 URL */
    val widgetShipAvatarUrl: Flow<String> = safeData.map { it[WIDGET_SHIP_AVATAR_KEY] ?: "" }

    /** 桌面小组件台词文本（空=未捕获，小组件回退内置问候语） */
    val secretaryWidgetLine: Flow<String> = safeData.map { it[SECRETARY_WIDGET_LINE_KEY] ?: "" }

    /** 组件语音表持久缓存原文（JSON，空=无缓存）；解码见 widget 包 decodeWidgetVoicesCache */
    val widgetVoicesCacheJson: Flow<String> = safeData.map { it[WIDGET_VOICES_CACHE_KEY] ?: "" }

    /** 桌面小组件台词捕获时间戳（0=无效） */
    val secretaryWidgetLineAt: Flow<Long> = safeData.map { it[SECRETARY_WIDGET_LINE_AT_KEY] ?: 0L }
    val secretaryAutoPlayEnabled: Flow<Boolean> = safeData.map { it[SECRETARY_AUTO_PLAY_ENABLED_KEY] ?: false }
    val secretaryAutoPlayIntervalMinutes: Flow<Int> = safeData.map { it[SECRETARY_AUTO_PLAY_INTERVAL_KEY] ?: 5 }
    val secretaryDialogueEnabled: Flow<Boolean> = safeData.map { it[SECRETARY_DIALOGUE_ENABLED_KEY] ?: true }

    /** 秘书舰 SD 小人皮肤名（"" / "default" 用默认皮肤，"gai" / "skin2" 等为其他皮肤） */
    val secretarySdSkin: Flow<String> = safeData.map { it[SECRETARY_SD_SKIN_KEY] ?: "" }

    /** 秘书舰 SD 小人显示缩放倍率（1.0 = 默认自适应大小，0.5 = 半尺寸，1.5 = 放大 50%） */
    val secretarySdScale: Flow<Float> = safeData.map { it[SECRETARY_SD_SCALE_KEY] ?: 1.0f }

    /** 秘书舰自定义 SD 资源 ID（空=跟随舰名匹配，非空=直接按 ID 加载该资源，解除舰名限制） */
    val secretarySdResourceId: Flow<String> = safeData.map { it[SECRETARY_SD_RESOURCE_ID_KEY] ?: "" }

    /** 秘书舰 SD 小人渲染帧率（30 或 60；历史脏数据/未设置回落到 [ChibiFrameRate.FPS_DEFAULT]） */
    val secretarySdFrameRate: Flow<Int> = safeData.map {
        ChibiFrameRate.normalize(it[SECRETARY_SD_FRAME_RATE_KEY] ?: ChibiFrameRate.FPS_DEFAULT)
    }
    
    // 悬浮窗状态
    val secretaryOverlayEnabled: Flow<Boolean> = safeData.map { it[SECRETARY_OVERLAY_ENABLED_KEY] ?: false }

    // 悬浮窗触摸穿透
    val secretaryOverlayTouchPassthrough: Flow<Boolean> = safeData.map { it[SECRETARY_OVERLAY_TOUCH_PASSTHROUGH_KEY] ?: false }

    // 悬浮窗主窗口位置（px；null=从未保存，服务用默认位置）
    val secretaryOverlayPosX: Flow<Int?> = safeData.map { it[SECRETARY_OVERLAY_POS_X_KEY] }
    val secretaryOverlayPosY: Flow<Int?> = safeData.map { it[SECRETARY_OVERLAY_POS_Y_KEY] }

    // 外观
    val uiStyle: Flow<UiStyle> = safeData.map { prefs ->
        when (prefs[UI_STYLE_KEY]) {
            UiStyle.CLASSIC.name -> UiStyle.CLASSIC
            else -> UiStyle.COMMAND_CENTER
        }
    }

    /** 默认 false — 跟随系统深浅色设置 */
    val forceDarkTheme: Flow<Boolean> = safeData.map { it[FORCE_DARK_THEME_KEY] ?: false }

    /** Material You 动态取色 — Android 12+ 默认开启 */
    val dynamicColorEnabled: Flow<Boolean> = safeData.map { prefs ->
        prefs[DYNAMIC_COLOR_KEY] ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    /** 沉浸式：隐藏状态栏，默认开启。用户可从屏幕顶部下滑临时呼出（sticky immersive） */
    val hideStatusBar: Flow<Boolean> = safeData.map { it[HIDE_STATUS_BAR_KEY] ?: true }

    /** 船坞顶栏「舰娘/学生」档案切换按钮，默认开启；关闭后隐藏切换器并自动回收顶栏空间 */
    val showArchiveSwitcher: Flow<Boolean> = safeData.map { it[SHOW_ARCHIVE_SWITCHER_KEY] ?: true }

    /**
     * 游戏资源导入页的排序方案（编码 `name_asc` / `time_desc`，空 = 从未设置）。
     *
     * 存原始字符串而不是枚举：解析放在 UI 侧（`GameSortOrder.parse`），
     * 脏数据只影响「记不住上次排序」，不会让设置读取链路抛异常。
     */
    val gameImportSort: Flow<String> = safeData.map { it[GAME_IMPORT_SORT_KEY] ?: "" }

    /**
     * 已被用户点「不再提示」关掉的设备提示 code 集合。
     *
     * 按 code 而不是一个布尔值记：将来新增另一种设备提示时，老用户关掉的只是旧提示，
     * 新提示照常出现，不会被一起静默掉。
     */
    val gameImportDismissedNotices: Flow<Set<String>> =
        safeData.map { it[GAME_IMPORT_DISMISSED_NOTICES_KEY] ?: emptySet() }

    /** Live2D 查看器：点击皮肤互动时播放对应语音，默认开启 */
    val live2dVoiceEnabled: Flow<Boolean> = safeData.map { it[LIVE2D_VOICE_ENABLED_KEY] ?: true }

    /** Live2D 查看器：语音播放时同步显示台词气泡，默认开启 */
    val live2dBubbleEnabled: Flow<Boolean> = safeData.map { it[LIVE2D_BUBBLE_ENABLED_KEY] ?: true }

    /** Live2D 查看器陪伴模式：随机动作触发间隔（秒），默认 3 分钟 */
    val live2dCompanionIntervalSec: Flow<Int> =
        safeData.map { it[LIVE2D_COMPANION_INTERVAL_SEC_KEY] ?: 180 }

    /** Live2D 查看器陪伴模式：右下角显示当前时间日期，默认开启 */
    val live2dCompanionShowClock: Flow<Boolean> =
        safeData.map { it[LIVE2D_COMPANION_SHOW_CLOCK_KEY] ?: true }

    /** Live2D 查看器陪伴模式：右下角显示设备电量，默认开启 */
    val live2dCompanionShowBattery: Flow<Boolean> =
        safeData.map { it[LIVE2D_COMPANION_SHOW_BATTERY_KEY] ?: true }

    /** Live2D 查看器陀螺仪注视/视差：默认开启（幅值轻微不抢戏，可在陪伴设置中关闭） */
    val live2dGyroEnabled: Flow<Boolean> =
        safeData.map { it[LIVE2D_GYRO_ENABLED_KEY] ?: true }

    /** Live2D 查看器陀螺仪灵敏度（百分比，40..220），默认 100 */
    val live2dGyroGainPercent: Flow<Int> =
        safeData.map { (it[LIVE2D_GYRO_GAIN_KEY] ?: GYRO_GAIN_DEFAULT).coerceIn(GYRO_GAIN_MIN, GYRO_GAIN_MAX) }

    /** 自动检测更新开关，默认开启 */
    val autoCheckUpdateEnabled: Flow<Boolean> = safeData.map { it[AUTO_CHECK_UPDATE_ENABLED_KEY] ?: true }

    /** 用户跳过的更新版本号，用于"稍后提醒"功能 */
    val skippedUpdateVersion: Flow<String> = safeData.map { it[SKIPPED_UPDATE_VERSION_KEY] ?: "" }

    // 语音语言
    val voiceLanguage: Flow<VoiceLanguage> = safeData
        .map { preferences: Preferences ->
            val langName = preferences[VOICE_LANGUAGE_KEY] ?: VoiceLanguage.CN.name
            try {
                VoiceLanguage.valueOf(langName)
            } catch (e: Exception) {
                VoiceLanguage.CN
            }
        }

    /**
     * 保存秘书舰（舰名/立绘/头像/网页小人一次成组原子写入，
     * 避免恢复时读到一半新一半旧的组合状态）
     */
    suspend fun saveSecretaryShip(shipName: String, figureUrl: String, avatarUrl: String, webChibiUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_SHIP_NAME_KEY] = shipName
            prefs[SECRETARY_FIGURE_URL_KEY] = figureUrl
            prefs[SECRETARY_AVATAR_URL_KEY] = avatarUrl
            prefs[SECRETARY_WEB_CHIBI_URL_KEY] = webChibiUrl
        }
    }

    /** 单独更新网页 Q版小人 URL（后台补齐抓取成功后落盘，供冷启动零网络恢复） */
    suspend fun setSecretaryWebChibiUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_WEB_CHIBI_URL_KEY] = url
        }
    }

    /**
     * 桌面小组件独立角色落盘（舰名/头像一次成组原子写入）。
     * 与 secretary_* 域完全隔离——组件换人绝不影响应用内秘书舰。
     */
    suspend fun setWidgetShip(shipName: String, avatarUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[WIDGET_SHIP_NAME_KEY] = shipName
            prefs[WIDGET_SHIP_AVATAR_KEY] = avatarUrl
        }
    }

    /**
     * 保存桌面小组件台词（文本与时间戳一次成组原子写入）。
     * 空行表示"无有效台词"，时间戳同步清零让小组件立即回退问候语。
     */
    suspend fun setSecretaryWidgetLine(line: String) {
        context.dataStore.edit { prefs ->
            if (line.isBlank()) {
                prefs[SECRETARY_WIDGET_LINE_KEY] = ""
                prefs[SECRETARY_WIDGET_LINE_AT_KEY] = 0L
            } else {
                prefs[SECRETARY_WIDGET_LINE_KEY] = line.trim()
                prefs[SECRETARY_WIDGET_LINE_AT_KEY] = System.currentTimeMillis()
            }
        }
    }

    /** 组件语音表持久缓存写入（覆盖式单 key，仅保留当前组件舰） */
    suspend fun setWidgetVoicesCache(json: String) {
        context.dataStore.edit { prefs ->
            prefs[WIDGET_VOICES_CACHE_KEY] = json
        }
    }

    suspend fun clearSecretaryShip() {
        context.dataStore.edit { prefs ->
            prefs.remove(SECRETARY_SHIP_NAME_KEY)
            prefs.remove(SECRETARY_FIGURE_URL_KEY)
            prefs.remove(SECRETARY_AVATAR_URL_KEY)
            prefs.remove(SECRETARY_WEB_CHIBI_URL_KEY)
            prefs.remove(SECRETARY_SD_SKIN_KEY)
            prefs.remove(SECRETARY_SD_RESOURCE_ID_KEY)
            // 注意：不清 SECRETARY_SD_SCALE_KEY —— 缩放是用户对"悬浮小人"的全局
            // 尺寸偏好，与具体舰娘无关；换舰/清除不应把尺寸重置回 1.0
        }
    }

    suspend fun setSecretaryAutoPlay(enabled: Boolean, intervalMinutes: Int = 5) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_AUTO_PLAY_ENABLED_KEY] = enabled
            prefs[SECRETARY_AUTO_PLAY_INTERVAL_KEY] = intervalMinutes.coerceIn(1, 60)
        }
    }

    suspend fun setSecretaryDialogueEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_DIALOGUE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSecretarySdSkin(skin: String) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_SD_SKIN_KEY] = skin
        }
    }

    suspend fun setSecretarySdScale(scale: Float) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_SD_SCALE_KEY] = scale.coerceIn(SECRETARY_SD_SCALE_MIN, SECRETARY_SD_SCALE_MAX)
        }
    }

    suspend fun setSecretarySdResourceId(resourceId: String) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_SD_RESOURCE_ID_KEY] = resourceId
        }
    }

    suspend fun setSecretarySdFrameRate(frameRate: Int) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_SD_FRAME_RATE_KEY] = ChibiFrameRate.normalize(frameRate)
        }
    }
    
    // 悬浮窗状态
    suspend fun setSecretaryOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_OVERLAY_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSecretaryOverlayTouchPassthrough(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_OVERLAY_TOUCH_PASSTHROUGH_KEY] = enabled
        }
    }

    /** 悬浮窗主窗口位置一次性原子写入（x/y 必须成对，避免读到一半新一半旧） */
    suspend fun setSecretaryOverlayPosition(x: Int, y: Int) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_OVERLAY_POS_X_KEY] = x
            prefs[SECRETARY_OVERLAY_POS_Y_KEY] = y
        }
    }

    suspend fun setUiStyle(style: UiStyle) {
        context.dataStore.edit { prefs ->
            prefs[UI_STYLE_KEY] = style.name
        }
    }

    suspend fun setForceDarkTheme(force: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[FORCE_DARK_THEME_KEY] = force
        }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DYNAMIC_COLOR_KEY] = enabled
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[HIDE_STATUS_BAR_KEY] = hide
        }
    }

    suspend fun setShowArchiveSwitcher(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SHOW_ARCHIVE_SWITCHER_KEY] = enabled
        }
    }

    /** 保存游戏资源导入页排序方案（见 [gameImportSort]） */
    suspend fun setGameImportSort(value: String) {
        context.dataStore.edit { prefs ->
            prefs[GAME_IMPORT_SORT_KEY] = value
        }
    }

    /**
     * 记住「不再提示」的设备提示 code（见 [gameImportDismissedNotices]）。
     *
     * 已存在就不写盘：DataStore 每次 edit 都会重新发一遍所有 Flow，用户连点两下按钮时
     * 没必要让整条设置链路抖动一次。
     */
    suspend fun dismissGameImportNotice(code: String) {
        if (code.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[GAME_IMPORT_DISMISSED_NOTICES_KEY].orEmpty()
            if (code !in current) prefs[GAME_IMPORT_DISMISSED_NOTICES_KEY] = current + code
        }
    }

    /** 撤销「不再提示」：清空记忆，被隐藏的设备提示重新出现 */
    suspend fun restoreGameImportNotices() {
        context.dataStore.edit { prefs -> prefs.remove(GAME_IMPORT_DISMISSED_NOTICES_KEY) }
    }

    suspend fun setLive2dVoiceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LIVE2D_VOICE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setLive2dBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LIVE2D_BUBBLE_ENABLED_KEY] = enabled
        }
    }

    /** 陪伴模式三选项一次落盘（间隔钳制到 30 秒 ~ 30 分钟） */
    suspend fun setLive2dCompanionConfig(intervalSec: Int, showClock: Boolean, showBattery: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LIVE2D_COMPANION_INTERVAL_SEC_KEY] = intervalSec.coerceIn(30, 1800)
            prefs[LIVE2D_COMPANION_SHOW_CLOCK_KEY] = showClock
            prefs[LIVE2D_COMPANION_SHOW_BATTERY_KEY] = showBattery
        }
    }

    /** 陀螺仪注视/视差两选项一次落盘（灵敏度钳制到 GYRO_GAIN_MIN..MAX） */
    suspend fun setLive2dGyroConfig(enabled: Boolean, gainPercent: Int) {
        context.dataStore.edit { prefs ->
            prefs[LIVE2D_GYRO_ENABLED_KEY] = enabled
            prefs[LIVE2D_GYRO_GAIN_KEY] = gainPercent.coerceIn(GYRO_GAIN_MIN, GYRO_GAIN_MAX)
        }
    }

    suspend fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_CHECK_UPDATE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSkippedUpdateVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[SKIPPED_UPDATE_VERSION_KEY] = version
        }
    }

    suspend fun setVoiceLanguage(language: VoiceLanguage) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_LANGUAGE_KEY] = language.name
        }
    }

    suspend fun saveFigure(shipName: String, figureUrl: String) {
        context.dataStore.edit { it[stringPreferencesKey(FIGURE_PREFIX + shipName)] = figureUrl }
    }

    suspend fun savePlayMode(mode: PlayMode) {
        context.dataStore.edit { preferences: MutablePreferences ->
            preferences[PLAY_MODE_KEY] = mode.name
        }
    }

    // ── 播放器产品化：倍速 / 会话快照 ──

    /** 播放倍速（0.5x – 2.0x，默认 1.0）。台词练习场景常用 0.75x。 */
    val playbackSpeed: Flow<Float> = safeData.map { prefs ->
        (prefs[PLAYBACK_SPEED_KEY] ?: 1f).coerceIn(SPEED_MIN, SPEED_MAX)
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[PLAYBACK_SPEED_KEY] = speed.coerceIn(SPEED_MIN, SPEED_MAX) }
    }

    /** 上次播放会话（JSON）；空串表示没有可恢复的会话 */
    val playerSession: Flow<String> = safeData.map { it[PLAYER_SESSION_KEY] ?: "" }

    suspend fun setPlayerSession(json: String) {
        context.dataStore.edit { it[PLAYER_SESSION_KEY] = json }
    }

    suspend fun clearPlayerSession() {
        context.dataStore.edit { it.remove(PLAYER_SESSION_KEY) }
    }

    suspend fun toggleFavorite(voiceUrl: String) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val currentString = preferences[FAVORITES_KEY] ?: ""
            val currentSet = currentString.split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (voiceUrl in currentSet) {
                currentSet.remove(voiceUrl)
            } else {
                currentSet.add(voiceUrl)
            }
            preferences[FAVORITES_KEY] = currentSet.joinToString(",")
        }
    }

    suspend fun addToPlayLater(item: PlayLaterItem) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<PlayLaterItem>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            if (currentList.none { it.voiceUrl == item.voiceUrl }) {
                currentList.add(0, item) // Add to top
                preferences[PLAY_LATER_KEY] = Json.encodeToString(currentList)
            }
        }
    }

    suspend fun removeFromPlayLater(voiceUrl: String) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<PlayLaterItem>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.voiceUrl == voiceUrl }
            preferences[PLAY_LATER_KEY] = Json.encodeToString(currentList)
        }
    }

    suspend fun clearPlayLaterList() {
        context.dataStore.edit { preferences: MutablePreferences ->
            preferences[PLAY_LATER_KEY] = "[]"
        }
    }

    suspend fun markAsPlayed(voiceUrl: String) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<PlayLaterItem>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val updatedList = currentList.map { item ->
                if (item.voiceUrl == voiceUrl) {
                    item.copy(hasPlayed = true)
                } else {
                    item
                }
            }
            preferences[PLAY_LATER_KEY] = Json.encodeToString(updatedList)
        }
    }

    // ── 小助手配置（敏感：加密落盘，见 SecretCipher） ──

    /** 小助手默认 UID（密文落盘；历史明文读取时原样返回，下次写入自动加密） */
    val assistantDefaultUid: Flow<String> = safeData
        .map { SecretCipher.decrypt(it[ASSISTANT_DEFAULT_UID_KEY]) }
        .flowOn(Dispatchers.Default)

    /** 小助手默认服务器名/ID（密文落盘） */
    val assistantDefaultServer: Flow<String> = safeData
        .map { SecretCipher.decrypt(it[ASSISTANT_DEFAULT_SERVER_KEY]) }
        .flowOn(Dispatchers.Default)

    suspend fun setAssistantDefaultUid(uid: String) {
        val encrypted = SecretCipher.encrypt(uid)
        context.dataStore.edit { it[ASSISTANT_DEFAULT_UID_KEY] = encrypted }
    }

    suspend fun setAssistantDefaultServer(server: String) {
        val encrypted = SecretCipher.encrypt(server)
        context.dataStore.edit { it[ASSISTANT_DEFAULT_SERVER_KEY] = encrypted }
    }

    // ── 指挥官昵称 ──

    /**
     * 指挥官昵称：成绩榜页标题显示；不再用于任何上传。
     *
     * 读取时兼容旧键 `leaderboard_nickname`（老用户升级后昵称不丢），
     * 下一次写入即落到新键；[purgeLegacyLeaderboardData] 会把旧键彻底清掉。
     */
    val commanderNickname: Flow<String> = safeData.map { prefs ->
        prefs[COMMANDER_NICKNAME_KEY]
            ?: prefs[stringPreferencesKey(LEGACY_COMMANDER_NICKNAME_KEY_NAME)].orEmpty()
    }

    suspend fun setCommanderNickname(nickname: String) {
        context.dataStore.edit { it[COMMANDER_NICKNAME_KEY] = nickname.trim() }
    }

    // ── AI 配置 ──

    /** AI API Key（密文落盘） */
    val aiApiKey: Flow<String> = safeData
        .map { SecretCipher.decrypt(it[AI_API_KEY_KEY]) }
        .flowOn(Dispatchers.Default)
    val aiCustomBaseUrl: Flow<String> = safeData.map { it[AI_CUSTOM_BASE_URL_KEY] ?: "" }
    val aiSystemPrompt: Flow<String> = safeData.map { it[AI_SYSTEM_PROMPT_KEY] ?: "" }
    val aiName: Flow<String> = safeData.map { it[AI_NAME_KEY] ?: "" }
    val aiAvatarUrl: Flow<String> = safeData.map { it[AI_AVATAR_URL_KEY] ?: "" }
    val aiVoiceEnabled: Flow<Boolean> = safeData.map { it[AI_VOICE_ENABLED_KEY] ?: true }
    val aiVoiceRandomChance: Flow<Float> = safeData.map {
        it[AI_VOICE_RANDOM_CHANCE_KEY] ?: 0.1f
    }
    val aiVoiceKeywords: Flow<String> = safeData.map { it[AI_VOICE_KEYWORDS_KEY] ?: "你好;早安;晚安;加油;辛苦了" }
    val aiChatHistory: Flow<String> = safeData.map { it[AI_CHAT_HISTORY_KEY] ?: "[]" }
    val aiModel: Flow<String> = safeData.map { it[AI_MODEL_KEY] ?: "" }
    val aiVoiceShipName: Flow<String> = safeData.map { it[AI_VOICE_SHIP_NAME_KEY] ?: "" }
    val aiVoiceShipAvatar: Flow<String> = safeData.map { it[AI_VOICE_SHIP_AVATAR_KEY] ?: "" }
    val aiStickersEnabled: Flow<Boolean> = safeData.map { it[AI_STICKERS_ENABLED_KEY] ?: true }
    val aiStickerChance: Flow<Float> = safeData.map { it[AI_STICKER_CHANCE_KEY] ?: 0.8f }

    suspend fun setAiApiKey(key: String) {
        val encrypted = SecretCipher.encrypt(key)
        context.dataStore.edit { it[AI_API_KEY_KEY] = encrypted }
    }
    suspend fun setAiCustomBaseUrl(url: String) { context.dataStore.edit { it[AI_CUSTOM_BASE_URL_KEY] = url } }
    suspend fun setAiSystemPrompt(prompt: String) { context.dataStore.edit { it[AI_SYSTEM_PROMPT_KEY] = prompt } }
    suspend fun setAiName(name: String) { context.dataStore.edit { it[AI_NAME_KEY] = name } }
    suspend fun setAiAvatarUrl(url: String) { context.dataStore.edit { it[AI_AVATAR_URL_KEY] = url } }
    suspend fun setAiVoiceEnabled(enabled: Boolean) { context.dataStore.edit { it[AI_VOICE_ENABLED_KEY] = enabled } }
    suspend fun setAiVoiceRandomChance(chance: Float) { context.dataStore.edit { it[AI_VOICE_RANDOM_CHANCE_KEY] = chance.coerceIn(0f, 1f) } }
    suspend fun setAiVoiceKeywords(keywords: String) { context.dataStore.edit { it[AI_VOICE_KEYWORDS_KEY] = keywords } }
    suspend fun setAiModel(model: String) { context.dataStore.edit { it[AI_MODEL_KEY] = model } }
    suspend fun setAiVoiceShipName(name: String) { context.dataStore.edit { it[AI_VOICE_SHIP_NAME_KEY] = name } }
    suspend fun setAiVoiceShipAvatar(avatar: String) { context.dataStore.edit { it[AI_VOICE_SHIP_AVATAR_KEY] = avatar } }
    suspend fun setAiStickersEnabled(enabled: Boolean) { context.dataStore.edit { it[AI_STICKERS_ENABLED_KEY] = enabled } }
    suspend fun setAiStickerChance(chance: Float) { context.dataStore.edit { it[AI_STICKER_CHANCE_KEY] = chance.coerceIn(0f, 1f) } }
    suspend fun clearAiChatHistory() { context.dataStore.edit { it[AI_CHAT_HISTORY_KEY] = "[]" } }

    // ── 历史对话会话管理 ──

    /**
     * 所有会话元数据列表。
     *
     * 会话快照内含 `apiKey`（历史设计为"会话保存当时的 API Key"，见 [ChatSession]），
     * 因此整个 JSON 以密文落盘：即使被读到 Preferences 文件，也不会直接暴露 Key。
     */
    val aiChatSessions: Flow<List<com.azurlane.blyy.data.model.ChatSession>> = safeData.map { prefs ->
        val json = readSecretJson(prefs, AI_CHAT_SESSIONS_KEY, "[]")
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ChatSession>>(json) } catch (e: Exception) { Log.w(TAG, "Failed to decode chat sessions", e); emptyList() }
    }.flowOn(Dispatchers.Default)

    /** 当前会话 ID */
    val aiCurrentSessionId: Flow<String> = safeData.map { it[AI_CURRENT_SESSION_ID_KEY] ?: "" }

    /** 保存会话列表（整段 JSON 加密落盘） */
    suspend fun setAiChatSessions(sessions: List<com.azurlane.blyy.data.model.ChatSession>) {
        val encoded = SecretCipher.encrypt(lenientJson.encodeToString(sessions))
        context.dataStore.edit { prefs ->
            preserveUndecryptable(prefs, AI_CHAT_SESSIONS_KEY, AI_CHAT_SESSIONS_ORPHAN_KEY)
            prefs[AI_CHAT_SESSIONS_KEY] = encoded
        }
    }

    /** 设置当前会话 ID */
    suspend fun setAiCurrentSessionId(id: String) {
        context.dataStore.edit { it[AI_CURRENT_SESSION_ID_KEY] = id }
    }

    /** 获取指定会话的消息 */
    fun aiSessionMessages(sessionId: String): Flow<List<com.azurlane.blyy.data.model.ChatMessage>> = safeData.map { prefs ->
        val key = stringPreferencesKey("ai_session_msgs_$sessionId")
        val json = prefs[key] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ChatMessage>>(json) } catch (e: Exception) { Log.w(TAG, "Failed to decode session messages for $sessionId", e); emptyList() }
    }.flowOn(Dispatchers.Default)

    /** 保存指定会话的消息 */
    suspend fun setAiSessionMessages(sessionId: String, messages: List<com.azurlane.blyy.data.model.ChatMessage>) {
        context.dataStore.edit { it[stringPreferencesKey("ai_session_msgs_$sessionId")] = lenientJson.encodeToString(messages) }
    }

    /** 删除指定会话的消息数据 */
    suspend fun deleteAiSessionMessages(sessionId: String) {
        context.dataStore.edit { it.remove(stringPreferencesKey("ai_session_msgs_$sessionId")) }
    }

    // ── 啾信预设配置管理 ──

    /** 所有已保存的啾信预设列表（内含 API Key，整段加密落盘） */
    val aiJiuxinPresets: Flow<List<com.azurlane.blyy.data.model.JiuxinPreset>> = safeData.map { prefs ->
        val json = readSecretJson(prefs, AI_JIUXIN_PRESETS_KEY, "[]")
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.JiuxinPreset>>(json) } catch (e: Exception) { Log.w(TAG, "Failed to decode presets", e); emptyList() }
    }.flowOn(Dispatchers.Default)

    /**
     * 预设列表原子更新：在 edit 事务内"读取当前落盘值 → 变换 → 写回"。
     *
     * 修复"保存一定数量后继续保存失效"：此前调用方基于 StateFlow 快照（滞后于 DataStore
     * 落盘回流，写队列积压时滞后可达秒级）拼接新列表后全量覆盖写回，后写会抹掉先写，
     * 造成已存条目丢失。edit 由 DataStore 串行化，事务内读到的永远是最新值，消除丢更新竞态。
     */
    suspend fun updateAiJiuxinPresets(transform: (List<com.azurlane.blyy.data.model.JiuxinPreset>) -> List<com.azurlane.blyy.data.model.JiuxinPreset>) {
        context.dataStore.edit { prefs ->
            val json = readSecretJson(prefs, AI_JIUXIN_PRESETS_KEY, "[]")
            val current = try {
                lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.JiuxinPreset>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode presets during atomic update, treating as empty", e)
                emptyList()
            }
            preserveUndecryptable(prefs, AI_JIUXIN_PRESETS_KEY, AI_JIUXIN_PRESETS_ORPHAN_KEY)
            prefs[AI_JIUXIN_PRESETS_KEY] = SecretCipher.encrypt(lenientJson.encodeToString(transform(current)))
        }
    }

    /** 会话列表自定义排序（session id 列表，用户拖动后的顺序） */
    val aiSessionOrder: Flow<List<String>> = safeData.map { prefs ->
        val json = prefs[AI_SESSION_ORDER_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<String>>(json) } catch (e: Exception) { Log.w(TAG, "Failed to decode session order", e); emptyList() }
    }

    /** 保存会话列表自定义排序 */
    suspend fun setAiSessionOrder(order: List<String>) {
        context.dataStore.edit { it[AI_SESSION_ORDER_KEY] = lenientJson.encodeToString(order) }
    }

    /** 聊天背景图片 URL（空表示使用默认纯色背景） */
    val aiChatBackgroundUrl: Flow<String> = safeData.map { it[AI_CHAT_BACKGROUND_URL_KEY] ?: "" }

    /** 保存聊天背景图片 URL */
    suspend fun setAiChatBackgroundUrl(url: String) {
        context.dataStore.edit { it[AI_CHAT_BACKGROUND_URL_KEY] = url }
    }

    // ── 多套 API 配置管理 ──

    /** 所有已保存的 API 配置列表（内含 API Key，整段加密落盘） */
    val aiApiConfigs: Flow<List<com.azurlane.blyy.data.model.ApiConfig>> = safeData.map { prefs ->
        val json = readSecretJson(prefs, AI_API_CONFIGS_KEY, "[]")
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ApiConfig>>(json) } catch (e: Exception) { Log.w(TAG, "Failed to decode API configs", e); emptyList() }
    }.flowOn(Dispatchers.Default)

    /** API 配置列表原子更新，机制见 [updateAiJiuxinPresets] */
    suspend fun updateAiApiConfigs(transform: (List<com.azurlane.blyy.data.model.ApiConfig>) -> List<com.azurlane.blyy.data.model.ApiConfig>) {
        context.dataStore.edit { prefs ->
            val json = readSecretJson(prefs, AI_API_CONFIGS_KEY, "[]")
            val current = try {
                lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ApiConfig>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode API configs during atomic update, treating as empty", e)
                emptyList()
            }
            preserveUndecryptable(prefs, AI_API_CONFIGS_KEY, AI_API_CONFIGS_ORPHAN_KEY)
            prefs[AI_API_CONFIGS_KEY] = SecretCipher.encrypt(lenientJson.encodeToString(transform(current)))
        }
    }

    // ── 多套舰娘人格配置管理 ──

    /** 所有已保存的舰娘人格配置列表 */
    val aiPersonaConfigs: Flow<List<com.azurlane.blyy.data.model.PersonaConfig>> = safeData.map { prefs ->
        val json = prefs[AI_PERSONA_CONFIGS_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.PersonaConfig>>(json) } catch (e: Exception) { Log.w(TAG, "Failed to decode persona configs", e); emptyList() }
    }.flowOn(Dispatchers.Default)

    /**
     * 舰娘人格配置列表原子更新，机制见 [updateAiJiuxinPresets]。
     *
     * 这是"已保存人格达到一定数量后，继续保存人格失效/丢失"的根治点：
     * 新增/编辑/删除人格都应在 transform 内基于最新落盘列表操作，禁止调用方
     * 持有过期快照后全量覆盖。
     */
    suspend fun updateAiPersonaConfigs(transform: (List<com.azurlane.blyy.data.model.PersonaConfig>) -> List<com.azurlane.blyy.data.model.PersonaConfig>) {
        context.dataStore.edit { prefs ->
            val json = prefs[AI_PERSONA_CONFIGS_KEY] ?: "[]"
            val current = try {
                lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.PersonaConfig>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode persona configs during atomic update, treating as empty", e)
                emptyList()
            }
            prefs[AI_PERSONA_CONFIGS_KEY] = lenientJson.encodeToString(transform(current))
        }
    }

    // ── 舰娘长期记忆管理 ──

    /** 所有舰娘的长期记忆（key = computeShipKey 身份标识） */
    val aiPersonaMemories: Flow<Map<String, com.azurlane.blyy.data.model.PersonaMemory>> = safeData.map { prefs ->
        val json = prefs[AI_PERSONA_MEMORIES_KEY] ?: "{}"
        try {
            lenientJson.decodeFromString<Map<String, com.azurlane.blyy.data.model.PersonaMemory>>(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode persona memories", e)
            emptyMap()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * 长期记忆原子更新，机制见 [updateAiJiuxinPresets]。
     * 摘要写入与用户手动编辑/清空可能并发，必须基于 edit 事务内的最新落盘值变换。
     */
    suspend fun updateAiPersonaMemories(
        transform: (Map<String, com.azurlane.blyy.data.model.PersonaMemory>) -> Map<String, com.azurlane.blyy.data.model.PersonaMemory>
    ) {
        context.dataStore.edit { prefs ->
            val json = prefs[AI_PERSONA_MEMORIES_KEY] ?: "{}"
            val current = try {
                lenientJson.decodeFromString<Map<String, com.azurlane.blyy.data.model.PersonaMemory>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode persona memories during atomic update, treating as empty", e)
                emptyMap()
            }
            prefs[AI_PERSONA_MEMORIES_KEY] = lenientJson.encodeToString(transform(current))
        }
    }

    // ── 用户（指挥官）配置 ──
    val userName: Flow<String> = safeData.map { it[USER_NAME_KEY] ?: "指挥官" }
    val userAvatarUrl: Flow<String> = safeData.map { it[USER_AVATAR_URL_KEY] ?: "" }

    suspend fun setUserName(name: String) { context.dataStore.edit { it[USER_NAME_KEY] = name } }
    suspend fun setUserAvatarUrl(url: String) { context.dataStore.edit { it[USER_AVATAR_URL_KEY] = url } }

    // ── 安全存储：敏感字段加密迁移 ──

    /** 需要加密落盘的 key（含凭证或在凭证快照里出现的 JSON 块） */
    private val secretKeys = listOf(
        AI_API_KEY_KEY,
        AI_API_CONFIGS_KEY,
        AI_CHAT_SESSIONS_KEY,
        AI_JIUXIN_PRESETS_KEY,
        ASSISTANT_DEFAULT_UID_KEY,
        ASSISTANT_DEFAULT_SERVER_KEY
    )

    /**
     * 读取加密 JSON：解不开时返回兜底值（UI 照常可用），**不会被当成"数据被删"**。
     * 原始密文在下次写入前由 [preserveUndecryptable] 挪进隔离 key。
     */
    private fun readSecretJson(
        prefs: Preferences,
        key: Preferences.Key<String>,
        fallback: String
    ): String {
        val raw = prefs[key] ?: return fallback
        val decoded = SecretCipher.tryDecrypt(raw) ?: return fallback
        return decoded.ifBlank { fallback }
    }

    /**
     * 覆盖写之前的保险丝：当前值是密文、但当前 Keystore 解不开时，先原样存进隔离 key。
     * 代价只有一次额外的 preferences 写入，换来的是"换机/恢复备份后配置不会凭空消失"。
     */
    private fun preserveUndecryptable(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        orphanKey: Preferences.Key<String>
    ) {
        val raw = prefs[key] ?: return
        if (!SecretCipher.isEncrypted(raw)) return
        if (SecretCipher.tryDecrypt(raw) != null) return
        prefs[orphanKey] = raw
        Log.w(TAG, "preserved undecryptable blob: ${key.name} -> ${orphanKey.name}")
    }

    /**
     * 把历史明文敏感字段就地替换为密文。
     *
     * 幂等：已加密的键全部跳过；Keystore 不可用时 [SecretCipher.encrypt] 会降级明文，
     * 因此本方法不会把可用配置写坏。启动时在 IO 线程调用一次即可。
     *
     * @return 本次实际加密的字段数（0 表示无需迁移或已迁移）
     */
    suspend fun migrateSecretsToEncrypted(): Int = withContext(Dispatchers.IO) {        var migrated = 0
        try {
            context.dataStore.edit { prefs ->
                secretKeys.forEach { key ->
                    val raw = prefs[key] ?: return@forEach
                    if (raw.isEmpty() || SecretCipher.isEncrypted(raw)) return@forEach
                    val encrypted = SecretCipher.encrypt(raw)
                    if (encrypted != raw) {
                        prefs[key] = encrypted
                        migrated++
                    }
                }
            }
            if (migrated > 0) Log.i(TAG, "migrateSecretsToEncrypted: $migrated field(s) encrypted")
        } catch (e: Exception) {
            Log.w(TAG, "migrateSecretsToEncrypted failed", e)
        }
        migrated
    }

    // ── 旧排行榜遗留数据清理 ──

    /**
     * 清除已下线的全服排行榜残留在 Preferences 里的数据，并把旧昵称键迁移到新键。
     *
     * 处理对象（均来自已下线的远端榜单实现）：
     * - `leaderboard_nickname` → 迁移到 `commander_nickname` 后删除
     * - `leaderboard_cache_json` / `leaderboard_cache_ts` → 直接删除（远端缓存已无意义）
     *
     * 幂等：无遗留数据时返回 0。启动时调用一次。
     */
    suspend fun purgeLegacyLeaderboardData(): Int = withContext(Dispatchers.IO) {
        var removed = 0
        try {
            context.dataStore.edit { prefs ->
                val legacyNickname = prefs[stringPreferencesKey(LEGACY_COMMANDER_NICKNAME_KEY_NAME)]
                if (!legacyNickname.isNullOrBlank() && prefs[COMMANDER_NICKNAME_KEY].isNullOrBlank()) {
                    prefs[COMMANDER_NICKNAME_KEY] = legacyNickname
                }
                // asMap().keys 是数据里真实存在的 typed key，按 name 前缀精确匹配后移除
                val doomed = prefs.asMap().keys.filter { it.name.startsWith(LEGACY_LEADERBOARD_KEY_PREFIX) }
                doomed.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    prefs.remove(key as Preferences.Key<Any>)
                    removed++
                }
            }
            if (removed > 0) Log.i(TAG, "purgeLegacyLeaderboardData: removed $removed key(s)")
        } catch (e: Exception) {
            Log.w(TAG, "purgeLegacyLeaderboardData failed", e)
        }
        removed
    }

    // ── 备份 / 恢复状态 ──

    val autoBackupEnabled: Flow<Boolean> = safeData.map { it[AUTO_BACKUP_ENABLED_KEY] ?: true }
    val lastBackupAt: Flow<Long> = safeData.map { it[LAST_BACKUP_AT_KEY] ?: 0L }
    val lastRestoreAt: Flow<Long> = safeData.map { it[LAST_RESTORE_AT_KEY] ?: 0L }

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_ENABLED_KEY] = enabled }
    }

    suspend fun setLastBackupAt(timestamp: Long) {
        context.dataStore.edit { it[LAST_BACKUP_AT_KEY] = timestamp }
    }

    suspend fun setLastRestoreAt(timestamp: Long) {
        context.dataStore.edit { it[LAST_RESTORE_AT_KEY] = timestamp }
    }

    /**
     * 导出可进备份的偏好项（已排除凭证/设备相关/缓存的 key）。
     *
     * 值按真实类型编码，恢复时才能用同类型 key 写回（float 不会退化成 int）。
     */
    suspend fun readBackupPrefs(): List<PrefBackup> = withContext(Dispatchers.IO) {
        val prefs = safeData.first()
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val name = key.name
            if (BACKUP_EXCLUDED_PREFIXES.any { name.startsWith(it) }) return@mapNotNull null
            when (value) {
                is String -> PrefBackup(name, PrefBackup.TYPE_STRING, value)
                is Boolean -> PrefBackup(name, PrefBackup.TYPE_BOOL, value.toString())
                is Int -> PrefBackup(name, PrefBackup.TYPE_INT, value.toString())
                is Long -> PrefBackup(name, PrefBackup.TYPE_LONG, value.toString())
                is Float -> PrefBackup(name, PrefBackup.TYPE_FLOAT, value.toString())
                is Set<*> -> PrefBackup(
                    name = name,
                    type = PrefBackup.TYPE_STRING_SET,
                    setValues = value.filterIsInstance<String>()
                )
                else -> null // 未知类型（未来 DataStore 新增）→ 跳过，不猜
            }
        }.sortedBy { it.name }
    }

    /**
     * 应用备份中的偏好项。
     *
     * 安全：仍然按 [BACKUP_EXCLUDED_PREFIXES] 过滤一次（即使备份文件被人手工改过，
     * 也不能借"恢复"把凭证写进来/覆盖掉）。
     *
     * @return 实际写入的项数
     */
    suspend fun writeBackupPrefs(entries: List<PrefBackup>): Int = withContext(Dispatchers.IO) {
        var applied = 0
        context.dataStore.edit { prefs ->
            entries.forEach { entry ->
                if (entry.name.isBlank()) return@forEach
                if (BACKUP_EXCLUDED_PREFIXES.any { entry.name.startsWith(it) }) return@forEach
                when (entry.type) {
                    PrefBackup.TYPE_STRING -> prefs[stringPreferencesKey(entry.name)] = entry.value
                    PrefBackup.TYPE_BOOL -> prefs[booleanPreferencesKey(entry.name)] =
                        entry.value.toBooleanStrictOrNull() ?: return@forEach
                    PrefBackup.TYPE_INT -> prefs[intPreferencesKey(entry.name)] =
                        entry.value.toIntOrNull() ?: return@forEach
                    PrefBackup.TYPE_LONG -> prefs[longPreferencesKey(entry.name)] =
                        entry.value.toLongOrNull() ?: return@forEach
                    PrefBackup.TYPE_FLOAT -> prefs[floatPreferencesKey(entry.name)] =
                        entry.value.toFloatOrNull() ?: return@forEach
                    PrefBackup.TYPE_STRING_SET -> prefs[stringSetPreferencesKey(entry.name)] =
                        (entry.setValues ?: emptyList()).toSet()
                    else -> return@forEach
                }
                applied++
            }
        }
        applied
    }

    /** 暂存"恢复时表里还没有的舰娘誓约"，等名录刷新后补上（JSON 数组） */
    suspend fun setPendingOathRestore(json: String) {
        context.dataStore.edit { it[PENDING_OATH_RESTORE_KEY] = json }
    }

    /** 读取并清空待补誓约名单 */
    suspend fun takePendingOathRestore(): String? = withContext(Dispatchers.IO) {
        var json: String? = null
        context.dataStore.edit { prefs ->
            json = prefs[PENDING_OATH_RESTORE_KEY]
            prefs.remove(PENDING_OATH_RESTORE_KEY)
        }
        json
    }

    /**
     * 诊断快照：把 DataStore 的全部 key 与「脱敏后的值」拼成文本，供诊断包导出。
     *
     * 规则：
     * - 命中 [SecretCipher.looksSensitive] 的 key（api_key/token/uid/server…）只输出状态，
     *   不输出任何明文或密文片段；
     * - 其他字符串值超过 120 字符时只给长度，避免诊断包变成用户数据备份。
     */
    suspend fun diagnosticSnapshot(): String = withContext(Dispatchers.IO) {
        val prefs = safeData.first()
        buildString {
            appendLine("# DataStore snapshot (${prefs.asMap().size} keys)")
            prefs.asMap().entries
                .sortedBy { it.key.name }
                .forEach { (key, value) ->
                    val name = key.name
                    val rendered = when {
                        SecretCipher.looksSensitive(name) -> "<redacted>"
                        value == null -> "null"
                        value is String -> when {
                            value.isEmpty() -> "\"\""
                            SecretCipher.isEncrypted(value) -> "<encrypted len=${value.length}>"
                            value.length <= 120 -> value
                            else -> "<string len=${value.length}>"
                        }
                        else -> value.toString()
                    }
                    appendLine("$name = $rendered")
                }
        }
    }

    // ── 网盘链接持久化缓存 ──

    /**
     * 读取网盘链接持久化缓存。
     * @return Pair<JSON 字符串, 缓存写入时间戳>，若不存在返回 null
     */
    suspend fun getCachedDriveLink(): Pair<String, Long>? {
        val prefs = safeData.first()
        val json = prefs[CACHED_DRIVE_LINK_JSON_KEY] ?: return null
        val ts = prefs[CACHED_DRIVE_LINK_AT_KEY] ?: 0L
        return json to ts
    }

    /** 写入网盘链接持久化缓存（JSON + 当前时间戳） */
    suspend fun setCachedDriveLink(json: String) {
        context.dataStore.edit { prefs ->
            prefs[CACHED_DRIVE_LINK_JSON_KEY] = json
            prefs[CACHED_DRIVE_LINK_AT_KEY] = System.currentTimeMillis()
        }
    }

    /** 清空网盘链接持久化缓存 */
    suspend fun clearCachedDriveLink() {
        context.dataStore.edit { prefs ->
            prefs.remove(CACHED_DRIVE_LINK_JSON_KEY)
            prefs.remove(CACHED_DRIVE_LINK_AT_KEY)
        }
    }

    // ── SD 资源下载链接持久化缓存 ──

    /**
     * 读取 SD 资源下载链接持久化缓存。
     * @return Pair<JSON 字符串, 缓存写入时间戳>，若不存在返回 null
     */
    suspend fun getCachedSdResourceLink(): Pair<String, Long>? {
        val prefs = safeData.first()
        val json = prefs[CACHED_SD_RESOURCE_LINK_JSON_KEY] ?: return null
        val ts = prefs[CACHED_SD_RESOURCE_LINK_AT_KEY] ?: 0L
        return json to ts
    }

    /** 写入 SD 资源下载链接持久化缓存（JSON + 当前时间戳） */
    suspend fun setCachedSdResourceLink(json: String) {
        context.dataStore.edit { prefs ->
            prefs[CACHED_SD_RESOURCE_LINK_JSON_KEY] = json
            prefs[CACHED_SD_RESOURCE_LINK_AT_KEY] = System.currentTimeMillis()
        }
    }

    /** 清空 SD 资源下载链接持久化缓存 */
    suspend fun clearCachedSdResourceLink() {
        context.dataStore.edit { prefs ->
            prefs.remove(CACHED_SD_RESOURCE_LINK_JSON_KEY)
            prefs.remove(CACHED_SD_RESOURCE_LINK_AT_KEY)
        }
    }

    // ── Live2D 皮肤名序列持久化缓存 ──

    /**
     * 读取 Live2D 皮肤名序列持久化缓存（解析后的换装序列 JSON，含每条目时间戳）。
     * @return JSON 字符串，若不存在返回 null
     */
    suspend fun getLive2dSkinNameSequences(): String? {
        return safeData.first()[LIVE2D_SKIN_SEQUENCES_JSON_KEY]
    }

    /** 写入 Live2D 皮肤名序列持久化缓存（整体 JSON 覆盖写） */
    suspend fun setLive2dSkinNameSequences(json: String) {
        context.dataStore.edit { prefs ->
            prefs[LIVE2D_SKIN_SEQUENCES_JSON_KEY] = json
        }
    }

    // ── 档案缓存时间戳 ──

    /** 获取档案缓存时间戳，返回 0 表示无缓存 */
    suspend fun getArchiveCacheTimestamp(archiveType: String): Long {
        val prefs = safeData.first()
        val key = if (archiveType == "STUDENT") ARCHIVE_CACHE_TIMESTAMP_STUDENT_KEY else ARCHIVE_CACHE_TIMESTAMP_DOCK_KEY
        return prefs[key] ?: 0L
    }

    /** 写入档案缓存时间戳 */
    suspend fun setArchiveCacheTimestamp(archiveType: String) {
        val key = if (archiveType == "STUDENT") ARCHIVE_CACHE_TIMESTAMP_STUDENT_KEY else ARCHIVE_CACHE_TIMESTAMP_DOCK_KEY
        context.dataStore.edit { it[key] = System.currentTimeMillis() }
    }

    /** 检查档案缓存是否过期（超过 5 分钟视为过期） */
    suspend fun isArchiveCacheExpired(archiveType: String): Boolean {
        val timestamp = getArchiveCacheTimestamp(archiveType)
        if (timestamp == 0L) return true
        return System.currentTimeMillis() - timestamp > ARCHIVE_CACHE_EXPIRY_MS
    }

    // ── 自定义 App 图标偏好 ──

    /** 当前图标类型（DEFAULT / CLASSIC / BLUE / PURPLE / CUSTOM） */
    val appIconType: Flow<String> = safeData
        .map { it[APP_ICON_TYPE_KEY] ?: "DEFAULT" }

    /** 自定义图标内部存储路径（仅 CUSTOM 类型有效） */
    val appCustomIconPath: Flow<String> = safeData
        .map { it[APP_CUSTOM_ICON_PATH_KEY] ?: "" }

    /** 图标选择时间戳 */
    val appIconSelectedAt: Flow<Long> = safeData
        .map { it[APP_ICON_SELECTED_AT_KEY] ?: 0L }

    /**
     * 保存图标选择。
     *
     * @param type 图标类型枚举名（见 [com.azurlane.blyy.util.AppIconType]）
     * @param customPath 自定义图标路径（仅 CUSTOM 类型需要，其它传空）
     */
    suspend fun setAppIcon(type: String, customPath: String = "") {
        context.dataStore.edit {
            it[APP_ICON_TYPE_KEY] = type
            it[APP_CUSTOM_ICON_PATH_KEY] = customPath
            it[APP_ICON_SELECTED_AT_KEY] = System.currentTimeMillis()
        }
    }
}

@kotlinx.serialization.Serializable
data class PlayLaterItem(
    val voiceUrl: String,
    val shipName: String,
    val scene: String,
    val dialogue: String,
    val skinName: String = "",
    val avatarUrl: String = "",
    val addedTime: Long = System.currentTimeMillis(),
    val hasPlayed: Boolean = false
)
