package com.mcxiaoke.carromed

import android.app.Application
import com.mcxiaoke.carromed.BuildConfig
import com.mcxiaoke.carromed.core.alarm.AppLogging
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.core.alarm.ReconcileWorker
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.CurrentDateHolder

/**
 * 应用入口。
 *
 * ## 存在的唯一理由：给周期对账一个必然的触发点
 *
 * `ReconcileWorker` 的排期需要**进程启动后**被重新确认一次。`MainActivity` 和
 * `BootReceiver` 都能做到，但它们各有盲区：
 *
 * - 只靠 `MainActivity`：用户不开 App、进程也没被别的组件拉起时，Worker 排期无人确认；
 * - 只靠 `BootReceiver`：正常冷启动（进程被系统回收后重新拉起）不经过它。
 *
 * `Application.onCreate` 覆盖两者 —— **任何**组件（Activity / Receiver / Worker / Service）
 * 拉起进程都会先执行它。这也让"周期对账是否已排上"这件事只有一个真相来源。
 *
 * ## 这里刻意不做的事
 *
 * **不在 `onCreate` 里跑 `AlarmReconciler.rescheduleAll`。**
 * 那会拖慢每次冷启动，且 `MainActivity` 已经会跑一次。Worker 的职责是**兜底**，
 * 不是首发路径；冷启动有 UI 反馈，用户等得起，广播进程等不起。
 */
class CarroMedApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 日志装配必须排第一：后续任何步骤的日志（含本方法内的失败）都要能被捕获
        AppLogging.install(this)
        // 进程生命周期时间线的锚点：crash 文件里至少有这一行，
        // 才能判断"崩溃前进程活到了哪一步"（被系统拉起 vs 用户点开，一目了然）
        AppLog.i("CarroMedApp", "process created, version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")

        // 通知渠道在 Application 里建，而不是等到第一次弹通知才建：
        // 渠道一经创建其 importance 就不可修改，而系统只在**首次弹通知时**才懒初始化。
        // 若首次弹通知时渠道还不存在，HIGH 这一档就永远生效了。
        Notifications.ensureChannel(this)

        // "今天是几号"的可刷新事实（M3-2）。进程级单例，任何页面都不再自己
        // `LocalDate.now()` 存字段 —— 那样进程跨夜存活时"今日"会永久停在昨天。
        CurrentDateHolder.install(this)

        // 兜底重排：系统升级 App / 用户清数据后，WorkManager 里的既有周期任务会被丢弃，
        // 这里确保它重新排上。
        //
        // ⚠️ 用 `KEEP` 而**不是** `REPLACE`（N4）。
        // `REPLACE` 的语义是"取消并删除同名既有任务再入队"，而 WorkManager 冷启动进程
        // 去执行本任务时，进程创建顺序固定为 `Application.onCreate` → 组件，
        // 于是 `onCreate` 会把"正要执行的那次任务"取消掉，顺带把 15 分钟周期计时重置。
        // 讽刺的是这个 Worker 的定位是「前两层都失效时的最终兜底」，
        // 它最需要生效的场景恰是"进程已死"——而那正是冷启动。
        //
        // `KEEP` 同样能自愈那两种情况：清数据会把 WorkManager 自己的库一起清掉，
        // 此时根本没有既有任务，`KEEP` 等价于首次入队；升级/换包由
        // `BootReceiver`（收 `MY_PACKAGE_REPLACED`）用 `REPLACE` 显式重排。
        runCatching { ReconcileWorker.enqueue(this, replace = false) }
            .onFailure { AppLog.e("CarroMedApp", "enqueue periodic reconcile failed", it) }
    }
}
