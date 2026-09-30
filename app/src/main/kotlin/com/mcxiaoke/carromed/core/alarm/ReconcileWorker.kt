package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.domain.AppLog
import java.util.concurrent.TimeUnit

/**
 * 周期对账兜底（兑现 `FINAL-PRODUCT` D-14 的第三档承诺，此前**零实现**）。
 *
 * ## 它补的是哪个洞
 *
 * 闹钟视野是有限的，排完 [AlarmReconciler.HORIZON_DAYS] 天之外就没有闹钟了。
 * 三层保险各管一段：
 *
 * | 层 | 位置 | 覆盖 |
 * | :--- | :--- | :--- |
 * | 1. 视野窗口 | `AlarmReconciler.HORIZON_DAYS = 14` | 用户不打开 App 也能覆盖半个月 |
 * | 2. 触发后续期 | 响铃弹通知 + [enqueueOneShot] 立即补一轮 | 即时，唤醒链自维持 |
 * | 3. **本 Worker** | 15 分钟一轮 | 前两层都失效时的最终兜底 |
 *
 * 缺了第 3 层，P0-2 的修复就只是"把窗口从 7 天拉到 14 天"，
 * 而 14 天依然是有限值 —— 装好 App 后一个月不碰它，第 15 天照样静默漏提醒。
 *
 * ## 为什么是 WorkManager 而不是 `AlarmManager.setRepeating`
 *
 * - WorkManager 自带**进程唤醒**：Worker 运行时系统会拉起我们的进程，
 *   不需要自己在 `BroadcastReceiver` 里 `goAsync` + 起协程（易被系统判为 ANR）。
 * - Doze / 厂商省电策略下，`setRepeating` 的实际间隔可以劣化到几小时甚至不触发；
 *   WorkManager 的调度是系统级统一编排，行为可预期得多。
 * - 指数退避（[setBackoffCriteria]）是内建能力，不需要自己写重试计数。
 *
 * **不需要也不应该有前台常驻服务** —— 见 `REMINDER-DOMAIN-REDESIGN.md` §6.5。
 *
 * ## 已知取舍：15 分钟是名义下限，不是保证
 *
 * `PeriodicWorkRequest` 的**实际**执行间隔由系统按电量优化与厂商策略决定。
 * 官方文档明确说 15 分钟是下限，真实间隔可能更长。
 * 所以本 Worker 是**第二层兜底**而不是唯一保障 —— 第 2 层（触发后续期）才是即时的。
 * 两者缺一不可，**不要**因为"有了 Worker"就削弱视野窗口或触发后续期。
 *
 * ## 无网络
 *
 * 本项目物理断网（`AndroidManifest.xml` 里严禁 `INTERNET` 权限），
 * 所以 [Constraints] 不设任何网络要求。写出来是为了让"为什么没有
 * `NetworkType.CONNECTED`"成为一个显式决定，而不是遗漏。
 */
class ReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val db = AppDatabase.getInstance(context)
            AlarmReconciler.rescheduleAll(context, db)
            // 周期与一次性任务共用 doWork，日志别写死 "periodic"——
            // 否则排查闹钟链路时会把 oneshot 的成功误读成周期任务（真实踩过）
            AppLog.i(TAG, "reconcile run done (tags=$tags)")
            Result.success()
        } catch (t: Throwable) {
            // ⚠️ 关键：抛异常时**必须**返回 retry 而不是直接 failure。
            // 返回 failure 等于"永久放弃这一轮"，而对账恰恰是最该重试的事情
            // （多半是数据库被另一个事务短暂占住）。
            AppLog.e(TAG, "reconcile run failed, will retry", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ReconcileWorker"

        /** 周期任务的唯一名。WorkManager 用它做去重，`KEEP` 策略下重复 enqueue 无副作用。 */
        const val UNIQUE_NAME = "carromed-periodic-reconcile"

        /**
         * 一次性对账的唯一名。响铃 / 开机后由 Receiver 交给本 Worker 补一轮全量对账，
         * **与周期任务不同名** —— `BootReceiver` 的 `REPLACE` 只该重排周期任务，
         * 不能连带把挂着的一次性对账一起换掉。
         */
        const val ONESHOT_NAME = "carromed-oneshot-reconcile"

        /**
         * 15 分钟是 `PeriodicWorkRequest` 的**名义下限**。
         * 再短会被 WorkManager 直接拒绝（抛 `IllegalArgumentException`）。
         */
        val REPEAT_INTERVAL_MINUTES = 15L

        /**
         * 指数退避：失败后按 1 起步、逐次翻倍、最低 10 分钟。
         *
         * 不设下限的话退避间隔会一路涨到几十分钟，对"下一轮排闹钟"这种
         * 十几分钟尺度的事情来说太慢了。
         */
        val BACKOFF_MINUTES = 10L

        /**
         * 构造周期请求。**提成独立函数是为了可测**：
         * `WorkInfo` 上读不到周期间隔与退避策略（那在 `WorkSpec` 上），
         * 而 `WorkManagerImpl.getWorkSpec` 是 `@RestrictTo` 内部 API。
         * 让测试直接断言这个函数的返回值，比反射读内部字段稳得多。
         */
        fun buildRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ReconcileWorker>(
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES,
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES   // flex：给系统 15 分钟窗口内自由调度
            )
                .setConstraints(Constraints.Builder().build())  // 无任何约束：断网也要跑
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_MINUTES, TimeUnit.MINUTES
                )
                .addTag(UNIQUE_NAME)
                .build()

        /**
         * 构造一次性对账请求。**提成独立函数是为了可测**（理由同 [buildRequest]）。
         */
        fun buildOneShotRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setConstraints(Constraints.Builder().build())  // 无任何约束：断网也要跑
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS
                )
                .addTag(ONESHOT_NAME)
                .build()

        /**
         * 入队**立即跑一轮**的全量对账。触发点：`AlarmReceiver` 响铃后、`BootReceiver` 开机后。
         *
         * ## 为什么对账本体不再在 Receiver 里跑（N3）
         *
         * `BroadcastReceiver` 的 `goAsync` 窗口就是广播超时（前台 10 秒），
         * 而 [AlarmReconciler.rescheduleAll] 的耗时随 药品数 × 时点数 × 14 天视野
         * **线性放大**（模拟器实测 4 药 64 槽位约 0.4–1.5s），内联迟早撞线。
         * Receiver 只保留"查槽位 + 弹通知"这种毫秒级工作，对账交给本 Worker：
         * 进程存活由系统托管，失败走 `Result.retry()` + 退避，比 `runCatching` 吞掉可靠。
         *
         * ## 为什么用 `REPLACE` 而不是 `KEEP`
         *
         * 提前 + 准点、或多个药品的闹钟可能同分钟连发。`REPLACE` 保证**最后一次**
         * 触发之后总有一轮新鲜的对账在跑（运行中的旧实例被取消 —— 对账幂等，
         * 中断无副作用，Room 事务原子）；`KEEP` 则可能让后发的触发被已经跑过半的
         * 旧实例"代表"，最新入库的槽位要等 15 分钟后的周期任务才能排上闹钟。
         */
        fun enqueueOneShot(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                buildOneShotRequest()
            )
            AppLog.i(TAG, "oneshot reconcile enqueued")
        }

        /**
         * 入队周期对账。**幂等** —— 重复调用只会保留一个已排的周期任务。
         *
         * 必须在**进程启动后**至少调用一次。两个调用点：
         * 1. `CarroMedApp.onCreate` —— 任何组件拉起进程时都会走到（含 WorkManager 自己）
         * 2. `BootReceiver` —— 开机后系统不会自动恢复 WorkManager 的周期任务
         * （`MainActivity` 不入队，只在 RESUMED 时于 IO 线程直接跑一轮对账）
         *
         * @param replace 用 `REPLACE` 强制重排。WorkManager 的周期任务本身会跨重启存活，
         *   但**系统升级 App** 后既有任务会被丢弃，此时必须重排。
         *
         *   ⚠️ **不要在 `Application.onCreate` 里传 `true`**（N4）：WorkManager 冷启动
         *   进程执行本任务时，`onCreate` 先跑，`REPLACE` 会把"正要执行的那次任务"
         *   取消掉并重置 15 分钟计时 —— 第三层兜底在最需要它的场景里自己饿死自己。
         *   `CarroMedApp` 走 `KEEP`；只有 `BootReceiver` 有理由用 `REPLACE`。
         */
        fun enqueue(context: Context, replace: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
                buildRequest()
            )
            AppLog.i(TAG, "periodic reconcile enqueued (replace=$replace)")
        }
    }
}
