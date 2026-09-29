package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import android.util.Log
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import java.time.LocalDate

/**
 * 闹钟全量对账器 (Reconciler)
 *
 * 在 App 启动、开机、换包、系统改时、备份恢复、以及每个闹钟触发后调用，保证：
 * 1. 未来 [HORIZON_DAYS] 天槽位已按当前策略幂等补齐
 * 2. 所有活跃药品的待服/推迟槽位均注册了精确闹钟
 * 3. 已停药/暂停/过期槽位的闹钟全部取消，杜绝幽灵唤醒
 * 4. 超过计划时间 2 小时仍 PENDING 的槽位标记 EXPIRED（不冤枉判漏服）
 * 5. 仍在 2 小时宽限期内、但闹钟已丢失（关机/重启）的槽位**补响一次**
 *
 * ## 宽限期为什么必须补响（决策 C / M1-7）
 *
 * 第 1 步的结算 cutoff 是 `now - 2h`，第 4 步的注册条件是 `> now`。
 * 于是 `(now-2h, now]` 这个区间**两边都不管**——旧实现里它是一个黑洞：
 * 关机 30 分钟的闹钟在开机后既不结算、也不补排，用户什么都不知道。
 * 现在第 4 步显式覆盖这个区间（见 `GRACE_CATCHUP_DELAY_MS`）。
 *
 * 注意这**不**与第 1 步冲突：cutoff 用的是严格小于，所以宽限期内的槽位
 * 在同一次对账里既不会被结算，也会被补响。
 *
 * ## 窗口为什么是 14 天而不是 7 天（P0-2）
 *
 * 旧实现只排未来 7 天，且**没有自续期** —— 唯一的续期途径是"用户某天打开 App"。
 * 后果是：装好 App 后不碰它，第 8 天的提醒全部静默消失，用户毫无察觉。
 *
 * 这条缺陷上一轮代码审查没能发现，原因是结构性的：
 * `FINAL-PRODUCT:159` 的验收标准恰好是「连续 **7 天**零漏提醒」，
 * **验收时长等于机制边界**，于是问题在测试期内不可能暴露。
 * 验收标准已因此改为 21 天（见 `docs/CODE-REVIEW-20260927-sbf.md` P0-2 末段）。
 *
 * 现在有三层保险，任何一层单独失效都不会漏提醒：
 * 1. **14 天窗口**（本类的 `HORIZON_DAYS`）
 * 2. **触发后续期**：[AlarmReceiver] 每次响铃后就地续期，唤醒链因此自维持
 * 3. **周期对账兜底**：`ReconcileWorker`（A6）周期性地重跑一次本方法
 *
 * ## 对账的顺序：先快照、后重排、再按快照清理孤儿
 *
 * `reconcileSchedule` 会删掉不再被投影命中的槽位行，但领域层不知道 `Context`，
 * 删掉的行对应的闹钟就成���孤儿。旧实现完全没处理这件事（P1-5 闹钟泄漏的另一半）。
 * 现在：
 * 1. 先对所有开放槽位拍快照（含 medId / date / time，足以定位闹钟身份）
 * 2. 跑重排
 * 3. 快照里已不在库中的、或已不该排的 ⇒ **取消其全部种类闹钟**
 * 4. 库中仍开放且活跃的 ⇒ 注册闹钟（同一 Uri 重复注册是"替换"，不会堆积）
 */
object AlarmReconciler {

    private const val TAG = "AlarmReconciler"

    /** 槽位过期判定窗口：计划时间过后 2 小时 */
    private const val EXPIRE_WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * 宽限期内错过时的补响延迟。
     *
     * 为什么是"30 秒后"而不是"立刻"：对账是全量重排，一次可能同时补响很多条，
     * 全部同一瞬间弹出会在锁屏上糊成一片。错开一点让用户还能看清是哪味药。
     */
    private const val GRACE_CATCHUP_DELAY_MS = 30_000L

    /**
     * 闹钟视野天数。
     *
     * 7 → 14 天的意义不只是"多排一周"：它把"用户多久没打开 App"与"会漏几天提醒"解耦。
     * 14 天意味着**一个月不开 App 也只会漏 1~2 天**（配合触发后续期几乎为零）。
     */
    const val HORIZON_DAYS = 14L

    /** 闹钟身份快照：足以定位一个 PendingIntent，且不依赖会变的 slot.id */
    private data class AlarmIdentity(
        val slotId: Long,
        val medicationId: Long,
        val date: String,
        val time: String
    ) {
        fun cancelAll(context: Context) =
            AlarmScheduler.cancelAll(context, medicationId, date, time, slotId)
    }

    private fun DoseSlotEntity.identity() = AlarmIdentity(id, medicationId, scheduledDate, scheduledTime)

    /**
     * 落在逾期宽限期内、但触发时刻已经过去的开放槽位。
     *
     * 与 `getStaleOpenSlots` 的 cutoff 是**同一条线**：结算用严格小于，
     * 这里用大于等于，两边互补不重叠。
     */
    private fun DoseSlotEntity.isWithinGrace(now: Long, graceFloor: Long): Boolean {
        val base = if (status == SlotStatus.SNOOZED) snoozeUntilTs else scheduledTs
        return base != null && base <= now && base >= graceFloor
    }

    /**
     * 撤掉该槽位**已经弹出**的托盘通知。
     *
     * ## 为什么这件事必须与撤闹钟同处一个决策点（B-05）
     *
     * 闹钟是"未来的意图"，托盘通知是"已经发生的陈述"。用户点完确认、
     * 槽位被结算成 EXPIRED 之后，那条通知还在说「该吃药了：环孢素」，
     * 底下还挂着三个可点的按钮 —— 而它们全部只会返回
     * "该服药记录已处理过"。
     *
     * 更糟的是它会**一直**留在通知栏（`setAutoCancel(true)` 只在用户点它时失效），
     * 于是状态与通知长期矛盾。
     *
     * 旧实现只在**用户主动操作**的 4 处撤通知（App 内打卡/跳过/推迟、通知栏 Action），
     * 而结算、暂停、归档这些**系统自己**改变槽位状态的路径一处都不撤。
     * 现在统一收敛到"任何使槽位不再开放的路径都撤"。
     *
     * 整个调用点都在 Robolectric/模拟器上跑，失败不应影响对账本身，故吞异常。
     */
    private fun cancelNotificationOf(context: Context, slotId: Long) {
        runCatching { Notifications.cancelDoseNotification(context, slotId) }
            .onFailure { Log.w(TAG, "cancel notification failed slot=$slotId", it) }
    }

    suspend fun rescheduleAll(context: Context, db: AppDatabase) {
        val now = System.currentTimeMillis()
        Log.i(TAG, "rescheduleAll start, now=$now")

        // 0. 拍快照：在重排之前。孤儿闹钟只能靠这份快照找回来。
        val snapshot = db.doseSlotDao().getOpenSlots().map { it.identity() }.toSet()

        // 1. 过期槽位结算 → EXPIRED。
        //    PENDING 按 `scheduled_ts` 判定；SNOOZED 按 `snooze_until_ts` 判定 ——
        //    用户主动推迟过，就不该再按原计划时间算他逾期（见 DoseSlotDao.getStaleOpenSlots）。
        //    严格限定为「已排期」的历史槽位：用户此刻新建的药品若时点设为 08:30 而当前已 10:36，
        //    那是一条排在过去的槽位，同样应结算为逾期；反之未来槽位永不误判。
        //
        //    两个 cutoff 是同一个 `now - EXPIRE_WINDOW_MS`：逾期宽限全 App 只有一个定义。
        val cutoff = now - EXPIRE_WINDOW_MS
        val staleSlots = db.doseSlotDao().getStaleOpenSlots(cutoff, cutoff)
        val expiredCount = staleSlots.count { stale ->
            // 幂等锚点：受影响行数为 0 说明已被别的路径结算过，不重复撤闹钟
            if (db.doseSlotDao().markExpired(stale.id) == 0) return@count false
            // 结算即不再需要闹钟；三种种类一次清干净
            stale.identity().cancelAll(context)
            // ⭐ 托盘通知同样要撤（B-05）。见下方 [cancelNotificationOf] 的说明。
            cancelNotificationOf(context, stale.id)
            true
        }
        if (expiredCount > 0) {
            Log.i(TAG, "expired $expiredCount overdue slots (pending+snoozed)")
        }

        // 2. 在服药品（**含暂停中的**）未来 HORIZON_DAYS 天排班幂等补齐；
        //    归档药品的开放槽位整段清扫（见 2b——停药的药不该再欠任何待办）
        //
        // ⚠️ 这里**不能**按"截至今天未暂停"来过滤。早先那么写，导致暂停中的药
        // 它的 `reconcileSchedule` 根本不被调用 ⇒ 暂停前已存在的槽位永远留在库里，
        // 仍出现在今日清单上（而闹钟已被撤掉，**永远不会响**），还被统计算成"漏服"。
        //
        // 现在统一交给投影层：`reconcileSchedule` 会把 `pausedUntil` 传进
        // `SlotProjectionEngine.projectSlots`，暂停期内压根不产生槽位，
        // 于是 diff 的"删"分支自然把它们清掉。**暂停是一种排期约束，不是显示过滤。**
        //
        // ⚠️ 暂停判断本身必须走 `MedicationOverview.isPausedOn(date)`（它转给
        // `ReminderSettingsEntity.isPausedOn`），**不能**写成 `pausedUntil != null`：
        // 暂停到期后那种写法会让闹钟静默不再排 —— 用户以为有提醒、实际没有。
        val today = LocalDate.now()
        val schedulableMeds = db.medicationDao().getActiveOverviews()
        val overviewByMed = schedulableMeds.associateBy { it.id }

        // 宽限期内错过的槽位仍然算"待办"，因此它们既不能被结算，也不能被当成孤儿撤掉
        val graceFloor = now - EXPIRE_WINDOW_MS

        /**
         * 某个槽位所在日期，该药是否处于暂停中。
         *
         * ⚠️ 必须按**槽位自己的日期**判断，不能用"截至今天是否暂停"。
         * 暂停到 10-05、视野到 10-12 的药：10-06~10-12 的槽位**该响**，
         * 只有 10-05 及之前的该静默。用 `today` 一刀切会让恢复日之后的提醒全部消失。
         *
         * 投影层（`SlotProjectionEngine`）用的是同一个判据，两边必须一致。
         */
        fun isPausedOn(slot: DoseSlotEntity): Boolean {
            val date = runCatching {
                LocalDate.parse(slot.scheduledDate, SlotProjectionEngine.DATE_FORMATTER)
            }.getOrNull() ?: return false
            return overviewByMed[slot.medicationId]?.isPausedOn(date) == true
        }

        val tracking = DoseTrackingService(db)
        for (med in schedulableMeds) {
            runCatching {
                tracking.reconcileSchedule(
                    medicationId = med.id,
                    fromDate = today,
                    toDate = today.plusDays(HORIZON_DAYS)
                )
            }.onFailure { Log.e(TAG, "reconcileSchedule failed med=${med.id}", it) }
        }

        // 2b. 归档药品的开放槽位清扫（P2#4）。
        //
        // 上面的循环只遍历在服药品，而归档只撤了闹钟（第 3 步的 activeIds 过滤），
        // 归档药名下残留的 PENDING/SNOOZED 槽位此前**没有任何代码路径会删**
        // （对比：删除药品有 FK 级联清空）。它们以"未知药品"的形态挂在今日清单上，
        // 且确认按钮照常工作、照常扣库存（takeDose 不校验 isArchived）。
        //
        // `reconcileSchedule` 现已把归档药按"无计划"投影（见 DoseTrackingService），
        // 这里把归档药也喂进去即可。窗口向前多留 7 天：覆盖"刚错过还没到逾期线"的
        // 宽限期槽位——它们同样不是既成事实。更早的开放槽位必然已被第 1 步结算成
        // EXPIRED，不会出现在这里。
        val archivedMeds = db.medicationDao().getAllMedications().filter { it.isArchived }
        for (med in archivedMeds) {
            runCatching {
                tracking.reconcileSchedule(
                    medicationId = med.id,
                    fromDate = today.minusDays(7),
                    toDate = today.plusDays(HORIZON_DAYS)
                )
            }.onFailure { Log.e(TAG, "archived sweep failed med=${med.id}", it) }
        }

        // 3. 清理孤儿：快照里已不在库中（被重排删掉）或已不该排的槽位
        val stillOpen = db.doseSlotDao().getOpenSlots().associateBy { it.id }
        val activeIds = schedulableMeds.map { it.id }.toSet()
        var cancelled = 0
        for (id in snapshot) {
            val slot = stillOpen[id.slotId]
            // 宽限期内错过的槽位仍算"待办"：第 1 步没结算它，第 4 步会补响它。
            // 这里若判它不该留，就会先撤掉已弹出的托盘通知、再在 30 秒后重弹一条，
            // 用户看到的是"通知自己闪了一下又冒出来"。判据与第 4 步必须一致。
            val withinGrace = slot != null && slot.isWithinGrace(now, graceFloor)
            val shouldKeep = slot != null &&
                slot.medicationId in activeIds &&
                !isPausedOn(slot) &&
                (slot.scheduledTs > now || slot.snoozeUntilTs?.let { it > now } == true || withinGrace)
            if (!shouldKeep) {
                runCatching { id.cancelAll(context) }
                    .onFailure { Log.e(TAG, "cancel failed slot=${id.slotId}", it) }
                // 暂停 / 归档 / 改计划会走"删槽位"，删掉的那一刻托盘上那条提醒
                // 就已经过期了（槽位不存在了，用户按「确认已吃」只会得到
                // "该记录已处理过"）。一并撤掉。
                cancelNotificationOf(context, id.slotId)
                cancelled++
            }
        }

        // 4. 注册：库中仍开放、属于在服药品、且当日不在暂停期内的槽位
        val advanceByMed = schedulableMeds.associate { it.id to it.advanceMinutes }
        var scheduled = 0
        for (slot in stillOpen.values) {
            if (slot.medicationId !in activeIds) continue
            // 暂停期内不排。正常情况下这些槽位已被投影层删掉了，这里是双保险 ——
            // 快照与本次读取之间若恰好又发生一次暂停，也能立刻撤掉闹钟。
            if (isPausedOn(slot)) continue
            val mainAt = slot.scheduledTs
            val snoozeAt = slot.snoozeUntilTs

            // 推迟中的槽位：主闹钟已无意义（用户主动改期），只排推迟唤醒
            if (slot.status == SlotStatus.SNOOZED) {
                if (snoozeAt != null && (snoozeAt > now || snoozeAt >= graceFloor)) {
                    // 推迟目标时刻已过但仍在宽限期内 ⇒ 同样补响一次。
                    // 判据用 `snoozeAt` 而不是 `mainAt`：SNOOZED 槽位的
                    // `scheduled_ts` 是**原计划时间**，早就过去了，拿它判会误补响。
                    val triggerAt = if (snoozeAt > now) snoozeAt else now + GRACE_CATCHUP_DELAY_MS
                    runCatching { AlarmScheduler.schedule(context, slot, triggerAt, AlarmScheduler.Kind.SNOOZE) }
                        .onFailure { Log.e(TAG, "snooze schedule failed slot=${slot.id}", it) }
                    scheduled++
                }
                continue
            }

            // 提前提醒：药品配了 advance_minutes 时，在计划时间前 N 分钟额外唤醒一次。
            // 身份靠 Uri 的 kind 段区分，取消主闹钟不会误伤提前闹钟（P0-1 修复的核心收益）。
            val advance = advanceByMed[slot.medicationId] ?: 0
            if (advance > 0) {
                val advanceAt = mainAt - advance * 60_000L
                if (advanceAt > now) {
                    runCatching { AlarmScheduler.schedule(context, slot, advanceAt, AlarmScheduler.Kind.ADVANCE) }
                        .onFailure { Log.e(TAG, "advance schedule failed slot=${slot.id}", it) }
                }
            }
            if (mainAt > now) {
                runCatching { AlarmScheduler.schedule(context, slot, mainAt, AlarmScheduler.Kind.MAIN) }
                    .onFailure { Log.e(TAG, "schedule failed slot=${slot.id}", it) }
                scheduled++
            } else if (mainAt >= graceFloor) {
                // 宽限期内错过的补响（决策 C / M1-7）。
                //
                // 落在 `(now - EXPIRE_WINDOW_MS, now]` 的 PENDING 槽位**既不结算也不注册**：
                // 过期结算只管 cutoff 之前，而"给未来槽位排闹钟"只管 `> now`。
                // 于是这 2 小时是一个**黑洞**——关机 / 重启 / 长时间后台导致闹钟丢失时，
                // 用户在这段时间内的服药既不会被提醒，也看不到任何解释。
                //
                // 补响一次是这里唯一说得通的处理：提醒不漏是产品的第一承诺，
                // 而"已经过了 2 小时"这条规则本来就是为"别冤枉人判漏服"设的，
                // 它**不该**被用来顺带吞掉一次提醒。补响后仍由用户决定打卡与否。
                runCatching {
                    AlarmScheduler.schedule(context, slot, now + GRACE_CATCHUP_DELAY_MS, AlarmScheduler.Kind.MAIN)
                }
                    .onFailure { Log.e(TAG, "grace catch-up schedule failed slot=${slot.id}", it) }
                Log.i(TAG, "grace catch-up: missed slot=${slot.id} at=$mainAt (now=$now)")
                scheduled++
            }
        }

        Log.i(
            TAG,
            "done: horizon=${HORIZON_DAYS}d meds=${activeIds.size}, " +
                "openSlots=${stillOpen.size}, scheduled=$scheduled, cancelled=$cancelled"
        )
    }
}
