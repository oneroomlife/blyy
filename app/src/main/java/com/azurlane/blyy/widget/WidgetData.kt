package com.azurlane.blyy.widget

// 桌面小组件数据层：纯逻辑（可 JVM 单测）+ 头像加载 + 应用内变更同步器

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import coil.Coil
import coil.request.ImageRequest
import com.azurlane.blyy.MainActivity
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.VoiceLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

/** 内置时段问候语池（wiki 台词未捕获/已失效时的兜底展示） */
object WidgetGreetings {

    private val morning = listOf(
        "早上好，指挥官。新的一天也请多指教。",
        "早安，清晨的海风很舒服呢，指挥官今天也要元气满满哦。",
        "早安。今天的作战计划已经准备好了。",
        "指挥官醒了吗？早餐和舰队报告都在桌上了。"
    )

    private val noon = listOf(
        "午安，指挥官。别忘了吃午饭。",
        "正午的阳光正好，要不要去甲板上走走？",
        "指挥官辛苦了，中午就交给我来守着吧。"
    )

    private val afternoon = listOf(
        "下午好，指挥官。要来一杯红茶吗？",
        "这个时间的海面最平静了，真让人安心。",
        "指挥官如果累了，就在我旁边休息一会儿吧。"
    )

    private val evening = listOf(
        "晚上好，指挥官。今天也辛苦了。",
        "晚上好。今晚的港口很安静，很适合聊聊天呢。",
        "工作到此为止吧，指挥官，晚安前好好休息一下。"
    )

    private val lateNight = listOf(
        "已经这么晚了吗……指挥官，该睡觉了哦。",
        "深夜的指挥室还亮着灯，真是不让人省心呢。",
        "再撑一会儿可以，但别忘了身体最重要。"
    )

    /**
     * 按时段确定性挑一条问候语。
     *
     * @param hour 当前小时（0-23）
     * @param daySeed 日期种子（epochDay），同一天同一时段结果稳定，
     * 时段切换时自然换句
     */
    fun pick(hour: Int, daySeed: Long): String {
        val pool = when (hour) {
            in 5..10 -> morning
            in 11..13 -> noon
            in 14..17 -> afternoon
            in 18..22 -> evening
            else -> lateNight
        }
        val bucketIndex = when (hour) {
            in 5..10 -> 0
            in 11..13 -> 1
            in 14..17 -> 2
            in 18..22 -> 3
            else -> 4
        }
        val seed = daySeed * 8L + bucketIndex
        return pool[(seed % pool.size).toInt().let { if (it < 0) it + pool.size else it }]
    }
}

/** wiki 台词捕获时长上限（超过则小组件回退内置问候语） */
const val WIDGET_LINE_FRESH_MS: Long = 3L * 24 * 60 * 60 * 1000

/** 组件语音表持久缓存有效期：舰娘台词基本不变，7 天足够 */
const val WIDGET_VOICES_CACHE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * 组件语音表持久缓存编解码（纯函数，可 JVM 单测）。
 * 格式：JSON { shipName, cachedAt, voices:[{skinName,scene,dialogue,audioUrlCn,audioUrlJp}] }。
 * 解码时校验 [ttlMs] 有效期与舰名匹配，过期/不匹配/损坏返回 null。
 */
fun encodeWidgetVoicesCache(shipName: String, voices: List<VoiceLine>, now: Long): String =
    kotlinx.serialization.json.Json.encodeToString(
        WidgetVoicesCachePayload.serializer(),
        WidgetVoicesCachePayload(
            shipName = shipName,
            cachedAt = now,
            voices = voices.map {
                VoiceLinePayload(it.skinName, it.scene, it.dialogue, it.audioUrlCn, it.audioUrlJp)
            }
        )
    )

fun decodeWidgetVoicesCache(
    json: String,
    expectedShipName: String,
    now: Long,
    ttlMs: Long = WIDGET_VOICES_CACHE_TTL_MS
): List<VoiceLine>? {
    if (json.isBlank()) return null
    return runCatching {
        val payload = kotlinx.serialization.json.Json.decodeFromString(
            WidgetVoicesCachePayload.serializer(), json
        )
        if (payload.shipName != expectedShipName || now - payload.cachedAt > ttlMs) {
            null
        } else {
            payload.voices.map {
                VoiceLine(it.skinName, it.scene, it.dialogue, it.audioUrlCn, it.audioUrlJp)
            }
        }
    }.getOrNull()
}

@kotlinx.serialization.Serializable
private data class VoiceLinePayload(
    val skinName: String,
    val scene: String,
    val dialogue: String,
    val audioUrlCn: String,
    val audioUrlJp: String
)

@kotlinx.serialization.Serializable
private data class WidgetVoicesCachePayload(
    val shipName: String,
    val cachedAt: Long,
    val voices: List<VoiceLinePayload>
)

/**
 * 从语音表挑一句适合问候展示的真实台词。
 * 优先日常互动场景（触摸/主界面/旗舰），无则回退任意非空台词；清洗换行并截断。
 */
fun pickDialogueLine(voices: List<VoiceLine>, random: Random = Random.Default): String? {
    val candidates = voices.mapNotNull { v ->
        val text = v.dialogue.replace("\n", " ").replace("\r", "").trim()
        if (text.isEmpty()) null else v.scene to text
    }
    if (candidates.isEmpty()) return null
    val preferred = candidates.filter { (scene, _) ->
        scene.contains("触摸") || scene.contains("主界面") || scene.contains("旗舰")
    }
    val chosen = (preferred.ifEmpty { candidates }).random(random).second
    return if (chosen.length > MAX_WIDGET_LINE_LEN) {
        chosen.take(MAX_WIDGET_LINE_LEN) + "…"
    } else {
        chosen
    }
}

/** 小组件台词展示截断长度（两行文本的舒适上限） */
const val MAX_WIDGET_LINE_LEN = 40

/**
 * 本地日期种子（形如 20261024）：同一天内多次计算结果一致，跨本地零点自然变化。
 * 不用 epochDay/86400000——那是 UTC 天界，东八区要到早上 8 点才跳日。
 */
fun localDaySeed(): Long {
    val c = Calendar.getInstance()
    return c.get(Calendar.YEAR) * 10000L +
        (c.get(Calendar.MONTH) + 1) * 100L +
        c.get(Calendar.DAY_OF_MONTH)
}

/** 小组件跳转 App 内路由的显式 Intent（走 MainActivity 导航桥解析 extra） */
fun widgetNavIntent(context: Context, route: String): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_NAV_ROUTE, route)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 每日推荐选舰池：后宅收藏达到 [minPool] 艘时只从收藏抽（推荐用户真正在意的舰），
 * 否则回退全部舰船图鉴。
 */
fun selectDailyPool(favorites: List<Ship>, all: List<Ship>, minPool: Int = 3): List<Ship> {
    return if (favorites.size >= minPool && favorites.isNotEmpty()) favorites else all
}

/**
 * 按日期确定性选舰：先按舰名排序消除 Room 查询顺序扰动，
 * 再以 [localDaySeed] 为种子洗牌取首——同一天任何时刻、任何入口计算结果一致。
 */
fun pickDailyShip(pool: List<Ship>, daySeed: Long): Ship? {
    if (pool.isEmpty()) return null
    val sorted = pool.sortedBy { it.name }
    return sorted.toMutableList().apply { shuffle(Random(daySeed)) }.first()
}

/** 稀有度 → 文字颜色（ARGB），与应用内稀有度视觉体系保持一致的简化版 */
fun rarityColorArgb(rarity: String): Long = when {
    rarity.contains("海上传奇") || rarity.contains("决战方案") -> 0xFFE8A838
    rarity.contains("超稀有") || rarity.contains("最高方案") -> 0xFF48CAE4
    rarity.contains("精锐") || rarity.contains("一星") -> 0xFFB08BEA
    rarity.contains("稀有") -> 0xFF6FA8FF
    else -> 0xFF9FB3C8
}.toLong()

/**
 * 经全局 Coil ImageLoader 加载头像为 Bitmap（自带 wiki 防盗链拦截与磁盘缓存）。
 * URL 空白或加载失败返回 null，由调用方回退占位图。
 * RemoteViews 跨进程有 binder 体积限制，降采样到 [sizePx] 控制位图大小；
 * 网络限时 [timeoutMs]（默认 1.2s）——provideGlance 阻塞期间小组件会一直停在
 * loading 布局，且新舰头像首刷无缓存时网络下载会拖慢整个组合：宁可先占位，
 * 由交互层的预取（本函数调用即写磁盘缓存）+ 确认刷在 ~1.5s 内补上真图。
 */
suspend fun loadAvatarBitmap(
    context: Context,
    url: String,
    sizePx: Int = 256,
    timeoutMs: Long = 1200
): Bitmap? {
    if (url.isBlank()) return null
    return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(sizePx)
                .build()
            val drawable: Drawable = Coil.imageLoader(context).execute(request).drawable
                ?: return@withTimeoutOrNull null
            drawableToBitmap(drawable)
        }.getOrNull()
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * 应用内数据变更 → 桌面小组件即时刷新的唯一入口。
 *
 * 【及时性】updateAllNow 经 GlanceAppWidgetManager 取当前 GlanceId 后逐个
 * `update(context, id)` 直连驱动会话——不进 WorkManager 队列（该队列在部分
 * ROM 上延迟可达 9~21 秒，曾导致"台词/舰名更新不及时"）。
 * 【可靠性】updateAll 在直连首刷后 1.5 秒再确认刷一次（防偶发丢更新）。
 * 刷新失败（如小组件未添加）静默吞掉，不影响业务流程。
 */
object WidgetSync {
    private val secretaryWidget = SecretaryGreetingWidget()
    private val dailyWidget = DailyShipWidget()
    private val birthdayWidget = BirthdayWidget()

    /** 双段刷新：低延迟首刷 + 1.5s 确认刷保证最终一致 */
    suspend fun updateAll(context: Context) {
        updateAllNow(context)
        kotlinx.coroutines.delay(1500)
        updateAllNow(context)
    }

    /** 直连刷新：立即组合并应用 RemoteViews（无 WorkManager 排队延迟） */
    suspend fun updateAllNow(context: Context) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        runCatching {
            manager.getGlanceIds(SecretaryGreetingWidget::class.java).forEach { id ->
                secretaryWidget.update(context, id)
            }
        }
        runCatching {
            manager.getGlanceIds(DailyShipWidget::class.java).forEach { id ->
                dailyWidget.update(context, id)
            }
        }
        runCatching {
            manager.getGlanceIds(BirthdayWidget::class.java).forEach { id ->
                birthdayWidget.update(context, id)
            }
        }
    }

    /** 非挂起上下文的 fire-and-forget 版本 */
    fun launchUpdate(scope: CoroutineScope, context: Context) {
        scope.launch { runCatching { updateAll(context) } }
    }

    /** 无协程作用域的调用方（如 Repository）使用的自带作用域版本 */
    fun enqueueUpdate(context: Context) {
        ownScope.launch { runCatching { updateAll(context) } }
    }

    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
