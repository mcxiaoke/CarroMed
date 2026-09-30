package com.mcxiaoke.carromed.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotActionPolicy
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.service.DoseActionResult
import com.mcxiaoke.carromed.core.domain.service.DoseEntryActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

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

        // ⭐ 按**业务键**（medId + date + time）反查开放槽位，而不是 extras 里的
        // slotId（osbf P1-4，与 AlarmReceiver 同一哲学）。
        //
        // `dose_slots.id` 是会变的：备份恢复会用备份里的 id 覆盖当前库，托盘上
        // 残留的旧通知带着旧库的 id —— 按 slotId 反查，"恢复前的通知点已吃"
        // 会把**别的药的新槽位**扣掉库存。通知按钮的身份是 `carromed://action/
        // {medId}/{date}/{time}/dose`，内容键指向谁，动作就落在谁身上。
        val key = AlarmReceiver.parseAlarmKey(intent.data)
        if (key == null) {
            AppLog.w(TAG, "unparseable action uri=${intent.data}")
            return
        }

        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val actions = DoseEntryActions(appContext, db)
                val slotId = db.doseSlotDao().findOpenSlotId(
                    medicationId = key.medicationId,
                    scheduledDate = key.date,
                    scheduledTime = key.time
                )
                val slot = slotId?.let { db.doseSlotDao().getSlotById(it) }
                // 幂等守卫：仅待服/推迟中、且**计划日不晚于今天**的槽位允许快捷操作
                // —— 防止双击连击重复扣减，也防止对未来的槽位表态。
                //
                // 比 `markCompletedIfOpen` 更严：后者允许对 EXPIRED 补记，
                // 而通知栏按钮的语义是"对刚响过的那条提醒表态" ——
                // 一条早已结算的提醒不该再被这里的按钮改判。
                //
                // 日期判据此前**只靠约定**（通知只为当天闹钟弹出）撑着；
                // 约定不是保证：任何把旧通知留在托盘上、或进程隔夜被拉起的场景，
                // 都会让这里的按钮对一条未来的槽位生效。
                val isStillOpen = slot != null &&
                    (slot.status == SlotStatus.PENDING || slot.status == SlotStatus.SNOOZED) &&
                    SlotActionPolicy.isActionableOn(
                        slot.scheduledDate,
                        LocalDate.now().format(SlotProjectionEngine.DATE_FORMATTER)
                    )

                when (action) {
                    Notifications.ACTION_TAKE -> {
                        // ⚠️ 不要把这个局部量命名成 `result`：外层 `result` 是
                        // `goAsync()` 的句柄（`finally` 里要 finish 它）。
                        // 同名遮蔽之下，"在分支里 finish 一下"会拿到枚举 —— 编译期就报错，
                        // 但排查时会先怀疑协程，所以从一开始就别让两个东西同名。
                        val applied = if (isStillOpen) {
                            // `!!` 安全：isStillOpen 为真 ⇒ slot 已取到 ⇒ slotId 非空
                            actions.confirm(slotId = slotId!!, note = "通知栏快捷打卡")
                        } else {
                            DoseActionResult.ALREADY_HANDLED
                        }
                        // 成功动作必须留痕（G5）：这是并发风险最高的写入口——
                        // 通知栏直接写库，进程可能刚被闹钟拉起，事后工单只有这里有现场
                        AppLog.i(TAG, "action=take key=$key result=$applied")
                        notifyUser(appContext, applied.takeMessage())
                    }

                    Notifications.ACTION_SNOOZE -> {
                        val minutes = intent.getIntExtra(Notifications.EXTRA_MINUTES, 30)
                        val ok = isStillOpen && actions.snooze(slotId!!, minutes)
                        AppLog.i(TAG, "action=snooze key=$key minutes=$minutes applied=$ok")
                        if (ok) notifyUser(appContext, "已推迟 $minutes 分钟，到时再提醒")
                    }

                    Notifications.ACTION_SKIP -> {
                        val applied = if (isStillOpen) {
                            actions.skip(slotId!!, reason = "通知栏快捷跳过")
                        } else {
                            DoseActionResult.ALREADY_HANDLED
                        }
                        AppLog.i(TAG, "action=skip key=$key result=$applied")
                        notifyUser(appContext, applied.takeMessage(skip = true))
                    }
                }
            } catch (t: Throwable) {
                // 异常围栏（P2#2）：协程体内任何异常都不允许逃逸——
                // 逃逸即走默认未捕获处理器，**整个进程被点通知栏按钮这一下打崩**，
                // 且 goAsync 的保护形同虚设。与 AlarmReceiver / BootReceiver 同一口径。
                AppLog.e(TAG, "dose action failed, action=$action key=$key", t)
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

/**
 * 托盘提示**必须说真话**。
 *
 * 对一条未来的槽位说"该提醒已处理过"是撒谎（事实是从未有机会处理），
 * 而说"库存已同步"会让用户以为打卡生效了 —— 两者都会让用户以为
 * "明天的药已经安排好了"，实际上明天的闹钟还在、药还没吃。
 */
private fun DoseActionResult.takeMessage(skip: Boolean = false): String = when (this) {
    DoseActionResult.APPLIED ->
        if (skip) "已跳过本次，不扣减库存" else "已记录服药，库存已同步 💊"

    DoseActionResult.FUTURE_SLOT -> "未来的服药时间不能提前确认"
    DoseActionResult.ALREADY_HANDLED -> "该提醒已处理过"
}
