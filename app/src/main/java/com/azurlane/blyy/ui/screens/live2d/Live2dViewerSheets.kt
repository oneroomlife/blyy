package com.azurlane.blyy.ui.screens.live2d

/*
 * Live2D 查看器的设置弹层 — 自 Live2dViewerScreen.kt 机械拆分而来，纯移动零逻辑改动。
 * CompanionSettingsSheet（陪伴模式）/ GyroSettingsSheet（陀螺仪）与其私有助手
 * CompanionSettingRow、档位常量随迁。
 */
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppAnimation
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppElevation
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.viewmodel.Live2dViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 陪伴模式触发间隔档位（秒 → 标签） */
private val COMPANION_INTERVALS = listOf(
    30 to "30秒",
    60 to "1分钟",
    180 to "3分钟",
    300 to "5分钟",
    600 to "10分钟"
)

/** 陪伴模式设置弹层：触发间隔档位 + 时钟/电量显示开关 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionSettingsSheet(
    intervalSec: Int,
    showClock: Boolean,
    showBattery: Boolean,
    onIntervalChange: (Int) -> Unit,
    onClockChange: (Boolean) -> Unit,
    onBatteryChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Screen.Horizontal)
                .padding(bottom = AppSpacing.Xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.Xs))
                Text(
                    "陪伴模式",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                "待机动作自动循环，每到设定间隔随机播放一个其他动作并配上对应语音",
                style = AppTypography.CaptionSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.Md)
            )
            Text(
                "动作触发间隔",
                style = AppTypography.LabelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AppSpacing.Sm)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                modifier = Modifier.padding(bottom = AppSpacing.Md)
            ) {
                COMPANION_INTERVALS.forEach { (sec, label) ->
                    val selected = sec == intervalSec
                    AssistChip(
                        onClick = { onIntervalChange(sec) },
                        label = { Text(label, style = AppTypography.LabelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = if (selected) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
            CompanionSettingRow(
                icon = { Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                title = "显示时间与日期",
                subtitle = "右下角常驻时钟角标",
                checked = showClock,
                onCheckedChange = onClockChange
            )
            CompanionSettingRow(
                icon = { Icon(Icons.Rounded.BatteryStd, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                title = "显示设备电量",
                subtitle = "电量偏低或充电时变色提示",
                checked = showBattery,
                onCheckedChange = onBatteryChange
            )
        }
    }
}

@Composable
private fun CompanionSettingRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(AppSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.BodyMedium)
            Text(
                subtitle,
                style = AppTypography.CaptionSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------- 陀螺仪注视/视差 ----------

/** 陀螺仪灵敏度档位（百分比 → 标签） */
private val GYRO_GAIN_OPTIONS = listOf(
    60 to "轻",
    100 to "标准",
    160 to "灵敏"
)

/**
 * 陀螺仪注视/视差设置弹层：开关 + 灵敏度档位。
 * 生效链路：DataStore → VM State → LaunchedEffect 下发 setGyro（即时生效，
 * 无需重建渲染端）；倾斜信号本身由 GyroFocusController 以 ~16Hz 持续下发。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GyroSettingsSheet(
    enabled: Boolean,
    gainPercent: Int,
    onEnabledChange: (Boolean) -> Unit,
    onGainChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Screen.Horizontal)
                .padding(bottom = AppSpacing.Xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpacing.Xs))
                Text(
                    "陀螺仪注视",
                    style = AppTypography.TitleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(AppSpacing.Xs))
            Text(
                "倾斜设备时，模型的视线与位置朝倾斜方向轻微跟随；指针交互期间自动让位",
                style = AppTypography.CaptionSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.Md)
            )
            CompanionSettingRow(
                icon = { Icon(Icons.Rounded.Explore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                title = "启用陀螺仪注视",
                subtitle = "无需额外权限，退后台自动停止",
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
            if (enabled) {
                Text(
                    "跟随灵敏度",
                    style = AppTypography.LabelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = AppSpacing.Sm, bottom = AppSpacing.Sm)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)
                ) {
                    GYRO_GAIN_OPTIONS.forEach { (percent, label) ->
                        val selected = percent == gainPercent
                        AssistChip(
                            onClick = { onGainChange(percent) },
                            label = { Text(label, style = AppTypography.LabelMedium) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = if (selected) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }
    }
}
