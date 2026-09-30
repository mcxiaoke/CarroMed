package com.mcxiaoke.carromed.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.IOException

/**
 * AppLog 门面的纯 JVM 测试（PLAN-LOGGING-20260929.md D10）。
 *
 * 变异验证基准（每条测试都要能失败）：
 * - 环形驱逐写错（如 `removeLast`）→ [ringEvictsOldestWhenFull] 红；
 * - sink 异常向上传播 → [sinkFailureIsIsolated] 红；
 * - 双重 install 覆盖旧 sink → [installIsIdempotent] 红；
 * - 未安装就打日志抛异常 → [loggingBeforeInstallDoesNotThrow] 红。
 */
class AppLogTest {

    private class RecordingSink : AppLog.Sink {
        val events = mutableListOf<AppLog.Event>()
        override fun log(event: AppLog.Event) {
            events.add(event)
        }
    }

    private class ExplodingSink : AppLog.Sink {
        override fun log(event: AppLog.Event) = throw IOException("disk on fire")
    }

    @BeforeEach
    fun reset() {
        AppLog.resetForTest()
    }

    @Test
    fun `logging before install does not throw`() {
        // 未 install 必须是安全 no-op：任何组件在 Application.onCreate 之前
        // （以及纯 JVM 测试里）打日志都不能崩
        assertDoesNotThrow {
            AppLog.i("T", "hello")
            AppLog.w("T", "warn")
            AppLog.e("T", "boom", RuntimeException("x"))
        }
        // 环形缓冲与 sink 无关，无条件记录——崩溃钩子的兜底靠它
        assertThat(AppLog.recentLines()).hasSize(3)
    }

    @Test
    fun `sink receives events with fields in order`() {
        val sink = RecordingSink()
        AppLog.install(listOf(sink))

        AppLog.i("Tag1", "first")
        AppLog.w("Tag2", "second", IOException("io"))

        assertThat(sink.events).hasSize(2)
        val first = sink.events[0]
        assertThat(first.level).isEqualTo(AppLog.Level.INFO)
        assertThat(first.tag).isEqualTo("Tag1")
        assertThat(first.message).isEqualTo("first")
        assertThat(first.error).isNull()

        val second = sink.events[1]
        assertThat(second.level).isEqualTo(AppLog.Level.WARN)
        assertThat(second.error).isInstanceOf(IOException::class.java)
        assertThat(second.timeMs).isAtLeast(first.timeMs)
    }

    @Test
    fun `ring evicts oldest when full`() {
        AppLog.install(listOf(RecordingSink()))
        for (n in 1..513) {
            AppLog.i("T", "log $n")
        }
        val lines = AppLog.recentLines()
        assertThat(lines).hasSize(512)
        // 最旧的第 1 条被挤出，从第 2 条开始保留
        assertThat(lines.first()).contains("log 2")
        assertThat(lines.last()).contains("log 513")
    }

    @Test
    fun `sink failure is isolated`() {
        val recording = RecordingSink()
        AppLog.install(listOf(ExplodingSink(), recording))

        // 坏 sink 抛异常：不能传染调用方，也不能拦住后面的好 sink
        assertDoesNotThrow { AppLog.i("T", "still delivered") }
        assertThat(recording.events).hasSize(1)
        assertThat(recording.events[0].message).isEqualTo("still delivered")
    }

    @Test
    fun `install is idempotent`() {
        val first = RecordingSink()
        val second = RecordingSink()
        AppLog.install(listOf(first))
        AppLog.install(listOf(second))

        AppLog.i("T", "once")
        // 第二次 install 是 no-op：事件不重复、后挂的 sink 不生效
        assertThat(first.events).hasSize(1)
        assertThat(second.events).isEmpty()
    }

    @Test
    fun `formatted line contains time level tag and message`() {
        val sink = RecordingSink()
        AppLog.install(listOf(sink))
        AppLog.i("MyTag", "hello world")

        val line = LogFormat.full(sink.events[0])
        assertThat(line).contains("I/MyTag: hello world")
        // 时间戳形如 2026-09-29 08:00:00.123
        assertThat(line).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} I/MyTag: hello world")
    }

    @Test
    fun `error stack trace is included in formatted line`() {
        val sink = RecordingSink()
        AppLog.install(listOf(sink))
        AppLog.e("T", "failed", IllegalStateException("root cause marker"))

        val line = LogFormat.full(sink.events[0])
        assertThat(line).contains("IllegalStateException: root cause marker")
        assertThat(line).contains("\n\tat ")
    }
}
