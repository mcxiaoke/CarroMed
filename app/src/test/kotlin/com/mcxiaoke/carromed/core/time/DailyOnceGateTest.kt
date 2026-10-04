package com.mcxiaoke.carromed.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「每自然日至多放行一次」的节流判据（3-3 引导横幅的低频约束）。
 *
 * 刻意做成**纯逻辑测试**：不碰 SharedPreferences、不读挂钟 —— 存储与"今天是哪天"
 * 由测试注入。若把它塞进 `MainActivity` 里，"同一天只放行一次"这条约束
 * 就只能靠真机反复前后台切换去肉眼看，而它恰恰是最容易被改坏、
 * 改坏后又只表现为"横幅天天弹/从来不弹"的那种约束。
 */
class DailyOnceGateTest {

    /** 一个用可变字段模拟"持久化"的替身存储。 */
    private class FakeStore(var lastDay: Long? = null)

    private fun gate(store: FakeStore, today: () -> Long) = DailyOnceGate(
        lastAllowedEpochDay = { store.lastDay },
        todayEpochDay = today,
        markAllowed = { store.lastDay = it }
    )

    @Test
    fun `从未放行过 放行一次`() {
        val store = FakeStore()
        val gate = gate(store) { 20_000L }
        assertThat(gate.tryPass()).isTrue()
    }

    @Test
    fun `同一天第二次调用 不放行`() {
        val store = FakeStore()
        val gate = gate(store) { 20_000L }
        assertThat(gate.tryPass()).isTrue()
        // 关键：放行的同一次调用里就记账，所以紧接着的第二次必须被拦下。
        // 若记账推迟到调用方"回头再记"，这里就会连续放行两次。
        assertThat(gate.tryPass()).isFalse()
        assertThat(gate.tryPass()).isFalse()
        assertThat(store.lastDay).isEqualTo(20_000L)
    }

    @Test
    fun `跨到第二天 重新放行`() {
        val store = FakeStore()
        var today = 20_000L
        val gate = gate(store) { today }
        assertThat(gate.tryPass()).isTrue()
        assertThat(gate.tryPass()).isFalse()

        today = 20_001L
        assertThat(gate.tryPass()).isTrue()
        assertThat(store.lastDay).isEqualTo(20_001L)
    }

    @Test
    fun `跨多天后 只放行一次并记账到当天`() {
        val store = FakeStore(lastDay = 19_000L)
        val gate = gate(store) { 20_500L }
        assertThat(gate.tryPass()).isTrue()
        assertThat(gate.tryPass()).isFalse()
        assertThat(store.lastDay).isEqualTo(20_500L)
    }
}
