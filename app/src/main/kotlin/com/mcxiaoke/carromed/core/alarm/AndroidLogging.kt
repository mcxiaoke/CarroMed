package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.util.Log
import com.mcxiaoke.carromed.BuildConfig
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.data.LogFileSink
import com.mcxiaoke.carromed.core.data.LogFiles
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
 * `crash-<时间戳>.txt`，然后**必须**交回系统默认 handler——留痕失败不能变成
 * 二次故障，吞掉崩溃更是绝对禁止。
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
            try {
                writeCrashFile(thread, error)
            } catch (ignored: Throwable) {
                // 崩溃钩子自身故障时什么都不做，最终交回默认 handler 是底线
            }
            previous?.uncaughtException(thread, error)
        }

        private fun writeCrashFile(thread: Thread, error: Throwable) {
            dir.mkdirs()
            val stamp = CRASH_FILE_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))
            val file = File(dir, "crash-$stamp.txt")
            file.writeText(
                buildString {
                    append("app=${appContext.packageName}")
                    append(" version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    append(" debug=${BuildConfig.DEBUG}")
                    append(" thread=${thread.name}")
                    append('\n')
                    append("== recent log (last ${AppLog.recentLines().size} lines) ==\n")
                    for (line in AppLog.recentLines()) {
                        append(line)
                        append('\n')
                    }
                    append("\n== uncaught exception ==\n")
                    val sw = java.io.StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    append(sw.toString())
                }
            )
        }
    }
}

private val CRASH_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
