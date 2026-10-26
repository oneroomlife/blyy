package com.azurlane.blyy.ui.screens.birthday

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.ui.components.AdaptiveScreenBackground
import com.azurlane.blyy.ui.components.BlyyHaptic
import com.azurlane.blyy.ui.components.BlyyTopBar
import com.azurlane.blyy.ui.components.rememberBlyyHaptics
import com.azurlane.blyy.ui.theme.AppColors
import com.azurlane.blyy.ui.theme.AppSpacing
import com.azurlane.blyy.ui.theme.AppTypography
import com.azurlane.blyy.ui.theme.LocalIsDark
import com.azurlane.blyy.util.LocalAvatarResolver
import com.azurlane.blyy.viewmodel.BirthdayCalendarViewModel
import java.time.LocalDate
import java.time.YearMonth

/** 周标题（周一为首，中文日历惯例） */
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 舰娘生日日历：近期生日横向预览 + 月历网格 + 选中日寿星列表。
 * 数据来自 Room ships.birthday（静态名单回填），空库时给出引导提示。
 */
@Composable
fun BirthdayCalendarScreen(
    onBack: () -> Unit,
    onOpenVoice: (shipName: String, avatarUrl: String) -> Unit,
    viewModel: BirthdayCalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = rememberBlyyHaptics()
    val today = remember { LocalDate.now() }

    AdaptiveScreenBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            BlyyTopBar(
                title = "生日日历",
                subtitle = "舰娘生日一览",
                onBackClick = onBack
            )

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                }
                return@Column
            }

            if (state.totalCount == 0) {
                EmptyBirthdayHint()
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.Screen.Horizontal)
                    .padding(bottom = AppSpacing.Xl)
            ) {
                if (state.upcoming.isNotEmpty()) {
                    UpcomingBirthdayRow(
                        upcoming = state.upcoming,
                        onShipClick = { ship ->
                            haptic(BlyyHaptic.Tick)
                            onOpenVoice(
                                ship.name,
                                LocalAvatarResolver.resolveOrDefault(
                                    context, ship.name, ship.archiveType, ship.avatarUrl
                                )
                            )
                        }
                    )
                }

                MonthNavigationBar(
                    month = state.month,
                    onPrevious = {
                        haptic(BlyyHaptic.Tick)
                        viewModel.previousMonth()
                    },
                    onNext = {
                        haptic(BlyyHaptic.Tick)
                        viewModel.nextMonth()
                    },
                    onToday = {
                        haptic(BlyyHaptic.Tick)
                        viewModel.jumpToToday()
                    }
                )

                CalendarGrid(
                    month = state.month,
                    selectedDate = state.selectedDate,
                    birthdaysByDay = state.birthdaysByDay,
                    today = today,
                    onSelectDate = { date ->
                        haptic(BlyyHaptic.Tick)
                        viewModel.selectDate(date)
                    }
                )

                SelectedDayPanel(
                    selectedDate = state.selectedDate,
                    ships = state.selectedShips,
                    onShipClick = { ship ->
                        haptic(BlyyHaptic.Tick)
                        onOpenVoice(
                            ship.name,
                            LocalAvatarResolver.resolveOrDefault(
                                context, ship.name, ship.archiveType, ship.avatarUrl
                            )
                        )
                    }
                )
            }
        }
    }
}

/** 空数据提示：静态名单未回填或档案尚未刷新 */
@Composable
private fun EmptyBirthdayHint() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Cake,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(AppSpacing.Md))
        Text("还没有生日数据", style = AppTypography.TitleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(AppSpacing.Xs))
        Text(
            "进入「船坞」刷新舰娘档案后，生日数据会自动补全",
            style = AppTypography.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 倒计时徽标文案 */
private fun countdownLabel(days: Int): String = when (days) {
    0 -> "今天"
    1 -> "明天"
    else -> "${days}天"
}

/** 近期生日横向预览：稀有度色圆环头像 + 倒计时徽标 */
@Composable
private fun UpcomingBirthdayRow(
    upcoming: List<BirthdayCalendarViewModel.UpcomingBirthday>,
    onShipClick: (Ship) -> Unit
) {
    val context = LocalContext.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)
    ) {
        items(upcoming, key = { "${it.ship.name}-${it.date}" }) { item ->
            val avatarUrl = remember(item.ship.name) {
                LocalAvatarResolver.resolveOrDefault(
                    context, item.ship.name, item.ship.archiveType, item.ship.avatarUrl
                )
            }
            val rarityColor = remember(item.ship.rarity) {
                AppColors.Rarity.getRarityColor(item.ship.rarity)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onShipClick(item.ship) }
            ) {
                Box {
                    Surface(
                        shape = CircleShape,
                        border = BorderStroke(2.dp, rarityColor),
                        tonalElevation = 1.dp
                    ) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = item.ship.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (item.daysUntil == 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 7.dp)
                    ) {
                        Text(
                            text = countdownLabel(item.daysUntil),
                            style = AppTypography.LabelSmall,
                            color = if (item.daysUntil == 0) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = item.ship.name,
                    style = AppTypography.LabelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 72.dp)
                )
            }
        }
    }
}

/** 月份导航：‹ 2026年10月 › + 今日 */
@Composable
private fun MonthNavigationBar(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Cake,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(AppSpacing.Xs))
        Text(
            text = "${month.year}年${month.monthValue}月",
            style = AppTypography.TitleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToday) { Text("今日") }
        IconButton(onClick = onPrevious, modifier = Modifier.size(34.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "上个月")
        }
        IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "下个月")
        }
    }
}

/**
 * 月历网格：周一为首 + 6 行固定行高（月份切换高度稳定）。
 * 有生日的日子渲染迷你头像堆叠（≤3 个 + 溢出计数）。
 */
@Composable
private fun CalendarGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    birthdaysByDay: Map<Int, List<BirthdayCalendarViewModel.DayBirthday>>,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit
) {
    val context = LocalContext.current
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1 // Monday=1 → 前置空位数
    val daysInMonth = month.lengthOfMonth()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Sm)
            .clip(RoundedCornerShape(AppSpacing.Corner.Md))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f))
            .padding(AppSpacing.Xs)
    ) {
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        val rows = (leadingBlanks + daysInMonth + 6) / 7
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val day = row * 7 + col - leadingBlanks + 1
                    Box(Modifier.weight(1f)) {
                        if (day in 1..daysInMonth) {
                            val date = month.atDay(day)
                            DayCell(
                                date = date,
                                ships = birthdaysByDay[day].orEmpty(),
                                isSelected = date == selectedDate,
                                isToday = date == today,
                                context = context
                            ) { onSelectDate(date) }
                        }
                    }
                }
            }
        }
    }
}

/** 单个日期格：日号 + 迷你头像堆叠；选中填充、今天描边 */
@Composable
private fun DayCell(
    date: LocalDate,
    ships: List<BirthdayCalendarViewModel.DayBirthday>,
    isSelected: Boolean,
    isToday: Boolean,
    context: android.content.Context,
    onClick: () -> Unit
) {
    val isDark = LocalIsDark.current
    val cellShape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .padding(1.dp)
            .clip(cellShape)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isToday) 1.5.dp else 0.dp,
                color = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = cellShape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = if (isToday) {
                AppTypography.LabelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                AppTypography.LabelMedium.copy(
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.9f else 0.8f)
                )
            }
        )
        if (ships.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = ships.take(3)
                shown.forEachIndexed { i, item ->
                    val avatarUrl = remember(item.ship.name) {
                        LocalAvatarResolver.resolveOrDefault(
                            context, item.ship.name, item.ship.archiveType, item.ship.avatarUrl
                        )
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = (-2 * i).dp)
                            .size(15.dp)
                            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (ships.size > 3) {
                    Text(
                        text = "+${ships.size - 3}",
                        style = AppTypography.LabelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = (-2 * shown.size).dp)
                    )
                }
            }
        }
    }
}

/** 选中日寿星列表 */
@Composable
private fun SelectedDayPanel(
    selectedDate: LocalDate,
    ships: List<BirthdayCalendarViewModel.DayBirthday>,
    onShipClick: (Ship) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.Md)
    ) {
        Text(
            text = if (ships.isEmpty()) "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 · 这一天没有舰娘生日"
            else "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 · ${ships.size} 位寿星",
            style = AppTypography.LabelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = AppSpacing.Sm)
        )
        ships.forEach { item ->
            BirthdayShipRow(ship = item.ship, onClick = { onShipClick(item.ship) })
        }
    }
}

/** 寿星行条目：稀有度色条 + 头像圆环 + 名称/收藏心/舰种阵营 */
@Composable
private fun BirthdayShipRow(ship: Ship, onClick: () -> Unit) {
    val context = LocalContext.current
    val isDark = LocalIsDark.current
    val rarityColor = remember(ship.rarity) { AppColors.Rarity.getRarityColor(ship.rarity) }
    val avatarUrl = remember(ship.name, ship.isFavorite) {
        LocalAvatarResolver.resolveOrDefault(
            context, ship.name, ship.archiveType, ship.isFavorite, ship.avatarUrl
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.7f else 0.6f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 34.dp)
                    .background(rarityColor, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(AppSpacing.Md))
            Surface(shape = CircleShape, border = BorderStroke(1.5.dp, rarityColor)) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = ship.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(Modifier.width(AppSpacing.Md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ship.name,
                        style = AppTypography.BodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (ship.isFavorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "已收藏",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    text = listOf(ship.type, ship.faction).filter { it.isNotBlank() }.joinToString(" · "),
                    style = AppTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
