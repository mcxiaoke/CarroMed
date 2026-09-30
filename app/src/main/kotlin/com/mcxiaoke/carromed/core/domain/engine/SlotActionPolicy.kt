package com.mcxiaoke.carromed.core.domain.engine

import java.time.LocalDate

/**
 * 槽位可表态判据（未来槽位不可操作）。
 *
 * ## 产品口径
 *
 * > **槽位可表态 ⇔ `scheduled_date <= 当前自然日`**
 *
 * | 槽位计划日 | 打卡 / 跳过 / 推迟 / 改判 | 依据 |
 * | :--- | :---: | :--- |
 * | 今天 | ✅ | 正常服药；`EXPIRED` 的当日补记同样允许 |
 * | 过去 | ✅ | 「静默待办」补记：槽位开放、无闹钟，用户随时可从今日清单补记 |
 * | 未来 | ❌ | 服药是**已发生**的事实，不能提前确认 |
 *
 * 「未来」是唯一的禁区。过去的既有能力一条都不动 —— 这样就不必碰
 * `markCompletedIfOpen` 守卫里的 `EXPIRED` 分支，也不用改「当日结束」的结算语义。
 *
 * ## 为什么是字符串比较，而不是 `LocalDate.parse`
 *
 * `scheduled_date` 是 `DoseSlotEntity` 的非空 `String`，由 [SlotProjectionEngine] 按
 * [SlotProjectionEngine.DATE_FORMATTER]（`yyyy-MM-dd`）写入；零填充的 `yyyy-MM-dd`
 * **字典序等价于时序**。两个理由：
 *
 * 1. `LocalDate.parse` 会抛异常，而本判据会被 SQL 与 Kotlin 两侧共用 ——
 *    一侧抛、一侧返回 false，就会出现"UI 放行而服务层拒绝"的诡异不一致
 *    （AGENTS.md §2 第 7 条：校验判据要和兜底判据同源）；
 * 2. 字符串比较**永不抛异常**，且两侧拿到的是**同一套比较**。
 *
 * 推论（刻意接受的边界）：非规范串（如 `2026-9-30`，月不零填充）字典序排在
 * `2026-09-30` 之后 ⇒ 判为未来 ⇒ 拒绝，方向安全；其余坏串的落点同样只由字典序决定
 * （空串落进"过去"、`not-a-date` 落进"未来"），**与 SQL 守卫的结论完全一致**。
 * Kotlin 侧刻意**不加**格式校验维度：那会让 SQL 放行、Kotlin 拒绝，反而制造分叉。
 * 坏串的可达性由入口校验负责（`saveReminderPolicy` 拒绝坏时点串）。
 *
 * ## 一处定义，两处使用
 *
 * - SQL：`dose_slots` 三个写入口的 `WHERE ... AND scheduled_date <= :todayStr`
 *   （判据落在数据上，任何调用方都绕不过去）；
 * - Kotlin：服务层为失败归因、UI 层决定渲不渲染按钮，都调本函数。
 *
 * `todayStr` 必须由**调用方现算**（服务层用注入的时钟），不要读
 * `CurrentDateHolder`：它最多滞后 60 秒，且单测里根本不 `install`。
 */
object SlotActionPolicy {

    /**
     * @param scheduledDate 槽位的计划日，规范格式 `yyyy-MM-dd`
     * @param todayStr 当前自然日，同一格式
     * @return true 表示这个槽位允许被用户施加新的结论（打卡 / 跳过 / 推迟 / 改判）
     */
    fun isActionableOn(scheduledDate: String, todayStr: String): Boolean = scheduledDate <= todayStr

    /**
     * 日期重载：与字符串重载是**同一条规则**，只是 UI 手里本来就是 `LocalDate`。
     *
     * 存在的意义是"判据只有一处"：UI 若自己写 `selectedDate <= today`，
     * 判据就有了第二份实现，而两份判据的漂移是静默的
     * （服务层拒了、UI 却渲染着可点的按钮 —— 正是本缺陷本身的样子）。
     */
    fun isActionableOn(scheduledDate: LocalDate, today: LocalDate): Boolean =
        !scheduledDate.isAfter(today)
}
