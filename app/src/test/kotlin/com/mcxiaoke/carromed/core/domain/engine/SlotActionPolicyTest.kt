package com.mcxiaoke.carromed.core.domain.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「槽位可表态 ⇔ `scheduled_date <= today`」这条判据本身的测试。
 *
 * 刻意做成**纯函数测试**（无 Robolectric、无数据库、无挂钟）：
 * 判据是服务层 SQL 守卫、服务层归因、UI 渲染三处共用的同一份语义，
 * 它一旦漂移，三处会**同时**漂移且互相印证成"看起来一致"。
 * 所以边界要在这里钉死，而且必须是确定的 —— 不依赖任何"今天是几号"。
 */
class SlotActionPolicyTest {

    @Test
    fun `计划日等于今天 允许表态`() {
        assertThat(SlotActionPolicy.isActionableOn("2026-09-30", "2026-09-30")).isTrue()
    }

    @Test
    fun `计划日在过去 允许表态（补记路径不能被误伤）`() {
        assertThat(SlotActionPolicy.isActionableOn("2026-09-29", "2026-09-30")).isTrue()
        assertThat(SlotActionPolicy.isActionableOn("2020-01-01", "2026-09-30")).isTrue()
    }

    @Test
    fun `计划日在未来 拒绝表态`() {
        assertThat(SlotActionPolicy.isActionableOn("2026-10-01", "2026-09-30")).isFalse()
    }

    @Test
    fun `跨月与跨年边界同样按自然日比较`() {
        assertThat(SlotActionPolicy.isActionableOn("2026-09-30", "2026-10-01")).isTrue()
        assertThat(SlotActionPolicy.isActionableOn("2026-10-01", "2026-09-30")).isFalse()
        assertThat(SlotActionPolicy.isActionableOn("2026-12-31", "2027-01-01")).isTrue()
        assertThat(SlotActionPolicy.isActionableOn("2027-01-01", "2026-12-31")).isFalse()
        // 闰年 2/29 存在，且它也是"零填充的 yyyy-MM-dd"，字典序照常成立
        assertThat(SlotActionPolicy.isActionableOn("2028-02-29", "2028-03-01")).isTrue()
        assertThat(SlotActionPolicy.isActionableOn("2028-02-29", "2028-02-28")).isFalse()
    }

    /**
     * ⭐ 非规范串走**同一条字符串比较**，不额外加"格式校验"这一维。
     *
     * `"2026-9-30"`（月不零填充）在字典序下大于 `"2026-09-30"`（第 6 位 `'9' > '0'`）
     * ⇒ 判为未来 ⇒ 拒绝。这不是巧合，而是"零填充串的字典序 == 时序"这条前提
     * 被破坏后的必然结果 —— 破坏前提时宁可拒绝，不可放行。
     *
     * 其余坏串的落点由**字典序**决定，而不是由"它是坏串"决定：
     * `"not-a-date"` 首字符 `'n' > '2'` ⇒ 排在今天之后 ⇒ 拒绝；
     * 空串排在一切非空串之前 ⇒ 落进"过去" ⇒ 允许。
     *
     * **这是刻意接受的**：SQL 守卫里同样是 `scheduled_date <= :todayStr`，
     * 两层必须给同一个答案。若在 Kotlin 侧再加一条 `length == 10` 之类的格式校验，
     * 就会出现"SQL 放行、Kotlin 拒绝"的分叉 —— 那正是本判据要消灭的东西。
     * 坏串在库里的可达性由入口校验负责（`saveReminderPolicy` 拒绝坏时点串）。
     */
    @Test
    fun `非规范日期串按同一套字符串比较判定 不抛异常`() {
        assertThat(SlotActionPolicy.isActionableOn("2026-9-30", "2026-09-30")).isFalse()
        assertThat(SlotActionPolicy.isActionableOn("2026-09-30", "2026-9-30")).isTrue()
        // 首字符大于 '2' 的垃圾串排在今天之后 ⇒ 与 SQL 同样判为"未来"
        assertThat(SlotActionPolicy.isActionableOn("not-a-date", "2026-09-30")).isFalse()
        // 空串排在一切非空串之前 ⇒ 与 SQL 同样落进"过去"
        assertThat(SlotActionPolicy.isActionableOn("", "2026-09-30")).isTrue()
    }
}
