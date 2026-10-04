package com.mcxiaoke.carromed.ui.theme

import androidx.compose.ui.graphics.Color

// CarroMed 核心品牌种子色 (M3 Seed Color)
// 选型：草本生机绿 (#4A7A00)，符合健康管理、生命活力与自然治愈心理暗示，并与 App 图标呼应
val DefaultSeedColor = Color(0xFF4A7A00)

// ============================================================================
// 【白名单特定语义色】：仅在有限场景使用，严禁在一般 UI 容器/组件中随意硬编码
// ============================================================================

// 专用于已完成打卡的清新绿 (医学语义辅助，不随主主题色动态偏移，保持明确的正向打卡反馈)
val SuccessGreen = Color(0xFF16A34A)
val SuccessGreenContainer = Color(0xFFDCFCE7)
val OnSuccessGreenContainer = Color(0xFF14532D)

// 专用于库存预警与禁忌注意的醒目琥珀橙黄 (医学权威警告语义，不受动态色干扰)
val WarningAmber = Color(0xFFD97706)
val WarningAmberContainer = Color(0xFFFEF3C7)
val OnWarningAmber = Color(0xFFFFFFFF)
val OnWarningAmberContainer = Color(0xFF92400E)
val WarningAmberBorder = Color(0xFFFDE68A)
