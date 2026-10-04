package com.mcxiaoke.carromed.ui.screen.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.domain.engine.StatsEngine
import com.mcxiaoke.carromed.ui.component.WeekLabels
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月度服药历史打卡日历 BottomSheet。
 *
 * 参考 MyTherapy 样式设计，纯只读展示任意月份的服药/跳过/漏服状况。
 * 用户可左右翻看历史月份，底部展示连续服药天数及鼓励卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseHistoryCalendarSheet(
    streakDays: Int,
    calendarMonth: YearMonth,
    dayStates: Map<LocalDate, StatsEngine.DayAdherenceState>,
    today: LocalDate,
    onSelectMonth: (YearMonth) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentYearMonth = YearMonth.from(today)
    val canGoNext = calendarMonth.isBefore(currentYearMonth)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            // 1. 月份切换控制器
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSelectMonth(calendarMonth.minusMonths(1)) }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.today_calendar_prev_month)
                    )
                }

                Text(
                    text = stringResource(
                        R.string.today_calendar_month_format,
                        calendarMonth.year,
                        calendarMonth.monthValue
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                IconButton(
                    onClick = { onSelectMonth(calendarMonth.plusMonths(1)) },
                    enabled = canGoNext
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.today_calendar_next_month),
                        tint = if (canGoNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. 星期表头 (周一至周日)：唯一实现 WeekLabels（L-9），
            // 替换硬编码中文——原实现在英文系统下仍显示中文。
            val weekDays = (1..7).map { WeekLabels.short(DayOfWeek.of(it)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                weekDays.forEach { dayName ->
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 3. 日历网格
            val daysInMonth = calendarMonth.lengthOfMonth()
            val firstDayOfWeek = calendarMonth.atDay(1).dayOfWeek.value // 1 (Mon) .. 7 (Sun)
            val leadingEmptyDays = firstDayOfWeek - 1
            val totalCells = leadingEmptyDays + daysInMonth
            val totalRows = (totalCells + 6) / 7

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (rowIndex in 0 until totalRows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        for (colIndex in 0 until 7) {
                            val cellIndex = rowIndex * 7 + colIndex
                            val dayNumber = cellIndex - leadingEmptyDays + 1
                            if (dayNumber in 1..daysInMonth) {
                                val date = calendarMonth.atDay(dayNumber)
                                val state = dayStates[date] ?: StatsEngine.DayAdherenceState.NONE
                                val isToday = (date == today)
                                CalendarDayCell(
                                    dayNumber = dayNumber,
                                    state = state,
                                    isToday = isToday,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                // 空白占位格
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 4. 状态图例说明 (Legend)
            CalendarLegendRow()

            Spacer(Modifier.height(24.dp))

            // 5. 底部连续天数卡片
            StreakSummaryCard(streakDays = streakDays)
        }
    }
}

/**
 * 单个日期格子。
 * 纯只读展示，状态以醒目的实心圆底或文字呈现。
 */
@Composable
private fun CalendarDayCell(
    dayNumber: Int,
    state: StatsEngine.DayAdherenceState,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    var bgColor = Color.Transparent
    var textColor = MaterialTheme.colorScheme.onSurface
    var fontWeight = FontWeight.Normal

    when (state) {
        StatsEngine.DayAdherenceState.FULLY_TAKEN -> {
            bgColor = MaterialTheme.colorScheme.primary
            textColor = MaterialTheme.colorScheme.onPrimary
            fontWeight = FontWeight.Bold
        }
        StatsEngine.DayAdherenceState.PARTIAL -> {
            bgColor = MaterialTheme.colorScheme.primaryContainer
            textColor = MaterialTheme.colorScheme.onPrimaryContainer
            fontWeight = FontWeight.Bold
        }
        StatsEngine.DayAdherenceState.MISSED -> {
            bgColor = MaterialTheme.colorScheme.error
            textColor = MaterialTheme.colorScheme.onError
            fontWeight = FontWeight.Bold
        }
        StatsEngine.DayAdherenceState.SKIPPED -> {
            bgColor = MaterialTheme.colorScheme.surfaceVariant
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        StatsEngine.DayAdherenceState.UPCOMING -> {
            textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
        StatsEngine.DayAdherenceState.NONE -> {
            textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        }
    }

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bgColor)
                .then(
                    if (isToday) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = dayNumber.toString(),
                color = textColor,
                fontSize = 14.sp,
                fontWeight = if (isToday) FontWeight.Bold else fontWeight,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 日历状态图例 (Legend)。
 */
@Composable
private fun CalendarLegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDotItem(color = MaterialTheme.colorScheme.primary, text = stringResource(R.string.today_calendar_legend_fully_taken))
        LegendDotItem(color = MaterialTheme.colorScheme.primaryContainer, text = stringResource(R.string.today_calendar_legend_partial), isContainer = true)
        LegendDotItem(color = MaterialTheme.colorScheme.error, text = stringResource(R.string.today_calendar_legend_missed))
        LegendDotItem(color = MaterialTheme.colorScheme.surfaceVariant, text = stringResource(R.string.today_calendar_legend_skipped))
    }
}

@Composable
private fun LegendDotItem(
    color: Color,
    text: String,
    isContainer: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (isContainer) Modifier.border(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f), CircleShape)
                    else Modifier
                )
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 底部连续服药天数鼓励卡片。
 */
@Composable
private fun StreakSummaryCard(
    streakDays: Int,
    modifier: Modifier = Modifier
) {
    val isActive = streakDays > 0
    val praiseText = if (isActive) {
        stringResource(R.string.today_streak_praise_good)
    } else {
        stringResource(R.string.today_streak_praise_zero)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.today_streak_title, streakDays),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = praiseText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
