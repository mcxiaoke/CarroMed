package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 周期对账 Worker 的**排期参数**测试（A6，兑现 `FINAL-PRODUCT` D-14 第三档）。
 *
 * ## 这类测试存在的意义：Worker 的失败模式全是**静默**的
 *
 * 一个 `PeriodicWorkRequest` 排错了，App 不会崩、不会报错、日志里也看不到异常，
 * 只是"提醒慢慢不再排了"。而提醒不排是本项目**最严重的用户可见故障**（P0-2）。
 * 所以下面每一条都在盯一个"错了但不报错"的参数。
 *
 * ## 为什么这里**不**测 `doWork()`
 *
 * 试过了，会假红：`doWork()` 内部走 `AppDatabase.getInstance(context)`，
 * 而那是 `companion object` 里的静态单例。Robolectric 每个测试方法都重建
 * `Application` 与文件系统，但 **Kotlin 单例不会重置**，
 * 于是后一个测试拿到的 DB 指向已被拆掉的沙箱文件而抛异常，
 * `doWork` 的 `catch (Throwable) → Result.retry()` 把这个异常吞成了一次"退避重试"。
 *
 * 也就是说：**写出来的失败信息（Retry）和真实原因完全无关**，这比不测更糟。
 * `doWork` 真正依赖的不变量是"`rescheduleAll` 幂等"，
 * 那个由 [AlarmReconcilerIdempotencyTest] 用显式传入的内存库来守，不走单例。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ReconcileWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        // 用同步执行器覆盖初始化，这样 enqueue 之后可以立刻断言排期结果而不必等异步生效。
        // 副作用：任务会在 enqueue 时**就地执行**，所以状态断言不能写死 ENQUEUED。
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun periodicInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ReconcileWorker.UNIQUE_NAME)
            .get()

    // ==================== 排期 ====================

    @Test
    fun `enqueue 后确实存在周期对账任务`() {
        ReconcileWorker.enqueue(context)
        assertThat(periodicInfos()).hasSize(1)
    }

    @Test
    fun `重复 enqueue 不会堆出多个任务`() {
        // 三个调用点（Application / BootReceiver / MainActivity）都会调，必须幂等
        repeat(5) { ReconcileWorker.enqueue(context) }
        assertThat(periodicInfos()).hasSize(1)
    }

    @Test
    fun `replace=true 能重排已有任务`() {
        ReconcileWorker.enqueue(context)
        ReconcileWorker.enqueue(context, replace = true)
        assertThat(periodicInfos()).hasSize(1)
    }

    // ==================== 间隔下限 ====================

    @Test
    fun `周期间隔不小于 WorkManager 的 15 分钟下限`() {
        val spec = ReconcileWorker.buildRequest().workSpec
        // ⚠️ 2.9.1 的 WorkSpec 上是毫秒 long；`WorkRequest.intervalDuration: Duration`
        // 那个扩展要到 2.10 才有。别照着新版文档写。
        assertThat(spec.intervalDuration).isAtLeast(15L * 60 * 1000)
        // flex 必须 ≤ interval，否则 WorkManager 没有调度自由度
        assertThat(spec.flexDuration).isAtMost(spec.intervalDuration)
    }

    @Test
    fun `REPEAT_INTERVAL_MINUTES 正好是官方下限`() {
        // 再短会被 WorkManager 直接抛 IllegalArgumentException；这个常量就是防线
        assertThat(ReconcileWorker.REPEAT_INTERVAL_MINUTES).isEqualTo(15L)
    }

    // ==================== 约束：App 物理断网，绝不能有网络约束 ====================

    @Test
    fun `任务约束里没有任何要求`() {
        // App 的 AndroidManifest 里严禁 INTERNET 权限（物理断网），
        // 一旦有人误加 NetworkType.CONNECTED，Worker 会**永远不执行**且没有任何报错。
        val constraints = ReconcileWorker.buildRequest().workSpec.constraints
        assertThat(constraints.requiredNetworkType)
            .isEqualTo(androidx.work.NetworkType.NOT_REQUIRED)
        assertThat(constraints.requiresCharging()).isFalse()
        assertThat(constraints.requiresDeviceIdle()).isFalse()
        assertThat(constraints.requiresBatteryNotLow()).isFalse()
        assertThat(constraints.requiresStorageNotLow()).isFalse()
    }

    // ==================== 退避 ====================

    @Test
    fun `失败后退避为指数且不低于 10 分钟`() {
        val spec = ReconcileWorker.buildRequest().workSpec
        assertThat(spec.backoffPolicy)
            .isEqualTo(androidx.work.BackoffPolicy.EXPONENTIAL)
        // 10 分钟：再短就追不上"下一轮排闹钟"的节奏，再长则一次失败要等太久
        assertThat(spec.backoffDelayDuration).isAtLeast(10L * 60 * 1000)
        // WorkManager 自己会在 5 小时处截断；别设到超过那里，否则配置被静默改写
        assertThat(spec.backoffDelayDuration)
            .isLessThan(androidx.work.WorkRequest.MAX_BACKOFF_MILLIS)
    }

    // ==================== 一次性对账（N3）====================

    private fun oneshotInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ReconcileWorker.ONESHOT_NAME)
            .get()

    @Test
    fun `enqueueOneShot 后存在一次性对账任务`() {
        ReconcileWorker.enqueueOneShot(context)
        assertThat(oneshotInfos()).hasSize(1)
    }

    @Test
    fun `连续触发一次性对账不会堆出多个实例`() {
        // 提前 + 准点、多药同分钟连发时，REPLACE 保证"最后一次触发之后
        // 总有一轮新鲜对账在跑"，而不是堆一串并发全量对账互相争锁
        repeat(3) { ReconcileWorker.enqueueOneShot(context) }
        assertThat(oneshotInfos()).hasSize(1)
    }

    @Test
    fun `一次性任务不与周期任务同名且无任何约束`() {
        val spec = ReconcileWorker.buildOneShotRequest().workSpec
        // unique name 不同：BootReceiver 的 REPLACE 只能重排周期任务，碰不到一次性对账
        assertThat(ReconcileWorker.ONESHOT_NAME).isNotEqualTo(ReconcileWorker.UNIQUE_NAME)
        assertThat(spec.intervalDuration).isEqualTo(0L)   // 必须是一次性，不是变相周期
        // 物理断网：一次性对账也要在断网下照跑（同周期任务的约束纪律）
        assertThat(spec.constraints.requiredNetworkType)
            .isEqualTo(androidx.work.NetworkType.NOT_REQUIRED)
        // 重试要追上"响铃后尽快补齐"的节奏，用下限 10s，别继承周期任务的 10 分钟
        assertThat(spec.backoffDelayDuration)
            .isEqualTo(androidx.work.WorkRequest.MIN_BACKOFF_MILLIS)
    }

    @Test
    fun `周期任务与一次性任务互不干扰`() {
        ReconcileWorker.enqueue(context)
        ReconcileWorker.enqueueOneShot(context)
        assertThat(periodicInfos()).hasSize(1)
        assertThat(oneshotInfos()).hasSize(1)
    }
}
