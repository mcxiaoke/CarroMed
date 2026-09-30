package com.mcxiaoke.carromed.core.domain

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 全项目唯一的日志门面（PLAN-LOGGING-20260929.md）。
 *
 * ## 为什么不用 `android.util.Log`，也不引 Timber
 *
 * 1. **纯 JVM 测试兼容**：`app/build.gradle.kts` 的 `testOptions.unitTests`
 *    未开 `returnDefaultValues`，纯 JVM 测试（jqwik `@Property`）里任何代码路径
 *    碰到 `android.util.Log` 会抛 "not mocked"。本类零 `android.*` import，
 *    未安装 sink 时默认 no-op，任何测试环境都安全。
 * 2. **崩溃留痕**：异步写文件与"崩溃前最后几行不丢"不可兼得，所以每次调用
 *    都同步把格式化行塞进 [CAPACITY] 行的环形缓冲；崩溃钩子（android 侧的
 *    `CrashLogging`）同步把缓冲 dump 进 crash 文件。
 * 3. 项目体量（80 文件、轻依赖手工装配）撑不起一个日志框架，Timber 只省 tag
 *    管理而不管文件滚动/崩溃钩子/导出——自建最短，见方案 D1。
 *
 * ## 输出通道：Sink 接口
 *
 * Android 侧实现（`core/alarm/AndroidLogging.kt`）在 `CarroMedApp.onCreate`
 * 经 `AppLogging.install(context)` 挂两个 sink：LogcatSink 与 LogFileSink。
 * 挂载方式与 `CurrentDateHolder.install` 同一模式（进程级单例 + 幂等 install）。
 *
 * ⚠️ **单进程假设**：FileSink 是单写者模型，依赖全 App 同进程（Manifest 已核实
 * 无 `android:process`）。若未来引入多进程，本类与 FileSink 必须重新评审。
 */
object AppLog {

    /** 严重级别。与 `android.util.Log` 的 i/w/e 三档一一对应。 */
    enum class Level { INFO, WARN, ERROR }

    /** 一条日志事件的不可变快照。时间在**入队时刻**取，不是 sink 消费时刻。 */
    class Event(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val error: Throwable?
    )

    /**
     * 输出通道。实现必须自己保证线程安全与非阻塞：
     * `log` 在调用方线程（常为主线程 / 广播线程）被同步调用，
     * 慢 IO 请自行切线程（`LogFileSink` 即如此）。
     */
    interface Sink {
        fun log(event: Event)
    }

    /** 环形缓冲容量。512 行 × 每行几百字节 ≈ 最多几百 KB 内存，可忽略。 */
    private const val CAPACITY = 512

    private val lock = Any()
    private val sinks = mutableListOf<Sink>()
    private val ring = ArrayDeque<String>(CAPACITY)
    private var installed = false

    /** 幂等：只在首次生效。`Application.onCreate` 只跑一次，但要防测试重复调。 */
    fun install(newSinks: List<Sink>) {
        synchronized(lock) {
            if (installed) return
            installed = true
            sinks.addAll(newSinks)
        }
    }

    fun i(tag: String, message: String) = dispatch(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        dispatch(Level.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        dispatch(Level.ERROR, tag, message, error)

    private fun dispatch(level: Level, tag: String, message: String, error: Throwable?) {
        val event = Event(System.currentTimeMillis(), level, tag, message, error)
        synchronized(lock) {
            // 环形缓冲**无条件记录**，与 sink 是否安装无关——
            // 崩溃钩子靠它兜底，FileSink 挂掉也不能丢掉这段最近历史。
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(LogFormat.full(event))
            val snapshot = sinks.toList()
            // sink 异常与调用方、与彼此完全隔离（在锁外分发，避免持锁做 IO）
            for (sink in snapshot) {
                try {
                    sink.log(event)
                } catch (t: Throwable) {
                    System.err.println("AppLog sink ${sink::class.java.simpleName} failed: $t")
                }
            }
        }
    }

    /** 供崩溃钩子同步 dump 最近日志（多行文本，含异常堆栈）。 */
    fun recentLines(): List<String> = synchronized(lock) { ring.toList() }

    /** 仅测试用：清空 sinks 与缓冲、复位 installed 标记 */
    fun resetForTest() = synchronized(lock) {
        sinks.clear()
        ring.clear()
        installed = false
    }
}

/**
 * 日志行的唯一格式化实现。FileSink 与环形缓冲共用它，
 * 保证"文件里的一行"与"crash 文件里的一行"长一个样，grep 规则只有一套。
 */
object LogFormat {

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** `2026-09-29 08:00:00.123 I/Tag: message`；有异常时另起一行缩进堆栈。 */
    fun full(event: AppLog.Event): String = buildString {
        append(Instant.ofEpochMilli(event.timeMs).atZone(ZoneId.systemDefault()).format(TIME))
        append(' ')
        append(event.level.name.first())
        append('/')
        append(event.tag)
        append(": ")
        append(event.message)
        if (event.error != null) {
            append('\n')
            append(stackTraceOf(event.error))
        }
    }

    // 纯 JVM 的堆栈转字符串；`android.util.Log.getStackTraceString` 在纯 JVM 测试里会炸
    private fun stackTraceOf(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd('\n')
    }
}
