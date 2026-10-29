@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.azurlane.blyy.widget

// 小组件交互：共享逻辑（performXxx）+ ActionCallback 薄壳 + 日期变化广播。
//
// 【交互通道】actionRunCallback 广播回调（Android AppWidget 标准模型）：
// 无窗口无动画零伪影（透明中转 Activity 方案在部分 GPU/ROM 上会产生屏幕扫描
// 黑线，已废弃）。进程死亡（非 STOPPED）时广播后台拉起进程执行；应用被
// force-stop 后组件由 ROM 重置为提示卡（widget_initial_stub），打开应用即恢复。
//
// 【及时更新】刷新全部走 WidgetSync 直连会话更新（无 WorkManager 排队延迟）。
//
// 【角色隔离】「换人」「语音」读写独立的 widget_ship_* / widget_line 域，
// 绝不触碰应用内秘书舰（secretary_*）。

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.domain.SelectSecretaryUseCase
import com.azurlane.blyy.service.PlaybackServiceConnection
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WidgetActions"

/** Hilt 依赖入口：小组件侧获取单例依赖的唯一通道 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playerSettings(): PlayerSettingsDataStore
    fun shipDao(): ShipDao
    fun shipRepository(): ShipRepository
    fun selectSecretaryUseCase(): SelectSecretaryUseCase
    fun getVoicesUseCase(): GetVoicesUseCase
    fun playbackServiceConnection(): PlaybackServiceConnection
}

fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

/** 主线程 Toast（广播回调协程线程无保证，Toast 必须在主线程） */
private suspend fun toastOnMain(context: Context, message: String, duration: Int) {
    withContext(Dispatchers.Main) {
        Toast.makeText(context, message, duration).show()
    }
}

/** 交互层 fire-and-forget 作用域（头像预取等不阻塞主流程的旁路任务） */
private val interactionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 获取舰娘语音表（三级）：DataStore 持久缓存（7 天 TTL，跨进程重启有效）
 * → wiki 网络 → 成功后回填缓存。命中缓存时换人台词捕获/语音播放均秒级完成。
 */
private suspend fun fetchVoiceLines(context: Context, shipName: String): List<VoiceLine> {
    val settings = context.widgetEntryPoint().playerSettings()
    val cached = decodeWidgetVoicesCache(
        json = settings.widgetVoicesCacheJson.first(),
        expectedShipName = shipName,
        now = System.currentTimeMillis()
    )
    if (!cached.isNullOrEmpty()) return cached

    val voices = withContext(Dispatchers.IO) {
        runCatching { context.widgetEntryPoint().getVoicesUseCase()(shipName).voices }
            .getOrNull()
            .orEmpty()
    }
    if (voices.isNotEmpty()) {
        runCatching {
            settings.setWidgetVoicesCache(
                encodeWidgetVoicesCache(shipName, voices, System.currentTimeMillis())
            )
        }
    }
    return voices
}

/**
 * 组件换人（两段式即时更新）：
 * 1.【秒出段】选舰 → 立即写舰名/头像并直连刷新（不等网络），同时后台预取头像
 *    写入 Coil 磁盘缓存——舰名与占位头像 <0.5s 上屏，不再被 wiki 台词抓取阻塞；
 * 2.【补全段】抓取台词（优先命中持久缓存）→ 落盘后再直连刷新一次，
 *    此时头像缓存通常已就绪，真头像与台词一起到位。
 * 只写组件独立域（widget_ship_*），不触碰应用内秘书舰。
 */
suspend fun performChangeSecretary(context: Context) {
    val appContext = context.applicationContext
    toastOnMain(appContext, "正在为指挥官挑选…", Toast.LENGTH_SHORT)
    val entry = appContext.widgetEntryPoint()
    val ship = withContext(Dispatchers.IO) { entry.selectSecretaryUseCase().selectRandomSecretary() }
    if (ship == null) {
        toastOnMain(appContext, "舰船图鉴为空，请先打开应用同步图鉴", Toast.LENGTH_LONG)
        return
    }
    entry.playerSettings().setWidgetShip(ship.name, ship.avatarUrl)
    // 秒出段：舰名先上屏；头像预取旁路进行（写磁盘缓存供下一次组合命中）
    runCatching { WidgetSync.updateAllNow(appContext) }
    interactionScope.launch {
        runCatching { loadAvatarBitmap(appContext, ship.avatarUrl, timeoutMs = 4000) }
    }
    // 补全段：台词捕获（换新舰首抓走网络，同舰复访走持久缓存）
    val line = fetchVoiceLines(appContext, ship.name).let { voices ->
        if (voices.isEmpty()) "" else (pickDialogueLine(voices) ?: "")
    }
    entry.playerSettings().setSecretaryWidgetLine(line)
    runCatching { WidgetSync.updateAllNow(appContext) }
    Log.d(TAG, "performChangeSecretary: 组件角色 → ${ship.name}")
}

/**
 * 播放组件角色的随机语音：角色来自组件独立域（与应用内秘书舰无关）。
 * 台词在播放提交前先落盘+直连刷新（上屏不等 ExoPlayer 网络缓冲），
 * 随后经共享 PlaybackService 后台播放（媒体通知随播放自动出现）。
 */
suspend fun performPlayVoice(context: Context) {
    val appContext = context.applicationContext
    val entry = appContext.widgetEntryPoint()
    val shipName = entry.playerSettings().widgetShipName.first()
    if (shipName.isEmpty()) {
        toastOnMain(appContext, "先点「换人」为组件抽一位舰娘吧", Toast.LENGTH_SHORT)
        return
    }
    val voices = fetchVoiceLines(appContext, shipName)
    if (voices.isEmpty()) {
        Log.e(TAG, "performPlayVoice: $shipName 语音抓取失败")
        toastOnMain(appContext, "语音加载失败，请稍后再试", Toast.LENGTH_SHORT)
        return
    }
    val voice = voices.random()
    // 台词先行：上屏不等播放缓冲
    entry.playerSettings().setSecretaryWidgetLine(
        voice.dialogue.replace("\n", " ").replace("\r", "").trim()
    )
    runCatching { WidgetSync.updateAllNow(appContext) }
    runCatching {
        // MediaController 全系方法要求主线程调用（曾因后台线程触发 wrong thread 崩溃）
        withContext(Dispatchers.Main) {
            val controller: MediaController =
                entry.playbackServiceConnection().mediaController.await()
            controller.setMediaItem(buildVoiceMediaItem(shipName, voice))
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.prepare()
            controller.play()
        }
    }.onFailure {
        Log.e(TAG, "performPlayVoice 播放失败", it)
        toastOnMain(appContext, "语音播放失败，请稍后再试", Toast.LENGTH_SHORT)
    }
}

/**
 * 打开「今日推荐舰娘」的语音页：推荐结果按日期确定性计算（同一天任何入口
 * 结果一致），无需从组件传参。
 */
suspend fun performOpenDaily(context: Context) {
    val appContext = context.applicationContext
    val route = runCatching {
        val entry = appContext.widgetEntryPoint()
        val favorites = entry.shipDao().getFavoriteShipsByArchiveType("DOCK").first()
        val all = entry.shipDao().getShipsByArchiveType("DOCK").first()
        val ship = pickDailyShip(selectDailyPool(favorites, all), localDaySeed())
        // 路由格式与 AppRoot 现有跳转一致（舰名不编码、avatarUrl 编码）
        if (ship != null) "voice/${ship.name}?avatarUrl=${Uri.encode(ship.avatarUrl)}" else "home"
    }.getOrNull() ?: "home"
    runCatching { appContext.startActivity(widgetNavIntent(appContext, route)) }
}

/** 语音 MediaItem：台词为标题、舰名·皮肤为艺人、场景为专辑 */
private fun buildVoiceMediaItem(shipName: String, voice: VoiceLine): MediaItem {
    val audioUrl = voice.getActiveAudioUrl(VoiceLanguage.CN)
    val cleanDialogue = voice.dialogue.replace("\n", " ").replace("\r", "").trim()
    val metadata = MediaMetadata.Builder()
        .setTitle(cleanDialogue)
        .setArtist("$shipName · ${voice.skinName}")
        .setAlbumTitle(voice.scene)
        .build()
    return MediaItem.Builder()
        .setMediaId(audioUrl)
        .setUri(audioUrl)
        .setMediaMetadata(metadata)
        .build()
}

/** 「换人」按钮：后台执行组件独立选舰（详见 [performChangeSecretary]） */
class ChangeSecretaryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        performChangeSecretary(context)
    }
}

/** 「语音」按钮：后台播放组件角色语音并同步台词（详见 [performPlayVoice]） */
class PlayVoiceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        performPlayVoice(context)
    }
}

/** 「今日推荐舰娘」整卡点击：直达语音页（详见 [performOpenDaily]） */
class OpenDailyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        performOpenDaily(context)
    }
}

// ---------- 生日组件跳转 ----------

/** 生日组件：舰娘语音页跳转参数键（actionRunCallback 携参） */
val birthdayShipNameKey = ActionParameters.Key<String>("birthday_widget.ship_name")
val birthdayShipAvatarKey = ActionParameters.Key<String>("birthday_widget.ship_avatar")

/** 生日组件整卡/头部点击：打开生日日历页（近期寿星、月历、寿星列表） */
suspend fun performOpenBirthdayCalendar(context: Context) {
    val appContext = context.applicationContext
    runCatching { appContext.startActivity(widgetNavIntent(appContext, "birthday_calendar")) }
}

/**
 * 生日组件寿星点击：直达该舰娘语音页（路由格式与船坞/日历页一致：
 * 舰名不编码、avatarUrl 编码）；参数缺失（旧布局缓存/异常）回退日历页。
 */
suspend fun performOpenBirthdayShip(context: Context, parameters: ActionParameters) {
    val appContext = context.applicationContext
    val name = parameters[birthdayShipNameKey]
    val route = if (name.isNullOrBlank()) {
        "birthday_calendar"
    } else {
        "voice/$name?avatarUrl=${Uri.encode(parameters[birthdayShipAvatarKey].orEmpty())}"
    }
    runCatching { appContext.startActivity(widgetNavIntent(appContext, route)) }
}

/** 「生日日历」点击：打开日历页 */
class OpenBirthdayCalendarAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        performOpenBirthdayCalendar(context)
    }
}

/** 生日组件寿星条目点击：直达语音页 */
class OpenBirthdayShipAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        performOpenBirthdayShip(context, parameters)
    }
}

/**
 * 跨零点/时区与时间变更广播（均为免注册限制的豁免隐式广播）：
 * 刷新每日推荐（换舰）与秘书舰问候（时段问候语切换）。
 */
class WidgetDateChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetSync.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
