package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcxiaoke.carromed.core.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启 / 应用换包 / 系统改时 自愈接收器
 * 触发后 1 秒内全量对账排期闹钟，杜绝重启后提醒丢失。
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
                AlarmReconciler.rescheduleAll(appContext, AppDatabase.getInstance(appContext))
            } finally {
                result.finish()
            }
        }
    }
}
