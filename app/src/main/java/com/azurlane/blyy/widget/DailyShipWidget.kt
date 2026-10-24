package com.azurlane.blyy.widget

// 今日推荐舰娘小组件（2x2）：按本地日期确定性抽一舰（后宅收藏 ≥3 艘时只从收藏抽），
// 点击整卡直达语音页（透明中转 Activity，可穿透 force-stop 的 STOPPED 状态）。
// 跨零点由 WidgetDateChangeReceiver 触发刷新 + 30 分钟周期自愈心跳。

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.azurlane.blyy.R
import com.azurlane.blyy.data.model.Ship
import kotlinx.coroutines.flow.first

class DailyShipWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 读取失败（DB 异常/进程冷启动竞态）降级为空态 UI，绝不抛出（防永久 loading）
        val ship = runCatching {
            val shipDao = context.widgetEntryPoint().shipDao()
            val favorites = shipDao.getFavoriteShipsByArchiveType("DOCK").first()
            val all = shipDao.getShipsByArchiveType("DOCK").first()
            pickDailyShip(selectDailyPool(favorites, all), localDaySeed())
        }.getOrNull()

        val avatar = ship?.let { loadAvatarBitmap(context, it.avatarUrl) }
        provideContent {
            DailyShipContent(ship, avatar)
        }
    }
}

@Composable
private fun DailyShipContent(ship: Ship?, avatar: Bitmap?) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg_dark))
            .clickable(onClick = actionRunCallback<OpenDailyAction>())
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp)
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 「今日推荐」主色标签胶囊
                Box(
                    modifier = GlanceModifier
                        .cornerRadius(10.dp)
                        .background(Color(0x3348CAE4))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "今日推荐",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF6FD8F0)),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                if (ship != null) {
                    Text(
                        text = ship.type,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF7C97B5)),
                            fontSize = 11.sp
                        )
                    )
                }
            }
            Column(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ship != null) {
                    Box(
                        modifier = GlanceModifier
                            .size(78.dp)
                            .cornerRadius(16.dp)
                            .background(Color(0xFF16293F)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatar != null) {
                            Image(
                                provider = ImageProvider(avatar),
                                contentDescription = null,
                                modifier = GlanceModifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Image(
                                provider = ImageProvider(R.drawable.ic_widget_person_placeholder),
                                contentDescription = null,
                                modifier = GlanceModifier.size(32.dp)
                            )
                        }
                        Image(
                            provider = ImageProvider(R.drawable.widget_avatar_ring),
                            contentDescription = null,
                            modifier = GlanceModifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                    Spacer(GlanceModifier.height(9.dp))
                    Text(
                        text = ship.name,
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        text = ship.rarity,
                        style = TextStyle(
                            color = ColorProvider(Color(rarityColorArgb(ship.rarity).toInt())),
                            fontSize = 12.sp
                        )
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_person_placeholder),
                        contentDescription = null,
                        modifier = GlanceModifier.size(36.dp)
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        text = "图鉴暂无数据，打开应用同步后使用",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFA9C7DE)),
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    }
}

/** 清单注册的 AppWidget 接收器 */
class DailyShipWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyShipWidget()
}
