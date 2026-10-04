package com.mcxiaoke.carromed.ui.theme

import androidx.compose.ui.graphics.Color

// CarroMed 核心品牌种子色 (M3 Seed Color)
// 选型：草本生机绿 (#4A7A00)，符合健康管理、生命活力与自然治愈心理暗示，并与 App 图标呼应。
//
// ⚠️ 本文件**只保留种子色**。
// 曾经的 SuccessGreen / WarningAmber 系列自定义语义色已全部删除，改为直接使用
// Material 3 预定义槽位（由 rememberDynamicColorScheme 按种子色推导，浅/深自动成对）：
//
//   「已服 / 完成」正向      → primary / primaryContainer / onPrimaryContainer / onPrimary
//   「告急 / 漏服 / 禁忌」警示 → error / errorContainer / onErrorContainer / onError
//
// 理由：单套固定色值无法同时满足浅色与深色的对比度要求 ——
// 同一个颜色在浅底上要够深、在深底上要够亮，两个区间数学上没有交集
// （见 docs/DARK-THEME-SEMANTIC-COLOR-PLAN-20261004.md 第三节）。
// M3 色板由算法保证每个槽位与其 on* 配对满足 WCAG 对比度，且深浅两套一起生成，
// 不会再有"漏了深色变体"这类问题。
//
// 全工程仅剩极少数允许的硬编码颜色（白名单见 docs/THEME-COLOR-SPEC-20261004.md）：
//   1. 用户自选的药品标记色 `MedicationFormOptions.COLORS` —— 药品的物理识别属性，
//      绝不能随主题变化，否则会引发服药混淆；
//   2. 数据反序列化兜底 `BackupFormat.DEFAULT_MEDICATION_COLOR`；
//   3. 高饱和药品色块 / 进度态实心圆上的纯白前景 `Color.White`。
val DefaultSeedColor = Color(0xFF4A7A00)
