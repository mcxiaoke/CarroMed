# 对《REVIEW-ALARM-NOTIFICATION-AUDIT-20261001》的复核报告（review of review）

> **复核日期**：2026-10-01 19:16 (GMT+8)
> **复核对象**：[`docs/REVIEW-ALARM-NOTIFICATION-AUDIT-20261001.md`](REVIEW-ALARM-NOTIFICATION-AUDIT-20261001.md)（19:06 提交，4 个 P0 / 6 个 P1 / 4 个 P2）
> **复核基准**：HEAD 源码逐行核对 + `app/src/test` 守门测试核对 + Android 官方契约与 AOSP 事实核对
> **复核结论一句话**：**14 条 finding 中 7 条完全成立、5 条部分成立（其中 3 条定级失真、1 条建议升 P0）、2 条不成立；报告漏掉 1 条与 P0 同级的结构性缺陷，且其 3 条改生产代码的整改里有 3 条会直接打破仓库里已有的守门测试。**
> **性质**：本次复核**不修改任何生产代码**，只交付判定与依据。

---

## 〇、判定总表

| 编号 | 报告结论 | 复核判定 | 定级是否准确 | 一句话理由 |
| :--- | :--- | :--- | :--- | :--- |
| P0-1 | API 31/32 精确闹钟权限断层 | **成立** | ✅ P0 恰当 | Manifest 只声明 API 33 才存在的 `USE_EXACT_ALARM`，API 31/32 上该权限不存在，链路必然跌到 `INEXACT` |
| P0-2 | `currentPrecision` 对 `setAlarmClock` 权限的错误假设 | **部分成立** | ❌ 应为 P2 | 降级链路本身是对的（`schedule()` 有 `try/catch`），真实缺陷只是"自检页文案说谎"；报告给的修法会打破 2 个守门测试并制造死枚举 |
| P0-3 | 划掉通知触发 30 秒"夺命连环催" | **根因成立，事实与定级失真** | ❌ 应为 P1 | 30 秒连锁在正常路径上不成立（响铃后那一轮托盘判据为 false）；实际节奏是 15 分钟一轮、2 小时约 8 次；且这是**有意设计 + 有守门测试**的行为 |
| P0-4 | 缺 WakeLock 导致 Doze 下协程被冻结 | **成立** | ✅ P0 恰当 | 全工程无 `newWakeLock`（已 grep 确认），Manifest 的 `WAKE_LOCK` 声明确实空转；但报告的机制描述不准，且漏了另外两个同模式 Receiver |
| P1-1 | 缺 Full-Screen Intent | **事实成立，定性错误** | ⚠️ 是已记录的 TODO | `ReminderSettings.kt:14-23` 明写"已摘除"及理由，报告提的方案就是那段 KDoc 自己写的下一步 |
| P1-2 | 通知渠道音频流缺失 | **部分成立** | ⚠️ 方向对、结论夸大 | "完全听不见"不成立（`enableVibration(true)` 仍震）；"无法穿透 DND"不准（已设 `CATEGORY_ALARM`）；**报告漏掉"改已有渠道的 setSound 对已装用户无效"** |
| P1-3 | 14 天预排撑爆 AlarmManager | **成立** | ⚠️ 建议升 P0 | 数学与 `MAX_ALARMS_PER_UID` 均属实；但报告漏了最关键的后果 —— 抛出的 `IllegalStateException` 被 `runCatching` 吞成日志，表现为**静默不排闹钟** |
| P1-4 | 补响未错开、毫秒级并发 | **成立** | ❌ 应为 P2 | 逐字对得上：`AlarmReconciler.kt:355/393` 与 `:96-99` 的注释确实自相矛盾 |
| P1-5 | 厂商自启引导缺失 | **成立** | ✅ P1 恰当 | `PermissionCheckScreen.kt:171-178` 确认 `action = null` |
| P1-6 | `slot.id.toInt()` 溢出 / 99999 碰撞 | **不成立** | ❌ 理论不可达 | 15 槽位/天 ⇒ id 到 99999 需约 18 年；`toInt()` 溢出需 21 亿行。**报告却漏掉了真正同类的问题**（见 §4 遗漏 O-4） |
| P2-1 | 改时区/改时间时日期不同步 | **成立** | ✅ P2 恰当 | `BootReceiver` 确实未调 `CurrentDateHolder.refresh()` |
| P2-2 | 推迟按钮连击叠乘 | **不成立** | ❌ 事实错误 | `snoozeDose` 是 `now + N` 不是 `existing + N`，连击只会**缩短**；且首次点击后通知已被 `cancelDoseNotification` 撤掉 |
| P2-3 | 后台 Toast 被拦截 | **部分成立** | ✅ P2 恰当 | Android 12+ 限制的是自定义 View Toast，文本 Toast 仍可显示（报告自己也承认了） |
| P2-4 | 未设 `VISIBILITY_PUBLIC` | **成立** | ✅ P2 恰当 | `NotificationCompat.Builder` 默认 `VISIBILITY_PRIVATE` |

---

## 一、复核方法与证据基准

不采信报告的转述，逐条回到源码与测试：

| 动作 | 范围 |
| :--- | :--- |
| 全文通读 | `AndroidManifest.xml`、`core/alarm/*`（8 个文件）、`core/domain/{CurrentDateHolder, engine/SlotProjectionEngine, service/DoseEntryActions, service/DoseTrackingService}`、`core/data/{dao/DoseSlotDao, entity/DoseSlotEntity}`、`ui/screen/settings/PermissionCheckScreen.kt`、`MainActivity.kt`、`CarroMedApp.kt` |
| 测试核对 | `AlarmPrecisionAndGraceTest.kt`（326 行，含 2 条直接钉住 P0-2/P0-3 的守门测试）、`AlarmIdentityTest.kt`、`AlarmReconcilerIdempotencyTest.kt`、`ReconcileWorkerTest.kt` |
| 全工程检索 | `newWakeLock`/`WakeLock`、`Mutex`/`withLock`/`isReconciling`、`areNotificationsReachable`/`showDoseNotification`（测试侧）、`.maestro` 走查清单 |
| 事实核对 | `USE_EXACT_ALARM` 引入版本（API 33）、Android 14 对 `SCHEDULE_EXACT_ALARM` 的默认拒绝、`setAlarmClock` 在 API 31+ 的权限要求、AOSP `AlarmManagerService.MAX_ALARMS_PER_UID` |

**上下文事实**：`docs/CHANGES-20261001.md` 顶部已自行记录——这份审查的 4 个 P0 "在本会话内发现、尚未验证、未进整改清单"。本次复核即是对那份"未验证"标注的兑现。

---

## 二、逐条核查

### 2.1 P0-1 API 31/32 权限断层 —— **成立，全报告最有价值的一条**

**代码事实**（`AndroidManifest.xml:16`）：

```xml
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

**规范事实**：`USE_EXACT_ALARM` 是 **API 33（Android 13）** 引入的权限；`SCHEDULE_EXACT_ALARM` 自 **API 31** 起存在。`minSdk = 26`、`targetSdk = 35`（`app/build.gradle.kts:26,27`）。因此在 API 26–30 上无需声明任何权限（`setExactAndAllowWhileIdle` 恒可用），在 **API 31/32 上 `USE_EXACT_ALARM` 在平台权限表里根本不存在 ⇒ 永远不会被授予**。

**运行链路**：`canScheduleExactAlarms()` 恒 false → `currentPrecision` 返回 `ALARM_CLOCK`（非 `EXACT`）→ `schedule()` 的 `when` 直接落到 `setAlarmClock` → API 31+ 同样需要精确闹钟权限 ⇒ 抛 `SecurityException` → 被 `:223` 捕获 → `:227` `setAndAllowWhileIdle`。报告描述的链路完全正确。

**修法正确**：报告给的

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

正是 Google 官方推荐形态（`SCHEDULE_EXACT_ALARM` 在 12 上安装即授予、33+ 不再声明以避开 Android 14 的"默认拒绝"变更；`USE_EXACT_ALARM` 在 13+ 是 normal 权限、自动授予）。**这条应当直接采纳。**

**报告漏掉的三点上下文**（不影响结论，影响落地）：

1. **没提 Android 14 的默认拒绝**：`SCHEDULE_EXACT_ALARM` 对 targetSdk ≥ 33 的应用在 API 34 上默认拒绝。报告的 `maxSdkVersion="32"` 恰好规避了它，但既然报告自称对标了 "API 26~35"，应当把这条约束写进去，否则后来者很容易"顺手去掉 maxSdkVersion"从而在 34+ 上把 P0-1 变成一个新的 P0。
2. **没提 Google Play 对 `USE_EXACT_ALARM` / `USE_FULL_SCREEN_INTENT` 的政策审核**：用药提醒属"闹钟类"可豁免，但上架前需申报。项目当前未公开发布，属低优先，但报告在 §四 反复对标 Play 渠道应用却未提这条。
3. **没提与自检页的联动**：在 API 31/32 上，声明了 `USE_EXACT_ALARM` 的应用**不出现** `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 入口（这正是 `PermissionCheckScreen.kt:132-135` 注释写的事），于是用户**在 App 内没有任何自救入口**。修完之后权限随安装自动授予，这条自然消失；但报告应当指出"未修之前这一屏对 31/32 用户是死路"。

---

### 2.2 P0-2 `currentPrecision` 的错误假设 —— **部分成立，定级应为 P2**

**报告指出的现象属实**（`AlarmScheduler.kt:270-274` + `strings_alarm.xml:5`）：

```kotlin
return if (am.canScheduleExactAlarms()) Precision.EXACT else Precision.ALARM_CLOCK
```

`alarm_precision_alarm_clock` = **"闹钟应用通道（准点）"**。而 API 31+ 上 `setAlarmClock` 与 `setExactAndAllowWhileIdle` 需要**同一个**权限，`canScheduleExactAlarms()==false` 时 `setAlarmClock` 必然抛 `SecurityException` ⇒ 这一档在 API 31+ 上**物理不可达**，用户却被告知"准点"。**这个"文案说谎"是真的。**

**但报告的三处失准**：

1. **把降级链路本身说成缺陷**。`schedule()` 的 `:214-226` 有 `try/catch (SecurityException)`，`:227` 有最终兜底。**投递结果本身是正确的**（必然落到 `INEXACT`），错的是"自检页显示的档位与实际档位不一致"。这是**可观测性缺陷**，不是**可靠性缺陷**，P0 定级不成立。
2. **"谎报档位"这个说法在 P0-1 已修完之后自动消失**：`SCHEDULE_EXACT_ALARM` 补上后 API 31/32 也是 `canScheduleExactAlarms()==true` ⇒ 走 `EXACT`。P0-2 只在"用户主动收回权限"或"厂商 ROM 额外限制"时才有独立价值 —— 那是低频场景。
3. **报告给的修法有四个问题**：

   | 问题 | 说明 |
   | :--- | :--- |
   | ❌ 打破守门测试 | `AlarmPrecisionAndGraceTest.kt:121-132`「未授权时档位如实降级」**显式断言** `currentPrecision() == ALARM_CLOCK`，注释写着"⚠️ 这条正是 P0-2 的守门测试"；`:90-95` 另有一条断言 `ALARM_CLOCK.isDegraded == true`。报告的修法让这两条**直接变红** |
   | ❌ 制造死枚举 | 返回值只剩 `EXACT` / `INEXACT` 两档，`Precision.ALARM_CLOCK` 与 `strings_alarm.xml:5` 变成死代码（但仍被测试断言，无法删） |
   | ❌ 没动真正该动的地方 | `schedule()` 里那次**注定抛异常**的 `setAlarmClock` 仍在，报告的修法让它继续无谓地抛一次 `SecurityException` 再被吞 —— 改完档位显示对了，但降级链路的冗余分支原封不动 |
   | ⚠️ 语义退步 | 原实现的降级链路在 API 26–30 仍有意义（`currentPrecision` 恒 `EXACT`，但 `setExactAndAllowWhileIdle` 仍可能因厂商 ROM 被拒）。报告的修法把三档压成两档，等于宣告"闹钟通道"这一档不存在 —— 这个判断本身需要论证，不能顺手删掉 |

   **建议的最小修法**（比报告的更小且不动测试语义）：保留 `ALARM_CLOCK` 枚举与 `currentPrecision` 现状，改为**修正文案** —— 把 `alarm_precision_alarm_clock` 改成"闹钟应用通道（若系统拒绝将退化为不精确，可能延迟约 1 小时）"，并让 `schedule()` 记录**实际生效档位**（代码里 `:204/221/228` 已经在打这条 INFO/WARN 日志了，自检页只是没消费它）。这样 P0-2 的真实诉求（"不许对用户撒谎"）被满足，而守门测试一条不动。

---

### 2.3 P0-3 补响"夺命连环催" —— **根因成立，但事实描述、定级与方案三者都需要修正**

**根因判断正确**：把 **"托盘当前有没有通知"** 当成 **"系统是否曾经提醒过"**，是真实的架构缺陷。`Notifications.kt:334-337` 只查 `activeNotifications`，划掉 / `setAutoCancel(true)` 点掉 / 清理软件移除 / 重启清空托盘，四种情况都让它变成 `false`。

**但报告描述的"30 秒连锁"在正常路径上不成立**。逐条走一遍报告的场景：

| 步骤 | 报告的描述 | 实际发生 |
| :--- | :--- | :--- |
| 1 | 08:00 收到通知 | ✅ |
| 2 | 用户划掉通知 | ✅ |
| 3 | 08:10 打开 App → `MainActivity` RESUMED 触发对账 | ✅（`MainActivity.kt:40-48`） |
| 4 | 判据 `mainAt ∈ [now-2h, now]` 且托盘无通知 → 排 `now+30s` | ✅（`AlarmReconciler.kt:379-398`） |
| 5 | 30 秒后响铃，重新弹通知，并 `enqueueOneShot` | ✅（`AlarmReceiver.kt:90-124`） |
| 6 | **"下一轮对账又会再次触发 30 秒补响"** | ❌ **不会**。这一步刚弹的通知此刻**在托盘上**，`isDoseNotificationShown` 返回 `true` ⇒ 补响判据为 false。这一步是 `AlarmReconciler.kt:381` 存在的**全部意义**，而 `Notifications.kt:319-333` 的 KDoc 与 `AlarmPrecisionAndGraceTest.kt:209-237` 就是在守它 |
| 7 | **"用户将被迫承受数十次重复轰炸"** | ❌ 数量级错误。实际驱动源是 15 分钟周期 Worker + 用户每次回前台 ⇒ 2 小时窗口内**约 8 次**（不是数十次），且每次都要用户先划掉才走下一轮 |

**所以真实痛点是**：「用户每划掉一次提醒，15 分钟内它就会再响一次，2 小时内共约 8 次」—— 依然很烦人，值得修，但它是 **P1 体验缺陷**，不是 P0 可靠性事故。

**更关键的是定性**：报告把一个**有意设计**当成了"无意缺陷"。证据有三处：

1. `Notifications.kt:326-330` KDoc 原文：*"通知不在（关机 / 重启 / 被清）才补响；**用户主动滑掉后下一轮对账会再补一次 —— 漏服提醒需要这份执着**，且仍受补响窗口封顶。"*
2. `AlarmReconciler.kt:41-52` 类 KDoc「补响为什么只响一次」整节在论证同一个取舍。
3. `AlarmPrecisionAndGraceTest.kt:209-237` 有一条测试**就叫**「托盘里还挂着通知就不补响 通知撤掉才补一次」，并且**断言了划掉后要补响**（`:234-236`），注释写着"变异验证：去掉 rescheduleAll 里的托盘查询，第一条断言立刻变红"。

**这意味着报告的整改方案会直接打破守门测试**：按报告的 `last_notified_ts == null` 判据，测试里那条槽位从未经过 `AlarmReceiver` ⇒ `lastNotifiedTs` 恒为 null ⇒ `:231` 的"通知在 ⇒ 不补响"断言**必然失败**。

**报告方案本身还有两个漏洞**：

- **不区分 `Kind`**：ADVANCE（07:50）与 MAIN（08:00）共用同一通知 id 与同一 `last_notified_ts`。用户划掉 07:50 的提前提醒后，08:00 的 MAIN 补响也会被一并挡掉 —— 恰好把最该响的那次挡了。判据必须落到 `(medId, date, time, kind)` 粒度，或至少按 MAIN 重置。
- **与项目规约冲突**：报告写 `ALTER TABLE dose_slots ADD COLUMN last_notified_ts INTEGER DEFAULT NULL;`（`:474`）。`AGENTS.md` §技术底账明写"**不写数据库迁移代码**，改 schema 直接删库重装"。整改清单里出现迁移 SQL，说明写报告时没有把项目规约纳入考量。

**关于"能不能不改 schema 就修掉"——不能，这一点报告其实说对了。**

"响铃丢了"与"响过了、被用户划掉"这两种世界状态，**在托盘里长得一模一样**，而 App 手头没有任何持久化的"这条已经响过"记录。要区分它们就必须**落一条事实**，所以 `last_notified_ts` 这个**方向**是对的。真正该批评的不是方向，是落地细节：

| 报告方案的问题 | 应当怎样 |
| :--- | :--- |
| 判据不区分 `Kind` | ADVANCE（07:50）与 MAIN（08:00）共用同一通知 id 与同一 `last_notified_ts` ⇒ 用户划掉提前提醒后，**最该响的准点补响也被挡掉**。写入必须按 `Kind` 落，或至少在 MAIN 响铃时重置 |
| 用了 `ALTER TABLE` | 改 `DoseSlotEntity` + DAO 局部 UPDATE（`WHERE status IN ('PENDING','SNOOZED')`，与同文件其余命令同一套守卫），**删库重装**，不写迁移 |
| 没提守门测试要一起改 | `:209-237` 守的是旧语义（"划掉后要补响"）。新语义是它的**反面**，必须**显式重写这条测试并改注释**，说明语义变更的理由 —— 而不是让整改顺手把它变红 |
| 没有"落库失败"的处置 | 补响判据一旦依赖 DB 里的这一列，通知弹成功但 `last_notified_ts` 没写进去 ⇒ 下一轮又开始补响。写入与弹通知必须同事务或在写入失败时跳过补响入队 |

**若产品其实想要"温和地多催几次"**（Medisafe 的 nagging），正确做法是**反向**加状态：给通知挂 `setDeleteIntent`，用户划掉时把槽位标成"已明确忽略"，之后不再补响；未划掉则按固定间隔（如 15 分钟）再来一次，最多 N 次。**本项目现有 KDoc 明确选了另一条路**（"漏服提醒需要这份执着"），所以不必引入这层复杂度 —— 但报告应当把这个取舍摆出来，而不是直接否定。

---

### 2.4 P0-4 缺 WakeLock —— **成立，定级恰当，机制描述需修正**

**代码事实已确认**（全工程检索）：

```
检索模式 newWakeLock|WakeLock|PowerManager
→ 仅 2 处命中，均在 PermissionCheckScreen.kt（import + isIgnoringBatteryOptimizations）
```

`AlarmReceiver.kt:57-131` 确实只有 `goAsync()` + `CoroutineScope(Dispatchers.IO).launch`，`AndroidManifest.xml:20` 的 `WAKE_LOCK` 声明零使用。**报告这条的核心断言成立。**

**机制描述有三处不准确**：

1. *"系统电源服务仅在 `BroadcastReceiver.onReceive()` 主线程执行期间隐式持有 WakeLock"* —— 不准。`AlarmManagerService` 派发 idle 闹钟时持有的临时 wakelock 覆盖**整个广播派发窗口**（`BROADCAST_TIMEOUT`，前台 10s），不是"主线程执行期间"。
2. *"协程调度延迟"* —— 不准。Doze 下对无 wakelock 进程用的是 **cgroup freezer**，线程被**整体停冻**，不是"调度慢一点"。用户侧症状（亮屏才弹）与报告一致，但机制不是"延迟"而是"冻结到下次唤醒"。
3. *"在 Android 的实际电源管理实现及厂商定制系统中"* —— 这句把结论建立在不可引用的经验事实上。应改为可引用的契约表述：**`goAsync()` 保证进程不被杀（ANR 超时前），不保证 CPU 保持唤醒**；`PowerManager.WakeLock` 的官方契约与 `WakefulBroadcastReceiver` 这一兼容库模式的存在，本身就是"广播场景必须自带 wakelock"的行业共识。

**报告漏掉的两点**：

- **同一模式存在于另外两个 Receiver**：`DoseActionReceiver.kt:59-133`、`BootReceiver.kt:46-64` 都是 `goAsync()` + 无 wakelock。实际风险集中在 `AlarmReceiver`（用户在 Doze 里被动被唤醒），另两个分别是用户主动点击和开机时刻，风险低 —— 但报告只字未提，等于留下了"改完 AlarmReceiver 就好了"的错觉。
- **示例代码不能照抄**：报告 `:490-503` 的片段**丢掉了** `intent.action` 校验与 `parseAlarmKey` 的 `null` 校验。若原样替换，任何落到这个 Receiver 的广播都会去拿 wakelock + 起协程 + 查库。整改时应明确"这是骨架，不是可直接粘贴的代码"。

---

### 2.5 P1-1 缺 Full-Screen Intent —— **事实成立，但不是"发现"，是已记录的 TODO**

`ReminderSettings.kt:14-23` 的 KDoc 原文已经写明：`full_screen_alert` 字段被摘除的原因、为什么不能留一个永远为真的假开关、以及**下一步该怎么做**（"把它连同 Android 14 的 `canUseFullScreenIntent` 授权流程一起另立 feature"）。报告 §3.2 提出的方案与那段 KDoc 里的 TODO 几乎逐字重合。

**报告漏掉的技术点**：Android 14 上 `USE_FULL_SCREEN_INTENT` 不只是"声明权限 + 检测引导"，还必须调用 `NotificationManager.canUseFullScreenIntent()` 走 AppOp 授权（`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`）。报告 §五只写了"增加权限声明与权限检测引导"，不足以落地。

**定级**：P1 可以接受（锁屏提醒确实是这个品类的主要交互面），但性质应标注为"按既定决策推进的新 feature"，而非审查发现的缺陷 —— 否则读报告的人会以为代码里有个没发现的洞。

---

### 2.6 P1-2 通知渠道音频流 —— **方向对，结论夸大，且漏掉了最致命的那一半**

**成立的部分**：`Notifications.kt:53-61` 的 `CHANNEL_DOSE_REMINDER` 确实没有 `setSound(null, audioAttributes)`、没有 `setVibrationPattern`。系统会用 `DEFAULT_NOTIFICATION_URI`（`USAGE_NOTIFICATION`），归入"通知音量"流。报告这条对。

**不成立的两处夸大**：

1. *"用户手机静音通知或调低音量时**完全听不见**"* —— **不成立**。该渠道是 `IMPORTANCE_HIGH` + `enableVibration(true)`，通知音量归零时**震动照旧**。真实受损场景只有"手机全局静音/勿扰"，而不是"通知音量小"。
2. *"无法穿透勿扰 (DND)"* —— **不准确**。`Notifications.kt:212` 已经设了 `setCategory(NotificationCompat.CATEGORY_ALARM)`，而这正是系统"勿扰例外 → 闹钟和提醒"所对应的通知类别：App **已经**接入了这条正规通道（是否放行仍由用户在系统里决定，App 无法强制穿透，但也没有像报告说的那样完全没接）。报告把已有能力说成了缺失。

**报告漏掉的最致命一点（这条会直接让整改静默失效）**：

> **NotificationChannel 的 `sound` / `vibrationPattern` / `importance` 一经创建即不可修改。**
> `Notifications.kt:74` 用的是 `createNotificationChannels`，而 `dose_reminder` 这个 channel id 对**已安装用户已经存在**。
> 也就是说报告 §五"配置 `AudioAttributes.USAGE_ALARM`"这个整改，**对所有存量用户完全不生效** —— 除非换 channel id 或显式做渠道迁移。

这条恰恰是本项目最在意的那一类缺陷（"用户以为改了、实际什么都没变"），`DoseSlotDao.updateDerivedColumns` 的 KDoc、`ReminderSettings` 的假开关 KDoc 都在反复强调它。报告在提出改渠道时完全没有意识到这个约束。

**另外报告没看到项目已有这套机制**：夜间静音（`CHANNEL_DOSE_REMINDER_SILENT`，`IMPORTANCE_LOW` + `setSound(null,null)` + `enableVibration(false)`）与"重要提醒"标记（`isCriticalReminder` 穿透夜间静音）已经是**已上线、可配置、按药品**的强弱分流。报告把它描述成"重要药品与普通药品共用一个渠道"，只说对了一半 —— 夜间分流是有的，只是"重要"这一维只影响夜间不影响其他时段。整改建议应当落在这个既有维度上（为重要药品独立渠道），而不是另起炉灶。

---

### 2.7 P1-3 14 天全量预排 —— **数学与框架事实都对，但漏了决定优先级的后果**

**数学已复核为真**：5 药 × 3 次/天 = 15 槽位/天；`advanceMinutes > 0` 时每个槽位排 `MAIN` + `ADVANCE` 两个（`AlarmReconciler.kt:367-378`）；`HORIZON_DAYS = 14`（`:108`）；`stillOpen` 取的是 `getOpenSlots()`，**无上界**，所以 14 天全部预排。`15 × 2 × 14 = 420`。✅

**框架事实已复核为真**：AOSP `AlarmManagerService` 确有 `mConstants.MAX_ALARMS_PER_UID`，越过时抛
`java.lang.IllegalStateException: Maximum limit of concurrent alarms 500 reached for uid: ..., callingPackage: ...`（该常量可由 `config_maxAlarmsPerUid` 配置，部分版本默认 500）。✅ 报告这一条不是臆想。

**但报告的算例没有真正越线**：420 < 500，它给的例子**并不超限**。真正越线的是多药用户：6 药 × 4 次/天 + 提前提醒 = `24 × 2 × 14 = 672 > 500`。对一个同时吃 6 种药的慢性病人，这不是边缘场景。报告举一个不越线的例子来论证"会打满上限"，说服力不足。

**报告漏掉了决定这条优先级的那一半**：

> `AlarmReconciler.kt:370-378` 里每一个 `AlarmScheduler.schedule` 调用**都包在 `runCatching { }.onFailure { AppLog.e(...) }` 中**。
> 也就是说，AMS 抛出来的 `IllegalStateException` **会被吞掉，只留一条 ERROR 日志**，而 `rescheduleAll` 照常返回"成功"（`:412` 打出 `done: ... scheduled=N`）。
>
> 真实后果不是报告说的"触发系统拒绝异常"，而是：**打满上限之后的那些槽位静默不排闹钟，对账器自认为成功，App 完全无感。**
> 对一个把"到点一定响"当第一承诺的应用，这比"崩溃"严重得多 —— 它是典型的静默失效。
>
> 因此这条**应当从 P1 升到 P0**，并且整改的第一件事不是"收敛到 48 小时"，而是**让排期失败可见**（统计失败数、失败即 WARN 上报、连续失败触发自检页告警）。

**报告的整改算术有误**：`ROLLING_ALARM_HORIZON_HOURS = 48` 对应 2 天的闹钟，按报告自己的前提是 `30 × 2 = 60` 个，不是它写的"骤降至 15 左右"。

**报告漏掉的取舍论证**：滚动窗口把"用户完全不打开 App、系统压制后台"这一场景的**无闹钟天数据 14 天降到 2 天**。`ReconcileWorker` 的 KDoc 自己写了"15 分钟是名义下限，不是保证，真实间隔可能更长"（`:47-52`）。也就是说滚动窗口的续期能力**完全依赖那个"不保证"的 15 分钟**。这不是错，但必须被写出来，否则就是把一个 14 天的保险换成了一个 2 天的、且依赖不可控时序的保险。

---

### 2.8 P1-4 补响未错开 —— **成立，逐字对得上，定级偏高**

`AlarmReconciler.kt:96-99` 注释：*"为什么是'30 秒后'而不是'立刻'：对账是全量重排，一次可能同时补响很多条，全部同一瞬间弹出会在锁屏上糊成一片。**错开一点**让用户还能看清是哪味药。"*

实际代码 `:355` 与 `:393` 传的都是同一个 `now + GRACE_CATCHUP_DELAY_MS`，`now` 在 `:173` 取一次，循环内不变 ⇒ **所有补响闹钟的触发时刻毫秒级相同**。注释与实现的矛盾属实，报告的整改（`catchupOffset += 5000L`）正确。

定级 P2 更合适：这是 3 条通知同时弹出 vs 隔 15 秒弹出三条的差别，不影响可靠性。

---

### 2.9 P1-5 厂商自启引导 —— **成立，核对无误**

`PermissionCheckScreen.kt:171-178` 的第四张卡片确实只传了 `title` / `status` / `desc` / `icon`，`action` 走默认 `null`（`:257`），`PermissionItemCard` 的 `if (action != null)` 分支（`:305`）直接跳过 ⇒ 确认无按钮。`strings_perm.xml:29` 也只给了纯文本指引。✅ 报告的整改方向（`VendorIntentHelper` + Intent 矩阵）合理。

**报告漏掉的一点**：`PermissionItemCard` 的 KDoc 记录了一条本项目自己的纪律（`:296-298`）——*"状态文字**始终**显示，即使没有按钮"*。第四张卡片是全页唯一 `ok = false` 且无按钮的项，用户既不知道结论也不能自救，这正是"虚假的未知"被伪装成"已知但需手动处理"。整改时应同时给一个"去应用详情页"的兜底按钮（`openAppDetails` 已存在，`PermissionCheckScreen.kt:237`）。

---

### 2.10 P1-6 `slot.id.toInt()` 溢出与 99999 碰撞 —— **不成立（理论不可达）**

**逐条验算**：

| 场景 | 触发条件 | 实际可达性 |
| :--- | :--- | :--- |
| `toInt()` 溢出为负 | `slot.id > 2_147_483_647` | 按 15 槽位/天需运行 **约 39 万年** |
| 与 `ID_OVERDUE_SUMMARY` 碰撞 | 某槽位 `id == 99999` | 按 15 槽位/天需 **约 18 年**；备份恢复是**用备份里的 id 覆盖**，不会让 id 跳号 |

报告在 `:402` 自己写了"虽然单库运行 20 亿行槽位概率极低"，却仍定级 P1 —— 自我论证与定级不一致。

**报告漏掉了真正同类的真实问题**（见 §4 遗漏 O-4）：本项目已经把**闹钟身份**与**通知动作身份**都改成了内容寻址（`carromed://alarm/...`、`carromed://action/...`），唯独**通知 id 与 `contentIntent` 的 requestCode 仍停留在会变的主键上**（`Notifications.kt:191`、`:242`、`:249`）。这是一个**架构一致性缺口**，比 id 溢出重要一个数量级，且是可以被真实事件触发的（见 O-4）。

---

### 2.11 P2-1 改时区/改时间 —— **成立，核对无误**

`CurrentDateHolder.install`（`:68-86`）只有两个刷新源：60 秒轮询 + `ProcessLifecycleOwner.onStart`。`BootReceiver.onReceive`（`:38-65`）收到 `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` 后只做两个 `enqueue`，**没有** `CurrentDateHolder.refresh()`。报告描述准确。整改方向（`:543`）也正确。

**报告漏掉的一点**：`MainActivity` 自己也没有在 RESUMED 时主动 `refresh()` —— 靠的是 `CurrentDateHolder` 注册的 `ProcessLifecycleOwner.onStart` 观察者间接触发。改时区后如果用户正好在后台，回到前台走的是 `onStart` → `refresh()`，所以前台场景其实是覆盖的；**真正没覆盖的只有"App 一直在前台、改了时区、且跨过了 60 秒轮询前"** 这个 ≤60 秒的窗口。定级 P2 正确。

---

### 2.12 P2-2 推迟按钮连击叠乘 —— **不成立，报告读错了代码**

报告称 `DoseSlotDao.snoozeSlot` 的守卫含 `status == 'SNOOZED'`，所以第二次点击仍被允许并"再次后延 30 分钟"。守卫部分属实（`DoseSlotDao.kt:285`），**但计算方式不是累加**：

```kotlin
// DoseTrackingService.kt:291-292
val safeMinutes = snoozeMinutes.coerceIn(1, 240)
val snoozeUntilTs = System.currentTimeMillis() + (safeMinutes * 60 * 1000L)
```

`snooze_until_ts` 是从**当前时刻**算的 `now + N`，**不是** `existing + N`。所以连击两次的结果是"重置为 now+30m"，即在 1 分钟内连点两次会**把推迟从 30 分钟缩短到 29 分钟**。**不存在报告描述的 30 → 60 分钟叠乘。**

第二重不可达：`DoseEntryActions.snooze` 在 `:186` 事务成功后立刻 `Notifications.cancelDoseNotification(context, slotId)` —— 第一次点击后通知已被撤掉，托盘上已无处可点第二次（`autoCancel` 之外的显式撤销）。

**这条应予驳回。** 若要留一手，唯一真实的相邻风险是：两个 PendingIntent 广播在第一次 `cancel` 之前并发到达时，第二次 `AlarmScheduler.schedule`（`:196`）会以更早的时刻覆盖 SNOOZE 闹钟（同一 Uri ⇒ 同一 PendingIntent ⇒ `FLAG_UPDATE_CURRENT` 替换）。这属于"缩短"，危害远小于报告描述。

---

### 2.13 P2-3 后台 Toast —— **部分成立，定级恰当**

`DoseActionReceiver.kt:136-140` 确实在后台广播里 `Handler(mainLooper).post { Toast... }`。报告自己也承认"系统文本 Toast 仍可能显示"，只主张部分厂商 ROM 会静默拦截。核对属实，定级 P2 恰当。

**报告漏掉的相邻真实问题**：`notifyUser` 用的是**应用 Context** 弹 Toast，而 Android 12+ 对**后台**自定义 View Toast 的限制之外，还有一条**频率限制**（每应用每秒 2 条），在"15 个闹钟同分钟响 → 15 条动作广播"的场景下会被系统折叠或丢弃。这条与 §4 遗漏 O-1 是一条链。

---

### 2.14 P2-4 未设 `VISIBILITY_PUBLIC` —— **成立，核对无误**

`Notifications.kt:198-230` 的 Builder 链确无 `setVisibility`。`NotificationCompat.Builder` 构造时把 `mNotification.visibility` 初始化为 `Notification.VISIBILITY_PRIVATE`。用户在系统里开启"锁屏时隐藏敏感内容"时，锁屏只显示应用名。报告描述准确，定级恰当。

**唯一需要补的一点**：报告 §五把它塞进 P1-2 的整改里（`:527`），但它与"音频流"是两件独立的事 —— 前者改通知内容可见性（Builder 级），后者改渠道音频（Channel 级），且**后者对存量用户无效、前者对存量用户立即生效**（`visibility` 每次构建通知都会重新写）。混在一起做，容易出现"渠道改了没生效"被误判成"整条整改都没生效"。

---

## 三、报告自身的问题（事实性错误 / 自相矛盾 / 规约冲突）

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| E-1 | §一.E 第 1 层 | **"14 天前向排班，即使离线 14 天也有系统底层 Alarm 保障"是错的。** Android 不会补发"触发时刻已过且未投递"的 RTC 闹钟（只有 `BOOT_COMPLETED` 一条恢复途径，本项目已用）。14 天窗口只覆盖**未来**的日子，覆盖不了"关机期间已经过去的那些点"。这与报告自己 P1-3 的前提直接冲突（若 14 天窗口真能覆盖离线，滚动窗口的必要性论证就不成立） |
| E-2 | §3.3 P0-3 步骤 6-7 | **"30 秒后再次弹出 → 下一轮又触发 30 秒补响 → 数十次"** 的链路不成立（见 §2.3）。这是全报告最关键的事实性错误，因为它把一个"15 分钟一次的骚扰"渲染成"30 秒死循环"，严重度被放大了一个数量级 |
| E-3 | §五 阶段二第 1 项 | 滚动窗口整改里"并发闹钟数从 400+ 骤降至 **15** 左右"**算术错误**：48 小时 × 30/天 = **约 60** 个 |
| E-4 | §五 阶段一第 3 项 | 整改里给的是 `ALTER TABLE dose_slots ADD COLUMN ...`，与 `AGENTS.md`「**不写迁移代码**，改 schema 直接删库重装」直接冲突 |
| E-5 | 全文 | **没有一处提到测试影响。** 报告提了 3 条会改生产代码的整改（P0-2 改 `currentPrecision`、P0-3 改补响判据、P1-2 改渠道），其中 **3 条会打破 4 个已有守门测试**（`AlarmPrecisionAndGraceTest` 的 `:90-95`、`:121-132`、`:209-237`，另 `:97-103` 的文案断言也可能受影响）。`AGENTS.md` §三验收标准第 2 条明写"改了什么不变量，确认守它的那条测试还在" |
| E-6 | 全文 | **没有一条整改说明"如何验证"。** P0-1 要在 API 31/32 真机或模拟器上装包验证 `canScheduleExactAlarms()`；P0-3 要构造"划掉通知 → 观察是否再响"；P1-3 要 `dumpsys alarm` 计数。`DEVGUIDE.md` §1/§3/§4 已有现成流程，报告未挂接 |
| E-7 | §五 阶段一第 4 项 | WakeLock 示例代码**删掉了** `intent.action` 校验与 `parseAlarmKey` 的 `null` 校验（`AlarmReceiver.kt:37-53`），原样替换会让无关广播也走完整链路 |
| E-8 | §四 对标表 | 表格里的"CarroMed 现状"列有两处与源码不符：*"无网络约束"*（`ReconcileWorker.kt:121` 确实无约束，✅）但 *"防漏服机制：查托盘通知"* 一栏把一个**有意设计 + 有守门测试**的取舍写成了缺陷；*"锁屏提醒形态：普通 Heads-up"* 一栏没提这是摘除假开关后的**显式决策** |
| E-9 | 执行摘要 | "事实依据确凿"这个自评与 §2.3（E-2）、§2.12（P2-2 读错代码）、§2.10（P1-6 自我论证与定级矛盾）三处不符 |

---

## 四、报告的遗漏（本次复核新增发现）

> 以下 11 条**不在**原报告里。前 3 条按价值排序，其中 **O-1 建议按 P0 处理**。

### O-1 `rescheduleAll` 无单飞保护，`REPLACE` 可在"删完槽位、未排闹钟"之间取消对账 —— **建议 P0**

**证据**：

- 全工程检索 `Mutex|withLock|isReconciling|AtomicBoolean` → **零命中**，`rescheduleAll` 没有任何单飞保护。
- 并发入口有 **4 个**，且互不排斥：
  1. 周期 Worker（`ReconcileWorker.enqueue`，unique name `carromed-periodic-reconcile`）
  2. 一次性 Worker（`enqueueOneShot`，unique name **`carromed-oneshot-reconcile`**）—— **与周期任务不同名，可与周期任务同时跑**（`ReconcileWorker.kt:94` 注释只说"不同名是刻意的"，没意识到这同时意味着"不互斥"）
  3. `MainActivity` RESUMED 直接调（`MainActivity.kt:44`），**不经 Worker**，与上面两个完全并行
  4. `DoseEntryActions.undo` 直接调（`:161`），同样不经 Worker
- `ReconcileWorker.enqueueOneShot` 用 `ExistingWorkPolicy.REPLACE`（`:160-166`），其 KDoc（`:153-158`）明写：*"**REPLACE** 保证最后一次触发之后总有一轮新鲜的对账在跑（**运行中的旧实例被取消** —— 对账幂等，**中断无副作用**，Room 事务原子）"*。

**"中断无副作用"这句断言与代码结构矛盾。** `rescheduleAll` **不是**一个事务，它是一个 4 步顺序流程：

| 步 | 行号 | 已产生的持久副作用 |
| :--- | :--- | :--- |
| ① 结算 EXPIRED | `:198-207` | 改 `status` + 撤闹钟 + 撤通知 |
| ② 重投影（**删行**） | `:251-282` | 物理删除 PENDING/SNOOZED 槽位 |
| ③ 按快照撤孤儿 | `:284-311` | 撤闹钟 + 撤通知 |
| ④ **注册闹钟** | `:313-399` | 逐槽位 `setExactAndAllowWhileIdle` |

**若在 ② 与 ④ 之间被取消（`REPLACE` 正是这么取消的），库里会留下一批"槽位存在、但没有任何闹钟"的开放槽位** —— 也就是"到点不响"，而且没有任何报错。下一轮对账（≤15 分钟）会补上，所以不是永久丢失。

**为什么这条比报告的 P0-3 更值得优先处理**：

- P0-3 的最坏后果是"用户烦"；O-1 的最坏后果是"到点不响" —— 正是产品第一承诺。
- **触发频率被 P1-3 放大**：用户的药常常集中在同一时刻（"早上一把吃"）。08:00 同一分钟响 15 个闹钟 ⇒ 15 次 `enqueueOneShot(REPLACE)` ⇒ 在系统最忙的时刻反复自毁重建，每次都开一个"②已完成、④未开始"的窗口。
- **且没有守门测试**：`AlarmReconcilerIdempotencyTest` 测的是**重复调用**（`repeat(5) { rescheduleAll() }`，串行），**没有一条测试覆盖并发或取消**。

**建议**：给 `rescheduleAll` 加 `Mutex`（或 `withLock` 包住整体）；或把 `enqueueOneShot` 改成 `KEEP`（代价：可能丢掉"最新一轮"，但对账幂等，下一轮 15 分钟内会覆盖）；或把 ②③④ 收进同一个 `db.withTransaction`（代价：事务时长变长）。**三选一都行，但必须显式选一个并加测试。**

### O-2 通知出口不可达时，MAIN 闹钟照排照响 —— **建议 P1**

`AlarmReconciler.kt:375-378` 的**正常主提醒排期**不看 `Notifications.areNotificationsReachable`；只有两条**补响**分支看（`:345`、`:380`）。

后果：用户永久关掉通知（或 Android 13+ 拒绝了 `POST_NOTIFICATIONS`）之后，14 天 × 每天 30 个闹钟**仍然逐个唤醒设备、查库、记一条 `notification NOT delivered` 的 ERROR 日志、什么都不显示**。自检页只提示"通知未授权"，没人知道这背后是每天几十次空唤醒 —— 恰恰是报告 §四 说的"被电池卫士判定为高耗电"的场景。

原报告只审了补响分支，没审主排期分支。

### O-3 排期失败被 `runCatching` 吞成日志（= P1-3 的真实后果，见 §2.7）—— **建议与 P1-3 合并并升 P0**

### O-4 通知 id 仍停留在会变的主键上（= P1-6 的真实问题，见 §2.10）—— **建议 P1**

`Notifications.kt:191`（`contentIntent` 的 requestCode）、`:242`（`notify` 的 id）、`:249`（`cancel` 的 id）全部用 `slot.id.toInt()`。而 `dose_slots.id` 在备份恢复后**会变** —— 这正是本项目把闹钟/通知动作改成内容寻址的原因（`Notifications.kt:78-93` 的 KDoc、`DoseActionReceiver.kt:46-57` 的注释都在讲这件事）。**通知本体却没跟上**：恢复备份后，旧通知的 id 指向**另一条槽位**，而 `Notifications.cancelDoseNotification(context, slot.id)`（`DoseEntryActions.kt:231`、`AlarmReconciler.kt:151`）会**撤掉别人的通知**。

这是内容寻址改造的**收尾缺口**，真实可达（备份恢复是已上线的功能），比 `toInt()` 溢出重要得多。

### O-5 `areNotificationsReachable` 的门过宽 —— **建议 P2**

`Notifications.kt:310-315`：任一渠道 `IMPORTANCE_NONE` 就整体 `return false`。用户只关掉**夜间静音**渠道（一个纯产品开关，与"我听不见提醒"无关），补响与聚合通知就整体停摆。判据应是"**我们将用的那个渠道**是否开着"（`showDoseNotification` 已经算出了 `channel`，`:165`），而不是"两个渠道都开着"。

### O-6 聚合提醒复用"托盘无通知"判据 —— **建议 P2**

`AlarmReconciler.kt:333`：`overdueBeyondCatchupCount` 也用 `!isDoseNotificationShown`。于是聚合通知里的**条数会随用户逐条划掉而漂移**，且每 15 分钟重算一次 → 同 P0-3 家族的问题在聚合分支上重演一遍。报告只审了补响分支。

### O-7 "防漏提醒指南"页从未进过 UI 走查 —— **建议 P1（流程）**

`.maestro/smoke-seeded.yaml` 共 8 屏：今日 / 药箱 / 进展 / 统计 / 药品详情 / 打卡 / 记录详情 / 撤销。**设置区与 `PermissionCheckScreen` 一屏未进。**

`AGENTS.md` §二 step 4 明文要求：*"新增全屏页面时，除导航注册外还要把页面登记进走查脚本，否则它永远不会被自动验证到"*。

而原报告的 **P0-1、P0-2、P1-2、P1-5 四条整改全部落在这一页上** —— 报告核心整改的验证面，恰好是全工程唯一没有自动化看图的一屏。报告对此毫无察觉。

### O-8 `doWork` 捕获 `Throwable` 会吞掉 `CancellationException` —— **建议 P2**

`ReconcileWorker.kt:74-80`：`catch (t: Throwable) { Result.retry() }`。`CancellationException` 是 `Throwable` 的子类，WorkManager 因 `REPLACE` 取消任务时抛出的正是它。把取消当成"失败要重试"会让退避计数与 WorkManager 自身的取消语义打架。与 O-1 同源。

### O-9 渠道 sound/vibration 不可变（= P1-2 整改方案会静默失效，见 §2.6）—— **建议单列为 P1**

### O-10 `setSmallIcon(android.R.drawable.ic_dialog_info)` —— **建议 P2**

`Notifications.kt:199` 与 `:279` 用系统 drawable 作小图标。非单色、跨 Android 版本外观不受控、部分 ROM 上会显示异常。应用应有自己的 `ic_stat_*` 单色资源。

### O-11 通知 `contentIntent` 无深链，且 `MainActivity` 是 standard + `CLEAR_TOP` —— **建议 P2**

`Notifications.kt:189-194` + `AndroidManifest.xml:32-41`（`MainActivity` 无 `launchMode`）。点提醒通知 ⇒ 现有 `MainActivity` 被 finish 并新建 ⇒ Compose 导航状态全丢，用户还要自己再点一次找到那条药。原报告 §一.C 只说"点击通知进入 App"，没发现这条体验链。

---

## 五、整改建议（按项目规约重排）

### 立刻做（无争议）

| # | 事项 | 依据 |
| :--- | :--- | :--- |
| 1 | `AndroidManifest.xml` 补 `SCHEDULE_EXACT_ALARM` + `maxSdkVersion="32"` | P0-1 ✅ 报告正确 |
| 2 | `AlarmReceiver` 加带超时的 `PARTIAL_WAKE_LOCK`，**保留**现有 action / parse 校验 | P0-4 ✅ 报告方向正确（注意 §2.4 的代码形态提醒） |
| 3 | 补响排期加自增 offset | P1-4 ✅ 报告正确 |
| 4 | `PermissionCheckScreen` 第四张卡片加"去设置"按钮（复用 `openAppDetails`） | P1-5 ✅ 报告方向正确 |
| 5 | `BootReceiver` 收到 `TIME_CHANGED` / `TIMEZONE_CHANGED` 时调 `CurrentDateHolder.refresh()` | P2-1 ✅ 报告正确 |
| 6 | 通知 Builder 加 `setVisibility(VISIBILITY_PUBLIC)` | P2-4 ✅ 报告正确 |

### 需要重新定级 / 改方案（先讨论，别照抄报告）

| # | 事项 | 处理方式 |
| :--- | :--- | :--- |
| 7 | **O-1 `rescheduleAll` 单飞 / 取消安全** | 提到 P0，三选一（Mutex / `KEEP` / 整段事务）+ 加并发与取消测试 |
| 8 | **O-3 排期失败可见** | 与 P1-3 合并提到 P0：统计失败数、失败即 WARN、`done` 日志不得在有失败时打成功 |
| 9 | **P0-2 档位文案** | 改为修正 `alarm_precision_alarm_clock` 文案 + 自检页消费**实际生效档位**日志，**不要**按报告改 `currentPrecision`（会打破 2 个守门测试） |
| 10 | **P0-3 补响判据** | 走 `last_notified_ts` 方向（无法免 schema，见 §2.3），但必须：**按 kind 分粒度**、**改 entity 而非写 ALTER**、**显式重写 `:209-237` 那条守门测试**、**处理"弹了通知但没落库"** |
| 11 | **P1-2 渠道音频** | 换 channel id 或做渠道迁移（否则存量用户无效）；在既有 `isCriticalReminder` 维度上分渠道，而不是另起体系 |
| 12 | **P1-3 滚动窗口** | 保留，但把"14 天 → 2 天无闹钟数据"这个取舍与"15 分钟不保证"的依赖写进决策记录；算术改正（60 不是 15） |

### 驳回

| # | 事项 | 理由 |
| :--- | :--- | :--- |
| 13 | P2-2 连击叠乘 | 事实错误（§2.12），机制上只会缩短不会叠乘 |
| 14 | P1-6 id 溢出 / 99999 碰撞 | 理论不可达（§2.10）；真正的问题改列为 O-4 |
| 15 | P0-3 的**事实描述**（"30 秒连环催""数十次"） | 事实错误（§2.3），严重度被放大约一个数量级。**驳回的只是这个描述，不是修法方向** —— 修法方向见本表第 10 项 |

---

## 六、复核结论

这份审查的**结构、覆盖面与代码定位能力是合格的** —— 14 条里 7 条完全成立，其中 P0-1（API 31/32 权限断层）是一条真实、可复现、修法正确的高价值发现，值得直接采纳。P1-3 引用的 `MAX_ALARMS_PER_UID` 也确实是 AOSP 事实而非道听途说。

它的三个系统性问题决定了不能照单全收：

1. **把"有意设计 + 有守门测试"当成了缺陷**（P0-3、P1-1、P2-2）。这个项目的代码 KDoc 密度极高，绝大多数反直觉的行为都写下了取舍与理由；审查只读代码行、不读 KDoc 与测试，就会把它们统统读成 bug。
2. **整改方案没有做落地校验**。3 条改生产代码的建议里 3 条会打破 4 个已有守门测试，1 条违反"不写迁移代码"的红线，1 条示例代码丢了分支，而全文没有一处提到"怎么验证"。
3. **漏掉了与 P0 同级、且没有守门测试的结构性缺陷**（O-1：对账无单飞 + `REPLACE` 可在删完槽位后取消它）。这一条的失败模式正是"到点不响"，比它列为 P0 的补响骚扰严重。

**建议的采纳姿态**：原报告 §五 的整改清单**不要整段执行**。请按本报告 §五 重新排的"立刻做 / 需讨论 / 驳回"三段处理，并把 O-1、O-3 补进 P0 —— 补上这两条之后，这份审查才算覆盖了"到点一定响"这条第一承诺的完整失败面。
