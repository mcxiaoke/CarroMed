package com.mcxiaoke.carromed.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 诊断日志外投的**选片与命名**判据（纯 JVM）。
 *
 * 这组测试守的是「崩溃现场到底有没有被投进 Downloads」——
 * 判据一旦放宽或收紧错位，用户的故障现场就会静默丢失，且没有任何报错：
 *
 * - 崩溃文件被按天筛掉 → [crashFilesAreExportedRegardlessOfAge] 红。
 *   这是本功能最容易写错的一处：崩溃样本本就稀缺，"三天前崩的"恰恰是
 *   用户重装后最想看的那次，按天筛等于白做。
 * - 时间戳格式错位 → [crashFileNameMatchesAgreedFormat] 红。
 *   用户按 `carromed-crash-20261002221245.txt` 这个格式去 Downloads 里找。
 * - 运行日志不限量 → 运行日志目录会整个倒进 Downloads，用户的下载文件夹被淹没。
 * - 判据放宽（导出目录里任何文件都投）→ [foreignFilesAreNeverExported] 红。
 *   清理逻辑删错文件是数据事故，判据必须只认自己名下的两种模式。
 * - 同名堆积 → [repeatedLaunchesProduceStableTargetNames] 红。
 *   每次启动都补投，若目标名不稳定，Downloads 里会攒出一串 `name (1).log`。
 */
class DiagnosticExportTest {

    @TempDir
    lateinit var tempDir: File

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun millisAt(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun file(name: String, modifiedAt: Long): File =
        File(tempDir, name).apply {
            writeText("content of $name")
            setLastModified(modifiedAt)
        }

    // ---------------- 文件名格式 ----------------

    @Test
    fun `crash file name matches agreed format`() {
        // 用户现场举例：carromed-crash-20261002221245.txt
        val at = LocalDateTime.of(2026, 10, 2, 22, 12, 45)
            .atZone(zone).toInstant().toEpochMilli()

        val name = DiagnosticExport.crashFileName(at, zone)

        assertThat(name).isEqualTo("carromed-crash-20261002221245.txt")
    }

    @Test
    fun `log file name is per-day and stable`() {
        val day = LocalDate.of(2026, 10, 2)

        val name = DiagnosticExport.logFileName(day)

        // 同一天不同时刻导出必须同名，否则每次启动都在 Downloads 攒新文件
        assertThat(name).isEqualTo("carromed-log-20261002.txt")
        assertThat(DiagnosticExport.logFileName(day)).isEqualTo(name)
    }

    // ---------------- 选片：崩溃文件 ----------------

    @Test
    fun `crash files are exported regardless of age`() {
        val now = millisAt(LocalDate.of(2026, 10, 2))
        // 40 天前的崩溃——远超默认 3 天窗口
        val oldCrash = file("crash-20260823-120000.txt", millisAt(LocalDate.of(2026, 8, 23)))

        val plan = DiagnosticExport.plan(listOf(oldCrash), now)

        assertThat(plan).hasSize(1)
        // 目标名由 mtime 推导（崩溃发生时刻），不是"何时导出"的时刻
        assertThat(plan.single().targetName).isEqualTo("carromed-crash-20260823120000.txt")
    }

    @Test
    fun `crash target name derives from file mtime so repeated launches overwrite`() {
        val day = LocalDate.of(2026, 10, 2)
        val at = LocalDateTime.of(2026, 10, 2, 22, 12, 45).atZone(zone).toInstant().toEpochMilli()
        val crash = file("crash-20261002-221245.txt", at)

        val first = DiagnosticExport.plan(listOf(crash), millisAt(day)).single().targetName
        val second = DiagnosticExport.plan(listOf(crash), millisAt(day) + 3_600_000).single().targetName

        // 目标名只由源文件决定，与"何时导出"无关 → 启动 N 次也只有一份
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `crash target name follows the internal file name not a drifted mtime`() {
        val day = LocalDate.of(2026, 10, 2)
        // 文件名记录崩溃于 22:12:45；mtime 却漂到了当晚 23:59（复制/同步/还原会这样）
        val crash = file(
            "crash-20261002-221245.txt",
            LocalDateTime.of(2026, 10, 2, 23, 59, 0).atZone(zone).toInstant().toEpochMilli()
        )

        val target = DiagnosticExport.plan(listOf(crash), millisAt(day)).single().targetName

        // 用户要拿 Downloads 里的名字去对照内部 crash-...-221245.txt，
        // 若信 mtime 就会变成 235900，看着像丢了另一次崩溃
        assertThat(target).isEqualTo("carromed-crash-20261002221245.txt")
    }

    @Test
    fun `unparseable crash file name falls back to mtime instead of dropping the file`() {
        val day = LocalDate.of(2026, 10, 2)
        val at = LocalDateTime.of(2026, 10, 2, 22, 12, 45).atZone(zone).toInstant().toEpochMilli()
        // 未来改格式时的旧文件：名字解析不了，但**绝不能因此不导出**（丢了就再也找不回）
        val odd = file("crash-legacy.txt", at)

        val target = DiagnosticExport.plan(listOf(odd), millisAt(day)).single().targetName

        assertThat(target).isEqualTo("carromed-crash-20261002221245.txt")
    }

    // ---------------- 选片：运行日志 ----------------

    @Test
    fun `recent app logs within window are exported`() {
        val now = millisAt(LocalDate.of(2026, 10, 2))
        val files = listOf(
            file("app-20261002.log", millisAt(LocalDate.of(2026, 10, 2))),
            file("app-20261001.log", millisAt(LocalDate.of(2026, 10, 1))),
            // 10 天前：超出 3 天窗口，不投
            file("app-20260922.log", millisAt(LocalDate.of(2026, 9, 22)))
        )

        val plan = DiagnosticExport.plan(files, now, keepLogDays = 3)

        assertThat(plan.map { it.targetName })
            .containsExactly("carromed-log-20261001.txt", "carromed-log-20261002.txt")
    }

    @Test
    fun `rolled app log variants are all exported under the same day name`() {
        val now = millisAt(LocalDate.of(2026, 10, 2))
        // LogFileSink 体积护栏会产生 -1 / -2 后缀的同日文件
        val files = listOf(
            file("app-20261002.log", millisAt(LocalDate.of(2026, 10, 2))),
            file("app-20261002-1.log", millisAt(LocalDate.of(2026, 10, 2)))
        )

        val plan = DiagnosticExport.plan(files, now)

        // 同日多片段都认得出来（认不出就等于漏投那段）
        assertThat(plan).hasSize(2)
        assertThat(plan.map { it.targetName }.toSet())
            .containsExactly("carromed-log-20261002.txt")
    }

    // ---------------- 选片：边界 ----------------

    @Test
    fun `foreign files are never exported`() {
        val now = millisAt(LocalDate.of(2026, 10, 2))
        val files = listOf(
            file("notes.txt", now),
            file("backup.db", now),
            file("app-20261002.log.bak", now),
            file("crash-20261002-221245.txt.tmp", now)
        )

        val plan = DiagnosticExport.plan(files, now)

        // 只认 app-*.log 与 crash-*.txt 两种模式，其余一律不碰
        assertThat(plan).isEmpty()
    }

    @Test
    fun `directories are skipped`() {
        val now = millisAt(LocalDate.of(2026, 10, 2))
        val dir = File(tempDir, "app-20261002.log").apply { mkdirs() }

        val plan = DiagnosticExport.plan(listOf(dir), now)

        assertThat(plan).isEmpty()
    }

    @Test
    fun `plan order is deterministic`() {
        val now = millisAt(LocalDate.of(2026, 10, 2))
        val files = listOf(
            file("crash-20261002-221245.txt", now),
            file("app-20261001.log", millisAt(LocalDate.of(2026, 10, 1))),
            file("app-20261002.log", now)
        )

        val forward = DiagnosticExport.plan(files, now).map { it.source.name }
        val reversed = DiagnosticExport.plan(files.reversed(), now).map { it.source.name }

        // 目录 listFiles() 的顺序不保证稳定，选片结果必须与入参顺序无关
        assertThat(forward).isEqualTo(reversed)
        assertThat(forward).containsExactly(
            "app-20261001.log", "app-20261002.log", "crash-20261002-221245.txt"
        ).inOrder()
    }
}
