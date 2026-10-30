package com.azurlane.blyy.widget

// 舰娘生日小组件（4x2）：今日寿星 + 近期生日 + 点击深链生日日历页。
//
// 数据链：Room ships.birthday（静态名单 blhx_birthdays.json 回填）→
// BirthdayWidgetData 纯函数组装 → Glance RemoteViews。provideGlance 内先做
// 幂等生日回填：v8→v9 迁移后未刷新档案的旧库，添加组件即可直接出数据。
//
// 更新触发（全部复用现有 WidgetSync 管线）：进程冷启动自愈、图鉴刷新成功、
// 收藏切换、日历页回填变更、跨零点/时区 WidgetDateChangeReceiver、30 分钟周期自愈。
//
// 点击：整卡 → 生日日历页；单个寿星条目（携参）→ 该舰娘语音页。

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.azurlane.blyy.R
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.util.LocalAvatarResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class BirthdayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 读取失败（DB 异常/进程冷启动竞态）降级为空态 UI，绝不抛出（防永久 loading）
        val state = runCatching {
            val entry = context.widgetEntryPoint()
            // 幂等生日回填（仅写差异行）：v9 迁移后未刷新档案的旧库也能直接展示
            runCatching { entry.shipRepository().seedDockBirthdaysIfNeeded() }
            val ships = entry.shipDao().getDockShipsWithBirthday().first()
            BirthdayWidgetData.build(ships, LocalDate.now()) { ship ->
                LocalAvatarResolver.resolveOrDefault(
                    context, ship.name, ship.archiveType, ship.isFavorite, ship.avatarUrl
                )
            }
        }.getOrNull() ?: BirthdayWidgetData.empty()

        // 头像并行限时加载：本地 assets 秒回；网络图超时先占位，由确认刷（+1.5s）
        // 补真图。128px 对 24~30dp 展示尺寸已超过 3x 密度需求，控制 RemoteViews
        // 的 binder 体积（多张 256px 位图叠加可能超事务上限导致更新失败）
        val bitmaps: Map<String, Bitmap?> = coroutineScope {
            (state.todayShips + state.upcomingShips)
                .map { ship ->
                    async { ship.name to loadAvatarBitmap(context, ship.avatarUrl, sizePx = 128) }
                }
                .awaitAll()
                .toMap()
        }

        provideContent {
            BirthdayWidgetContent(state) { ship -> bitmaps[ship.name] }
        }
    }
}

@Composable
private fun BirthdayWidgetContent(
    state: BirthdayWidgetState,
    bitmapOf: (BirthdayShipUi) -> Bitmap?
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg_dark))
            // 整卡兜底点击：空白区域也能进日历页（寿星条目上的点击就近命中条目动作）
            .clickable(onClick = actionRunCallback<OpenBirthdayCalendarAction>())
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            // ── 头部：蛋糕图标 + 标题 + 日期 ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_cake),
                    contentDescription = null,
                    modifier = GlanceModifier.size(15.dp)
                )
                Spacer(GlanceModifier.width(5.dp))
                Text(
                    text = "舰娘生日",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = state.dateLabel,
                    style = TextStyle(
                        color = ColorProvider(AppColors.Widget.TextTertiary),
                        fontSize = 11.sp
                    )
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            // 主色小分隔线：标题与内容的层次点缀（同秘书舰组件）
            Box(modifier = GlanceModifier.width(22.dp).height(2.dp).background(AppColors.Widget.AccentCyan)) {}
            Spacer(GlanceModifier.defaultWeight())

            if (!state.hasAnyData) {
                // ── 空态：档案未刷新 / 无任何生日数据 ──
                Text(
                    text = "打开应用，在船坞刷新档案后展示舰娘生日",
                    style = TextStyle(
                        color = ColorProvider(AppColors.Widget.TextSecondary),
                        fontSize = 12.sp
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
            } else {
                // ── 今日寿星行 ──
                TodayBirthdayRow(state, bitmapOf)
                Spacer(GlanceModifier.height(9.dp))
                // ── 近期生日行（今日无寿星时承担全部信息；都没有时该行省略） ──
                if (state.upcomingShips.isNotEmpty()) {
                    UpcomingBirthdayRow(state, bitmapOf)
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

/** 今日寿星行：「今日寿星」胶囊 + 寿星条目（≤3，超出 "+N"）或空提示 */
@Composable
private fun TodayBirthdayRow(
    state: BirthdayWidgetState,
    bitmapOf: (BirthdayShipUi) -> Bitmap?
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .cornerRadius(9.dp)
                .background(AppColors.Widget.AccentCyanContainer)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = "今日寿星",
                style = TextStyle(
                    color = ColorProvider(AppColors.Widget.AccentCyanBright),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        if (state.todayShips.isEmpty()) {
            Spacer(GlanceModifier.width(9.dp))
            Text(
                text = "今天没有舰娘生日",
                style = TextStyle(
                    color = ColorProvider(AppColors.Widget.TextSecondary),
                    fontSize = 12.sp
                )
            )
        } else {
            Spacer(GlanceModifier.width(9.dp))
            state.todayShips.forEachIndexed { index, ship ->
                if (index > 0) Spacer(GlanceModifier.width(10.dp))
                ShipChip(ship = ship, bitmap = bitmapOf(ship), avatarSize = 26.dp, showCountdown = false)
            }
            if (state.todayCount > state.todayShips.size) {
                Spacer(GlanceModifier.width(7.dp))
                Text(
                    text = "+${state.todayCount - state.todayShips.size}",
                    style = TextStyle(
                        color = ColorProvider(AppColors.Widget.TextTertiary),
                        fontSize = 11.sp
                    ),
                    maxLines = 1
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

/** 近期生日行：「接下来」+ 最近 2 位寿星（头像 + 舰名 + 倒计时徽标） */
@Composable
private fun UpcomingBirthdayRow(
    state: BirthdayWidgetState,
    bitmapOf: (BirthdayShipUi) -> Bitmap?
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "接下来",
            style = TextStyle(
                color = ColorProvider(AppColors.Widget.TextTertiary),
                fontSize = 11.sp
            )
        )
        Spacer(GlanceModifier.width(9.dp))
        state.upcomingShips.forEachIndexed { index, ship ->
            if (index > 0) Spacer(GlanceModifier.width(12.dp))
            ShipChip(ship = ship, bitmap = bitmapOf(ship), avatarSize = 24.dp, showCountdown = true)
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

/** 单个寿星条目：头像 + 舰名（+ 倒计时），点击直达该舰娘语音页 */
@Composable
private fun ShipChip(
    ship: BirthdayShipUi,
    bitmap: Bitmap?,
    avatarSize: Dp,
    showCountdown: Boolean
) {
    Row(
        modifier = GlanceModifier.clickable(
            onClick = actionRunCallback<OpenBirthdayShipAction>(
                actionParametersOf(
                    birthdayShipNameKey to ship.name,
                    birthdayShipAvatarKey to ship.avatarUrl
                )
            )
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(avatarSize)
                .cornerRadius(8.dp)
                .background(AppColors.Widget.PanelDark),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = ship.name,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_person_placeholder),
                    contentDescription = ship.name,
                    modifier = GlanceModifier.size((avatarSize.value * 0.55f).dp)
                )
            }
        }
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = ship.name,
            style = TextStyle(
                color = ColorProvider(AppColors.Widget.TextPrimary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            // Glance 1.1.1 无 overflow 参数：maxLines=1 超宽舰名直接裁剪（RemoteViews 行为）
            maxLines = 1
        )
        if (showCountdown) {
            Spacer(GlanceModifier.width(3.dp))
            Text(
                text = BirthdayWidgetData.countdownLabel(ship.daysUntil),
                style = TextStyle(
                    color = ColorProvider(AppColors.Widget.AccentCyanBright),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

/** 清单注册的 AppWidget 接收器 */
class BirthdayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BirthdayWidget()
}
