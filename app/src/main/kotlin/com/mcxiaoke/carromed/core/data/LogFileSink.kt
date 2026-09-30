package com.mcxiaoke.carromed.core.data

import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.LogFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 日志文件落盘 sink（PLAN-LOGGING-20260929.md D4/D5）。
 *
 * ## 线程模型：单写者
 *
 * `AppLog` 会在调用方线程（主线程/广播线程）同步调 [log]，这里只做一件事：
 * 把写任务 `launch` 到 **并行度 1** 的 dispatcher 上。单线程 FIFO 保证文件内
 * 行序与调用序一致，因此不需要文件锁；这也依赖全 App 单进程（见 AppLog KDoc）。
 *
 * ## 时间与滚动：只信事件时间戳
 *
 * 滚动判据用 `event.timeMs`（与行内打印的时间是**同一个值**），不用"写盘时刻"——
 * 由此得到一个审计不变量：**一行日志落在哪个文件，与行首打印的日期一致**。
 * 若按写盘时刻滚动，跨夜瞬间排队的历史行会印着昨天的日期躺在今天的文件里。
 *
 * - 按天滚动：跨天自动换 `app-<yyyyMMdd>.log`（同名即 append，时钟回拨也安全）；
 * - 体积护栏：单文件超过 [maxFileBytes]（默认 2 MB）滚动 `-N` 序号后缀。
 *   正常一天 INFO 量 < 100 KB，超 2 MB 必然有循环在刷屏——护栏兼作异常信号；
 * - 过期清理**不在这里做**（写路径上多一次目录扫描就把 IO 问题请回来），
 *   由 `LogFiles.cleanupExpiredLogs` 在 App 启动时跑一次。
 *
 * 纯 JVM（java.io + java.time），无 android.* 依赖，可在纯 JVM 单测里直测。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogFileSink(
    private val dir: File,
    private val maxFileBytes: Long = 2L * 1024 * 1024
) : AppLog.Sink {

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pending = AtomicInteger(0)

    // 以下状态只在写线程上访问（单写者），无需额外同步
    private var writer: BufferedWriter? = null
    private var openDate: LocalDate? = null
    private var openSeq: Int = 0
    private var openFile: File? = null

    override fun log(event: AppLog.Event) {        pending.incrementAndGet()
        writeScope.launch {
            try {
                writeNow(event)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** 测试辅助：等排队中的写任务全部落盘 */
    suspend fun flushForTest() {
        while (pending.get() > 0) yield()
    }

    /**
     * 关闭写句柄。单测（Windows 上打开的句柄会让 @TempDir 删不掉目录）
     * 与 orderly shutdown 用；App 进程由系统回收时 OS 自会兜底。
     * 调用前先等写队列排空（单测里即 [flushForTest]）。
     */
    fun close() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        openFile = null
    }

    private fun writeNow(event: AppLog.Event) {
        try {
            ensureWriter(event.timeMs)
            val w = writer ?: return
            w.write(LogFormat.full(event))
            w.newLine()
            w.flush()
        } catch (t: Throwable) {
            // 落盘失败绝不能向外抛（AppLog 会兜住，但磁盘满这类持续失败不该刷爆 stderr）
            System.err.println("LogFileSink write failed: $t")
        }
    }

    /** 跨天换文件；超体积换 `-N` 后缀。只在写线程调用。 */
    private fun ensureWriter(nowMs: Long) {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val current = writer
        if (current != null && today == openDate) {
            val f = openFile
            if (f == null || f.length() <= maxFileBytes) return
            current.close()
            openSeq += 1
            openWriter(today)
            return
        }
        current?.close()
        openDate = today
        openSeq = 0
        openWriter(today)
    }

    private fun openWriter(date: LocalDate) {
        dir.mkdirs()
        val suffix = if (openSeq == 0) "" else "-$openSeq"
        val f = File(dir, "app-${DATE_FORMAT.format(date)}$suffix.log")
        writer = BufferedWriter(FileWriter(f, /* append = */ true))
        openFile = f
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

/**
 * 日志目录的文件级操作：过期清理。
 *
 * 只碰自己名下的模式（`app-*.log` / `crash-*.txt`），目录本身不删、
 * 不匹配模式的不碰——清理删错文件是数据事故，判据必须收窄。
 */
object LogFiles {

    /**
     * 删除过期日志文件，返回实际删除的文件列表（供启动日志自证行为）。
     *
     * @param keepDays `app-*.log` 按修改时间保留最近 N 天
     * @param keepCrashes crash 文件不按天删（崩溃样本稀缺得多），只留最近 M 个
     */
    fun cleanupExpiredLogs(
        dir: File,
        nowMs: Long,
        keepDays: Int = 7,
        keepCrashes: Int = 5
    ): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        val deleted = mutableListOf<File>()

        val logCutoff = nowMs - keepDays * 24L * 60 * 60 * 1000
        val crashes = mutableListOf<File>()
        for (f in files) {
            when {
                f.isFile && f.name.startsWith("app-") && f.name.endsWith(".log") -> {
                    if (f.lastModified() < logCutoff && f.delete()) deleted.add(f)
                }
                f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") ->
                    crashes.add(f)
            }
        }
        crashes.sortByDescending { it.lastModified() }
        for (f in crashes.drop(keepCrashes)) {
            if (f.delete()) deleted.add(f)
        }
        return deleted
    }
}
