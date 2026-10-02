package com.mcxiaoke.carromed.core.time

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * "今天是几号"的可刷新事实（M3-2）。
 *
 * ## 为什么住在 `core/time` 而不是 `core/domain`
 *
 * 它挂在 `ProcessLifecycleOwner` 上、还持有 `Application`，天然带 `android.*` 依赖；
 * 而领域层（AGENTS §1 铁律）必须零 android 依赖，才能在 JVM 单测里真跑起来
 * （Room 的 `withTransaction` 是唯一豁免）。它既不是领域计算也不是 UI，
 * 而是**进程级的时间基础设施**，因此单列 `core/time`。（此前误居 `core/domain`，见 §二-29。）
 *
 * ## 为什么它必须是 Flow 而不是 `val today = LocalDate.now()`
 *
 * 进展页与今日页都把 `LocalDate.now()` 存成**字段**，
 * 于是"今天"在 ViewModel 构造的那一刻被**永久冻结**。
 * 进程跨夜存活（用户睡前打开 App，早晨再看）时：
 *
 * - 今日页显示**昨天**的清单，日期选择器的高亮停在昨天；
 * - 进展页的 7 天矩阵永远不滚动，最后一列标着「今日」其实是昨天；
 * - `isFutureDay` 判据全部基于那个陈旧的 today，整列状态错位。
 *
 * 而且**没有任何翻转监听**：没有任何代码会在午夜或回到前台时重算。
 * 用户唯一的察觉方式是"这个 App 好像卡在昨天了"，却无处可说。
 *
 * ## 两个刷新源，缺一不可
 *
 * | 源 | 覆盖的场景 |
 * | :--- | :--- |
 * | 定时器（每分钟） | App 一直开着，跨过午夜那一刻 |
 * | 进程回到前台 | App 被切走很久（进程还活着）后回到前台 |
 *
 * 只做定时器不够：App 在后台时协程可能被挂起，回到前台的第一屏就已是错的 ——
 * 而那正是最需要正确的时刻。
 * 只做前台监听也不够：用户盯着屏幕看着它从 23:59 变成 00:00，
 * 那一刻没有任何生命周期事件。
 *
 * ## 挂在哪里
 *
 * 进程级单例，由 [install] 在 `CarroMedApp.onCreate` 里挂一次。
 * 每页各写一遍定时器 / 监听，迟早有一页漏掉，而漏掉的那页不会有任何报错。
 */
object CurrentDateHolder {

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /**
     * 轮询间隔。
     *
     * 60 秒是权衡：一分钟内跨过午夜时用户最多等一分钟，
     * 而 14 天对账本身也是 15 分钟一轮 —— 这里再密没有额外价值。
     */
    const val TICK_INTERVAL_MS = 60_000L

    @Volatile
    private var installed = false

    /** 幂等。重复调用无副作用（Application.onCreate 只跑一次，但要防测试重复调） */
    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        // 进程级 scope：它比任何页面活得都长，理应独立于 UI 存活
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                refresh()
                delay(TICK_INTERVAL_MS)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = refresh()
            }
        )
    }

    /**
     * 只有日期真的变了才发射。
     *
     * 每分钟无条件发射会让所有下游 combine 被唤醒 1440 次/天，
     * 而绝大多数时候"今天"根本没变。
     */
    fun refresh() {
        val now = LocalDate.now()
        if (now != _today.value) _today.value = now
    }

    /** 供测试显式推进"今天" */
    fun setTodayForTest(date: LocalDate) {
        _today.value = date
    }

    /** 仅测试用：复位到真实今天并清掉 installed 标记 */
    fun resetForTest() = synchronized(this) {
        installed = false
        _today.value = LocalDate.now()
    }
}