package com.mcxiaoke.carromed.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.domain.AppLog

/**
 * 精确闹钟调度器（三档降级链路）
 * 1. 已授予精确闹钟权限 → setExactAndAllowWhileIdle
 * 2. 未授权 → setAlarmClock（系统闹钟通道，不受 Doze 限制）
 * 3. 都不行 → setAndAllowWhileIdle（**系统可对齐到窗口边界，可能晚约 1 小时**）
 *
 * ## 第 3 档不是"误差通常 < 15 分钟"（P0-1，旧注释的说法是错的）
 *
 * `setAndAllowWhileIdle` 在 Android 上会带**最小 1 小时的窗口**（`window=3600000`）。
 * 旧实现在 `canScheduleExactAlarms() == false` 时**静默**落到这一档，
 * 而 Manifest 声明的是可被撤销的 `SCHEDULE_EXACT_ALARM`、App 又从不引导用户授权，
 * 于是这个分支是**常态**而不是兜底。模拟器实测 67 个闹钟全部带 +1h 窗口。
 *
 * 两处修复：
 * 1. Manifest 改声明 `USE_EXACT_ALARM`（闹钟类应用免授权），让第 1 档成为常态；
 * 2. 当前档位由 [currentPrecision] **可查询**，系统特权自检页如实显示，
 *    降级对用户可见（见 `docs/CODE-REVIEW-20260929-ds.md` P0-1）。
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

    /**
     * 闹钟的 [PendingIntent]。**生产与测试共用这一份构造**。
     *
     * @param forCancel 取消路径用 `FLAG_NO_CREATE`。
     *
     *   `FLAG_UPDATE_CURRENT` 在**没有**匹配项时也会**创建一个** PendingIntent ——
     *   于是"取消一个从未排过的闹钟"会凭空留下一条系统记录（不影响投递，
     *   但会让 `dumpsys` 与后续任何"按 PendingIntent 存在性"的判断失真）。
     *   取消时正确的 flag 是 `FLAG_NO_CREATE`：匹配不到就返回 null，不留痕。
     *   匹配到时 `FLAG_NO_CREATE` 不带 `UPDATE_CURRENT` 语义，但 `cancel()`
     *   本来就不需要它 —— 取消是丢弃而不是更新。
     */
    private fun pendingIntent(
        context: Context,
        medicationId: Long,
        date: String,
        time: String,
        slotId: Long,
        kind: Kind,
        forCancel: Boolean = false
    ): PendingIntent? = android.app.PendingIntent.getBroadcast(
        context,
        0,                                   // requestCode 恒为 0，身份完全由 Intent 内容决定
        alarmIntent(context, medicationId, date, time, slotId, kind),
        if (forCancel) {
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        }
    )

    /**
     * 排一个闹钟。
     *
     * @param kind 决定闹钟身份，三种种类互不干扰。
     *
     * 成功路径必须落一条 INFO（G2）：uri + 触发时刻 + **实际生效的精度档位**。
     * 这是"档位降级可查可见"纪律在时间线上的落实——自检页只能查"当前"档位，
     * 而排查"闹钟没响"需要的是"当时每一次排程各落在哪一档"。
     * 降级（EXACT 被拒 → ALARM_CLOCK）与最终兜底（INEXACT，+1h 窗口）一律 WARN。
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
        ) ?: return
        val uri = alarmUri(slot.medicationId, slot.scheduledDate, slot.scheduledTime, kind)
        val triggerAt = "=$triggerAtMillis (${formatTs(triggerAtMillis)})"

        when (currentPrecision(alarmManager)) {
            Precision.EXACT -> {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                    AppLog.i(TAG, "scheduled uri=$uri triggerAt$triggerAt precision=EXACT")
                    return
                } catch (e: SecurityException) {
                    // 权限被运行时回收（用户刚在系统设置里关掉 / 某些 ROM 的额外限制），
                    // 落入下方兜底
                    AppLog.w(TAG, "exact alarm denied, falling back: ${e.message}")
                }
            }
            else -> Unit
        }
        try {
            // `AlarmClockInfo` 带 showIntent 时，系统状态栏会显示闹钟图标；
            // 传 null 会让这条路径在部分 ROM 上退化为普通闹钟，甚至被拒绝。
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context)),
                pi
            )
            AppLog.i(TAG, "scheduled uri=$uri triggerAt$triggerAt precision=ALARM_CLOCK")
            return
        } catch (e: SecurityException) {
            // 部分 ROM 上 setAlarmClock 同样要求精确闹钟权限
            AppLog.w(TAG, "setAlarmClock denied, falling back to inexact: ${e.message}")
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        AppLog.w(TAG, "scheduled uri=$uri triggerAt$triggerAt precision=INEXACT (may fire ~1h late)")
    }

    /** 状态栏闹钟图标的落点：点开直接进 App，不做任何业务动作 */
    private fun showIntent(context: Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.mcxiaoke.carromed.MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 当前**实际生效**的投递精度档位。
     *
     * 存在的理由：降级链路（[schedule] 里三级 try）此前是**静默**的 ——
     * 系统未授予精确闹钟时全部落到 `setAndAllowWhileIdle`，而实测该路径在
     * Android 上带 **+1 小时窗口**，用户却仍被告知"到点提醒"正常。
     * 这是"产品第一承诺在目标系统上不成立"而 App 自己毫无察觉的典型。
     *
     * 现在它是一个**可查询的事实**，供系统特权自检页如实显示。
     * 任何降级都必须对用户可见 —— 见 `docs/CODE-REVIEW-20260929-ds.md` P0-1。
     */
    enum class Precision(val label: String) {
        /** 精确闹钟：到点必响，Doze 下也不延迟 */
        EXACT("精确闹钟（到点必响）"),

        /** 闹钟应用通道：走系统闹钟通道，仍是准点的 */
        ALARM_CLOCK("闹钟应用通道（准点）"),

        /** 不精确：系统可对齐到窗口边界，**可能晚约 1 小时** */
        INEXACT("不精确提醒（可能延迟约 1 小时）");

        val isDegraded: Boolean get() = this != EXACT
    }

    /**
     * 查询当前档位。**只读探测，不排任何闹钟**。
     *
     * Android 12 以下恒为 [Precision.EXACT]（系统没有精确闹钟授权模型）。
     */
    fun currentPrecision(alarmManager: AlarmManager? = null): Precision {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Precision.EXACT
        val am = alarmManager ?: return Precision.INEXACT
        return if (am.canScheduleExactAlarms()) Precision.EXACT else Precision.ALARM_CLOCK
    }

    /** 便捷重载：给需要 `Context` 的调用方（自检页、Worker） */
    fun currentPrecision(context: Context): Precision =
        currentPrecision(context.getSystemService(AlarmManager::class.java))

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
            // 取消用 FLAG_NO_CREATE：匹配不到就返回 null，**不凭空造一条 PendingIntent**。
            // 旧实现用 FLAG_UPDATE_CURRENT，cancel 一个从未排过的闹钟会在系统里
            // 留下一条无主记录（不投递，但让"存在性"这件事不再可信）。
            pendingIntent(
                context, medicationId, date, time, slotId, kind, forCancel = true
            )?.let { alarmManager.cancel(it) }
        }
        AppLog.i(
            TAG,
            "cancelled alarms slot=$slotId med=$medicationId date=$date time=$time kinds=${kinds.joinToString(",") { it.code }}"
        )
    }

    /** 触发时刻的人类可读形式：排查"没响"时先对钟，毫秒数对不上日历没用 */
    private fun formatTs(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
