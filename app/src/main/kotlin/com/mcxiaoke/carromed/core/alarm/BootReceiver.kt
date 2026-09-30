package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcxiaoke.carromed.core.domain.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启 / 应用换包 / 系统改时 自愈接收器
 * 触发后把周期对账重新排上，并让 Worker 立即补一轮全量对账，杜绝重启后提醒丢失。
 *
 * ## 为什么 `BootReceiver` 必须显式重排周期任务
 *
 * WorkManager 的 `PeriodicWorkRequest` **不会**随 `BOOT_COMPLETED` 自动恢复 ——
 * 它的内部闹钟由 WorkManager 自己用 `PendingIntent` + `BOOT_COMPLETED` 注册的
 * 接收器排期，但**升级 App / 用户清数据**后既有任务会被整体丢弃且不重建。
 * 此时若不显式 `REPLACE`，周期对账就永久消失了，而且**没有任何报错**。
 *
 * 所以这里用 `replace = true` 覆盖 `CarroMedApp.onCreate` 里的幂等重排。
 * 两者同时存在是有意的冗余：`onCreate` 覆盖"进程被系统拉起"，
 * `BootReceiver` 覆盖"开机后进程压根没被拉起"。
 *
 * ## 为什么对账本体不在这里跑（N3）
 *
 * 全量对账随数据量线性放大（模拟器实测 0.4–1.5s），开机场景下系统繁忙、
 * 数据库冷，是最慢的组合 —— 放在 goAsync 窗口（广播超时）里没有意义。
 * 现在这里只做两个毫秒级的 enqueue，对账由 `ReconcileWorker` 的一次性任务接手。
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return

        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 先把周期对账的排期找回来（`REPLACE` 有理由，见上），再把"立即对账"
                // 也交给 Worker（与 `AlarmReceiver` 同口径，N3）：对账本体**不在广播窗口里跑**，
                // goAsync 只需要罩住两个毫秒级的 enqueue。一次性任务在 REPLACE 之后入队，
                // 跑的时候自然覆盖"周期任务刚被重排"之后的状态。
                runCatching { ReconcileWorker.enqueue(appContext, replace = true) }
                    .onFailure { AppLog.e(TAG, "enqueue periodic reconcile failed", it) }

                runCatching { ReconcileWorker.enqueueOneShot(appContext) }
                    .onFailure { AppLog.e(TAG, "enqueue oneshot reconcile failed", it) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "boot reconcile failed", t)
            } finally {
                result.finish()
            }
        }
    }
}
