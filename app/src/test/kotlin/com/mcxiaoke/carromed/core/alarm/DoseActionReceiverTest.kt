package com.mcxiaoke.carromed.core.alarm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 通知栏快捷操作接收器的异常围栏测试（治代码审查 zcg 报告 P2#2）。
 *
 * ## 缺陷
 *
 * `DoseActionReceiver` 的协程体只有 `try { ... } finally { result.finish() }`，
 * **没有 `catch`**——同族的 `AlarmReceiver`（catch + Log.e）与 `BootReceiver`
 * （catch + Log.e）都有围栏，唯独它没有。用户点一下通知栏按钮，
 * 任何 DB 异常（库被短暂占用、句柄失效）都会成为未捕获异常
 * 走默认处理器 ⇒ **整个 App 进程被打崩**，且 `goAsync` 的保护形同虚设。
 *
 * ## 为什么是源码扫描而不是行为注入
 *
 * 行为上逼出这个异常，唯一入口是把 `AppDatabase.getInstance` 的静态单例弄坏
 * ——而 AGENTS §3 明令测试不得依赖/污染单例（它跨测试方法不重置，
 * 弄坏一次全沙箱遭殃）。所以退而求其次用**形状扫描**：
 * 与 `AppendOnlyFactTableTest` 的静态半同一模式（同样的"剥注释"处理）。
 * 静态扫描拦不住"形状对但逻辑错"，但这里要守的恰好就是一个形状：
 * 协程体里必须存在 `catch (...: Throwable)`。
 *
 * ⚠️ 修法必须与 `AlarmReceiver` 同款：协程体内 `catch (t: Throwable) { Log.e(...) }`。
 * 若改成 `catch (e: Exception)` 本测试会继续红——故意为之：
 * `Throwable` 是这三个接收器之间已经统一的围栏口径，别引入第二种。
 */
class DoseActionReceiverTest {

    private val receiverSource =
        File("src/main/kotlin/com/mcxiaoke/carromed/core/alarm/DoseActionReceiver.kt")

    @Test
    fun `通知栏快捷操作的协程体必须兜住 Throwable 不允许异常逃逸崩溃进程`() {
        assertThat(receiverSource.exists()).isTrue()
        val stripped = stripComments(receiverSource.readText(Charsets.UTF_8))

        // ★ 当前实现没有任何 catch——用户点通知栏按钮即可让进程崩溃
        val hasThrowableCatch = Regex("""catch\s*\(\s*\w+\s*:\s*Throwable\s*\)""")
            .containsMatchIn(stripped)
        assertThat(hasThrowableCatch).isTrue()
    }

    /** 与 `AppendOnlyFactTableTest` 同款：先把注释剥掉，防止 KDoc 里的字样误报 */
    private fun stripComments(src: String): String =
        src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
}
