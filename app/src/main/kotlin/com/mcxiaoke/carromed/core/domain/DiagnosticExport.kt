package com.mcxiaoke.carromed.core.domain

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 诊断日志外投到系统 Downloads 的**纯逻辑层**：文件名生成 + 选片。
 *
 * ## 为什么要外投（这个类存在的唯一理由）
 *
 * 内部日志目录 `filesDir/logs/` 是**应用私有数据**。用户反馈的故障形态是：
 * 重装 → 首次打开不久就崩 → 为了恢复可用只能「清除数据」→ **日志随之一并消失**，
 * 于是崩溃现场永远拿不到。私有目录里的日志在这种恢复路径下等于不存在。
 *
 * Downloads 是公共目录，不随「清除数据」消失，也不随卸载消失（用户主动删除外），
 * 是唯一能跨过"清数据"这道坎的落点。
 *
 * ## 为什么不直接写在 android 侧
 *
 * 文件名与选片判据是**这个功能里最容易出错、也最该被测试盯住**的部分
 * （时间戳格式、app 名来源、只导出自己名下的文件、导出后删源避免重复堆积）。
 * 把它们收进零 `android.*` 的纯 JVM 类，才能在 `testDebugUnitTest` 里直接断言，
 * 不必起 Robolectric 沙箱。与 `AppLog` 门面同一分层思路。
 *
 * 写入侧（MediaStore / legacy File）见 `core/data/DownloadsLogExporter.kt`。
 */
object DiagnosticExport {

    /** 外投文件名前缀。与 app 名拼接，见 [fileName]。 */
    const val CRASH_PREFIX = "carromed-crash-"

    /** 常规运行日志的外投前缀。 */
    const val LOG_PREFIX = "carromed-log-"

    /**
     * 外投文件扩展名。
     *
     * `.txt` 而非 `.log`：MediaStore（API 29+）会依 MIME 反推扩展名，`.log` 会被
     * 追加成 `.log.txt`，导致请求名与落盘名不一致、去重失效。详见 [crashFileName]。
     */
    const val EXPORT_EXT = ".txt"

    /** Downloads 下的子目录名。散在 Downloads 根目录会淹没用户的其他文件。 */
    const val EXPORT_SUBDIR = "CarroMed"

    /**
     * 崩溃现场文件名：`carromed-crash-20261002221245.txt`。
     *
     * 格式与用户现场举例对齐：`carromed-crash-` + 秒级时间戳（14 位）。
     *
     * ## 为什么是 `.txt` 而不是 `.log`（真机实测后定的，不是一开始就这么想）
     *
     * 原设计用 `.log`，实测在 API 29+ 上 MediaStore 依 `MIME_TYPE=text/plain`
     * 反推扩展名，`.log` 不在映射表内 → 落盘变成 `xxx.log.txt`。系统行为不可绕过。
     *
     * 更麻烦的是**连锁反应**：按请求名去重永远命中不了旧件（旧件叫 `xxx.log.txt`，
     * 新件请求 `xxx.log`），于是每次导出都多一份 `xxx.log (1).txt` 僵尸副本。
     *
     * 既然 `.txt` 躲不掉，那就**直接用 `.txt`**：请求名 == 落盘名，
     * 去重、清理、用户肉眼所见三者自然一致，不必再靠"插入后回读实际名字"
     * 这层 workaround 去猜系统会怎么改名。
     *
     * ⚠️ **秒级而非毫秒级**：崩溃循环（启动即崩 → 重启 → 再崩）能在同一秒内产生多次，
     * 但那种场景下写入方是同一个崩溃处理器，重复覆盖同一文件反而**保住了最新一次**——
     * 而分成 `..._001` / `..._002` 只会让用户不知道该看哪份。崩溃排查要的是"最近一次"。
     */
    fun crashFileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        CRASH_PREFIX + STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + EXPORT_EXT

    /**
     * 运行日志外投文件名：`carromed-log-20261002.txt`。
     *
     * 按**天**命名而非按导出时刻：同一天多次启动覆盖同一份，用户在 Downloads 里
     * 永远只看到"今天这份"，不会攒出一堆 `-2`/`-3` 的僵尸文件。
     */
    fun logFileName(day: LocalDate): String =
        LOG_PREFIX + DAY.format(day) + EXPORT_EXT

    /**
     * 决定把内部 `filesDir/logs/` 里的哪些文件外投。
     *
     * @param files 内部日志目录下的文件列表（不保证已排序/已过滤）
     * @param nowMs 当前时刻
     * @param keepLogDays 运行日志外投最近 N 天
     * @return 需要外投的 (源文件 → 目标名)，按源文件名排序保证结果确定
     */
    fun plan(
        files: List<File>,
        nowMs: Long,
        keepLogDays: Int = 3
    ): List<Plan> {
        val logCutoff = nowMs - keepLogDays * DAY_MILLIS
        val plans = mutableListOf<Plan>()

        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            when {
                // 崩溃文件：无条件外投，且**不限天数**。
                // 崩溃样本本就稀缺（keepCrashes 只留 5 个），"三天前崩的"恰恰是
                // 用户 reinstall 后最想看的那次——按天筛掉它等于白做这个功能。
                isCrashFile(name) -> plans.add(
                    Plan(f, crashFileName(crashTimeMs(name) ?: f.lastModified()))
                )

                // 运行日志：按天限量，避免把整个历史目录倒进 Downloads
                isAppLogFile(name) && f.lastModified() >= logCutoff -> plans.add(
                    Plan(f, logFileName(dayOf(f.lastModified())))
                )
            }
        }
        return plans.sortedBy { it.source.name }
    }

    /**
     * 从内部崩溃文件名 `crash-<yyyyMMdd-HHmmss>.txt` 反解崩溃时刻。
     *
     * **为什么不用 mtime**：文件名里的时间戳是崩溃处理器当场写下的权威记录，
     * 而 mtime 可能因文件复制、同步、备份还原而漂移。两者不一致时，
     * 用户拿 Downloads 里的 `...221245.log` 去对照内部 `crash-...-221245.txt`
     * 会以为丢了一次崩溃——所以这里信文件名，mtime 仅在解析失败时兜底。
     *
     * @return 崩溃时刻毫秒数；文件名不符合预期格式时返回 null
     */
    private fun crashTimeMs(fileName: String): Long? = runCatching {
        val stamp = fileName.removePrefix("crash-").removeSuffix(".txt")
        LocalDateTime.parse(stamp, CRASH_STAMP_IN).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** 崩溃现场文件（`CrashLogging` 产出）。 */
    fun isCrashFile(name: String): Boolean =
        name.startsWith("crash-") && name.endsWith(".txt")

    /** 运行日志文件（`LogFileSink` 产出，含 `-N` 体积滚动后缀）。 */
    fun isAppLogFile(name: String): Boolean =
        name.startsWith("app-") && name.endsWith(".log")

    private fun dayOf(timeMs: Long): LocalDate =
        Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 一条外投计划。 */
    data class Plan(val source: File, val targetName: String)

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 内部崩溃文件名里的时间戳格式（`CrashLogging` 产出，见 `AndroidLogging.kt`）。 */
    private val CRASH_STAMP_IN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
