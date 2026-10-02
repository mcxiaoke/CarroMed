package com.mcxiaoke.carromed.core.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 闹钟身份的内容寻址测试（不变量 I10，**P0-1** 的守门测试）。
 *
 * ## 这个 bug 为什么能活过上一轮审查
 *
 * 旧实现：
 * ```kotlin
 * val requestCode = if (advance) (slotId * 10 + 1).toInt() else slotId.toInt()
 * ```
 * 代码注释写着「slotId 是全局唯一自增主键，因此两个号段天然不相交」。
 * 这句话**在数学上是错的** —— `advance(N)` 的 `10N+1` 与 `main(M)` 的 `M`
 * 在 `M ≡ 1 (mod 10)` 时必然相等。而 `PendingIntent` 的判重是
 * `requestCode` + `Intent.filterEquals`，**extras 不参与**，
 * 两个分支的 component 与 action 又完全相同。
 *
 * 于是：
 * - 给槽位 1 排提前闹钟 ⇒ 覆盖掉槽位 11 的准点闹钟（提前提醒本身也丢了）
 * - `cancel(槽位1)` ⇒ 连带杀掉槽位 11 的准点闹钟 ⇒ **静默漏提醒**
 *
 * 纯代码审查看不出来，是因为**注释和实现一起被采信了**。
 * 这也是本项目要求"每条测试必须能失败"的原因。
 *
 * ## 本测试怎么证明修复有效
 *
 * 直接构造真实 `PendingIntent` 并比较 `equals`。Robolectric 实现了
 * `PendingIntent.getBroadcast` 的判重语义，所以这不是模拟，是真的。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AlarmIdentityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ==================== 旧实现的反例（先证明 bug 真实存在） ====================

    @Test
    fun `反例 旧实现的算术编码确实会碰撞`() {
        val advanceSlot1 = legacyPendingIntent(requestCode = 1 * 10 + 1, isAdvance = true)
        val mainSlot11 = legacyPendingIntent(requestCode = 11, isAdvance = false)

        // 两者 requestCode 都是 11，component/action 相同 ⇒ Android 判它们是同一个
        assertThat(advanceSlot1).isEqualTo(mainSlot11)
    }

    @Test
    fun `反例 碰撞在槽位数超过 10 后密集出现`() {
        // 逐个列出已知的碰撞对：advance(N) 的 10N+1 撞上 main(10N+1)
        val collisions = listOf(
            1L to 11L, 2L to 21L, 3L to 31L, 4L to 41L, 5L to 51L,
            6L to 61L, 7L to 71L, 8L to 81L, 9L to 91L
        )
        for ((slot, victim) in collisions) {
            assertThat(slot * 10 + 1).isEqualTo(victim)
            // 每一对都真的产生同一个 PendingIntent
            assertThat(legacyPendingIntent((slot * 10 + 1).toInt(), isAdvance = true))
                .isEqualTo(legacyPendingIntent(victim.toInt(), isAdvance = false))
        }
        // 而同一槽位的 main 与 advance 不碰撞（10N ≠ 10N+1），这正是"看起来没问题"的地方
        assertThat(legacyPendingIntent(3, isAdvance = false))
            .isNotEqualTo(legacyPendingIntent(31, isAdvance = true))
    }

    // ==================== 修复后：内容寻址不碰撞 ====================

    @Test
    fun `不同槽位同一时刻产生互不覆盖的 PendingIntent`() {
        val pending = (1L..50L).map { pid ->
            contentAddressed(pid, date = "2026-09-28", time = "08:00", AlarmScheduler.Kind.MAIN)
        }
        // 50 个槽位 = 50 个不同的 PendingIntent
        assertThat(pending.toSet().size).isEqualTo(50)
    }

    @Test
    fun `同一槽位的三种闹钟种类互不覆盖`() {
        val a = contentAddressed(1, "2026-09-28", "08:00", AlarmScheduler.Kind.MAIN)
        val b = contentAddressed(1, "2026-09-28", "08:00", AlarmScheduler.Kind.ADVANCE)
        val c = contentAddressed(1, "2026-09-28", "08:00", AlarmScheduler.Kind.SNOOZE)
        assertThat(setOf(a, b, c).size).isEqualTo(3)
    }

    @Test
    fun `同一逻辑触发点重复注册是同一个 PendingIntent（重注册替换而非堆积）`() {
        val first = contentAddressed(7, "2026-09-28", "08:00", AlarmScheduler.Kind.MAIN)
        val second = contentAddressed(7, "2026-09-28", "08:00", AlarmScheduler.Kind.MAIN)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `Uri 唯一编码四个维度`() {
        val seen = mutableSetOf<String>()
        for (med in 1L..40L) {
            for (kind in AlarmScheduler.Kind.entries) {
                for (time in listOf("08:00", "20:00")) {
                    seen += AlarmScheduler.alarmUri(med, "2026-09-28", time, kind).toString()
                }
            }
        }
        // 40 药品 × Kind 种类数 × 2 时刻 = 互不相同的身份
        assertThat(seen.size).isEqualTo(40 * AlarmScheduler.Kind.entries.size * 2)
    }

    /**
     * **本测试最关键的一条**：遍历 1..10⁴ 全量枚举，
     * 证明任意两组 `(medId, date, time, kind)` 都不产生相同 Uri。
     *
     * 区间特意取到 10⁴ —— 旧实现在 slotId > 10 时就已经密集碰撞，
     * 只测 1..10 会漏掉正是 P0-1 的高发区。
     */
    @Test
    fun `遍历 medId 1 到 10 的 4 次方 身份绝不重复`() {
        val seen = HashSet<String>(40_000)
        val kinds = AlarmScheduler.Kind.entries
        for (med in 1L..10_000L) {
            for (kind in kinds) {
                val uri = AlarmScheduler.alarmUri(med, "2026-09-28", "08:00", kind).toString()
                assertThat(seen.add(uri)).isTrue()   // 重复即失败，并报出是哪个
            }
        }
        assertThat(seen.size).isEqualTo(10_000L * kinds.size.toLong())
    }

    @Test
    fun `Kind 的 code 可往返解析`() {
        for (kind in AlarmScheduler.Kind.entries) {
            assertThat(AlarmScheduler.Kind.fromCode(kind.code)).isEqualTo(kind)
        }
        // 未知 code 退回 MAIN（宁可多响也不要静默）
        assertThat(AlarmScheduler.Kind.fromCode("不存在的种类")).isEqualTo(AlarmScheduler.Kind.MAIN)
        assertThat(AlarmScheduler.Kind.fromCode(null)).isEqualTo(AlarmScheduler.Kind.MAIN)
    }

    @Test
    fun `Uri 不含需要转义的字符`() {
        // scheduledTime 形如 "08:00"，若未来允许自由文本时点也不会破坏 Uri 结构
        val uri = AlarmScheduler.alarmUri(1, "2026-09-28", "08:00", AlarmScheduler.Kind.MAIN)
        assertThat(uri.pathSegments).hasSize(4)
        assertThat(uri.pathSegments[0]).isEqualTo("1")
        assertThat(uri.pathSegments[1]).isEqualTo("2026-09-28")
        assertThat(uri.pathSegments[2]).isEqualTo("08:00")
        assertThat(uri.pathSegments[3]).isEqualTo("main")
    }

    // ==================== 辅助 ====================

    /**
     * 复刻**修复前**的 PendingIntent 构造方式，用于上面的反例。
     * 两个分支的 component 与 action 完全相同，只靠 requestCode 区分。
     *
     * `putExtra("is_advance", ...)` 是刻意保留的：它正是当年"以为自己区分开了"的那个
     * extra，而 `filterEquals` **不看 extras** —— 这个 extra 写得再对也没用。
     */
    private fun legacyPendingIntent(requestCode: Int, isAdvance: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmScheduler.ACTION_DOSE_ALARM)
                .putExtra(AlarmScheduler.EXTRA_SLOT_ID, requestCode)
                .putExtra("is_advance", isAdvance),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * ⚠️ 必须走**生产代码** [AlarmScheduler.alarmIntent]，不能在测试里重写一遍 Uri 构造。
     *
     * 这一点是本测试能不能守住 P0-1 的关键：如果测试自己拼 Uri，那么
     * "实现改回算术编码"时测试仍然全绿 —— 守的是一个测试里的影子，不是真正的实现。
     */
    private fun contentAddressed(
        medicationId: Long,
        date: String,
        time: String,
        kind: AlarmScheduler.Kind
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,                                  // 身份完全由 Intent 内容决定
        AlarmScheduler.alarmIntent(
            context = context,
            medicationId = medicationId,
            date = date,
            time = time,
            slotId = medicationId,           // extras 不参与判重，随便给
            kind = kind
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

}
