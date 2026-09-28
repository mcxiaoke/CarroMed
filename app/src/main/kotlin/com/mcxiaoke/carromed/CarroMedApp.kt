package com.mcxiaoke.carromed

import android.app.Application
import android.util.Log
import com.mcxiaoke.carromed.core.alarm.Notifications
import com.mcxiaoke.carromed.core.alarm.ReconcileWorker

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

        // 通知渠道在 Application 里建，而不是等到第一次弹通知才建：
        // 渠道一经创建其 importance 就不可修改，而系统只在**首次弹通知时**才懒初始化。
        // 若首次弹通知时渠道还不存在，HIGH 这一档就永远生效了。
        Notifications.ensureChannel(this)

        // 兜底重排：系统升级 App / 用户清数据后，WorkManager 里的既有周期任务会被丢弃。
        // 用 REPLACE 幂等重排，避免"任务已丢失却没人发现"。
        runCatching { ReconcileWorker.enqueue(this, replace = true) }
            .onFailure { Log.e("CarroMedApp", "enqueue periodic reconcile failed", it) }
    }
}
