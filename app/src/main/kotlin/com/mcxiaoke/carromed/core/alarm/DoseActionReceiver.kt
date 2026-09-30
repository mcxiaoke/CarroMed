package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.service.DoseEntryActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知栏快捷操作接收器 (极速直写，无需打开 App)
 * [✅ 确认已吃] → 打卡 + 扣库存 + 销毁通知 + 取消闹钟
 * [⏰ 推迟30分钟] → 槽位置 SNOOZED + 重排临时闹钟 + 销毁通知
 * [⏭️ 跳过本次] → 写入跳过事实 + 销毁通知 + 取消闹钟
 *
 * 具体编排（事务 → 闹钟 → 通知）统一在 [DoseEntryActions]，
 * 本类只负责"解析意图 + 幂等守卫 + 一句提示"。此前这些副作用在本类与
 * `TodayViewModel` 各写一份，记录详情页会是第三份 —— 见该类的 KDoc。
 *
 * 打卡与撤销均以 dose_slots 主键状态为幂等锚点，双击/连击不会重复扣减。
 */
class DoseActionReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "DoseActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Notifications.ACTION_TAKE &&
            action != Notifications.ACTION_SNOOZE &&
            action != Notifications.ACTION_SKIP
        ) return

        val slotId = intent.getLongExtra(Notifications.EXTRA_SLOT_ID, -1L)
        if (slotId <= 0) return

        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val actions = DoseEntryActions(appContext, db)
                val slot = db.doseSlotDao().getSlotById(slotId)
                // 幂等守卫：仅待服/推迟中的槽位允许快捷操作，防止双击连击重复扣减。
                //
                // 比 `markCompletedIfOpen` 更严：后者允许对 EXPIRED 补记，
                // 而通知栏按钮的语义是"对刚响过的那条提醒表态" ——
                // 一条早已结算的提醒不该再被这里的按钮改判。
                val isStillOpen = slot != null &&
                    (slot.status == SlotStatus.PENDING || slot.status == SlotStatus.SNOOZED)

                when (action) {
                    Notifications.ACTION_TAKE -> {
                        val ok = isStillOpen && actions.confirm(
                            slotId = slotId,
                            note = "通知栏快捷打卡"
                        )
                        // 成功动作必须留痕（G5）：这是并发风险最高的写入口——
                        // 通知栏直接写库，进程可能刚被闹钟拉起，事后工单只有这里有现场
                        AppLog.i(TAG, "action=take slot=$slotId applied=$ok")
                        notifyUser(appContext, if (ok) "已记录服药，库存已同步 💊" else "该提醒已处理过")
                    }

                    Notifications.ACTION_SNOOZE -> {
                        val minutes = intent.getIntExtra(Notifications.EXTRA_MINUTES, 30)
                        val ok = isStillOpen && actions.snooze(slotId, minutes)
                        AppLog.i(TAG, "action=snooze slot=$slotId minutes=$minutes applied=$ok")
                        if (ok) notifyUser(appContext, "已推迟 $minutes 分钟，到时再提醒")
                    }

                    Notifications.ACTION_SKIP -> {
                        val ok = isStillOpen && actions.skip(slotId, reason = "通知栏快捷跳过")
                        AppLog.i(TAG, "action=skip slot=$slotId applied=$ok")
                        notifyUser(appContext, if (ok) "已跳过本次，不扣减库存" else "该提醒已处理过")
                    }
                }
            } catch (t: Throwable) {
                // 异常围栏（P2#2）：协程体内任何异常都不允许逃逸——
                // 逃逸即走默认未捕获处理器，**整个进程被点通知栏按钮这一下打崩**，
                // 且 goAsync 的保护形同虚设。与 AlarmReceiver / BootReceiver 同一口径。
                AppLog.e(TAG, "dose action failed, action=$action slotId=$slotId", t)
            } finally {
                result.finish()
            }
        }
    }

    private fun notifyUser(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
