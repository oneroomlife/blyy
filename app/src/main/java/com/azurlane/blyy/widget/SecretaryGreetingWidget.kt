package com.azurlane.blyy.widget

// 秘书舰问候小组件（4x2）：头像 + 舰名 + 台词 +「换人」「语音」按钮。
// 【角色隔离】展示数据来自组件独立域（widget_ship_*），与应用内秘书舰互不影响。
// 【交互穿透】按钮经透明中转 Activity 执行（force-stop 后广播回调失效，
// Activity 启动是豁免路径），透明无动画，感知上仍是纯后台执行。
// provideGlance 零强制网络（头像限时加载，弱网回退占位图，绝不阻塞在 loading）。

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
import androidx.glance.action.Action
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
import kotlinx.coroutines.flow.first
import java.util.Calendar

class SecretaryGreetingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    /** 一次性读取的展示快照；任一步失败都降级为默认值，绝不抛出（防永久 loading） */
    private data class GreetingState(
        val shipName: String = "",
        val avatarUrl: String = "",
        val line: String = ""
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching {
            val settings = context.widgetEntryPoint().playerSettings()
            val shipName = settings.widgetShipName.first()
            val avatarUrl = settings.widgetShipAvatarUrl.first()
            val savedLine = settings.secretaryWidgetLine.first()
            val lineAt = settings.secretaryWidgetLineAt.first()
            // 台词：换人/播放语音捕获的真实台词优先，超期（3 天）或缺失回退内置时段问候
            val lineFresh = savedLine.isNotBlank() &&
                lineAt > 0 &&
                System.currentTimeMillis() - lineAt < WIDGET_LINE_FRESH_MS
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            GreetingState(
                shipName = shipName,
                avatarUrl = avatarUrl,
                line = if (lineFresh) savedLine else WidgetGreetings.pick(hour, localDaySeed())
            )
        }.getOrNull() ?: GreetingState(
            line = "小组件已就绪，点「换人」抽一位舰娘"
        )

        // 头像限时加载：失败/超时回退占位图，下一次更新自动重试（磁盘缓存已预热）
        val avatar = loadAvatarBitmap(context, state.avatarUrl)

        provideContent {
            SecretaryGreetingContent(
                shipName = state.shipName,
                avatar = avatar,
                line = state.line
            )
        }
    }
}

@Composable
private fun SecretaryGreetingContent(
    shipName: String,
    avatar: Bitmap?,
    line: String
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg_dark))
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(14.dp)
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像区不挂任何点击动作：组件交互保持纯后台（不打开应用界面）
                Box(
                    modifier = GlanceModifier
                        .size(56.dp)
                        .cornerRadius(16.dp)
                        .background(AppColors.Widget.PanelDark),
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
                            modifier = GlanceModifier.size(26.dp)
                        )
                    }
                    // 主色半透明描边环，压在头像上形成精致边框
                    Image(
                        provider = ImageProvider(R.drawable.widget_avatar_ring),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = shipName.ifEmpty { "尚未抽选舰娘" },
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.height(5.dp))
                    // 舰名下主色小分隔线：标题与台词的层次点缀
                    Box(modifier = GlanceModifier.width(22.dp).height(2.dp).background(AppColors.Widget.AccentCyan)) {}
                    Spacer(GlanceModifier.height(5.dp))
                    Text(
                        text = if (shipName.isEmpty()) "点下方骰子，为组件抽一位专属舰娘" else line,
                        style = TextStyle(
                            color = ColorProvider(AppColors.Widget.TextOnAccent),
                            fontSize = 12.sp
                        )
                    )
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionChip(
                    label = "换人",
                    iconRes = R.drawable.ic_widget_dice,
                    action = actionRunCallback<ChangeSecretaryAction>()
                )
                Spacer(GlanceModifier.width(10.dp))
                ActionChip(
                    label = "语音",
                    iconRes = R.drawable.ic_widget_speaker,
                    action = actionRunCallback<PlayVoiceAction>()
                )
            }
        }
    }
}

/** 胶囊按钮：渐变 drawable 底（background(ImageProvider) 随内容自适应）+ 图标 + 文本 */
@Composable
private fun ActionChip(label: String, iconRes: Int, action: Action) {
    Row(
        modifier = GlanceModifier
            .cornerRadius(17.dp)
            .background(ImageProvider(R.drawable.widget_btn_bg))
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .clickable(onClick = action),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = label,
            modifier = GlanceModifier.size(14.dp)
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(AppColors.Widget.TextPrimary),
                fontSize = 11.sp
            )
        )
    }
}

/** 清单注册的 AppWidget 接收器 */
class SecretaryGreetingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SecretaryGreetingWidget()
}
