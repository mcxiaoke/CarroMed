# CarroMed 全面审阅报告 —— 代码 / 业务逻辑 / UI-UX / 竞品对标

> **审阅日期**：2026-09-30（GMT+8）
> **审阅对象**：`CarroMed`（Android 原生 · Kotlin 2.0.21 · Jetpack Compose Material 3 · Room 2.6.1 · minSdk 26 / targetSdk 35）
> **代码规模**：`app/src/main` 共 85 个 Kotlin 文件、23,253 行；测试源集 58 个文件、508 个 `@Test` + 10 个 `@Property`
> **审阅方式**：全量源码通读 + 既有审查报告交叉核验 + 实测构建与单测 + 竞品外部检索
> **审阅基线**：工作区 `master`（HEAD = `ed1d380`），工作树干净

---

## 0. 结论摘要

### 0.1 总体判断

**这是一个工程质量显著高于同类开源项目的代码库。** 领域层的三条核心不变量（库存事件溯源、槽位可再生产、事实不可变）不是写在文档里的口号，而是**由数据库约束、SQL 守卫、类型设计三层结构性地保证**的。注释质量尤其突出：绝大多数 KDoc 记录的是"为什么这么写、不这么写会怎样、这个错误当时是怎么踩出来的"，而非复述代码。

本次审阅**未发现 P0 级缺陷**（提醒失效 / 数据丢失 / 静默损坏）。这一结论经过实测验证：

| 验证项 | 命令 | 结果 |
| :--- | :--- | :--- |
| 单元测试全量 | `./gradlew testDebugUnitTest` | ✅ BUILD SUCCESSFUL |
| Release 编译 | `./gradlew compileReleaseKotlin` | ✅ BUILD SUCCESSFUL（16 tasks） |
| 工作树状态 | `git status --short` | ✅ 干净（无未提交改动） |

**真正的短板不在"写错了什么"，而在"还没做什么"。** 与 MyTherapy / Medisafe / Pillo 等业界主流产品横向对比，CarroMed 在**提醒可靠性内核**上并不落后（甚至更严谨），但在**行为学兜底机制**与**数据入口效率**上存在明显代差 —— 详见 §5。

### 0.2 缺陷分布

| 严重度 | 数量 | 含义 |
| :---: | :---: | :--- |
| **P0** | 0 | 提醒失效 / 数据丢失 / 静默损坏 |
| **P1** | 4 | 用户可见的功能缺失或体验断裂 |
| **P2** | 7 | 一致性 / 性能 / 边界严谨性 |
| **P3** | 8 | 清理卫生与文档失真 |
| **竞品短板** | 6 | 对标主流产品的能力缺口（§5） |

### 0.3 与既有审查的关系（重要）

项目已有 6 份历史审查报告（`CODE-REVIEW-20260927-sbf` / `-20260929-ds` / `-osbf` / `-sba` / `-zcg` / `-DB`）与一份汇总清单 `OPEN-ISSUES-20260930.md`。**本次审阅对其中每一条可核验项都回了当前代码**，结论：

- **已修复且经本次复核确认**：`fallbackToDestructiveMigration` 已 debug 门控、盘点可录 0、清除结束日生效、补录时间窗 7 天、通知 Action 内容寻址、恢复后 `cancelAll()`、枚举降级留日志、`rescheduleAll` 已切 IO、备份读取剥 BOM、重复 policyTime 校验已加、药物删除已拍 presnap、`record_id` 索引已加、`Type.kt` 的 `titleSmall` 已补。

- **本次新发现（既有报告未覆盖）**：见 §1.1（通知身份仍用 slot.id）、§1.3（无应用锁）、§2.5（路径导出的 `context` 未用 applicationContext）、§4.5（`labelMedium` 等 M3 槽位缺失）。

- 本报告**不重复列举**已修复项作为缺陷。

---

## 1. P1 —— 用户可见的功能缺失或体验断裂

### P1-1. 通知身份仍用 `slot.id`，与已完成的"内容寻址"改造只做了一半

**证据**
- `core/alarm/Notifications.kt:222` — `NotificationManagerCompat.from(context).notify(slot.id.toInt(), notification)`
- `core/alarm/Notifications.kt:180` — `contentIntent` 的 `requestCode` 也是 `slot.id.toInt()`
- `core/alarm/Notifications.kt:226` — `cancelDoseNotification(context, slotId)` 同用 `slotId.toInt()`

**问题**

`AlarmScheduler` 与 `DoseActionReceiver` 都已经完成了彻底的**内容寻址**改造（`carromed://alarm/{medId}/{date}/{time}/{kind}`），KDoc 里也把理由写得很清楚：

> `dose_slots.id` 是**会变**的：备份恢复会用备份里的 id 覆盖当前库，于是"恢复前排的闹钟"带着旧 id……

但**通知本身的通知 ID 与 contentIntent 的 requestCode 仍然是 `slot.id`**。这是同一个问题的两个面：

1. **备份恢复后通知身份交叠**。恢复是整库替换，新库里槽位 id 会与旧库的 id 空间重叠。`DataExporter` 已经在恢复后调了 `cancelAll()`（`DataExporter.kt:1150`）——这确实缓解了残留问题。但恢复后**新产生的**通知与旧 id 空间的重叠仍存在语义歧义，只是 `cancelAll()` 恰好掩盖了它。

2. **`cancelDoseNotification(slotId)` 的判据与通知的实际语义不再对齐**。`AlarmReconciler` 里有两处调用它：结算逾期（`AlarmReconciler.kt:205`）与孤儿清理（`:308`）。这两处都是"该槽位的提醒已经过期，撤掉托盘上那条"。但如果 id 变化过（改计划导致删行重插），`slot.id` 指向的可能是**另一条槽位**的通知 —— 撤掉的是无辜者的提醒。

3. **`Notifications.isDoseNotificationShown(context, slot.id)`**（`AlarmReconciler.kt:332,365`）是**补响判据的核心**，它判断"这条槽位的提醒是否还挂在托盘上"。这个判据一旦因 id 漂移而失准，要么漏补响（第一承诺受损），要么重复补响（30 秒循环风险）。

**为什么这是 P1 而不是 P2**

补响判据是"到点一定响"这条第一承诺在"闹钟丢失"场景下的唯一兜底。它的准确性依赖 `slot.id` 的稳定性，而 `slot.id` **在设计上就是可变的**（改计划走"删旧插新"，`reconcileSchedule` 的 diff 只保留仍命中的；改时刻必换 id）。

**修法**

把通知 ID 也改为内容寻址的稳定哈希：

```kotlin
// Notifications.kt
/**
 * 通知身份 = 业务键的稳定哈希。
 *
 * 与 AlarmScheduler.alarmUri 同一哲学：身份必须由"业务身份"决定，
 * 而不是由会变的自增主键决定。改计划导致 slot.id 变化时，
 * "这条槽位的通知"与"这条槽位的闹钟"仍然指向同一个逻辑对象。
 */
private fun notificationId(slot: DoseSlotEntity): Int =
    android.net.Uri.parse(
        "carromed://notify/${slot.medicationId}/${slot.scheduledDate}/${slot.scheduledTime}/dose"
    ).toString().hashCode()
```

同步改造三个调用点：`notify(...)`、`contentIntent` 的 `requestCode`、`cancelDoseNotification(...)`、`isDoseNotificationShown(...)`。注意 `cancelDoseNotification` 的**签名需要改**：它当前只收 `slotId: Long`，改为收 `DoseSlotEntity`（或 `medId + date + time`）后，`AlarmReconciler` 的两处调用点天然已经持有完整槽位对象，改造成本极低。

**验收**：Robolectric 断言 —— 对同一 `(medId, date, time)` 的槽位，改计划前后（`slot.id` 变化）`notificationId` 相同；不同槽位不碰撞（可用属性测试覆盖哈希碰撞率）。

---

### P1-2. 无「重复提醒直到确认」机制 —— 提醒强度上限只在"响一次"

**证据**
- 全工程 `grep -rn "重复提醒\|repeat.*remind"` 在 `core/alarm` 下**零命中**
- `AlarmReconciler.kt:361-381` 的补响逻辑**只对"闹钟丢失"生效**（`mainAt > now` 走正常排程，`mainAt >= catchupFloor` 走一次补响），且 `isDoseNotificationShown` 判据保证"通知还在就不补"
- `README.md:316` 已自认此限制：「重复提醒直到确认 | P1 | 需新增重复闹钟调度与会话状态」

**问题**

当前提醒行为是：**到点 → 弹一次高优先级通知 → 若用户没看到/滑掉，就结束了。**

这正是主流产品与 CarroMed 差距最大的一处：

| 产品 | 重复/升级机制 |
| :--- | :--- |
| **Pillo** | 核心卖点就是"alarm won't stop until you take it"（`pillo.care`）——不确认就一直响 |
| **YouGot** | "Nag Mode"：忽略后自动升级，第二次、第三次消息直到确认（`yougot.ai`） |
| **Medisafe** | 持续提醒 + 达到时间未确认则通知 MedFriend（照护者） |
| **Alarmed** | "repeats its alarm until you respond to it" |
| **CarroMed** | 响一次；用户滑掉通知后下一轮对账（≤15 分钟）会补响一次 |

`CATCHUP_WINDOW_MS` 是 2 小时，且 `isDoseNotificationShown` 判据是"通知不在才补"。用户**主动滑掉**通知后确实会补，但补的时机取决于下一次 `rescheduleAll`（周期 Worker 名义 15 分钟、Doze 下可能更久），而且补的仍是"一次"，不是"持续"。

对于**关键药品**（抗凝药、胰岛素、免疫抑制剂 —— 项目已经支持 `isCriticalReminder` 标记），"用户漏看一次通知"就是真实的临床风险。

**修法（分两步，可分别排期）**

**第一步（低成本，立刻可做）**：为 `isCriticalReminder` 的药品引入**重复提醒链**。`AlarmScheduler.Kind` 已有 `MAIN / ADVANCE / SNOOZE` 三态，新增 `MAIN_REPEAT`：

```kotlin
// AlarmScheduler.Kind
/** 准点提醒未确认后的重复提醒（仅关键药品） */
MAIN_REPEAT("repeat"),
```

在 `AlarmReconciler` 注册闹钟时，若 `isCriticalReminder && 槽位仍未确认`，除 `MAIN` 外额外注册 N 次 `MAIN_REPEAT`（例如 +5min / +10min / +15min），全部复用已有的内容寻址身份机制（`kind` 段区分），无需新的状态表。

**第二步（需要会话状态）**：把"本次提醒已提醒过几次"落成派生量 —— 不要新增表，用 `dose_slots.actual_taken_ts IS NULL AND status IN ('PENDING','SNOOZED')` + `now - scheduled_ts` 计算剩余重复次数即可。理由与本项目一贯的"派生量不存储"纪律一致（见 `MedicationEntity` 关于 `is_paused` 的 KDoc）。

**注意**：必须复用 `isDoseNotificationShown` 这道判据，否则与本轮已修复的"30 秒循环"缺陷重蹈覆辙。

**验收**：Robolectric 断言关键药品槽位注册了 4 个闹钟（MAIN + 3×MAIN_REPEAT）、非关键药品只有 1 个；通知栏"确认已吃"后全部 `MAIN_REPEAT` 被取消。

---

### P1-3. 无应用锁 / 隐私保护 —— 用药数据在锁屏与借出场景完全裸露

**证据**
- 全工程 `grep -rn "Biometric\|Keyguard\|app_lock\|passcode"` **零命中**
- `core/alarm/Notifications.kt` 无 `setVisibility(NotificationCompat.VISIBILITY_SECRET)` 调用（全局 grep 仅命中 `DataExporter` 的 `cancelAll`）
- `MedicationEntity.name` 会直接出现在通知标题：`Notifications.kt:192` `getString(R.string.notif_title_critical, med.name)`

**问题**

用药数据是**最高敏感度的健康隐私**之一。当前状况：

1. **锁屏上明文显示药名**。`Notifications.kt` **全程未调用 `setVisibility(...)`**（全局 grep 确认），因此走系统默认值 —— 是否在锁屏显示正文由用户的"锁屏通知"设置决定，而默认是显示。`notif_title_critical` 直接拼药名（`Notifications.kt:192`）。对"共享手机"或"手机放在桌上"的场景，这是直接的隐私泄漏。
2. **App 本身无任何访问控制**。手机解锁后任何人点开 CarroMed，能看到完整用药史、疾病线索（`description` 字段可能写着"预防器官移植排斥反应"）、服药依从率。
3. README 用"纯本地、无网络"作为隐私卖点（`README.md:3`），但**本地隐私 ≠ 访问隐私**。无网络保证了"数据不上传"，但没有保证"数据不被旁人看到"。

对标：

| 产品 | 隐私能力 |
| :--- | :--- |
| **MedRemind** | "Lock-screen privacy mode. Two notification channels hide medication names until the phone is unlocked. **Nobody else in the category has this.**" |
| **Pillio** | "App Passcode Protection"、"Your health data is encrypted" |
| **MedMinder** | 可按患者粒度开关"是否在提醒中显示药名与剂量" |
| **CarroMed** | 无 |

**修法（按性价比排序）**

**(a) 通知隐私（1 小时工作量，收益最大）**：设置页加 "提醒中隐藏药名" 开关，开启时通知标题降级为通用文案（"该服药了 · 重要提醒"），`setVisibility(VISIBILITY_SECRET)`（锁屏不显示内容）+ 新增一条 `VISIBILITY_SECRET` 渠道。

```kotlin
// Notifications.kt —— 复用已有的"渠道重要性不可事后修改"经验
const val CHANNEL_DOSE_REMINDER_SECRET = "dose_reminder_secret"
// ensureChannel 里一并创建，IMPORTANCE_HIGH 但 setLockscreenVisibility(VISIBILITY_SECRET)
```

**(b) 应用锁**：`androidx.biometric:biometric` 依赖 + `MainActivity` 冷启动/前台恢复时校验。**注意用户明确的三条不妥协**：不能用账号体系、不能用网络校验、不能引入云端 —— `BiometricPrompt` 全部满足（纯本地）。

**(c) 敏感字段分级**：`MedicationEntity.description` 与 `precautions` 在锁屏/最近任务缩略图中不可见（`FLAG_SECURE`）。

**(d) 最近任务缩略图**：`window.setFlags(FLAG_SECURE)` 防止 Recents 截图泄漏。

**验收**：Robolectric/仪器测试断言 —— 开关开启时 `notification.visibility == VISIBILITY_SECRET` 且标题不含药名。

---

### P1-4. 通知使用系统占位图标 —— 品牌与识别度为零

**证据**
- `AndroidManifest.xml:32,34` — `android:icon="@android:drawable/sym_def_app_icon"` / `roundIcon` 同
- `Notifications.kt:188` — `.setSmallIcon(android.R.drawable.ic_dialog_info)`

**问题**

应用图标是 Android 系统自带的**通用灰色占位图**，通知小图标是系统信息对话框图标。这带来三重实际影响：

1. **通知栏无法识别**。用户装了 3 个提醒类 App，通知栏里三者长得一模一样，只能靠读文字区分 —— 而通知小图标的**首要职能**就是"一眼知道这是谁的提醒"。
2. **`setSmallIcon` 传系统资源是不合规做法**。Android 官方明确要求 smallIcon 必须是应用的 drawable；系统资源在不同 ROM/版本上存在取不到的风险，部分定制 ROM 会直接导致通知**静默不显示**。这与"提醒可靠"第一承诺直接冲突。
3. **桌面找不到 App**。用户装完后在桌面搜"CarroMed"能看到，但图标是灰块，很难在几十个图标里定位。

**为什么这是 P1 而非 P3**

第 2 条是**功能性风险**：`@android:drawable/ic_dialog_info` 属于非公开稳定 API，且不同 Android 版本该 drawable 的形态不同（有的是 `!` 圆标，有的是方框）。这不是"不好看"，而是"可能不显示"。

**修法**

1. 用 `generate_image` 生成应用图标（药丸 + 提醒的极简符号），导出 `mipmap-{m,h,xh,xxh,xxxh}dpi` 五档 + `mipmap-anydpi-v26` 的 adaptive icon XML。
2. 通知 smallIcon 改用**纯白单色轮廓**资源（`drawable/ic_notify_dose.xml`，VectorDrawable，`android:tint` 由系统处理）。
3. `themes.xml` 的父主题从 `android:Theme.Material.Light.NoActionBar` 换成 `Theme.Material3.DayNight.NoActionBar`（见 §2.6）。

**验收**：仪器测试或人工走查确认通知栏图标为应用自有图标；`assembleRelease` 后 aapt 校验 `smallIcon` 资源 id 属于本包。

---

## 2. P2 —— 一致性 / 性能 / 边界严谨性

### P2-1. `rescheduleAll` 在每次 `onResume` 无节流执行

**证据**：`MainActivity.kt:40-48`

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        launch(Dispatchers.IO) {
            runCatching { AlarmReconciler.rescheduleAll(...) }
        }
    }
}
```

**问题**

`rescheduleAll` 的代价是 O(药品数 × 窗口查询 + 逐槽位系统闹钟调用)。项目自己的 KDoc 记录了实测数据（`AlarmReconciler.kt:158-160`）：**0.4–1.5 秒**。

而 `repeatOnLifecycle(RESUMED)` 意味着：**每次 App 回到前台都会跑一次**。用户一天开 20 次 App，就是 20 次全量对账、约 8–30 秒的累计 CPU/IO 开销与相应电量消耗。更重要的是：

- 用户切出去接个电话再切回来 → 又跑一次
- 从通知栏点回 App → 又跑一次
- 每次相机权限对话框返回 → 又跑一次

这在一个把"物理断网、零广告、零后台"当作卖点的 App 里，是**与产品定位不符的能耗行为**。

**修法**

加时间戳节流 + 事件驱动的显式失效：

```kotlin
// AlarmReconciler 或 MainActivity
private var lastReconcileAt = 0L
private const val MIN_RECONCILE_INTERVAL_MS = 60_000L  // 1 分钟

// RESUMED 路径
if (System.currentTimeMillis() - lastReconcileAt >= MIN_RECONCILE_INTERVAL_MS) {
    rescheduleAll(...)
    lastReconcileAt = System.currentTimeMillis()
}
```

**但不能只靠时间节流**：改计划、删药、打卡这些路径**必须**立即对账。检查现有调用点，确认它们走的是**显式调用**（`MedicationDetailViewModel.kt:251`、`ReminderSettingsViewModel.kt:458` 等）而非依赖 `onResume` —— 经核查确实如此，所以节流是安全的。

另外建议把节流状态放到 `AlarmReconciler` 内部（例如 `private var lastFullReconcileAt`），让"多久算过期"这个判据只有一处定义。

**验收**：Robolectric 断言 1 分钟内连续两次 RESUMED 只触发一次对账；且改计划路径的显式对账**不受节流影响**。

---

### P2-2. 低库存与临期**不会主动通知**，只能在打开 App 时看到

**证据**
- 全工程 `notify(` 调用点**只有一处**：`Notifications.kt:222`（服药提醒）
- `AlarmReconciler` 中 `grep "isLowStock\|expiry"` **零命中**
- 低库存告警（`StatsEngine.isLowStock`）只在今日页横幅、药箱页、详情页、库存页、补药页渲染

**问题**

README 把"三件套展示：剩余量 → 预计可用天数 → 补货提醒"（`README.md:81`）列为功能。但严格说，**只有"补货提示"，没有"补货提醒"** —— 用户必须在某个时间点主动打开 App 才看得到。

考虑真实场景：用户的降压药还剩 3 天量，他连续出差 5 天没打开 App。第 4 天开始断药，**App 从头到尾没有发出过任何信号**。

对标：
- **Medisafe**："Refill reminders based on remaining pill counts" —— 独立于服药提醒的通知
- **Pillo**："Stock Refill Tracking — Get notified before your meds run out so you never miss a refill"
- **Dosecast** / **CareZone** / **Pillio** 均有独立补货提醒

**同时**：临期提醒（`MedicationEntity.expiryDate` + `InventoryTransactionEntity.expiryDate`）在国内场景下价值很高（海外产品普遍缺失），但目前完全没有消费路径 —— **采集了数据却不产生任何提醒**。

**修法**

在 `AlarmReconciler` 的周期对账里增加一条"库存与效期巡检"：

```kotlin
// 每轮对账（名义 15 分钟，实际由 Worker 决定）做一次，但用独立节流避免重复提醒
// 判据：预计可用天数 <= 3 天 或 存在 30 天内到期的批次
// 动作：发一条独立通知（独立渠道 CHANNEL_STOCK_ALERT，避免与服药提醒混在一起）
```

关键设计约束（沿用项目既有纪律）：
- **通知去重**：同一药品同一告警级别**每天最多一次**（用 `app_settings` 记 last alert date，或复用"通知在托盘就不重发"的既有模式）
- **不要打扰用户**：低库存通知走静默渠道（`IMPORTANCE_LOW`），只在通知栏留痕，不弹横幅 —— 它与"该吃药了"的紧急度完全不同
- **负库存不重复告警**：账面为负（D-9 允许）时不要每天弹一条"库存 -2"

**验收**：Robolectric 断言 —— 库存耗尽日期在 3 天内时产生一条通知；同一天第二次对账不产生第二条。

---

### P2-3. 数据入口效率落后一代：无扫码 / OCR，全手输

**证据**
- 全工程 `grep -rn "Barcode\|QR\|camera\|MLKit"` 零命中
- `AddEditMedicationScreen.kt` 的录入是 16 个字段的手工表单
- `README.md:318` 自认：「扫码 / OCR 录入药品 | P2 | 需引入相机与识别依赖」

**问题**

宠物药、慢病多药场景下，"把药盒上的名称 / 规格 / 有效期敲进去"是**用户流失率最高的环节**。业界已经把"扫码建档"当作默认能力：

| 产品 | 数据入口 |
| :--- | :--- |
| **MedRemind** | barcode + photo scanning，"sets up a full regimen in a few taps" |
| **CareZone** | "Camera-based pill bottle scanning for instant medication entry"、"Scanning technology drastically reduces the time needed for data entry" |
| **PillWise** | "AI-powered label scanning for easy medication entry" |
| **Medisafe** | 截至 2026-04 仍**无**扫码（被明确列为短板） |
| **MyTherapy** | 无 |

**但这里有一个真实的产品约束**：引入相机依赖打破了"零第三方运行时依赖"（`README.md:175`）与"无任何网络库"的洁癖。需要明确决策：

- **(a) 不引入相机，改做"输入加速"**：把国内常见药名做成本地词表 + 前缀联想（配合已有的 `alias` 字段），以及在补药页把上次的批号/药房做记忆。**零依赖，覆盖 60% 收益**。
- **(b) 引入 MLKit 条码扫描**（on-device，无网络）：`com.google.mlkit:barcode-scanning`，增加约 1.5MB。与"无 INTERNET 权限"不冲突（MLKit 条码识别是本地模型）。
- **(c) 不推荐**：接入在线药品数据库 —— 与产品定位直接冲突。

**建议**：先做 (a)，把 (b) 作为下一版本的可选项并**明确告知用户"扫码识别在本机完成，不上传图片"**。

---

### P2-4. 备份/导出全量驻留内存，随使用年限线性恶化

**证据**
- `DataExporter.kt:898` — `file.writeText(encodeBackup(buildBackup(db)), Charsets.UTF_8)`
- `DataExporter.kt:534` — `fun encodeBackup(backup: BackupFile): String = json.encodeToString(...)`
- `DataExporter.kt:266,314` — CSV 路径用 `StringBuilder` 全量拼接后 `writeText`

**问题**

`buildBackup` → 构造完整 `BackupFile` 对象树 → `encodeToString` 产出**单个 String** → `writeText` 整体落盘。全链路**三次全量驻留内存**：

1. `BackupFile` 对象树（POJO）
2. `encodeToString` 的 String（UTF-16，约为 JSON 字节数的 2 倍）
3. `writeText` 内部的 `toByteArray()` 副本

估算规模：一个 3 年用药史的用户，若每天 3 次服药、10 种药：

| 表 | 行数 | 说明 |
| :--- | ---: | :--- |
| `dose_records` | ~32,850 | 3 年 × 365 × 3 × 10 |
| `dose_slots` | ~32,850+ | 与记录同量级 |
| `inventory_transactions` | ~40,000+ | 每次打卡 + 补药 + 撤销 + 校准 |

单条 `DoseRecordEntity` 序列化后约 120 字节 → JSON 约 4MB → String 约 8MB → 字节数组 4MB。加上另外两张表，**峰值可能在 30–50MB**。这在低端机（堆上限 128MB）上，叠加 Compose 与 Room 的开销，有 OOM 风险。

**更关键的是**：`writeSafetySnapshot`（`DataExporter.kt:910`）也走同一条路径，而它是在**恢复流程中间**调用的 —— 一旦这里 OOM，恢复会中途失败。

**修法**

改用流式写入。`kotlinx.serialization` 支持 `encodeToStream`：

```kotlin
// 用 Json.encodeToStream + Okio/Java 的 OutputStream，逐表写入
suspend fun exportFullBackupJson(context: Context, db: AppDatabase): File {
    val file = File(exportDir(context), ...)
    file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
        json.encodeToStream(buildBackupStreaming(db), writer)  // 或手写 JSON 流
    }
    return file
}
```

若 `kotlinx.serialization` 的流式 API 不便（它是"整对象编码"），另一条路：**分表写入**一个 JSON 数组流（`{"version":8,"medications":[...],"doseRecords":[...]}` 逐段 append），恢复侧用 `JsonReader`（`kotlinx-serialization-json` 提供 `decodeToSequence`）流式读取。

**同时**：`buildBackup` 已包 `withTransaction`（`DataExporter.kt:415`），这是正确的（读一致性快照）。流式化时**必须保留事务包裹**，否则会读到跨表不一致的快照。

**验收**：Robolectric 构造 10 万条 `dose_records`，断言导出成功且 `Runtime.totalMemory() - freeMemory()` 峰值增量 < 20MB。

---

### P2-5. 跨组件 `Context` 传递缺少强制约束（预防性）

**证据**：`DataExporter` 的 `contentResolver.openInputStream(uri)`（`:973`）与 `FileProvider` 分享路径（`AndroidManifest.xml:70-76`）接收的都是 `Context`。经核查当前所有调用点都传 `applicationContext` 或 `getApplication<Application>()`，**无实际泄漏**。

**问题**：这是一条"靠调用方自觉"的约定 —— 没有类型或文档强制。`Notifications` / `DataExporter` 都是 object 单例，若未来有人传 Activity context 进来并被长期持有，就会泄漏。

**修法**：在 `DataExporter` 与 `Notifications` 的对象 KDoc 里显式写明「所有 `context` 参数必须是 application context；本对象持有跨进程生命周期」，或在内部统一做 `context.applicationContext` 归一化。

**严重度说明**：这是**预防性建议**，不是已发生的缺陷。列在 P2 是因为它属于"下次有人加调用点时会踩"的那一类。

---

### P2-6. 主题缺 `DayNight`，系统深色模式下状态栏图标与窗口背景脱节

**证据**
- `app/src/main/res/values/themes.xml:3` — `<style name="Theme.CarroMed" parent="android:Theme.Material.Light.NoActionBar" />`
- 无 `values-night/` 目录（`find res -name "values-*"` 零命中）
- `Theme.kt:93-94` — `insetsController.isAppearanceLightStatusBars = !darkTheme`

**问题**

Compose 侧（`CarroMedTheme`）**完整实现了深色模式**：`DarkColorScheme` 定义了 29 个颜色槽位，`isSystemInDarkTheme()` 正确驱动。但 **XML 主题层锁死在 Light**：

1. **冷启动白闪**。App 从冷启动到 Compose 首帧渲染之间存在一个窗口，此时显示的是 `Theme.Material.Light.NoActionBar` 的白色背景。深色模式用户在夜间打开 App，会先被闪一下白屏。
2. **`values-night` 缺失**导致系统无法在 XML 层切换到深色窗口背景，`windowBackground` 始终是浅色。
3. `insetsController.isAppearanceLightStatusBars = !darkTheme` 是**正确的**（深色模式用浅色图标），但状态栏**背景**由 XML 主题决定，两者可能在一帧内不一致。

**修法**

```xml
<!-- values/themes.xml -->
<style name="Theme.CarroMed" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:windowBackground">@color/window_background</item>
</style>

<!-- values/colors.xml -->
<color name="window_background">#FFFBFE</color>

<!-- values-night/colors.xml -->
<color name="window_background">#1C1B1F</color>
```

注意：换 `Theme.Material3.DayNight` 需要引入 `com.google.android.material:material` 依赖，或**用 `Theme.MaterialComponents.DayNight.NoActionBar` 的等价纯平台方案**（`android:Theme.DeviceDefault.DayNight`，API 29+ 可用，但 minSdk 26 需要兼容处理）。

**最简方案（推荐）**：保持 `android:Theme.Material.NoActionBar` 但加 `-night` 限定符：

```xml
<!-- values/themes.xml -->
<style name="Theme.CarroMed" parent="android:Theme.Material.Light.NoActionBar" />

<!-- values-night/themes.xml -->
<style name="Theme.CarroMed" parent="android:Theme.Material.NoActionBar" />
```

零新依赖，`values-night/` 由系统在深色模式时自动选用。

**验收**：深色模式下冷启动录屏，无白色帧；`windowBackground` 在 `values-night` 生效。

---

### P2-7. 统计页的"准时率"口径缺失 —— 只有"是否服用"，没有"是否按时"

**证据**
- `StatsEngine.kt` 全文无"准时 / onTime / punctuality"概念
- 依从率公式固定为 `completed / (completed + skipped + missed)`（`StatsEngine.kt:275-279`）
- `README.md:319` 自认：「±N 分钟准时率口径 | P2 | 需先写《统计口径规范》再动统计代码」

**问题**

"依从率 95%" 这个数字对医生和用户都有歧义：是"95% 的次数服了药"，还是"95% 的次数在规定时间内服了药"？

当前实现给了答案（**前者**），但界面上没有说明；而临床与主流产品普遍需要**后者**：

| 产品 | 准时率 |
| :--- | :--- |
| **Pill Reminder & Meds Tracker** | "On-time percentage for scheduled doses" —— 独立的准时率指标 |
| **Dosecast** | "Dose history that tracks late, skipped, and postponed doses accurately" |
| **CarroMed** | 无 |

**具体表现**：用户把 08:00 的药在 21:00 才打卡，`dose_slots.actual_taken_ts = 21:00`、`scheduled_ts = 08:00`，依从率记 100% 满分。**这对用户是虚假的安慰** —— 与 `StatsEngine.calculateStockRunway` KDoc 里那句"给用户虚假的保证"是同一类问题。

**数据是齐备的**：`dose_slots` 同时有 `scheduled_ts` 与 `actual_taken_ts`，差值就是延迟。不需要新表。

**修法**

1. 先写《统计口径规范》文档（README 已经指出这是前置条件）—— 至少定义：准时窗口（±30 分钟？±2 小时？）、延迟的呈现层级（准时 / 延迟 / 漏服）。
2. `StatsEngine` 新增：

```kotlin
/** 准时窗口（分钟）：计划时刻前后该范围内打卡视为"准时" */
const val ON_TIME_WINDOW_MINUTES = 60

fun onTimeRate(slots: List<DoseSlotEntity>): Float  // 准时数 / 已服数
```

3. `StatsScreen` 增加一个 StatTile「准时率」，与「依从率」并列。分母为 0 时显示 "—"（沿用既有约定，`StatsScreen.kt:176-180`）。

**验收**：构造"08:00 计划、21:00 打卡"的槽位，断言准时率不计入分子；`StatsEngineTest` 补充边界用例。

---

## 3. P3 —— 清理卫生与文档失真

### P3-1. 死 DAO 方法残留（约 8 处）

**证据**：对 `main` 源集做调用计数（`grep -rn "methodName" app/src/main/kotlin --include=*.kt`），以下方法**零生产调用**：

| 方法 | 位置 | 备注 |
| :--- | :--- | :--- |
| `observeSlotsInRange` | `DoseSlotDao.kt:149` | 生产用 `observeSlotsForDate` |
| `getSlotsForDate` | `DoseSlotDao.kt:146` | **仅测试 fixture 使用**（`getSlotsForDate` 在 8+ 个测试文件里被调用），不可直接删 |
| `getPendingSlotsAfter` | `DoseSlotDao.kt:166` | 生产用 `getPendingSlotsForMedicationAfter` |
| `countCompletedSlotsForDate` | `DoseSlotDao.kt:318` | 生产走 `observeSlotStatusCounts` |
| `countTotalSlotsForDate` | `DoseSlotDao.kt:321` | 同上 |
| `getActiveMedications` | `MedicationDao.kt:168` | `getActiveOverviews` 已覆盖 |
| `observeArchivedOverviews` | `MedicationDao.kt:147` | 归档列表走 `getAllMedications().filter { it.isArchived }` |
| `getAllPoliciesForMedication` | `SchedulePolicyDao.kt:72` | 仅测试使用 |

**⚠️ 删除前必须区分两类**：

- **可安全删除**（生产与测试均零调用）：`observeSlotsInRange`、`getPendingSlotsAfter`、`countCompletedSlotsForDate`、`countTotalSlotsForDate`、`getActiveMedications`、`observeArchivedOverviews`
- **保留但需加标注**（`getSlotsForDate`、`getAllPoliciesForMedication` 被 8+ 个测试文件用作 fixture 查询）：建议保留，并在 KDoc 里标注「测试 fixture 专用」——沿用项目对 `forceStatusForTest` 的处置方式（意图写进签名/文档，而不是靠零调用就删）。

**为什么值得清理**：这些方法的**存在本身**比"多几行代码"危险 —— 它们各自是一个"看起来像正经 API 的旁路入口"。未来有人想"快速拿一下今天的槽位"时，会自然找到 `getSlotsForDate` 而绕过 `observeSlotsForDate`（Flow 版，有 Room 失效通知），于是页面不再自动刷新。`OPEN-ISSUES-20260930.md` 的 L1 记录了同一类判断。

---

### P3-2. `BackupFormat.kt` 单位默认值硬编码中文

**证据**：`BackupFormat.kt:66` — `val unit: String = "片",`

**问题**：`BackupFormat` 是**持久化格式**定义，默认值 `"片"` 会被写进备份 JSON。这与项目已完成的 i18n 改造（`PLAN-I18N-20260930.md`，18 个字符串资源文件）方向不一致：**备份是数据不是 UI**，但如果未来支持多语言，非中文用户导出的备份里会出现中文字符串。

**说明**：同类问题在 `MedicationEntity.kt:31` 也存在，但那里是**数据库默认值**，有 `AGENTS.md` 的"改 schema 直接删库"兜底，危害可控。`BackupFormat` 是**跨版本契约**，改动成本更高。

**修法**：备份格式里 `unit` 用稳定 key（如 `TABLET` / `CAPSULE`）而非展示文本，展示层用 `MedVocab` 解析 —— 与项目现有的 `category` / `form` 稳定 key 做法完全一致。**但这会破坏已有备份的兼容性**，需评估（项目未发布，可接受）。

---

### P3-3. `RefillViewModel` 默认药房硬编码

**证据**：`RefillViewModel.kt:22` — `val channel: String = "同仁堂实体药房",`

**问题**：这是**业务默认值**，不是 UI 文案。虽然 `RefillScreen.kt:224-228` 提供了 4 个选项（同仁堂/医院/线上/家人），但默认值硬编码到具体品牌，对非北京用户是不合理的。且与 `hardcoded_strings_report.txt` 里标记的硬编码问题属同一批。

**修法**：默认值改为空串（让 placeholder 引导），或改为中性的 `"实体药房"`。

---

### P3-4. `Type.kt` 仍缺 M3 排版槽位

**证据**：`Type.kt` 定义了 9 个槽位（`headlineLarge` / `headlineMedium` / `titleLarge` / `titleMedium` / `titleSmall` / `bodyLarge` / `bodyMedium` / `labelLarge` / `labelSmall`），**缺 `bodySmall` / `labelMedium` / `displayLarge` / `displayMedium` / `displaySmall` / `headlineSmall`**。

**问题**：`grep "typography.bodySmall"` 在 UI 层**有 67 处使用**、`labelMedium` **6 处使用**。它们当前落在 M3 默认值上（`bodySmall` = 12sp、`labelMedium` = 12sp）。

这本身可用，但项目已经**显式收紧过整套字号阶梯**（`bodyLarge` 16→15、`bodyMedium` 14→13），且 `titleSmall` 的 KDoc 明确写了：

> 只有 titleSmall 悄悄用着 M3 默认值……它是**默认值而不是决定** —— 将来有人整体调 `bodyMedium`，标题与正文的层级关系会在无人察觉的情况下被改掉。

**`bodySmall` 的问题更严重**：它被用了 67 次，是**最常用的正文辅助样式**，却仍在默认值上。而 `labelMedium`（6 处）与 `bodySmall` 默认**同为 12sp** —— 层级完全靠 M3 默认值维持，不是项目的决定。

**修法**：补齐 `bodySmall`（建议 12sp，与 `bodyMedium` 13sp 保持 1sp 落差）、`labelMedium`（建议 12sp，字重 Medium 以区分），其余 display 槽位若确实不用可显式 `TODO` 标注。

**验收**：`Type.kt` 对每个在 UI 层被使用的槽位都有显式定义；`grep` 确认无 `typography.*` 落在未定义槽位。

---

### P3-5. `strings.xml` 与 `values-night` 之外的资源组织问题

**证据**：`app/src/main/res/` 下**只有** `values/` 与 `xml/`，无 `mipmap-*`、无 `drawable/`、无 `values-night/`。

**说明**：应用图标与通知图标问题已在 P1-4 覆盖。此处仅记录"资源目录结构不完整"这一事实，作为 P1-4 / P2-6 的佐证。

---

### P3-6. `hardcoded_strings_report.txt` 残留在仓库根目录

**证据**：仓库根目录存在 `hardcoded_strings_report.txt`（39 行，来自 `scan_hardcoded_strings.ps1`），内容为 i18n 扫描时的中间产物。

**问题**：`AGENTS.md §3` 明确要求「临时/中间产物放 `temp/`，**严禁在项目根目录堆放临时文件**」。该文件却留在根目录且已提交。

**修法**：移到 `temp/` 或加入 `.gitignore`（若脚本会反复生成）。同时 `scan_hardcoded_strings.ps1` 也应输出到 `temp/`。

---

### P3-7. 测试中的硬编码远期日期

**证据**
- `AppDatabaseRealTest.kt:358,465` — `setPausedUntil(medId, "2026-12-31")`
- `MedicationAdminServiceTest.kt:154,173` — `endDate = "2026-12-31"`

**问题**：`ReminderSettingsIsolationTest.kt:45` 已经修过同类问题并留下注释：

> "远期未来"的暂停截止日。相对今天取值 —— 硬编码 `2026-12-31` 的版本 [会在到期后假红]

但 `AppDatabaseRealTest` 与 `MedicationAdminServiceTest` 里仍有硬编码。当前看是"数据往返断言"（存什么读什么），不会因日期流逝而假红。**但 `AppDatabaseRealTest.kt:358` 的 `setPausedUntil("2026-12-31")` 在 2027 年之后会变成"已过期的暂停"**，如果该测试后续加了对 `isPausedOn(now)` 的断言（`ReminderPauseTest` 就在做这件事），就会静默假红。

**修法**：统一改为 `LocalDate.now().plusMonths(3).toString()`。

---

### P3-8. `LogFileSink` 的日志上限需要复核

**证据**：`LogFileSink.kt:105` — `if (f == null || f.length() <= maxFileBytes) return`；`:151-168` 有清理逻辑

**说明**：日志轮转机制**已实现**（不是缺陷）。但需要确认 `maxFileBytes` 的取值与 `AppLogging.install` 的装配是否在生产构建里也启用 —— 如果生产环境持续写日志且上限较大，会占用存储。建议在 `docs/PLAN-LOGGING-20260929.md` 的验收里补一条"生产构建日志上限实测"。

**优先级说明**：此项为**验证建议**，不是已确认缺陷。

---

## 4. UI / UX 专项审阅

### 4.1 总体评价：信息架构优秀，细节打磨到位

项目的 UI 设计有清晰的**产品判断**，不是"把功能堆到页面上"：

| 判断 | 体现 | 评价 |
| :--- | :--- | :--- |
| **三分离** | 药品信息 / 提醒设置 / 库存 各有独立编辑页，添加时合一 | 参照 MyTherapy 并超越；避免了"改个药名滚过整套闹钟计划" |
| **三区分离** | 今日清单 待服 / 已服 / 跳过 独立计数 | 符合"跳过不污染依从率分子"的数据口径 |
| **口径可见** | 分母为 0 显示 "—" 而非 100% | 拒绝虚假满分，**这一点比多数竞品做得好** |
| **降级可见** | 精确闹钟档位可查询并显示在自检页 | 少见的诚实设计 |
| **判据单一** | `isLowStock` / `isActionableOn` / `adherenceOf` 全 App 唯一实现 | 从"约定"升级为"结构" |

**文案质量**同样高于平均水平。`CHANGES-20260930.md` 记录的文案标准化（清除"冲正/改判/账面/台账"等会计黑话、统一"服药/服用"、修正"已到期"歧义）是**真正从用户视角出发的改写**。

### 4.2 已改写到位且值得保持的做法

- **未来日只读**：卡片不渲染 ✓，改为"明天 10:30 服用"（`TodayViewModel.kt:72-75`）
- **失败必须说真话**：`takeMessage` 区分 APPLIED / FUTURE_SLOT / ALREADY_HANDLED（`DoseActionReceiver.kt:150-156`）
- **首启空态与"有药但今日无排班"严格区分**（`TodayUiState.hasAnyMedication`）
- **Streak 徽章与月历**：对齐 MyTherapy 的激励设计，且算法边界处理严谨（`calculateStreak` 对"今天进行中不断签"、"无排班日跳过不惩罚"）

### 4.3 无障碍（Accessibility）现状评估

| 检查项 | 现状 | 评价 |
| :--- | :--- | :--- |
| `contentDescription` | 71 处 `Icon(`，28 处 `contentDescription = null` | 多数装饰性图标；`CarroMedTopAppBar` 的 `actionContentDescription` 已正确参数化 |
| `TestTags` | 50+ 个语义标签，覆盖全部页面 | ✅ 完整 |
| 触控目标 | 已采用 M3 标准控件（`SegmentedButton` / `TopAppBar` / `ModalBottomSheet`） | ✅ 自带 48dp 最小尺寸 |
| 大字号 | 存在固定 `.height(46.dp)`（`MedicationDetailScreen.kt:318,550,561`）等 | ⚠️ 见 4.4 |
| 深色模式 | Compose 侧完整，XML 侧缺失 | ⚠️ 见 P2-6 |
| TalkBack 流程 | 无专项测试 | ⚠️ 见 4.5 |

### 4.4 固定高度容器在大字号下可能裁切

**证据**：`MedicationDetailScreen.kt:318,550,561` 的 `.height(46.dp)`

**问题**：`46.dp` 在默认字号（1.0x）下容纳单行文字 + 内边距是合理的。但当用户在系统里把字号调到 **1.3x / 1.5x**（老年慢病用户的常见设置）时，`46.dp` 内的文字会**裁切或挤压**。

对 CarroMed 尤其重要 —— 目标用户群（"慢病患者、老人及其代管家属"，`README.md:7`）**正是最可能放大字号的人群**。

**修法**

```kotlin
// ❌ 固定高度
Modifier.height(46.dp)

// ✅ 最小高度
Modifier.defaultMinSize(minHeight = 46.dp)
// 或直接不限定高度，由内容 + padding 决定
Modifier.padding(vertical = 12.dp)
```

**说明**：全工程对 `Modifier.height(N.dp)` 的使用**相当克制** —— 抽查显示绝大多数是 `Spacer(Modifier.height(...))`（间距，与字号无关）。需要逐个评估的主要是**容器或按钮**上的固定高度。建议排查清单：

```
grep -rn "\.height([0-9]\+\.dp)" app/src/main/kotlin/.../ui
# 排除 Spacer(Modifier.height(...)) 后逐个确认
```

**验收**：系统字号设为 1.5x，走查全部 13 个页面截图，无文字裁切。

### 4.5 UI 层硬编码与 i18n 完备度

**证据**：`hardcoded_strings_report.txt` 显示残留：

| 位置 | 内容 | 判断 |
| :--- | :--- | :--- |
| `DoseTrackingService.kt`（9 处） | `"服药剂量必须大于 0，当前 $takenAmount"` 等 | ⚠️ 领域层 `require` 消息 —— 不面向用户，可接受 |
| `MedicationAdminService.kt`（9 处） | `"药品名称不能为空"` / `"保存提醒行为失败：..."` | ⚠️ 同上 |
| `RefillViewModel.kt:22` | `"同仁堂实体药房"` | ❌ 业务默认值硬编码（P3-3） |
| `AlarmReceiver.kt` | `"skip: no open slot for $key (已打卡/已结算/已删除)"` | ⚠️ 日志消息，中英混排（团队规范是英文日志，`AGENTS.md §1`） |

**关于领域层 `require` 消息**：这是**正确的取舍**。`DoseTrackingService` 的 KDoc 已说明这些异常不面向用户（`ManualDoseViewModel.kt:194` 有 `// 不透出 t.message：领域层抛的是不变量违约文本，用户看不懂`）。保持领域层零 `android.*` 依赖（`AGENTS.md` 架构铁律）意味着它**不能** `getString()`，硬编码是必然结果。**不算缺陷**。

**唯一可改进项**：`AlarmReceiver.kt:66` 的日志消息 `"skip: no open slot for $key (已打卡/已结算/已删除)"` 混用中英。`AGENTS.md §1` 规定"标准 log 输出用英文"。建议改为 `"skip: no open slot for $key (taken/settled/deleted)"`。

### 4.6 缺失的 UX 能力（对标视角）

以下能力在主流产品中属常规，CarroMed 尚未提供。按性价比排序：

| 能力 | 竞品参照 | 价值 | 成本 |
| :--- | :--- | :--- | :--- |
| **桌面 Widget** | Pillo："Home Widget — Set unique reminder styles per medication" | 高（减少打开 App 的摩擦） | 中（独立子系统，`README.md:320` 已列 P2） |
| **PDF 就诊报告** | Pillio："Export your history: generate clear PDF reports for your doctor"；MyTherapy："PDF Reports" | 高（医生沟通场景） | 中（`reportlab` 思路可移植到 Android 的 `PdfDocument`） |
| **健康指标记录** | MyTherapy："blood pressure, weight, blood sugar"；Pillo 同类 | 中（但打破"只管药"的定位边界） | 中 |
| **自定义例外日历** | Dosecast："weekends only"；`README.md:317` 已列 P1 | 中（节假日漏提醒） | 低（本质是 `DAYS_OF_WEEK` 的扩展） |
| **±N 分钟准时率** | Pill Reminder & Meds Tracker："On-time percentage" | 中 | 低（见 P2-7，数据已齐备） |

**关于"家人代管"**：`README.md:319` 明确标注「与"纯本地零网络"定位冲突」，判为不做。**这个判断是正确的** —— 主流产品的照护者功能（Medisafe MedFriend、Pillo SMS 告警）全部依赖网络，CarroMed 的定位使其无法复制。建议在文档里把这个取舍的理由写得更明确，避免后续反复被提起。

---

## 5. 竞品对标：能力矩阵与短板定位

### 5.1 对标产品选择说明

选定 MyTherapy / Medisafe / Pillo / Dosecast / Round Health / MedRemind / MedTimer 七款，覆盖三类：

- **主流标杆**：MyTherapy（免费、欧盟、功能全）、Medisafe（用户量最大、功能最丰富）
- **机制专精**：Pillo（持续提醒）、Dosecast（复杂排程）、Round Health（时间窗）
- **隐私同路人**：MedTimer（开源、纯本地、零遥测）、MedRemind（本地优先）

**检索时间**：2026-09-30。**来源性质**：厂商官网 + 第三方横评，均为二手信息（无一手评测环境），故下表结论标注置信度。

### 5.2 能力矩阵

| 能力 | CarroMed | MyTherapy | Medisafe | Pillo | MedTimer | 判定 |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **离线可用** | ✅ 完全 | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 | ✅ 完全 | **优势** |
| **无账号** | ✅ | ⚠️ 可选 | ❌ 必需 | ❌ | ✅ | **优势** |
| **无网络权限** | ✅ | ❌ | ❌ | ❌ | ✅ | **优势（罕见）** |
| 精确闹钟 + Doze 穿透 | ✅ | ⚠️ | ✅ | ✅ | ⚠️ | **持平或优** |
| 槽位/事实三层解耦 | ✅ | ❌ | ❌ | ❌ | ❌ | **结构性优势** |
| 库存事件溯源 | ✅ | ❌ | ⚠️ | ⚠️ | ❌ | **结构性优势** |
| 复杂排程（周期/隔N天/按需） | ✅ | ✅ | ✅ | ✅ | ✅ | 持平 |
| 数据导出 CSV/JSON | ✅ | ⚠️ | ⚠️ | ⚠️ | ✅ CSV | 持平或优 |
| **重复提醒至确认** | ❌ | ❌ | ✅ | ✅ | ❌ | **短板 P1-2** |
| **锁屏隐私模式** | ❌ | ❌ | ⚠️ | ❌ | ❌ | **短板 P1-3** |
| **应用锁** | ❌ | ❌ | ⚠️ | ✅ | ❌ | **短板 P1-3** |
| **补货主动提醒** | ❌ | ✅ | ✅ | ✅ | ❌ | **短板 P2-2** |
| **扫码/OCR 录入** | ❌ | ❌ | ❌ | ❌ | ❌ | 短板 P2-3（行业普遍缺失） |
| **准时率指标** | ❌ | ❌ | ⚠️ | ⚠️ | ❌ | 短板 P2-7 |
| 依从率报告 | ✅ | ✅ | ✅ | ✅ | ⚠️ | 持平 |
| **PDF 就诊报告** | ❌ | ✅ | ✅ | ✅ | ❌ | 短板 4.6 |
| 桌面 Widget | ❌ | ⚠️ | ⚠️ | ✅ | ❌ | 短板 4.6 |
| 照护者共享 | ❌ | ⚠️ | ✅ | ✅ | ❌ | **定位取舍，不做** |
| 药物相互作用检查 | ❌ | ❌ | ✅ | ✅ | ❌ | 定位取舍（需药品库） |
| 健康指标记录 | ❌ | ✅ | ✅ | ✅ | ❌ | 定位取舍 |
| 有效期/临期提醒 | ✅ | ❌ | ❌ | ❌ | ❌ | **独有优势** |
| 多语言 | ❌ 仅中文 | ✅ 多语 | ✅ 多语 | ✅ 多语 | ⚠️ | 短板（若面向国际） |
| 开源可审计 | ⚠️ 内部项目 | ❌ | ❌ | ❌ | ✅ | — |

> 置信度说明：CarroMed 列为**本次代码核实结果**（高置信）；竞品列为厂商自述与第三方横评（中置信），标注 ⚠️ 者为"信息不完整或依版本而异"。

### 5.3 短板定位结论

**CarroMed 的位置是清晰的：它是"隐私优先 + 数据严谨"路线的产品，不是"功能最全"路线。**

三条真正的能力短板（按对核心承诺的威胁程度排序）：

1. **重复提醒缺失（P1-2）** —— 直接威胁"提醒可靠"第一承诺。主流产品里 Pillo、Medisafe 都有；**这是最该补的一项**。
2. **隐私访问控制缺失（P1-3）** —— 威胁"数据自主"隐含的用户预期。README 用隐私做卖点，但只做到了"不上传"，没做到"不被看"。**卖点与实现的落差**比单纯的缺失更伤信任。
3. **补货/临期不主动通知（P2-2）** —— 弱化了产品已有的差异化能力（国内特色的有效期管理），把它降级成"用户主动看才有的信息"。

**不建议追的方向**（与定位冲突，且是竞品用网络能力建立的护城河）：照护者共享、药物相互作用库、云同步、健康指标。

### 5.4 竞品趋势观察（用于路线图输入）

第三方横评归纳的 2026 年趋势（来源：`truereviewnow.com`、`medremindapp.com`）：

- **AI 个性化提醒**（基于依从行为调整提醒时机）
- **照护者可见性**成为标配（老年场景驱动）
- **语音助手支持**（seniors、免手操作）
- **可穿戴集成**（Apple Watch / Health Connect）
- **隐私优先设计**已从"加分项"变成"基本预期"

其中**"隐私优先已成都市基本预期"** 对 CarroMed 是**有利信号** —— 但前提是隐私要做得完整（当前缺访问控制层）。

---

## 6. 改进路线图

### 6.1 分期建议

| 批次 | 内容 | 价值 | 工作量 |
| :--- | :--- | :--- | :---: |
| **① 立即（本轮收尾）** | P1-4 应用图标与通知图标 · P2-6 深色模式 XML · P3-6 临时文件归位 · P3-3 硬编码药房 | 消除明显的产品化缺口 | 0.5 天 |
| **② 可靠性加固** | P1-1 通知内容寻址 · P1-2 关键药品重复提醒 · P2-1 对账节流 | 直接强化第一承诺 | 3–5 天 |
| **③ 隐私完整化** | P1-3 通知隐私 + 应用锁 + FLAG_SECURE | 让隐私卖点名副其实 | 2–3 天 |
| **④ 主动提醒扩展** | P2-2 补货/临期通知 | 兑现已有的差异化功能 | 2 天 |
| **⑤ 数据严谨性** | P2-4 备份流式化 · P2-7 准时率（需先定口径） | 长使用周期下的稳健性 | 3–4 天 |
| **⑥ 入口效率** | P2-3 输入加速（先做本地词表方案） | 降低建档摩擦 | 2–3 天 |
| **⑦ 清理批次** | P3-1/2/4/7 死代码 · 排版槽位 · 测试日期 | 卫生 | 1 天 |

### 6.2 需要产品先拍板的两项

**决策 A：提醒强度分级**
一次提醒 vs 重复至确认 vs 分级（普通药一次、关键药重复）。
**建议**：分级。理由 —— 项目已有 `isCriticalReminder` 字段与对应的夜间穿透逻辑，复用成本最低，且避免对普通药过度打扰。

**决策 B：i18n 的边界**
`BackupFormat` 的 `unit` 字段是否 key 化？是否需要英文界面？
**建议**：若确定只面向中文用户，`unit` 保持现状并把决定写进文档；若要国际化，现在就改（未发布，成本最低），否则越晚越贵。

### 6.3 文档层面的建议

1. **补《统计口径规范》**。`README.md:319` 已指出这是准时率的前置条件。当前依从率、消耗量、Streak 三套口径分散在 `StatsEngine` 的 KDoc 里，建议合并成一份文档。
2. **在 README 的"明确未做"表格里，把"为什么不做"写得更明确**。例如"家人代管"当前理由是"与纯本地零网络冲突"—— 建议补充"主流产品的照护者功能全部依赖网络，本项目的定位使其无法复制"。这样能避免后续反复被提起。
3. **`AGENTS.md` 的自检清单可加一条**：涉及通知/闹钟身份改动时，必须同步检查"通知 ID、闹钟 requestCode、Action data"三处是否同源。P1-1 正是"只改了两处、漏了第三处"。

---

## 7. 附：本次审阅的证据链与验证记录

### 7.1 实际执行的验证

| 验证 | 命令 / 方法 | 结果 |
| :--- | :--- | :--- |
| 单元测试 | `./gradlew testDebugUnitTest` | ✅ BUILD SUCCESSFUL in 35s |
| Release 编译 | `./gradlew compileReleaseKotlin` | ✅ BUILD SUCCESSFUL in 2s（16 tasks up-to-date） |
| UTF-8 编码核查 | `iconv -f UTF-8 -t UTF-8` 遍历全部 `.kt/.kts/.xml/.py/.md` | ✅ 无非 UTF-8 文件 |
| 工作树状态 | `git status --short` | ✅ 干净 |
| 变更历史 | `git log --oneline -25` | 确认 HEAD = `ed1d380` |

### 7.2 已核验的关键点（无问题，供参考）

以下内容经代码核实**确认正确**，不构成缺陷：

1. **`fallbackToDestructiveMigration` 已 debug 门控**（`AppDatabase.kt:150-152`）—— H1 已修复。
2. **盘点校准允许录 0**（`InventoryViewModel.kt:264` 用 `parseNonNegative`）—— H2 已修复。
3. **清除结束日生效**（`ReminderSettingsViewModel.kt:421` `clearEndDate = !s.hasEndDate || s.endDate == null`）—— H3 已修复。
4. **补录时间窗 7 天且 DatePicker 有 `minDate`**（`ManualDoseScreen.kt:267-270,290,301`）—— H4 已修复。
5. **恢复后 `cancelAll()`**（`DataExporter.kt:1150`）—— H5 的一半已修复。
6. **新建药品 `minStockAlert` 默认 "0"**（`AddEditMedicationViewModel.kt:126`）—— H6 已修复。
7. **`rescheduleAll` 已包 `withContext(Dispatchers.IO)`**（`AlarmReconciler.kt:172`）—— M1 已修复。
8. **`reconcileSchedule` 走 SQL 内过滤**（`DoseSlotDao.kt:162-163` `getSlotsInRangeForMedication`）—— M2 已修复。
9. **提醒设置页暂停改为只读**（`ReminderSettingsViewModel.kt:96-105,451-455`）—— M3 已修复（且注释与代码一致）。
10. **`buildBackup` 已包 `withTransaction`**（`DataExporter.kt:415`）—— M4 已修复。
11. **`RefillViewModel` 补了 `onFailure`**（`RefillViewModel.kt:140-144`）—— M5 已修复。
12. **枚举降级有日志**（`AppConverters`，M6 已修复）。
13. **`CabinetViewModel` N+1 已消除**（`CabinetViewModel.kt:114-118` 批量取回）—— M7 已修复。
14. **统计页补录探针已加**（`StatsViewModel.kt:216` `recordCountProbe`）—— M8 已修复。
15. **二级页面已 Flow 化**（`MedHistoryViewModel.kt:95-96`、`InventoryViewModel.kt:91-94`、`MedicationDetailViewModel.kt:85-90`）—— M9 已修复。
16. **备份 UUID 校验补齐**（`DataExporter.kt:156,711-718` `DUPLICATE_POLICY_TIME`）—— M10 已修复。
17. **删药已拍 presnap**（`MedicationDetailViewModel.kt:239-241`）—— M11 已修复。
18. **`inventory_transactions` 已有 `record_id` 索引**（`InventoryTransactionEntity.kt:33`）—— L2 已修复。
19. **`Type.kt` 已补 `titleSmall`**（`Type.kt:52-68`）—— L8 的一部分已修复（但 `bodySmall` 仍缺，见 P3-4）。
20. **所有数字键盘已是 `KeyboardType.Decimal`**（8 处，`grep` 零命中 `KeyboardType.Number`）—— L4 已修复。
21. **`MedicationDao.update` 整行覆盖已删除**（`MedicationDao.kt:65` 有删除说明）—— L1 的一部分已修复。
22. **库存守恒不变式结构健全**：`balanceAfter = balanceOf + changeAmount.milli`（`DoseTrackingService.kt:135`），且 `medications` 表无余额列（`MedicationEntity.kt:61-67`）。
23. **`markCompletedIfOpen` 有 `scheduled_date <= :todayStr` 守卫**（`DoseSlotDao.kt:221-230`）—— 未来槽位不可打卡。
24. **`revertToPending` 刻意不带日期守卫**（`DoseSlotDao.kt:284-291`）—— 保留修复通道，设计意图明确。
25. **`snoozeSlot` 有状态守卫**（`DoseSlotDao.kt:269-278`）—— 防"已打卡后又被推迟"的重复扣库存路径。
26. **结算线是「当地当日 0 点」而非 2 小时窗口**（`AlarmReconciler.kt:197-198`）—— 避免上午没吃药就被判漏服。
27. **补响用托盘通知判据防 30 秒循环**（`AlarmReconciler.kt:332,365` + `Notifications.isDoseNotificationShown`）。
28. **无 INTERNET 权限、`allowBackup=false`**（`AndroidManifest.xml:9-18`）—— 隐私基线成立。
29. **`USE_EXACT_ALARM` 而非 `SCHEDULE_EXACT_ALARM`**（`AndroidManifest.xml:21`）—— 闹钟类应用免授权，第一档成为常态。
30. **`ReconcileWorker` 用 `KEEP` 而非 `REPLACE`**（`CarroMedApp.kt`）—— 避免冷启动取消正在执行的任务。
31. **通知渠道有静音版本**（`Notifications.kt:35,63-72`）—— 夜间免打扰真实生效。
32. **重要提醒穿透静音**（`ReminderSettings.kt:61-62`）—— 临床刚需被正确实现。
33. **`StatsEngine` 所有聚合是整数毫单位**（`Dose` / `groupByUnit`）—— 无浮点漂移。
34. **`RUNWAY_UNLIMITED = -1` 而非 `Int.MAX_VALUE`**（`StatsEngine.kt:140`）—— 哨兵可判定。
35. **`aggregateDailyOverallBreakdowns` 与 `calculateStreak` 边界处理严谨**（`StatsEngine.kt:347-398`）。

### 7.3 本次未能验证的项（诚实标注）

| 项 | 原因 | 建议验证方式 |
| :--- | :--- | :--- |
| **Doze 下层实际唤醒延迟** | 无真机/模拟器 10 分钟 `dumpsys alarm` 观察条件 | `OPEN-ISSUES-20260930.md` §五已列出该验收项，建议补做 |
| **厂商 ROM 的杀后台表现** | 需多台真机（小米/华为/OPPO/vivo） | 建议建立厂商矩阵测试清单 |
| **10 万行数据的备份内存峰值** | 未构造大数据集 | P2-4 的验收方法已给出 |
| **TalkBack 完整流程** | 需人工用读屏走查 | 建议纳入 UI 走查脚本的可选项 |
| **竞品功能细节的版本时效** | 检索为二手来源，且厂商功能随版本变化 | 关键词标注置信度；关键决策前建议实测 |
| **`app/src/debug` 源集的 release 隔离** | 未反编译 APK 验证 | `README.md:270` 已声明；建议在发布前用 `apkanalyzer` 抽查 |

---

## 8. 一句话总结

**CarroMed 的代码质量与领域建模水平超出同类项目一个层级，三条核心不变量有结构性保证，本次审阅未发现 P0 缺陷；真正的短板集中在"产品化收尾"（图标、深色模式、隐私访问控制）与"行为学兜底"（重复提醒、补货主动通知）两处，前者半天可清，后者是兑现"提醒可靠"承诺的关键补充。**

---

*报告生成：2026-09-30（GMT+8）· 审阅基线 `master` @ `ed1d380`*
