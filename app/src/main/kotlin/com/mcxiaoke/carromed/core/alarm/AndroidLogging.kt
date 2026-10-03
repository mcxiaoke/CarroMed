package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.util.Log
import com.mcxiaoke.carromed.BuildConfig
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.data.DownloadsLogExporter
import com.mcxiaoke.carromed.core.data.LogFileSink
import com.mcxiaoke.carromed.core.data.LogFiles
import com.mcxiaoke.carromed.core.domain.DiagnosticExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日志系统的 android 侧装配（PLAN-LOGGING-20260929.md S1）。
 *
 * 门面本体 `AppLog` 在 `core/domain` 且零 android 依赖（纯 JVM 测试兼容），
 * 所有会碰到 `android.*` 的实现都收拢在本文件。`CarroMedApp.onCreate` 第一行
 * 调 [install]——它必须早于其他任何初始化，让后续步骤的日志都能被捕获。
 */
object AppLogging {

    private const val TAG = "AppLogging"

    fun install(context: Context) {
        val dir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
        AppLog.install(
            listOf(
                LogcatSink(includeInfo = BuildConfig.DEBUG),
                LogFileSink(dir)
            )
        )
        CrashLogging.install(context, dir)
        // 清理只在启动时跑一次（D5），写路径上不做目录扫描
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val deleted = LogFiles.cleanupExpiredLogs(dir, System.currentTimeMillis())
            if (deleted.isNotEmpty()) {
                AppLog.i(TAG, "expired logs cleaned: ${deleted.size} file(s)")
            }
            // 崩溃补投：崩溃现场那次外投可能因超时/进程已死而没落盘（见 DownloadsLogExporter），
            // 也可能压根没走到 UncaughtExceptionHandler（native crash / OOM kill）——
            // 两种情况都靠这次启动扫描把内部日志补进 Downloads。清数据后的用户
            // 重开 App 仍能拿到上一次的崩溃现场，这是这个功能的核心承诺。
            val exported = DownloadsLogExporter.exportAll(context.applicationContext, dir, System.currentTimeMillis())
            if (exported.isNotEmpty()) {
                AppLog.i(
                    TAG,
                    "diagnostics exported to Downloads/${DiagnosticExport.EXPORT_SUBDIR}: " +
                        "${exported.joinToString()} (via ${DownloadsLogExporter.activePathLabel(context)})"
                )
            }
        }
    }

    /** 导出诊断日志时需要的目录（设置页读取，不依赖 sink 内部状态） */
    fun logDir(context: Context): File =
        File(context.applicationContext.filesDir, LOG_DIR_NAME)

    const val LOG_DIR_NAME = "logs"
}

/** logcat 通道：release 只保留 WARN+（省系统缓冲），文件不跟着关（离线排查全靠它）。 */
class LogcatSink(private val includeInfo: Boolean) : AppLog.Sink {

    override fun log(event: AppLog.Event) {
        when (event.level) {
            AppLog.Level.INFO -> if (includeInfo) {
                Log.i(event.tag, event.message, event.error)
            }
            AppLog.Level.WARN -> Log.w(event.tag, event.message, event.error)
            AppLog.Level.ERROR -> Log.e(event.tag, event.message, event.error)
        }
    }
}

/**
 * 全局崩溃钩子（PLAN-LOGGING-20260929.md D3）。
 *
 * 崩溃时**同步**（不走任何 dispatcher）把 `AppLog.recentLines()` 与堆栈写进
 * `crash-<时间戳>.txt`，**并额外外投一份到系统 Downloads**，然后**必须**
 * 交回系统默认 handler——留痕失败不能变成二次故障，吞掉崩溃更是绝对禁止。
 *
 * ## 为什么必须写两份
 *
 * 内部 `filesDir/logs/` 是应用私有数据，**「清除数据」会一并抹掉**。
 * 而本项目最典型的故障恢复动作恰恰就是清数据：重装后首次打开即崩 →
 * 用户清数据 → 能用了 → 但崩溃现场也一起没了，永远查不出原因。
 * Downloads 不随清数据消失，是唯一能跨过这道坎的落点。
 *
 * 内部那份不能省：它得支撑 App 内的诊断日志入口，且外投超时时（见
 * `DownloadsLogExporter` 的超时说明）它是唯一的兜底。
 */
object CrashLogging {

    fun install(context: Context, dir: File) {
        val appContext = context.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Handler) return // 幂等
        Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, dir, current))
    }

    private class Handler(
        private val appContext: Context,
        private val dir: File,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            // 先取时间戳：崩溃瞬间的时钟读数，比任何后续 IO 失败都更可信
            val nowMs = System.currentTimeMillis()
            val report = runCatching { buildReport(thread, error, nowMs) }.getOrNull()

            if (report != null) {
                // 内部落盘在前：纯 File IO，无 IPC，不会被 system_server 拖住
                runCatching { writeInternal(report, nowMs) }
                // 外投在后：内部已有底稿，这一步失败不致命（有界超时，见 exporter）
                runCatching {
                    DownloadsLogExporter.exportCrashBlocking(appContext, report, nowMs)
                }
            } else {
                AppLog.e("CrashLogging", "failed to build crash report")
            }

            // 交回系统默认 handler：无论上面成败，崩溃都必须照原样呈现
            previous?.uncaughtException(thread, error)
        }

        private fun writeInternal(report: String, nowMs: Long) {
            dir.mkdirs()
            val stamp = CRASH_FILE_FORMAT.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
            File(dir, "crash-$stamp.txt").writeText(report)
        }

        private fun buildReport(thread: Thread, error: Throwable, nowMs: Long): String = buildString {
            append("app=${appContext.packageName}")
            append(" version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append(" debug=${BuildConfig.DEBUG}")
            append(" thread=${thread.name}")
            append('\n')
            append("== recent log (last ${AppLog.recentLines().size} lines) ==")
            append('\n')
            for (line in AppLog.recentLines()) {
                append(line)
                append('\n')
            }
            append("\n== uncaught exception ==\n")
            val sw = java.io.StringWriter()
            error.printStackTrace(PrintWriter(sw))
            append(sw.toString())
        }
    }
}

private val CRASH_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
