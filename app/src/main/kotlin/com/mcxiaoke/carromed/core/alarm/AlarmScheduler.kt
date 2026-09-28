package com.mcxiaoke.carromed.core.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity

/**
 * 精确闹钟调度器（三档降级链路）
 * 1. Android 12+ 且已授予精确闹钟权限 → setExactAndAllowWhileIdle
 * 2. 未授权精确闹钟 → setAlarmClock（系统闹钟通道，不受 Doze 限制）
 * 3. 都不行 → setAndAllowWhileIdle（Doze 下仍允许唤醒，误差通常 < 15 分钟）
 *
 * ## 闹钟身份：内容寻址而非算术编码（P0-1）
 *
 * ### 旧实现错在哪
 *
 * ```kotlin
 * val requestCode = if (advance) (slotId * 10 + 1).toInt() else slotId.toInt()
 * ```
 *
 * `PendingIntent` 的相等判定是 **`requestCode` + `Intent.filterEquals`**，
 * 而 **`filterEquals` 不比较 extras**。旧实现两个分支的 component（`AlarmReceiver`）
 * 与 action（`DOSE_ALARM`）完全相同，于是：
 *
 * > `advance(slotId = 1)` 算出 `1*10+1 = 11`，
 * > `main(slotId = 11)` 算出 `11`。
 * > 两者 `requestCode` 相同、Intent 过滤条件相同 ⇒ **是同一个 PendingIntent**。
 *
 * 代码注释写的「slotId 是全局唯一自增主键，因此两个号段天然不相交」在数学上是错的
 * —— `10N+1` 与 `M` 在 `M ≡ 1 (mod 10)` 时必然相交，自增主键救不了它。
 *
 * 后果有两条，都很严重：
 * - **提前提醒被吞**：给槽位 1 排提前闹钟时，会把槽位 11 的主闹钟覆盖掉。
 * - **`cancel(槽位1)` 连带杀掉槽位 11 的主闹钟**（`cancel` 同时取消 main 与 advance），
 *   于是槽位 11 **静默地不再提醒** —— 用户以为有提醒，实际没有。
 *
 * ### 修法：`setData(Uri)` 让内容参与判重
 *
 * ```
 * carromed://alarm/{medId}/{date}/{time}/{kind}
 * ```
 *
 * `Uri` 参与 `filterEquals`，所以同一逻辑触发点**只能存在一个 PendingIntent**：
 * - 碰撞由定义不可能（不同 medId/date/time/kind 必然是不同 Uri）；
 * - 同一槽位重注册天然**替换**旧闹钟而不是堆积 —— 孤儿闹钟自限；
 * - 取消只影响自己那一个。
 *
 * 这也顺带解决了「重投影后 slot.id 变化导致闹钟身份漂移」的问题（A3-6 的 diff 重排
 * 保留了 id，但即使 id 变了，Uri 相同仍然是同一个 PendingIntent）。
 *
 * @see docs/REMINDER-DOMAIN-REDESIGN.md §6.2
 */
object AlarmScheduler {

    const val ACTION_DOSE_ALARM = "com.mcxiaoke.carromed.action.DOSE_ALARM"
    const val EXTRA_SLOT_ID = "slot_id"

    private const val TAG = "AlarmScheduler"
    private const val URI_SCHEME = "carromed"
    private const val URI_AUTHORITY = "alarm"

    /**
     * 闹钟身份的内容寻址键。
     *
     * 用"药品 + 计划日 + 计划时刻 + 种类"而不是 `slot.id`：前四者构成一个提醒的
     * **业务身份**，在重投影、换 id、换闹钟策略之后都不变。`slot.id` 恰恰是会变的那个。
     */
    enum class Kind(val code: String) {
        /** 准点提醒 */
        MAIN("main"),

        /** 提前提醒（药品配了 advance_minutes 时） */
        ADVANCE("advance"),

        /** 用户手动推迟后的再次提醒 */
        SNOOZE("snooze");

        companion object {
            fun fromCode(code: String?): Kind =
                entries.firstOrNull { it.code == code } ?: MAIN
        }
    }

    /**
     * 闹钟身份的内容寻址 Uri：`carromed://alarm/{medId}/{date}/{time}/{kind}`。
     *
     * **生产代码与测试共用这一份实现**。测试若自己复制一份 Uri 构造，
     * 守的就不是真正的身份规则 —— 实现改回算术编码时测试仍然全绿。
     */
    fun alarmUri(
        medicationId: Long,
        date: String,
        time: String,
        kind: Kind
    ): Uri = Uri.Builder()
        .scheme(URI_SCHEME)
        .authority(URI_AUTHORITY)
        .appendPath(medicationId.toString())
        .appendPath(date)
        .appendPath(time)
        .appendPath(kind.code)
        .build()

    /**
     * 构造闹钟 Intent。`requestCode` 恒为 0，**身份完全由 Intent 内容决定**。
     *
     * 同样对测试开放：`AlarmIdentityTest` 必须走这条路径才能真正守住 P0-1。
     *
     * ⚠️ 这里**不要**再加回"是提前提醒"之类的 extra：extras 不参与 `filterEquals`，
     * 加了既不能区分身份，又会让人误以为它参与了判重（这正是 P0-1 的成因）。
     * `kind` 已经在 Uri 里，Receiver 从 `intent.data` 读。
     */
    fun alarmIntent(
        context: Context,
        medicationId: Long,
        date: String,
        time: String,
        slotId: Long,
        kind: Kind
    ): Intent = Intent(context, AlarmReceiver::class.java)
        .setAction(ACTION_DOSE_ALARM)
        .setData(alarmUri(medicationId, date, time, kind))
        // extras 不参与判重，仅供 Receiver 快速取用（仍以 slotId 为准并做存在性校验）
        .putExtra(EXTRA_SLOT_ID, slotId)

    private fun pendingIntent(
        context: Context,
        medicationId: Long,
        date: String,
        time: String,
        slotId: Long,
        kind: Kind
    ): PendingIntentWrapper = PendingIntentWrapper(
        android.app.PendingIntent.getBroadcast(
            context,
            0,                                   // requestCode 恒为 0，身份完全由 Intent 内容决定
            alarmIntent(context, medicationId, date, time, slotId, kind),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
    )

    /** 薄包装，让下面的降级链读起来不必反复写全限定名 */
    private data class PendingIntentWrapper(val value: android.app.PendingIntent)

    /**
     * 排一个闹钟。
     *
     * @param kind 决定闹钟身份，三种种类互不干扰。
     */
    fun schedule(
        context: Context,
        slot: DoseSlotEntity,
        triggerAtMillis: Long,
        kind: Kind = Kind.MAIN
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(
            context, slot.medicationId, slot.scheduledDate, slot.scheduledTime, slot.id, kind
        ).value

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                return
            } catch (e: SecurityException) {
                // 权限被运行时回收（用户刚在系统设置里关掉），落入下方兜底
                Log.w(TAG, "exact alarm denied, falling back: ${e.message}")
            }
        }
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, null), pi)
            return
        } catch (e: SecurityException) {
            // 部分 ROM 上 setAlarmClock 同样要求精确闹钟权限
            Log.w(TAG, "setAlarmClock denied, falling back to inexact: ${e.message}")
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    /**
     * 取消该槽位的闹钟。
     *
     * @param kinds 实际要清的种类，**默认全清**。默认全清而不是让调用方选，
     * 是因为业务语义上"这个槽位不再需要提醒了"从来都是一次性的
     * （打卡 / 跳过 / 结算逾期 / 计划变更），没有"只清一半"这种需求。
     *
     * 需要保留部分种类的唯一场景是**推迟**：此时清 `MAIN` + `ADVANCE`、
     * 再排一个新的 `SNOOZE`，由调用方显式传 `kinds`。
     */
    fun cancelAll(
        context: Context,
        medicationId: Long,
        date: String,
        time: String,
        slotId: Long,
        kinds: List<Kind> = Kind.entries
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        kinds.forEach { kind ->
            alarmManager.cancel(
                pendingIntent(context, medicationId, date, time, slotId, kind).value
            )
        }
    }
}
