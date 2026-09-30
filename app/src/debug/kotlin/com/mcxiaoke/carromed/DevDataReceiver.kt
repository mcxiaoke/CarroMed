package com.mcxiaoke.carromed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.DevSampleDataSeeder
import com.mcxiaoke.carromed.core.domain.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 调试数据遥控器 —— **仅存在于 debug 源集，release 包内完全没有这段代码与这条广播**
 *
 * 冷启动路径刻意不播种，首启必须是干净空库。开发 / 演示 / UI 走查时若需要一批
 * 真实排班数据（例如让「库存管理」「进展追踪」有内容可看），从 adb 触发：
 *
 * ```powershell
 * # 灌入演示数据（库非空则不动用户数据）
 * adb -s emulator-5554 shell am broadcast -a com.mcxiaoke.carromed.dev.SEED
 *
 * # 清空全部业务数据（回到首启空态）
 * adb -s emulator-5554 shell am broadcast -a com.mcxiaoke.carromed.dev.CLEAR
 * ```
 *
 * 配套脚本：`tools/app_screenshots.py`（`--seed` / `--clear` 参数即调用这两条广播）。
 *
 * ⚠️ 因为 receiver 是 exported，任何装了 debug 包的设备都能触发播种。
 * 这是 debug 构建的刻意取舍（换来可脚本化的走查流程），release 不注册该 receiver。
 */
class DevDataReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 崩溃演练入口（PLAN-LOGGING-20260929.md S1 验收项）：
        // 必须在 goAsync/协程/try 之外同步抛出——协程里的 catch(Throwable) 会把它吞掉，
        // 就走不到 UncaughtExceptionHandler，演练不了真实崩溃路径。
        if (intent.action == ACTION_CRASH) {
            throw IllegalStateException(
                "Debug-only crash trigger (dev.CRASH broadcast) — crash log should appear in filesDir/logs/"
            )
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                when (intent.action) {
                    ACTION_SEED -> {
                        DevSampleDataSeeder.seedIfNeeded(db)
                        AppLog.i(TAG, "Dev data seeded: ${db.medicationDao().getAllMedications().size} medication(s)")
                    }

                    ACTION_CLEAR -> {
                        db.clearAllTables()
                        AppLog.i(TAG, "Dev data cleared")
                    }

                    else -> AppLog.w(TAG, "Unknown action: ${intent.action}")
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Dev data action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CarroMedDevData"
        const val ACTION_SEED = "com.mcxiaoke.carromed.dev.SEED"
        const val ACTION_CLEAR = "com.mcxiaoke.carromed.dev.CLEAR"
        const val ACTION_CRASH = "com.mcxiaoke.carromed.dev.CRASH"
    }
}
