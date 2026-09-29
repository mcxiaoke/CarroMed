# 逾期窗口（EXPIRED）改造方案

- 日期：2026-09-29
- 状态：**已实施**（2026-09-29 21:49，见 `docs/CHANGES-20260929.md` 顶部；实施与本文档的差异见文末 §8）
- 触发场景：一天一次的药（如 09:00），上午没吃、下午吃完全正常，但现状 11:00 就被判逾期
- 不在本轮：记录详情页统一改造另见 `docs/PLAN-RECORD-DETAIL-20260929.md`

---

## 0. 一句话

把**结算窗口**从「计划时间 + 2 小时」改为「当地当日结束」，
并把**补响窗口**从结算窗口里拆出来（两者现在共用同一个常量）。

## 1. 问题（用户 2026-09-29 提出）

一天一次的药，上午没吃、下午吃没有实际问题，但现状在计划时间 + 2 小时后：

| 位置 | 现状表现 | 代码 |
| :--- | :--- | :--- |
| 今日清单 | 徽标「已逾期 xx，尚未确认」 | `TodayScreen.kt` 的 `StatusBadge` |
| 统计 | `EXPIRED` 立即进依从率分母，算作漏服 | `StatsEngine.calculateAdherence` |
| 进展 · 打卡矩阵 | 当天显示 `MISSED` | `StatsEngine.resolveDayState`（`missed > 0` 优先） |

用户下午补记（`EXPIRED → COMPLETED`）**能**修正统计（`markCompletedIfOpen`
的守卫含 `EXPIRED`），但中途这段时间的口径是错的、文案也是指责性的。

## 2. 根因

**2 小时这个数字没有产品依据。** 从 `REMINDER-DOMAIN-REDESIGN` 的决策记录看，
它的约束是"必须远大于 15 分钟的对账粒度"，是工程安全值，不是"多久算漏服"的推导。

**且一个常量同时承担两个职责。** `AlarmReconciler.EXPIRE_WINDOW_MS` 被用在四处：

1. 结算 cutoff（`PENDING`/`SNOOZED` → `EXPIRED`）
2. 补响下界 `graceFloor`
3. `isWithinGrace()`
4. 孤儿清理的 `shouldKeep` 判据

所以**不能只改数字** —— 后三处会跟着漂移。

## 3. 方案

### 3.1 拆成两个常量

| 常量 | 含义 | 取值 |
| :--- | :--- | :--- |
| 结算窗口 | 多久之后把"没结论"判定为漏服 | **当地当日 0 点** |
| `CATCHUP_WINDOW_MS` | 因关机/重启错过时，多久之内补响一次 | 保持 2 小时 |

### 3.2 结算判据

```kotlin
val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
db.doseSlotDao().getStaleOpenSlots(pendingCutoffTs = startOfToday, snoozeCutoffTs = startOfToday)
```

- `PENDING`：`scheduled_ts < startOfToday`（严格小于）⇒ 今天 09:00 的槽位今天不结算
- `SNOOZED`：`snooze_until_ts < startOfToday` ⇒ 23:30 推迟 60 分钟到次日 00:30 的槽位不结算

SQL 本身不用改，只有传参语义从"某个时刻"变成"今日 0 点"。

### 3.3 前置必修：补响的一次性判据

**现状（代码推断，需实测确认）**：补响条件是 `mainAt >= graceFloor`，
而 `AlarmReceiver` 响铃后**自己也会调** `AlarmReconciler.rescheduleAll` ——
于是 30 秒后再次满足条件、再排一个 `now + 30s` 闹钟，形成反复补响。
`docs/CHANGES-20260929.md` 与决策点 C 承诺的是"宽限期内**补响一次**"。

在 2 小时窗口下这最多多响几次；**一旦把结算窗口拉到一整天而补响仍挂在
`graceFloor` 上，就会变成一天响几十次**。所以顺序必须是先修这里。

**推荐修法**（无需 schema 变更）：补响前查该槽位的通知是否还在通知栏。

```kotlin
// 通知 id 就是 slotId（Notifications.showDoseNotification 的约定）
val stillShown = NotificationManagerCompat.from(context)
    .activeNotifications.any { it.id == slotId.toInt() }
if (!stillShown) { /* 补响一次 */ }
```

语义正好闭合：**托盘通知是"已经发生过的陈述"**（`AlarmReconciler.cancelNotificationOf`
的 KDoc 已经这么写）—— 通知还在说明用户已经被提醒过，不该再响；通知不在
（关机 / 重启 / 被清）才补响。

备选修法：新增一列 `last_notified_at`，补响条件加 `IS NULL`。
代价是 `@Entity` 变更 + `AppDatabase.version` 升级 + schema 断言。

### 3.4 语义变化

- 当天不再出现"已逾期"徽标；跨天后仍未处理的才结算为漏服
- 当日依从率不再把"还没结束的一天"计入分母（这本身是修正，不是放宽）
- 昨日未处理的槽位：次日 0 点后的第一轮对账结算（最迟 15 分钟内）
- 边界：昨晚 23:00 未处理的槽位，跨天后立刻结算为 `EXPIRED`（与今天 09:00 未处理的待遇不同，
  这是"当日结束"规则的必然结果，需要在 KDoc 写明）

## 4. 影响面

| 位置 | 改动 |
| :--- | :--- |
| `AlarmReconciler` | 四处常量用法 + KDoc 第 4 条承诺（"超过计划时间 2 小时…"） |
| `DoseSlotDao.getStaleOpenSlots` | 只改传参语义，SQL 不变；KDoc 的"逾期宽限只有一个定义"要更新 |
| `StatsEngine` | 口径不变（`EXPIRED` 仍是 missed），只是出现得更晚 |
| 测试 fixture | **依赖"计划时间 + 2 小时"这条线的要重新校准** —— `REMINDER-DOMAIN-REDESIGN.md:717` 记过：fixture 时点 13:30 越过那条线会让测试早上全绿、下午全红 |
| `AlarmReconcilerTest` / `DoseSlotDstServiceTest` 等 | 断言结算时机的用例要改 |

## 5. 验收

1. **必须实测，不能只看单测**（对账链路是本项目 P0 事故最密集处）：

```powershell
adb -s emulator-5554 shell "dumpsys alarm" > temp\alarm.txt
python temp\alarmcheck.py temp\alarm.txt
```

2. 一条 09:00 的槽位，当天 15:00 打开 App：仍是待服、无"已逾期"徽标、未被结算
3. 次日 0 点后的第一轮对账：结算为 `EXPIRED`
4. 补响只发生一次：撤掉通知后触发对账 → 补响一次；通知存在时不再补响
5. `./gradlew testDebugUnitTest` 全绿

## 6. 未做的备选（不推荐本轮）

**按频次自适应窗口**：一天一次 → 当日结束；一天多次 → 下一次计划时点前。
更精确，但结算器要读 `schedule_policies` 与 `policy_times` 算"下一个时点"，
把一条简单规则变成对策略表的依赖，复杂度不值当。

## 7. 与记录详情页的关系

两件事**互相独立**，但有一条交集：记录详情页的「撤销仅当天」规则按**自然日**判定，
而本次改造按**当日结束**判定结算 —— 两者对齐后语义一致（"当天都还算数，
跨天就定案"），实施时不要把两个判据写成两份。

## 8. 实施记录（2026-09-29）与本文档的分歧

1. **§3.3 的补响循环比本文档写的更严重，且当天就活着**：本文档说"在 2 小时窗口下
   这最多多响几次"，实际机制是响铃 → 对账 → `now+30s` 再响，每条未确认的服药
   以约 31 秒为周期循环到宽限期结束（约 230 轮，osbf 轮审查同日报了 P0）。
   且不止 reboot 补响场景 —— **准点响铃后**同样进入循环。
2. **修法用了推荐修法（托盘判据），未用备选 `last_notified_at`**：零 schema 变更。
   语义取舍已写进 `Notifications.isDoseNotificationShown` 的 KDoc：用户划掉通知后
   下一轮对账会再补响一次（执着性），受补响窗口封顶。
3. **§4 影响面漏了一处**：`AlarmReconciler` 第 3 步孤儿判据 `shouldKeep` 里的
   `scheduledTs > now || … || withinGrace` 分支必须删掉 —— 旧结算线下它与结算互补
   （过去的开放槽位必在宽限内），结算线改自然日后它会把静默待办的托盘通知
   每轮撤掉。见 `CHANGES-20260929.md`。
4. **补响判据落点变了**：本文档成稿时 `AlarmReceiver` 响铃后内联调 `rescheduleAll`；
   实施N3 已改为 `enqueueOneShot`（Worker）。"先弹通知、后对账"的顺序约束仍在，
   已写进 `AlarmReceiver` 注释。
5. **测试比 §4 预估的多一条纪律**：静默待办用例必须**跑两轮对账** —— 同一轮
   `rescheduleAll` 里结算（第 1 步）先于投影（第 2 步），刚投影出的槽位轮不到
   结算，只跑一轮对旧结算线恒绿（变异验证抓出）。
6. **§5 验收第 3 条"最迟 15 分钟内"是名义值**：`ReconcileWorker` 自己的 KDoc
   写明 Doze 下实际间隔可以更长，验收别按 15 分钟卡。
