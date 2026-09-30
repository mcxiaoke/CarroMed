package com.mcxiaoke.carromed.core.data

import com.google.common.truth.Truth.assertThat
import com.mcxiaoke.carromed.core.domain.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * LogFileSink 滚动/写盘 与 LogFiles 清理的纯 JVM 测试（PLAN-LOGGING-20260929.md D10）。
 *
 * 变异验证基准：
 * - 跨天不换文件 → [rotatesFileAcrossDays] 红；
 * - 体积护栏失效（永远不滚）→ [rollsFileWhenSizeLimitExceeded] 红；
 * - 落盘异常向上传播 → [writeFailureNeverThrowsToCaller] 红；
 * - 清理判据放宽（删掉不匹配模式的文件）→ [cleanupRemovesExpiredAppLogsOnly] 红；
 * - 清理把最新 crash 也删了 → [cleanupKeepsNewestCrashFiles] 红。
 *
 * 时间不变量（见 LogFileSink KDoc）：行落在哪个文件 = 行内时间戳的日期，
 * 所以测试直接用 `Event.timeMs` 控制日期，无需注入时钟。
 */
class LogFileSinkTest {

    @TempDir
    lateinit var tempDir: File

    private fun millisAt(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 12 * 60 * 60 * 1000

    @Test
    fun `writes events to daily file`() = runBlocking {
        val sink = LogFileSink(tempDir)
        val t = millisAt(LocalDate.of(2026, 9, 29))

        try {
            sink.log(AppLog.Event(t, AppLog.Level.INFO, "Tag", "event one", null))
            sink.log(AppLog.Event(t, AppLog.Level.WARN, "Tag", "event two", null))
            sink.flushForTest()

            val file = File(tempDir, "app-20260929.log")
            assertThat(file.exists()).isTrue()
            val text = file.readText()
            assertThat(text).contains("I/Tag: event one")
            assertThat(text).contains("W/Tag: event two")
            // 每行独立成行，供 grep 逐行审计
            assertThat(text.lines().count { it.isNotBlank() }).isEqualTo(2)
        } finally {
            sink.close()
        }
    }

    @Test
    fun `rotates file across days`() = runBlocking {
        val sink = LogFileSink(tempDir)
        val day1 = millisAt(LocalDate.of(2026, 9, 28))
        val day2 = millisAt(LocalDate.of(2026, 9, 29))

        try {
            sink.log(AppLog.Event(day1, AppLog.Level.INFO, "T", "day one", null))
            sink.flushForTest()
            sink.log(AppLog.Event(day2, AppLog.Level.INFO, "T", "day two", null))
            sink.flushForTest()

            assertThat(File(tempDir, "app-20260928.log").readText()).contains("day one")
            assertThat(File(tempDir, "app-20260929.log").readText()).contains("day two")
            // 跨天后旧文件不再追加：行落在的文件 = 行内日期（时间不变量）
            assertThat(File(tempDir, "app-20260928.log").readText()).doesNotContain("day two")
        } finally {
            sink.close()
        }
    }

    @Test
    fun `rolls file when size limit exceeded`() = runBlocking {
        val sink = LogFileSink(tempDir, maxFileBytes = 64)
        val t = millisAt(LocalDate.of(2026, 9, 29))

        try {
            repeat(10) { n -> sink.log(AppLog.Event(t, AppLog.Level.INFO, "T", "filler line number $n", null)) }
            sink.flushForTest()

            // 首文件超限后滚出 -1 序号文件；两个文件都在、且都有内容
            val first = File(tempDir, "app-20260929.log")
            val rolled = File(tempDir, "app-20260929-1.log")
            assertThat(first.exists()).isTrue()
            assertThat(rolled.exists()).isTrue()
            assertThat(rolled.readText()).isNotEmpty()
            // 护栏生效：首文件不再继续膨胀（+256 = 单行时间戳等富余）
            assertThat(first.length()).isAtMost(64 + 256)
        } finally {
            sink.close()
        }
    }

    @Test
    fun `write failure never throws to caller`() = runBlocking {
        // 目录被一个同名"文件"占住：mkdirs 失败 → openWriter 抛 IOException → 必须被吞
        val blocker = File(tempDir, "blocked")
        blocker.createNewFile()
        val sink = LogFileSink(File(blocker, "logs"))
        val t = millisAt(LocalDate.of(2026, 9, 29))

        try {
            assertDoesNotThrow {
                sink.log(AppLog.Event(t, AppLog.Level.INFO, "T", "into the void", null))
                runBlocking { sink.flushForTest() }
            }
        } finally {
            sink.close()
        }
    }
}

class LogFilesCleanupTest {

    @TempDir
    lateinit var tempDir: File

    private fun touch(file: File, lastModifiedMs: Long) {
        file.createNewFile()
        file.setLastModified(lastModifiedMs)
    }

    @Test
    fun `cleanup removes expired app logs only`() {
        val now = 1_800_000_000_000L
        val cutoff = now - 7 * 24L * 60 * 60 * 1000
        val fresh = File(tempDir, "app-20260929.log")
        val stale = File(tempDir, "app-20260920.log")
        val crash = File(tempDir, "crash-20260920-120000.txt")
        val untouched = File(tempDir, "user-data.json")
        // 后缀 .log 但不是本系统名下的文件——判据必须是"前缀+后缀"双匹配
        val foreignLog = File(tempDir, "other-20260920.log")
        touch(fresh, now)
        touch(stale, cutoff - 1)
        touch(crash, cutoff - 1)
        touch(untouched, cutoff - 1)
        touch(foreignLog, cutoff - 1)

        val deleted = LogFiles.cleanupExpiredLogs(tempDir, now)

        assertThat(deleted).containsExactly(stale)
        assertThat(fresh.exists()).isTrue()
        // crash 文件不按天删，只按"最近 N 个"留（D5）
        assertThat(crash.exists()).isTrue()
        assertThat(untouched.exists()).isTrue()
        assertThat(foreignLog.exists()).isTrue()
    }

    @Test
    fun `cleanup keeps newest crash files`() {
        val now = 1_800_000_000_000L
        val crashes = (1..7).map { n ->
            File(tempDir, "crash-2026092$n-120000.txt").also { touch(it, now + n) }
        }

        val deleted = LogFiles.cleanupExpiredLogs(tempDir, now, keepCrashes = 5)

        // 留最新 5 个，删最旧 2 个
        assertThat(deleted).containsExactly(crashes[0], crashes[1])
        assertThat(crashes.drop(2).all { it.exists() }).isTrue()
    }

    @Test
    fun `cleanup on missing dir is a no-op`() {
        val missing = File(tempDir, "does-not-exist")
        val deleted = LogFiles.cleanupExpiredLogs(missing, nowMs = 0L)
        assertThat(deleted).isEmpty()
    }
}
