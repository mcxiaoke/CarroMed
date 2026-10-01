package com.mcxiaoke.carromed.core.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mcxiaoke.carromed.MainActivity
import com.mcxiaoke.carromed.R
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.MedicationOverview
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.ui.component.Quantity
import com.mcxiaoke.carromed.core.domain.model.Dose
import com.mcxiaoke.carromed.core.data.entity.MedicationEntity

/**
 * 服药提醒通知构建器
 * 高优先级渠道 + Heads-up 浮动横幅 + 通知栏快捷操作 (已吃/推迟/跳过)
 */
object Notifications {

    /**
     * V2 服药提醒渠道（P1-4）：
     * 绑定 AudioAttributes.USAGE_ALARM，铃声走系统闹钟音频流，不受普通通知静音影响。
     * Android 渠道属性一经创建即不可变，因此存量迁移必须通过更换 Channel ID 完成。
     */
    const val CHANNEL_DOSE_REMINDER_V2 = "dose_reminder_v2"

    /**
     * 重要药品专用强提醒渠道（P1-4）：
     * 强力震动与闹钟音频流，确保关键处方药强效提醒。
     */
    const val CHANNEL_DOSE_REMINDER_CRITICAL = "dose_reminder_critical"

    @Deprecated("Use CHANNEL_DOSE_REMINDER_V2 instead", ReplaceWith("CHANNEL_DOSE_REMINDER_V2"))
    const val CHANNEL_DOSE_REMINDER = "dose_reminder"

    /**
     * 夜间静音渠道。
     * Android 的渠道重要性 (IMPORTANCE) 一经创建不可修改，因此要静音必须走**独立渠道**，
     * 再在推送时按「是否处于夜间 + 是否重要提醒」选择用哪个渠道。
     * 此前本项目只有一个 HIGH 渠道，导致设置页的「夜间免打扰」开关彻底无效。
     */
    const val CHANNEL_DOSE_REMINDER_SILENT = "dose_reminder_silent"

    // 通知栏快捷操作指令
    const val EXTRA_MINUTES = "snooze_minutes"
    const val ACTION_TAKE = "com.mcxiaoke.carromed.action.DOSE_TAKE"
    const val ACTION_SNOOZE = "com.mcxiaoke.carromed.action.DOSE_SNOOZE"
    const val ACTION_SKIP = "com.mcxiaoke.carromed.action.DOSE_SKIP"

    // 通知栏 Action 按钮 requestCode：同一时刻最多 3 种 Action，
    // 身份由 action + data（业务键）区分，requestCode 恒为小常量即可。
    private const val RC_TAKE = 0
    private const val RC_SNOOZE = 1
    private const val RC_SKIP = 2

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // 存量升级与清理（P1-4）：老渠道 dose_reminder 使用普通通知音频流，在此静默删除
        runCatching { nm.deleteNotificationChannel(CHANNEL_DOSE_REMINDER) }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val loud = NotificationChannel(
            CHANNEL_DOSE_REMINDER_V2,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            setSound(alarmSoundUri, audioAttributes)
            setShowBadge(true)
        }

        val critical = NotificationChannel(
            CHANNEL_DOSE_REMINDER_CRITICAL,
            context.getString(R.string.notif_channel_name_critical),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_desc_critical)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
            setSound(alarmSoundUri, audioAttributes)
            setShowBadge(true)
        }

        val silent = NotificationChannel(
            CHANNEL_DOSE_REMINDER_SILENT,
            context.getString(R.string.notif_channel_name_silent),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc_silent)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
        }

        nm.createNotificationChannels(listOf(loud, critical, silent))
    }

    /**
     * 通知栏 Action 的 PendingIntent —— **内容寻址**（osbf P1-4）。
     *
     * ## 为什么必须和闹钟一样用业务键寻址
     *
     * 旧实现把 `slot.id` 塞进 extras，接收端按 slotId 反查。而 `dose_slots.id`
     * 是**会变**的：备份恢复会用备份里的 id 覆盖当前库，托盘上残留的旧通知
     * 带着"旧库的 id"，点「已吃」会反查到**别的药的新槽位**上扣库存 ——
     * `filterEquals` 不看 extras，extras 从来就不是身份。
     *
     * 现在与 `AlarmScheduler.alarmUri` 同一哲学：身份 = `medId + date + time`
     * （`carromed://action/{medId}/{date}/{time}/dose`），接收端
     * [DoseActionReceiver] 用 `findOpenSlotId` 按内容反查当前库里的开放槽位。
     * 恢复后 id 交叠也无所谓：内容键指向谁，动作就落在谁身上。
     *
     * requestCode 恒为小常量：不同槽位的 data 不同 ⇒ `filterEquals` 已能区分，
     * 无需（也不能）再靠 slotId 算术编码。
     */
    private fun actionPendingIntent(
        context: Context,
        slot: DoseSlotEntity,
        action: String,
        requestCode: Int,
        extras: Intent.() -> Unit = {}
    ): PendingIntent {
        val intent = Intent(context, DoseActionReceiver::class.java)
            .setAction(action)
            .setData(actionUri(slot))
            .apply(extras)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 通知 Action 的内容身份，与 `AlarmScheduler.alarmUri` 同形状、同判据。 */
    private fun actionUri(slot: DoseSlotEntity) = Uri.parse(
        "carromed://action/${slot.medicationId}/${slot.scheduledDate}/${slot.scheduledTime}/dose"
    )

    /**
     * 弹出某槽位的服药提醒通知 (Heads-up)
     *
     * @param behavior 用户在设置页 / 提醒设置页配置的提醒行为。
     *   推迟按钮的分钟数此前写死 30，现改为跟随配置；
     *   夜间静音通过切换到 LOW 重要性渠道实现。
     */
    fun showDoseNotification(
        context: Context,
        slot: DoseSlotEntity,
        /**
         * 传读模型而不是裸 `MedicationEntity`。
         *
         * 重要提醒标记已随 A2 迁到 `reminder_settings` 表，实体上取不到；
         * 更重要的是**签名本身就是护栏** —— 若这里收实体，调用方必须自己去别处
         * 拼 `isCriticalReminder`，拼错就是"重要药品夜里被静音"这种静默故障。
         */
        overview: MedicationOverview,
        behavior: ReminderSettings.Behavior = ReminderSettings.Behavior(),
        /**
         * 闹钟种类，决定标题与文案（P1-20）。
         *
         * 原先提前提醒和准点提醒的文案**完全一样**，用户看到"该吃药了"却还有 10 分钟，
         * 分不清是提醒早了还是自己在看手机。
         */
        kind: AlarmScheduler.Kind = AlarmScheduler.Kind.MAIN
    ): Boolean {
        val med = overview.medication
        ensureChannel(context)

        // 输出端可达性检查（orsbf P0-5）：通知权限被拒 / 渠道被关时 `notify()` 是
        // **静默空操作** —— Android 13+ 不抛异常、不上屏。旧实现照样走完构建流程，
        // 调用方把"调用了 notify()"当成"用户收到了"，而补响判据（托盘里有没有）
        // 恒为 false，于是每条漏掉的服药以 30 秒为周期反复唤醒设备直到补响窗口结束。
        // 把"没送出去"如实返回给调用方，链路才有正确的失败信号。
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            AppLog.e("Notifications", "notify skipped for slot=${slot.id}: notifications disabled")
            return false
        }

        // doseAmount 是整数毫单位（D-7）。⚠️ 原先的 `doseAmount % 1f == 0f`
        // 判断能编译（Kotlin 允许 Int % Float）却恒为真，会把 1 片显示成「1000 片」。
        val doseText = Quantity.withUnit(Dose(slot.doseAmount).asFloat, med.unit)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val silent = ReminderSettings.shouldSilence(behavior, overview.isCriticalReminder, hour)
        val channel = when {
            silent -> CHANNEL_DOSE_REMINDER_SILENT
            overview.isCriticalReminder -> CHANNEL_DOSE_REMINDER_CRITICAL
            else -> CHANNEL_DOSE_REMINDER_V2
        }

        val advanceMinutes = overview.advanceMinutes
        val body = buildString {
            // ⚠️ `slot.scheduledTime` 是**原始计划时点**，不是推迟目标。
            // 推迟目标是 `snoozeUntilTs`。SNOOZE 闹钟在 `snoozeUntilTs` 那一刻响，
            // 此时"已推迟到 <scheduledTime>"是错的（那正是用户已经错过的时间）。
            when (kind) {
                AlarmScheduler.Kind.ADVANCE ->
                    append(
                        context.getString(
                            R.string.notif_body_advance,
                            advanceMinutes, slot.scheduledTime, doseText
                        )
                    )
                AlarmScheduler.Kind.SNOOZE ->
                    append(context.getString(R.string.notif_body_snooze, doseText))
                AlarmScheduler.Kind.MAIN ->
                    append(context.getString(R.string.notif_body_main, slot.scheduledTime, doseText))
            }
            if (med.noticeShort.isNotBlank()) append("\n${med.noticeShort}")
            if (silent) append(context.getString(R.string.notif_body_night_silent))
        }

        val notifId = notificationIdOf(slot.id)
        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze = behavior.snoozeMinutes.coerceIn(1, 240)

        // 锁屏全屏强提醒 (Full-Screen Intent, P1-3)
        val fullScreenIntent = com.mcxiaoke.carromed.ui.screen.alert.AlarmAlertActivity.createIntent(
            context = context,
            slotId = slot.id,
            medId = med.id,
            scheduledDate = slot.scheduledDate,
            scheduledTime = slot.scheduledTime,
            medName = med.name,
            doseText = doseText,
            notice = med.noticeShort,
            isCritical = overview.isCriticalReminder,
            snoozeMinutes = snooze
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canUseFullScreen = if (Build.VERSION.SDK_INT >= 34) {
            val systemNm = context.getSystemService(NotificationManager::class.java)
            systemNm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
        val isEligibleForFullScreen = !silent && (overview.isCriticalReminder || kind == AlarmScheduler.Kind.MAIN)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                when {
                    // 重要提醒优先：它决定的是"响不响"，不是"什么时候提醒"
                    overview.isCriticalReminder -> context.getString(R.string.notif_title_critical, med.name)
                    kind == AlarmScheduler.Kind.ADVANCE -> context.getString(R.string.notif_title_advance, med.name)
                    kind == AlarmScheduler.Kind.SNOOZE -> context.getString(R.string.notif_title_snooze, med.name)
                    else -> context.getString(R.string.notif_title_main, med.name)
                }
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(false)
            .addAction(
                0, context.getString(R.string.notif_action_take),
                actionPendingIntent(context, slot, ACTION_TAKE, RC_TAKE)
            )
            .addAction(
                0, context.getString(R.string.notif_action_snooze, snooze),
                actionPendingIntent(context, slot, ACTION_SNOOZE, RC_SNOOZE) {
                    putExtra(EXTRA_MINUTES, snooze)
                }
            )
            .addAction(
                0, context.getString(R.string.notif_action_skip),
                actionPendingIntent(context, slot, ACTION_SKIP, RC_SKIP)
            )

        if (isEligibleForFullScreen && canUseFullScreen) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }
        val notification = builder.build()

        // 渠道级关闭（用户在系统设置里单独关掉这个渠道）同样不可达：
        // IMPORTANCE_NONE 的渠道 notify() 也是静默空操作。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            AppLog.e("Notifications", "notify skipped for slot=${slot.id}: channel $channel disabled")
            return false
        }

        return runCatching {
            nm.notify(notifId, notification)
            true
        }.onFailure { AppLog.e("Notifications", "notify failed for slot=${slot.id}", it) }
            .getOrDefault(false)
    }

    /**
     * 将 slotId 映射为安全的 32 位正整数通知 ID (P1-6)。
     * 避开保留 ID (ID_OVERDUE_SUMMARY = 99999)，防止负数溢出。
     */
    fun notificationIdOf(slotId: Long): Int {
        val positive = slotId and 0x7FFFFFFF
        return ((positive % 89000) + 1000).toInt()
    }

    fun cancelDoseNotification(context: Context, slotId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationIdOf(slotId))
    }

    const val ID_OVERDUE_SUMMARY = 99999

    /**
     * 发布低优先级待服聚合提醒通知。
     * 当开机或对账发现当天存在早于补响窗口（>2h）且托盘无独立通知的未服待办时提醒用户，
     * 避免因长时间关机或通知被清空而导致漏药无人知晓。
     */
    fun showOverdueSummaryNotification(context: Context, count: Int): Boolean {
        if (count <= 0) {
            cancelOverdueSummaryNotification(context)
            return false
        }
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false

        val contentIntent = PendingIntent.getActivity(
            context,
            ID_OVERDUE_SUMMARY,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notif_title_overdue_summary)
        val body = context.getString(R.string.notif_body_overdue_summary, count)

        val notification = NotificationCompat.Builder(context, CHANNEL_DOSE_REMINDER_SILENT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        return runCatching {
            nm.notify(ID_OVERDUE_SUMMARY, notification)
            true
        }.onFailure { AppLog.e("Notifications", "notify overdue summary failed", it) }
            .getOrDefault(false)
    }

    fun cancelOverdueSummaryNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_OVERDUE_SUMMARY)
    }

    /**
     * 通知出口当前是否可达：应用级通知权限已授予，且提醒主渠道未被用户全部关闭。
     *
     * 供补响判据**前置**（orsbf P0-5）：出口不可达时 `notify()` 是静默空操作，
     * "托盘里有没有"恒为 false，补响会以 30 秒为周期空转唤醒直到补响窗口结束。
     * 出口不可达时直接跳过补响排程 —— 修复出口（开权限）之前，反复唤醒只是耗电。
     */
    fun areNotificationsReachable(context: Context): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val none = NotificationManager.IMPORTANCE_NONE
            val v2Off = nm.getNotificationChannel(CHANNEL_DOSE_REMINDER_V2)?.importance == none
            val criticalOff = nm.getNotificationChannel(CHANNEL_DOSE_REMINDER_CRITICAL)?.importance == none
            if (v2Off && criticalOff) return false
        }
        return true
    }

    /**
     * 该槽位的提醒通知当前是否还挂在托盘上。
     *
     * ## 为什么补响判据要用它（PLAN-EXPIRE-WINDOW-20260929 §3.3）
     *
     * 托盘通知是「已经发生过的陈述」（撤它的理由见 [AlarmReconciler.cancelNotificationOf]）：
     * 通知还在 = 用户已经被提醒过，对账就不该再补响 —— 否则 AlarmReceiver 响铃后
     * 就地重跑对账，刚响过的槽位 30 秒后再次满足补响条件，每条未确认的服药
     * 都会以 30 秒为周期反复响到补响窗口结束。
     * 通知不在（关机 / 重启 / 被用户清掉）才补响；用户主动滑掉后下一轮对账
     * 会再补一次 —— 漏服提醒需要这份执着，且仍受补响窗口封顶。
     *
     * 查询失败按「不在」处理：漏提醒比多提醒严重（提醒不漏是第一承诺），
     * 不能因为查询失败就把补响整个吞掉。
     */
    fun isDoseNotificationShown(context: Context, slotId: Long): Boolean =
        runCatching {
            val targetId = notificationIdOf(slotId)
            NotificationManagerCompat.from(context).activeNotifications.any { it.id == targetId }
        }.getOrDefault(false)
}
