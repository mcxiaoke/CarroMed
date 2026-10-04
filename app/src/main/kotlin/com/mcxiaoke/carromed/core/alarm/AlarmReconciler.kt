package com.mcxiaoke.carromed.core.alarm

import android.content.Context
import com.mcxiaoke.carromed.core.data.AppDatabase
import com.mcxiaoke.carromed.core.data.entity.DoseSlotEntity
import com.mcxiaoke.carromed.core.data.model.SlotStatus
import com.mcxiaoke.carromed.core.domain.AppLog
import com.mcxiaoke.carromed.core.domain.engine.SlotProjectionEngine
import com.mcxiaoke.carromed.core.domain.service.DoseTrackingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * 闹钟全量对账器 (Reconciler)
 *
 * 在 App 启动、开机、换包、系统改时、备份恢复、以及每个闹钟触发后调用，保证：
 * 1. 未来 [HORIZON_DAYS] 天槽位已按当前策略幂等补齐
 * 2. 所有活跃药品的待服/推迟槽位均注册了精确闹钟
 * 3. 已停药/暂停/过期槽位的闹钟全部取消，杜绝幽灵唤醒
 * 4. 跨过当地当日 0 点仍未处理的槽位结算 EXPIRED（「当日结束」规则，
 *    见 `docs/PLAN-EXPIRE-WINDOW-20260929.md`；一天没过完，当天的欠账不当场认定）
 * 5. 仍在补响窗口（2 小时）内、但闹钟已丢失（关机/重启）的槽位**补响一次**；
 *    托盘里还挂着该槽位的通知就不补（通知在 = 已经提醒过）
 *
 * ## 结算与补响是两条独立的线（PLAN-EXPIRE-WINDOW-20260929）
 *
 * 结算线是**当地当日 0 点**：跨天仍未处理的才定案为漏服。
 * 补响线是 `now - CATCHUP_WINDOW_MS`：只对"闹钟刚丢"（关机/重启/进程死亡
 * 的高发区间）补响一次。两者曾共用一个 2 小时常量 —— 那个数字的出处是
 * "必须远大于 15 分钟的对账粒度"这条工程约束，不是"多久算漏服"的推导，
 * 让它顺带决定"几点算逾期"冤枉了所有上午没吃药的人（11:00 就挂「已逾期」，
 * 而下午吃完全正常）。
 *
 * 两条线之间 —— (今日 0 点, now-2h] —— 是**静默待办**：槽位开放、无徽标、
 * 无闹钟，用户随时可从今日清单补记。它形似旧实现里"两边都不管"的黑洞，
 * 但性质相反：黑洞里的槽位（结算窗口 = 2 小时的年代）是被迫留下的空档，
 * 静默待办是「当日结束」规则下刻意不响、但仍摆在清单上等用户回来的状态。
 *
 * ## 补响为什么只响一次
 *
 * [AlarmReceiver] 响铃后就地重跑 [rescheduleAll]（触发后续期）。
 * 补响判据若只看时刻，刚响过的槽位 30 秒后再次满足条件 ⇒ 每条未确认的
 * 服药以 30 秒为周期反复响到补响窗口结束（最坏 2 小时约 240 次）。
 * 所以补响前必须查托盘：通知还在 = 已经提醒过，不再补；通知不在
 * （关机 / 重启 / 被清）才补。用户主动滑掉通知后，下一轮对账会再补一次
 * —— 漏服提醒需要这份执着，且仍受补响窗口封顶。
 *
 * 注意与第 1 步的执行顺序：结算先跑，补响只看**结算后仍开放**的槽位 ——
 * 昨晚 23:00 未处理的槽位虽落在补响窗口内，但已跨过结算线，
 * 会在同一次对账里定案为 EXPIRED 而不是补响（「当日结束」规则的必然结果）。
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
 * 2. **触发后续期**：[AlarmReceiver] 每次响铃后触发一轮续期（经一次性 Worker，N3），
 *    唤醒链因此自维持
 * 3. **周期对账兜底**：`ReconcileWorker`（A6）周期性地重跑一次本方法
 *
 * ## 对账的顺序：先快照、后重排、再按快照清理孤儿
 *
 * `reconcileSchedule` 会删掉不再被投影命中的槽位行，但领域层不知道 `Context`，
 * 删掉的行对应的闹钟就成了孤儿。旧实现完全没处理这件事（P1-5 闹钟泄漏的另一半）。
 * 现在：
 * 1. 先对所有开放槽位拍快照（含 medId / date / time，足以定位闹钟身份）
 * 2. 跑重排
 * 3. 快照里已不在库中的、或已不该排的 ⇒ **取消其全部种类闹钟**
 * 4. 库中仍开放且活跃的 ⇒ 注册闹钟（同一 Uri 重复注册是"替换"，不会堆积）
 */
object AlarmReconciler {

    private const val TAG = "AlarmReconciler"

    /**
     * 对账专用的 `runCatching`（3-9）：[CancellationException]（含子类）**一律重抛**，
     * 其余异常照旧包成 failure。
     *
     * 对账运行在 Worker 协程里，取消语义必须穿透：裸 `runCatching` 吞掉
     * 取消异常后，已取消的协程会继续跑完剩余步骤，停在「结算完 EXPIRED
     * 但闹钟没重排」的静默中间态；叠加 `ReconcileWorker` 的
     * `ExistingWorkPolicy.KEEP`，卡住的这一轮还会把**所有**后续续期挡在门外。
     * 重抛后 Worker 立即让位，WorkManager 按自身策略重试。
     */
    private inline fun <T> runCatchingOrCancel(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /**
     * 对账全局互斥锁 (P0-2)。
     * 防止并发多入口（周期Worker、一次性Worker、前台Resume、Undo）互相交错，
     * 特别是避免在"删失效槽位"后与"排新闹钟"前被打断导致闹钟丢失。
     */
    private val reconcileMutex = Mutex()

    /**
     * 补响窗口：计划 / 推迟时刻过后多久之内，还值得为"闹钟丢了"补响一次。
     *
     * ⚠️ 与**结算窗口**（当地当日 0 点，见第 1 步）是两条独立的线。
     * 两者曾共用一个 2 小时常量 `EXPIRE_WINDOW_MS`，导致补响判据跟着
     * 结算语义一起漂移 —— 拆开后结算看自然日（用户的心智模型），
     * 补响看这条工程下限（远大于 15 分钟对账粒度即可）。
     */
    private const val CATCHUP_WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * 宽限期内错过时的补响延迟。
     *
     * 为什么是"30 秒后"而不是"立刻"：对账是全量重排，一次可能同时补响很多条，
     * 全部同一瞬间弹出会在锁屏上糊成一片。错开一点让用户还能看清是哪味药。
     */
    private const val GRACE_CATCHUP_DELAY_MS = 30_000L

    /**
     * 前台 RESUMED 全量对账的最小间隔（xdsf P2-1 / §二-22）。
     *
     * 每次 RESUMED 都跑全量对账实测 0.4–1.5s，频繁前后台切换时线性叠加。
     * 此间隔内重复进入前台直接跳过 —— 数据没变的概率极高，且还有
     * `ReconcileWorker` 周期兜底。**显式路径不受限**：保存/恢复/打卡这些
     * 刚改变过数据的调用方仍走 [rescheduleAll] 原方法。
     */
    private const val RESUME_RECONCILE_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** 上次全量对账的开始时刻；任何成功进入 [rescheduleAll] 的路径都会刷新它 */
    @Volatile
    private var lastFullReconcileAt: Long = 0L

    /**
     * 节流版全量对账：仅供 MainActivity 的 RESUMED 入口使用。
     * 距上次全量对账不足 [RESUME_RECONCILE_MIN_INTERVAL_MS] 时直接跳过。
     */
    suspend fun rescheduleAllOnResume(context: Context, db: AppDatabase) {
        val now = System.currentTimeMillis()
        if (now - lastFullReconcileAt < RESUME_RECONCILE_MIN_INTERVAL_MS) {
            AppLog.i(TAG, "resume reconcile skipped (last run ${now - lastFullReconcileAt}ms ago)")
            return
        }
        rescheduleAll(context, db)
    }

    /**
     * 闹钟视野天数。
     *
     * 7 → 14 天的意义不只是"多排一周"：它把"用户多久没打开 App"与"会漏几天提醒"解耦。
     * 14 天意味着**一个月不开 App 也只会漏 1~2 天**（配合触发后续期几乎为零）。
     */
    const val HORIZON_DAYS = 14L

    /**
     * 闹钟身份快照：足以定位一个 PendingIntent，且不依赖会变的 slot.id。
     * 公开给调用方（osbf P3-9）：凡是**先删行、后对账**的调用方
     * （删药、改计划），必须在删行之前把快照带进来 ——
     * [rescheduleAll] 内部拍的快照看不到已经删掉的行。
     */
    data class AlarmIdentity(
        val slotId: Long,
        val medicationId: Long,
        val date: String,
        val time: String
    ) {
        fun cancelAll(context: Context) =
            AlarmScheduler.cancelAll(context, medicationId, date, time, slotId)
    }

    private fun DoseSlotEntity.identity() = AlarmIdentity(id, medicationId, scheduledDate, scheduledTime)

    /** 拍一份当前开放槽位的闹钟身份快照，供 [rescheduleAll] 的 `presnap` 参数使用 */
    suspend fun snapshotOpenAlarms(db: AppDatabase): Set<AlarmIdentity> =
        db.doseSlotDao().getOpenSlots().map { it.identity() }.toSet()

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
        runCatchingOrCancel { Notifications.cancelDoseNotification(context, slotId) }
            .onFailure { AppLog.w(TAG, "cancel notification failed slot=$slotId", it) }
    }

    /**
     * 全量对账。**IO 密集**（每药一次窗口查询 + 逐槽位系统闹钟调用），
     * 内部已切到 [Dispatchers.IO]，调用方**不必**（也不应）再自行切线程 ——
     * 一次包裹覆盖全部现有与未来的调用点（osbf P2-1：此前 6 个 ViewModel
     * 调用点全在 Main 上裸跑）。
     *
     * @param presnap 调用方在**删行之前**拍的闹钟身份快照（osbf P3-9）。
     *   删药 / 改计划这类"先删槽位行、后调对账"的路径必须传：
     *   内部快照是在对账开始时拍的，**看不到已经删掉的行**，
     *   那些行对应的闹钟就成了孤儿（到点空唤醒，最长 14 天）。
     *   不删行的调用方（恢复、打卡、撤销）保持默认空集即可。
     */
    suspend fun rescheduleAll(
        context: Context,
        db: AppDatabase,
        presnap: Set<AlarmIdentity> = emptySet()
    ) = withContext(Dispatchers.IO) {
        reconcileMutex.withLock {
            val now = System.currentTimeMillis()
            AppLog.i(TAG, "rescheduleAll start, now=$now")
            // 刷新节流锚点：显式路径的对账同样让 RESUMED 入口在间隔内免跑
            lastFullReconcileAt = now

        // 0. 拍快照：在重排之前。孤儿闹钟只能靠这份快照找回来。
        //    调用方带了 presnap（删行前拍的）就以它为准 —— 它是**超集**：
        //    包含了此刻已经不在库里的行的身份。
        val snapshot = if (presnap.isEmpty()) snapshotOpenAlarms(db) else presnap

        // 1. 过期槽位结算 → EXPIRED。
        //    结算窗口是「当地当日 0 点」：上午没吃的药下午吃完全正常，
        //    不该 11:00 就被挂上「已逾期」、立刻进依从率分母。跨天后仍未处理的，
        //    0 点后的第一轮对账结算为漏服（最迟 15 分钟是名义值，Doze 下可能更晚）。
        //
        //    PENDING 按 `scheduled_ts`、SNOOZED 按 `snooze_until_ts` 判定，
        //    都是严格小于 ⇒ 恰好把"今天"完整豁免：今天 09:00 的槽位今天不结算；
        //    23:30 推迟到次日 00:30 的槽位次日也不结算（推迟本身把这条挪进了
        //    下一天，要等下下个 0 点才定案 —— 与「当日结束」规则自洽，
        //    但比直觉晚一天，改动时别当 bug 修）。
        //    推迟过的按 `snooze_until_ts` 判定，不按原计划时间：
        //    用户主动给的期限就是期限（见 DoseSlotDao.getStaleOpenSlots）。
        //
        //    严格限定为「已排期」的历史槽位：用户此刻新建的药品若时点设为 08:30
        //    而当前已 10:36，那是一条排在今天的过去槽位，按「当日结束」规则
        //    **不结算**，留在清单上等用户补记；跨天前的未来槽位永不误判。
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 两条单分支查询拼接（ocsbf P1-9 / §一-13）：OR 双分支吃不到索引
        val staleSlots = db.doseSlotDao().getStaleOpenSlotsBothKinds(startOfToday)
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
            AppLog.i(TAG, "expired $expiredCount overdue slots (pending+snoozed)")
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

        // 补响窗口下界：只对"闹钟刚丢"（关机/重启/进程死亡的高发区间）补响。
        // 更早的开放槽位是静默待办 —— 无徽标、无闹钟、留在清单上等用户回来，
        // 跨过结算线（次日 0 点）才定案。见类 KDoc「结算与补响是两条独立的线」。
        val catchupFloor = now - CATCHUP_WINDOW_MS

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
            val date = runCatchingOrCancel {
                LocalDate.parse(slot.scheduledDate, SlotProjectionEngine.DATE_FORMATTER)
            }.getOrNull() ?: return false
            return overviewByMed[slot.medicationId]?.isPausedOn(date) == true
        }

        val tracking = DoseTrackingService(db)
        for (med in schedulableMeds) {
            runCatchingOrCancel {
                tracking.reconcileSchedule(
                    medicationId = med.id,
                    fromDate = today,
                    toDate = today.plusDays(HORIZON_DAYS)
                )
            }.onFailure { AppLog.e(TAG, "reconcileSchedule failed med=${med.id}", it) }
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
            runCatchingOrCancel {
                tracking.reconcileSchedule(
                    medicationId = med.id,
                    fromDate = today.minusDays(7),
                    toDate = today.plusDays(HORIZON_DAYS)
                )
            }.onFailure { AppLog.e(TAG, "archived sweep failed med=${med.id}", it) }
        }

        // 3. 清理孤儿：快照里已不在库中（被重排删掉）或已不该排的槽位
        val stillOpen = db.doseSlotDao().getOpenSlots().associateBy { it.id }
        val activeIds = schedulableMeds.map { it.id }.toSet()
        var cancelled = 0
        for (id in snapshot) {
            val slot = stillOpen[id.slotId]
            // 判据只剩「归属与状态」：槽位还开着、药在服、没暂停 ⇒ 留。
            //
            // ⚠️ **过去**的开放槽位（结算线之后、补响窗口之外的静默待办）也必须留。
            // 旧判据 `scheduledTs > now || ... || withinGrace` 在"结算窗口 = 2 小时"
            // 的年代与第 1 步互补（过去的开放槽位必在宽限内，判它不留就等于判它已结算）；
            // 结算线改成自然日后，过去的开放槽位合法存在 —— 它们本就不该有闹钟
            // （第 4 步不排），但托盘通知若还在，它说的「该吃药了」仍是真话：
            // 槽位确实还开着。若沿用旧判据，每轮对账都会把静默待办的通知撤掉，
            // 用户 15:00 就看不到 09:00 那条还没吃的提醒。
            val shouldKeep = slot != null &&
                slot.medicationId in activeIds &&
                !isPausedOn(slot)
            if (!shouldKeep) {
                runCatchingOrCancel { id.cancelAll(context) }
                    .onFailure { AppLog.e(TAG, "cancel failed slot=${id.slotId}", it) }
                // 暂停 / 归档 / 改计划会走"删槽位"，删掉的那一刻托盘上那条提醒
                // 就已经过期了（槽位不存在了，用户按「确认已吃」只会得到
                // "该记录已处理过"）。一并撤掉。
                cancelNotificationOf(context, id.slotId)
                cancelled++
            }
        }

        // 4. 注册：库中仍开放、属于在服药品、且当日不在暂停期内的槽位
        val advanceByMed = schedulableMeds.associate { it.id to it.advanceMinutes }
        val todayStr = today.format(SlotProjectionEngine.DATE_FORMATTER)
        val repeatEnabled = db.appSettingDao().getValue(ReminderSettings.KEY_REPEAT_REMINDER_ENABLED)?.toBoolean() ?: false
        val repeatInterval = db.appSettingDao().getValue(ReminderSettings.KEY_REPEAT_REMINDER_INTERVAL)?.toIntOrNull() ?: ReminderSettings.DEFAULT_REPEAT_INTERVAL_MINUTES
        val repeatMaxCount = db.appSettingDao().getValue(ReminderSettings.KEY_REPEAT_REMINDER_MAX_COUNT)?.toIntOrNull() ?: ReminderSettings.DEFAULT_REPEAT_MAX_COUNT

        var overdueBeyondCatchupCount = 0
        var scheduled = 0
        var scheduleFailures = 0
        var catchupOffset = 0L
        for (slot in stillOpen.values) {
            if (slot.medicationId !in activeIds) continue
            // 暂停期内不排。正常情况下这些槽位已被投影层删掉了，这里是双保险 ——
            // 快照与本次读取之间若恰好又发生一次暂停，也能立刻撤掉闹钟。
            if (isPausedOn(slot)) continue
            val mainAt = slot.scheduledTs
            val snoozeAt = slot.snoozeUntilTs

            // 统计今天已错过超过 catchup 窗口（>2h）且托盘无通知的待服槽位（开机/长时间静默待办聚合提醒）
            val isToday = slot.scheduledDate == todayStr
            val isPastCatchup = if (slot.status == SlotStatus.SNOOZED) {
                snoozeAt != null && snoozeAt < catchupFloor
            } else {
                mainAt < catchupFloor
            }
            if (isToday && isPastCatchup && !Notifications.isDoseNotificationShown(context, slot.id)) {
                overdueBeyondCatchupCount++
            }

            // 推迟中的槽位：主闹钟已无意义（用户主动改期），只排推迟唤醒
            if (slot.status == SlotStatus.SNOOZED) {
                if (snoozeAt != null) {
                    if (snoozeAt > now) {
                        runCatchingOrCancel { AlarmScheduler.schedule(context, slot, snoozeAt, AlarmScheduler.Kind.SNOOZE, logVerbose = false) }
                            .onSuccess { scheduled++ }
                            .onFailure {
                                scheduleFailures++
                                AppLog.e(TAG, "snooze schedule failed slot=${slot.id}", it)
                            }
                    } else if (snoozeAt >= catchupFloor &&
                        slot.lastSnoozeNotifiedTs == null &&
                        Notifications.areNotificationsReachable(context) &&
                        !Notifications.isDoseNotificationShown(context, slot.id)
                    ) {
                        // 推迟目标时刻已过但仍在补响窗口内，且未成功弹出过推迟提醒 ⇒ 补响一次。
                        // 错开 5 秒 (P2-2)：避免多个补响同时到达在毫秒级重叠
                        val catchupAt = now + GRACE_CATCHUP_DELAY_MS + catchupOffset
                        catchupOffset += 5000L
                        runCatchingOrCancel {
                            AlarmScheduler.schedule(
                                context, slot, catchupAt, AlarmScheduler.Kind.SNOOZE
                            )
                        }
                            .onSuccess { scheduled++ }
                            .onFailure {
                                scheduleFailures++
                                AppLog.e(TAG, "snooze catch-up schedule failed slot=${slot.id}", it)
                            }
                    }
                }
                continue
            }

            // 提前提醒：药品配了 advance_minutes 时，在计划时间前 N 分钟额外唤醒一次。
            // 身份靠 Uri 的 kind 段区分，取消主闹钟不会误伤提前闹钟（P0-1 修复的核心收益）。
            val advance = advanceByMed[slot.medicationId] ?: 0
            if (advance > 0) {
                val advanceAt = mainAt - advance * 60_000L
                if (advanceAt > now) {
                    runCatchingOrCancel { AlarmScheduler.schedule(context, slot, advanceAt, AlarmScheduler.Kind.ADVANCE, logVerbose = false) }
                        .onFailure {
                            scheduleFailures++
                            AppLog.e(TAG, "advance schedule failed slot=${slot.id}", it)
                        }
                }
            }
            if (mainAt > now) {
                runCatchingOrCancel { AlarmScheduler.schedule(context, slot, mainAt, AlarmScheduler.Kind.MAIN, logVerbose = false) }
                    .onSuccess { scheduled++ }
                    .onFailure {
                        scheduleFailures++
                        AppLog.e(TAG, "schedule failed slot=${slot.id}", it)
                    }
            } else if (mainAt >= catchupFloor &&
                slot.lastMainNotifiedTs == null &&
                Notifications.areNotificationsReachable(context) &&
                !Notifications.isDoseNotificationShown(context, slot.id)
            ) {
                // 补响一次（决策 C / M1-7 / P1-1）：未曾提醒过且在补响窗口内，错开 5 秒 (P2-2)
                val catchupAt = now + GRACE_CATCHUP_DELAY_MS + catchupOffset
                catchupOffset += 5000L
                runCatchingOrCancel {
                    AlarmScheduler.schedule(context, slot, catchupAt, AlarmScheduler.Kind.MAIN)
                }
                    .onSuccess {
                        AppLog.i(TAG, "grace catch-up: missed slot=${slot.id} at=$mainAt (now=$now, delay=${catchupAt - now}ms)")
                        scheduled++
                    }
                    .onFailure {
                        scheduleFailures++
                        AppLog.e(TAG, "grace catch-up schedule failed slot=${slot.id}", it)
                    }
            } else if (repeatEnabled && isToday && slot.lastMainNotifiedTs != null && slot.reminderCount < repeatMaxCount) {
                // 需求 5：重复提醒对账。已提醒过但用户未表态 (PENDING)，且在最大重试次数内
                val nextRepeatAt = slot.lastMainNotifiedTs + repeatInterval * 60_000L
                if (nextRepeatAt > now) {
                    runCatchingOrCancel {
                        AlarmScheduler.schedule(context, slot, nextRepeatAt, AlarmScheduler.Kind.REPEAT, logVerbose = false)
                    }
                        .onSuccess { scheduled++ }
                        .onFailure {
                            scheduleFailures++
                            AppLog.e(TAG, "repeat schedule failed slot=${slot.id}", it)
                        }
                } else if (nextRepeatAt >= catchupFloor &&
                    Notifications.areNotificationsReachable(context) &&
                    !Notifications.isDoseNotificationShown(context, slot.id)
                ) {
                    val catchupAt = now + GRACE_CATCHUP_DELAY_MS + catchupOffset
                    catchupOffset += 5000L
                    runCatchingOrCancel {
                        AlarmScheduler.schedule(context, slot, catchupAt, AlarmScheduler.Kind.REPEAT)
                    }
                        .onSuccess {
                            AppLog.i(TAG, "repeat catch-up: slot=${slot.id} next=$nextRepeatAt (now=$now)")
                            scheduled++
                        }
                        .onFailure {
                            scheduleFailures++
                            AppLog.e(TAG, "repeat catch-up schedule failed slot=${slot.id}", it)
                        }
                }
            }
        }

        // 5. 今日超期（>2h）未服待办低优先级聚合提醒：
        //    关机超过 2 小时开机后，旧闹钟已错过且超过 catchupFloor，不再夺命连环响，
        //    但若托盘完全无通知，用户易彻底遗漏今日待服药。
        //    若存在这类槽位且通知可达，则发一条低优先级常驻概览通知；若已全被处理，则撤销该聚合通知。
        if (overdueBeyondCatchupCount > 0 && Notifications.areNotificationsReachable(context)) {
            AppLog.i(TAG, "showing overdue summary notification for $overdueBeyondCatchupCount slots")
            Notifications.showOverdueSummaryNotification(context, overdueBeyondCatchupCount)
        } else {
            Notifications.cancelOverdueSummaryNotification(context)
        }

        if (scheduleFailures > 0) {
            AppLog.w(
                TAG,
                "rescheduleAll encountered $scheduleFailures schedule failures! " +
                    "(possible exact alarm permission denied or UID alarm limit reached)"
            )
        }

        AppLog.i(
            TAG,
            "done: horizon=${HORIZON_DAYS}d meds=${activeIds.size}, " +
                "openSlots=${stillOpen.size}, scheduled=$scheduled, failures=$scheduleFailures, " +
                "cancelled=$cancelled, overdueBeyondCatchup=$overdueBeyondCatchupCount"
        )
        }
    }
}
