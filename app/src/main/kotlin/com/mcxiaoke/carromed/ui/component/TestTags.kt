package com.mcxiaoke.carromed.ui.component

/**
 * UI 测试语义标识池（PLAN-UI-TEST-20260929.md §1）。
 *
 * Compose UI Test 与 Maestro 共用这一批 tag（Maestro 的 `id:` 依赖
 * `testTagsAsResourceId`，后者仅 debug 构建开启，见 `AppNavigation`）。
 *
 * **最小加标集**，不做全量覆盖：只给"无文本可依"的交互元素。
 * 有稳定文案/`contentDescription` 的控件（页面标题、按钮文字）直接用文本定位，
 * 重复维护两套定位点必腐化。带参数的槽位/药品用工厂函数生成带 id 的 tag，
 * 语义树里仍是一个普通资源 id 字符串。
 */
object TestTags {

    // ---- 底部 4 个主 Tab ----
    const val TAB_TODAY = "tab_today"
    const val TAB_CABINET = "tab_cabinet"
    const val TAB_PROGRESS = "tab_progress"
    const val TAB_STATS = "tab_stats"

    // ---- 今日清单 ----
    const val DOSE_CARD = "dose_card"
    const val DOSE_CONFIRM = "dose_confirm"
    const val DOSE_FUTURE = "dose_future"

    // ---- 药箱 ----
    const val MED_CARD = "med_card"

    // ---- 药品详情页三个入口行 ----
    const val DETAIL_ROW_EDIT = "detail_row_edit"
    const val DETAIL_ROW_REMINDER = "detail_row_reminder"
    const val DETAIL_ROW_INVENTORY = "detail_row_inventory"

    /** 今日清单的待服卡片（同一屏多张，测试需要点名某一张） */
    fun doseCard(slotId: Long) = "${DOSE_CARD}_$slotId"

    /** 卡片上的「确认服药」按钮 */
    fun doseConfirm(slotId: Long) = "${DOSE_CONFIRM}_$slotId"

    /**
     * 未来槽位卡片上的**只读说明**（"明天 10:30 服用"）。
     *
     * 与 [doseConfirm] 成对：未来日**有它、没有**确认按钮，
     * 走查脚本据此断言"未来不可操作"而不是靠肉眼看文案。
     */
    fun doseFuture(slotId: Long) = "${DOSE_FUTURE}_$slotId"

    /** 药箱列表的药品卡片 */
    fun medCard(medId: Long) = "${MED_CARD}_$medId"
}
