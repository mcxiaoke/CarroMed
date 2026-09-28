package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcxiaoke.carromed.core.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启 / 应用换包 / 系统改时 自愈接收器
 * 触发后全量对账排期闹钟，杜绝重启后提醒丢失。
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
 */
class BootReceiver : BroadcastReceiver() {

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
                // 先把周期对账的排期找回来，再做这一轮全量对账。
                // 顺序反了的话，本轮对账的结果可能被随后的 Worker 覆盖 —— 不会出错，
                // 但白白多跑一次；更重要的是 Worker 若此时没排上，本轮就成了孤军。
                runCatching { ReconcileWorker.enqueue(appContext, replace = true) }
                    .onFailure { Log.e("BootReceiver", "enqueue periodic reconcile failed", it) }

                AlarmReconciler.rescheduleAll(appContext, AppDatabase.getInstance(appContext))
            } catch (t: Throwable) {
                Log.e("BootReceiver", "boot reconcile failed", t)
            } finally {
                result.finish()
            }
        }
    }
}
