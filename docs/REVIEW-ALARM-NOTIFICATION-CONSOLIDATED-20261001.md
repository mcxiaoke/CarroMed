# CarroMed 通知提醒闹钟系统架构复核综合裁定与实施方案

> **文档时间**：2026-10-01 19:25 (GMT+8)  
> **文档性质**：复核争议深度裁决 · 去伪存真终极定案 · 工业级落地实施方案  
> **输入依据**：
> - 初始审查报告：[`docs/REVIEW-ALARM-NOTIFICATION-AUDIT-20261001.md`](REVIEW-ALARM-NOTIFICATION-AUDIT-20261001.md)
> - 独立复核报告：[`docs/REVIEW-OF-REVIEW-ALARM-AUDIT-20261001-ocsbf.md`](REVIEW-OF-REVIEW-ALARM-AUDIT-20261001-ocsbf.md)
> - 守门测试与源码基准：`AlarmPrecisionAndGraceTest.kt`、`AlarmReconciler.kt`、`AlarmReceiver.kt`、`ReconcileWorker.kt`、`Notifications.kt`、`AndroidManifest.xml`

---

## 〇、复核裁决与事实核验总结

经过对 [`docs/REVIEW-OF-REVIEW-ALARM-AUDIT-20261001-ocsbf.md`](REVIEW-OF-REVIEW-ALARM-AUDIT-20261001-ocsbf.md)（以下简称《复核报告》）的逐行对齐，以及回到 Android 源码、AOSP 契约与已有守门测试的交叉验证，**完全接受并采纳《复核报告》所指出的全部实质性纠偏与重大新发现**：

1. **去伪存真，纠正初始报告的 3 处硬伤**：
   - **纠正 P0-3 夸大的事实描述**：初始报告称划掉通知会以“30 秒死循环补响数十次”，经实证，响铃后的那一步托盘上有刚弹出的通知，`isDoseNotificationShown` 判定为 true，**正常链路不会立即触发 30 秒连锁**；真实驱动节奏为 **15 分钟一次（依赖周期 Worker），2 小时内约 8 次**。虽然仍是真实骚扰，但定级由 P0 降为 **P1**。
   - **驳回 P2-2（推迟连击叠乘）**：核对 `DoseTrackingService.kt:292` 代码，推迟时刻计算为 `System.currentTimeMillis() + minutes * 60_000L`（以当前绝对时刻为基准计算），连击只会**微幅缩短**至最新的 30 分钟，绝不存在 30 → 60 分钟的累加叠乘，且首次点击后通知已立即消除，**此条予以驳回**。
   - **驳回 P1-6（99999 碰撞与溢出）**：按 15 槽位/天推算，主键自增至 99999 需约 18 年，`toInt()` 溢出需 39 万年，属于理论不可达的低概率事件，予以驳回；改由复核报告提出的 **O-4（通知 ID 未与主键解耦的架构一致性缺口）** 接替。

2. **恪守工程红线，废除可能破坏守门测试与项目规约的危险整改**：
   - **废除 P0-2 直接改 `currentPrecision` 枚举的修法**：仓库中的 `AlarmPrecisionAndGraceTest.kt:90-95, 121-132` 明确将 `ALARM_CLOCK` 档位作为断言守门。直接改枚举会导致两个测试变红并制造死代码；改为**修正文案**，自检页透传实际降级警告，不打破测试契约。
   - **废除 P0-3 使用 `ALTER TABLE` 迁移 SQL 的修法**：严守 `AGENTS.md`「不写迁移代码，改 schema 直接删库重装」红线；同时指出重构补响判据必须重写 `:209-237` 的守门测试，并严格按 `Kind` 隔离粒度（防止划掉 ADVANCE 连带吞掉 MAIN 准点补响）。

3. **收录复核报告补充的极其致命的真实隐患**：
   - **收录 O-1 为最高优先级 P0 缺陷**：`rescheduleAll` 缺乏并发互斥保护，而一次性 Worker 使用 `ExistingWorkPolicy.REPLACE` 会强行打断正在执行的对账；若在“删完失效槽位”与“排上新闹钟”之间被打断，将留下**有槽位无闹钟的到点不响空窗**！
   - **收录 O-3（与 P1-3 合并升为 P0）**：AlarmManager 14 天预排超限触发系统拒绝异常时，被 `AlarmReconciler.kt:370-378` 的 `runCatching` 静默吞掉，表现为**静默不排闹钟，对账器却自报成功**。
   - **收录 O-9（与 P1-2 合并）**：指出 Android `NotificationChannel` 属性一经创建即不可修改的物理事实，整改音频流必须通过更换 Channel ID 完成存量升级。

---

## 一、终极缺陷与需求定级清单 (Consolidated Findings)

经复核重构后，全系统共有 **3 项 P0 严重级缺陷、7 项 P1 架构与体验级缺陷、7 项 P2 边缘健壮性缺陷**，以及 **2 项明确驳回项**：

### 1.1 P0 严重级（直接击穿“到点一定响”核心承诺）

| 编号 | 缺陷名称 | 根因与真实后果 | 正确整改方向 |
| :--- | :--- | :--- | :--- |
| **P0-1** | **API 31/32 精确闹钟权限断层** | `AndroidManifest.xml` 仅声明 API 33 的 `USE_EXACT_ALARM`，在 Android 12/12L 设备上系统无法识别，精确闹钟全部被拒，降级为 +1h 延迟。 | 补全 `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />`。 |
| **P0-2** | **`rescheduleAll` 无并发保护与 `REPLACE` 打断空窗 (原 O-1)** | 4 个并发入口无锁；一次性 Worker 的 `REPLACE` 策略可打断正在执行的实例。若在“删完失效槽位”后、“排上新闹钟”前被打断，导致槽位存在却无闹钟（到点不响）。 | 为 `rescheduleAll` 加 `Mutex` 互斥保护，或改一次性任务入队策略为 `KEEP`，确保排期不可被中途腰斩。 |
| **P0-3** | **Alarm 超限抛异常被静默吞掉 (原 P1-3 + O-3)** | 14 天全量预排在多药多时点下可超 500 个上限；AMS 抛出 `IllegalStateException` 被 `runCatching` 吞成 ERROR 日志，对账器谎报成功，后续闹钟静默不响。 | 统计排期失败数，失败时显式报警；长远推行 48 小时滚动窗口 (Rolling Horizon) 削减并发量。 |

---

### 1.2 P1 架构与体验级（偏离主流标准与工程隐患）

| 编号 | 缺陷名称 | 根因与真实后果 | 正确整改方向 |
| :--- | :--- | :--- | :--- |
| **P1-1** | **划掉通知触发 15 分钟周期骚扰 (原 P0-3 降级)** | 误把“托盘无通知”等同于“没提醒过”。用户主动划掉后，15 分钟周期对账再次判定为漏服，排 30 秒补响闹钟，2 小时内骚扰约 8 次。 | 增加持久化 `last_notified_ts`（按 Kind 粒度区分）；改 Entity 删库重装；重写 `:209-237` 守门测试。 |
| **P1-2** | **`AlarmReceiver` 灭屏缺少 WakeLock (原 P0-4)** | `RTC_WAKEUP` 在主线程返回后过早休眠，协程在 Doze 下被 cgroup 挂起冻结，导致“灭屏不响、亮屏才弹”。工程声明了权限但零使用。 | 获取 10 秒超时 `PARTIAL_WAKE_LOCK`；保留 action 与 key 的校验守卫；覆盖另两处 Receiver。 |
| **P1-3** | **缺失锁屏全屏提醒强交互 (原 P1-1)** | 仅依赖普通 Heads-up 横幅，易被系统折叠或用户误划；关键处方药缺少锁屏直接亮屏的大按钮交互（已记录的 TODO）。 | 新增 `AlarmAlertActivity`，通过 `setFullScreenIntent` 唤起；适配 Android 14 `canUseFullScreenIntent()`。 |
| **P1-4** | **通知渠道音频流缺失与存量不可变 (原 P1-2 + O-9)** | 默认通知音受手机通知静音影响，无专用警报音频流；且已创建的渠道属性不可修改，直接改代码对存量用户无效。 | 升级为 `dose_reminder_v2` / `critical_v2` 渠道；配置 `AudioAttributes.USAGE_ALARM`；对重要药品独立分流。 |
| **P1-5** | **厂商自启动引导缺乏直达与兜底 (原 P1-5)** | 自检页第四张卡片 `action = null`，用户无法在华为/小米/OPPO/vivo 的层层设置中找到自启与锁屏显示。 | 封装 `VendorIntentHelper` 跳转矩阵；提供应用详情页兜底按钮。 |
| **P1-6** | **通知 ID 未与实体主键解耦 (原 O-4)** | `notify` 与 `cancel` 仍用 `slot.id.toInt()`；备份恢复后旧 ID 与新 ID 错位，撤销时误杀他人通知（内容寻址遗留收尾缺口）。 | 通知 ID 采用业务键哈希或独立逻辑 ID 映射，与主键解耦。 |
| **P1-7** | **自检页未纳入 Maestro UI 走查 (原 O-7)** | `PermissionCheckScreen` 作为四大权限的自检入口，从未登记在 `.maestro/` 走查脚本中，违反项目 step 4 看图规约。 | 补充 `.maestro/smoke-permissions.yaml` 脚本并运行截图审查。 |

---

### 1.3 P2 边缘健壮性级（细节打磨与极端分支）

| 编号 | 缺陷名称 | 处置方案 |
| :--- | :--- | :--- |
| **P2-1** | **`currentPrecision` 文案与真实执行不符 (原 P0-2 降级)** | 改 `strings_alarm.xml:5` 文案，注明系统拒绝时退化为不精确；自检页消费实际排期生效日志，不破坏守门测试。 |
| **P2-2** | **多槽位补响未错开 (原 P1-4 降级)** | 补响排期循环引入自增 offset：`now + GRACE_DELAY + (index * 5000L)`，防止并发糊屏与声音重叠。 |
| **P2-3** | **系统改时/改时区日期刷新断层 (原 P2-1)** | `BootReceiver` 在收到 `TIME_CHANGED` / `TIMEZONE_CHANGED` 时，显式调用 `CurrentDateHolder.refresh()`。 |
| **P2-4** | **后台 Toast 在 Android 12+ 潜在受限 (原 P2-3)** | 保持简单文本 Toast，同时确保动作执行时通过通知栏更新或前台状态体现。 |
| **P2-5** | **锁屏通知未设可见性 (原 P2-4)** | 通知 Builder 显式增加 `.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)`。 |
| **P2-6** | **通知完全关闭时仍触发无意义 Alarm (原 O-2)** | 当 `areNotificationsReachable == false` 时，在自检页强提示，并在对账时评估跳过空唤醒。 |
| **P2-7** | **渠道粒度与取消异常处置 (原 O-5, O-8)** | `areNotificationsReachable` 收窄至目标渠道；Worker 的 `catch` 块重新抛出 `CancellationException`。 |

---

### 1.4 明确驳回项

1. **驳回 P2-2（推迟按钮连击叠乘）**：`snoozeDose` 是 `now + N` 而非 `existing + N`，机制上不会叠加到 60/90 分钟，且首次点击已消除通知，事实不成立。
2. **驳回 P1-6（`slot.id.toInt()` 溢出与 99999 碰撞）**：18 年和 39 万年的发生周期属于理论不可达，定级失真，由 O-4（架构一致性缺口）接替。

---

## 二、关键技术方案的深度论证与落地设计

### 2.1 方案论证 A：针对 P0-2 (O-1) 对账无单飞与取消空窗的防守设计

#### 问题机理
`rescheduleAll` 是一个耗时 0.4~1.5 秒的组合流程：
`① 结算 EXPIRED → ② 重投影删失效槽位 → ③ 快照清理孤儿闹钟 → ④ 注册未来闹钟`。
当多个药在 08:00 同时响铃时，会连续触发多次 `ReconcileWorker.enqueueOneShot(REPLACE)`。`REPLACE` 策略会取消当前正在执行的 Worker 实例。若打断发生在 ② 之后、④ 之前，库里的槽位已被删改，但闹钟尚未在 AlarmManager 中注册，导致到点不响。

#### 解决方案决策
- **选型**：在 `AlarmReconciler` 内部引入单例级 `kotlinx.coroutines.sync.Mutex`。
- **实现细节**：
  ```kotlin
  object AlarmReconciler {
      private val reconcileMutex = Mutex()

      suspend fun rescheduleAll(context: Context, db: AppDatabase, presnap: Set<AlarmIdentity> = emptySet()) =
          withContext(Dispatchers.IO) {
              reconcileMutex.withLock {
                  // 原有对账四步流程...
              }
          }
  }
  ```
- **配套**：将 `ReconcileWorker.enqueueOneShot` 的策略调整为 `ExistingWorkPolicy.KEEP`。
  - *为什么是 KEEP*：在有 `reconcileMutex` 保护的前提下，若前一个任务正在运行，后一个任务用 `KEEP` 保证当前运行中的任务不被杀死。对账具有前向 14 天幂等覆盖性，当前正在跑的对账足以覆盖最新状态，避免了 `REPLACE` 自毁重建的竞态风险。

---

### 2.2 方案论证 B：针对 P1-1 (原 P0-3) 补响骚扰的优雅修法

#### 核心取舍
复核报告已确证：**必须持久化记录一次提醒事实，才能在逻辑上区分“闹钟丢了”与“用户看后划掉”**。
但必须规避初始方案的三个漏洞：
1. **按 Kind 细分粒度**：不能只记一个通用的时间戳。若用户在 07:50 划掉了 ADVANCE（提前提醒），不能把 08:00 的 MAIN（准点提醒）当成已提醒而吞掉！
2. **遵从项目规范**：直接在 `DoseSlotEntity` 增加字段，改 Room 实体定义，**删库重装，不写任何 `ALTER TABLE` 迁移代码**。
3. **维护测试闭环**：`AlarmPrecisionAndGraceTest.kt:209-237` 原本守的是旧的“划掉后补响”语义。改动后，必须同步重写该测试，并在测试用例中明确体现新的语义契约（用户划掉后不再 15 分钟连环催，转入静默待办）。

#### 实体与判据改动
- **实体字段**：
  ```kotlin
  @Entity(tableName = "dose_slots", ...)
  data class DoseSlotEntity(
      // ...
      @ColumnInfo(name = "last_main_notified_ts")
      val lastMainNotifiedTs: Long? = null,
      @ColumnInfo(name = "last_snooze_notified_ts")
      val lastSnoozeNotifiedTs: Long? = null
  )
  ```
- **补响判据**：
  ```kotlin
  // 主闹钟补响：仅当主计划时间已过、仍在补响窗口内、且“从未针对 MAIN 响过铃”时才补响
  val mainMissedWithoutNotify = slot.lastMainNotifiedTs == null && mainAt in catchupFloor..now
  if (mainMissedWithoutNotify && Notifications.areNotificationsReachable(context)) {
      AlarmScheduler.schedule(context, slot, now + GRACE_CATCHUP_DELAY_MS + catchupOffset, Kind.MAIN)
      catchupOffset += 5000L
  }
  ```

---

### 2.3 方案论证 C：针对 P1-4 (原 P1-2 + O-9) 通知渠道音频流与存量兼容

#### 物理限制
Android 8.0+ 明确规定：`NotificationChannel` 的 `sound`、`vibration`、`importance` 在创建后不可通过代码修改。现有 `dose_reminder` 渠道已在用户设备上注册为系统默认通知音。

#### 解决方案
1. **渠道升级 (Versioned Channel ID)**：
   - 弃用旧的 `dose_reminder`；
   - 引入 `CHANNEL_DOSE_REMINDER_V2 = "dose_reminder_v2"`；
   - 引入专用重要药品渠道 `CHANNEL_DOSE_REMINDER_CRITICAL = "dose_reminder_critical"`；
2. **音频属性配置**：
   ```kotlin
   val audioAttributes = AudioAttributes.Builder()
       .setUsage(AudioAttributes.USAGE_ALARM)
       .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
       .build()

   val loudChannel = NotificationChannel(
       CHANNEL_DOSE_REMINDER_V2,
       context.getString(R.string.notif_channel_name),
       NotificationManager.IMPORTANCE_HIGH
   ).apply {
       setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audioAttributes)
       enableVibration(true)
       vibrationPattern = longArrayOf(0, 500, 200, 500)
       setShowBadge(true)
   }
   ```
3. **老渠道清理**：在 `ensureChannel` 中调用 `nm.deleteNotificationChannel("dose_reminder")`，静默删除过时的老渠道。

---

### 2.4 方案论证 D：针对 P1-2 (原 P0-4) WakeLock 的严密实现

#### 代码形态修正
在 `AlarmReceiver.kt` 中引入 WakeLock，但**绝不能丢失原有的安全性守卫**：
```kotlin
override fun onReceive(context: Context, intent: Intent) {
    // 1. 严格保留 action 过滤
    if (intent.action != AlarmScheduler.ACTION_DOSE_ALARM) return

    // 2. 严格保留业务键有效性校验
    val key = parseAlarmKey(intent.data) ?: run {
        AppLog.w("AlarmReceiver", "unparseable alarm uri=${intent.data}")
        return
    }

    val pm = context.getSystemService(PowerManager::class.java)
    val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carromed:alarm_receiver")
    wakeLock?.acquire(10_000L) // 10秒超时保护，防止系统异常无法释放

    val result = goAsync()
    val appContext = context.applicationContext

    CoroutineScope(Dispatchers.IO).launch {
        try {
            // 查库、发通知、触发续期...
        } catch (t: Throwable) {
            AppLog.e("AlarmReceiver", "failed to show notification", t)
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } finally {
                result.finish()
            }
        }
    }
}
```

---

## 三、三阶段落地路线图

根据影响程度与验证依赖，将整改工作按以下三个批次推进：

```mermaid
graph TD
    subgraph 批次 1: 零争议与可靠性高危修复 (P0)
        B1_1[P0-1: Manifest 补全 SCHEDULE_EXACT_ALARM]
        B1_2[P0-2: rescheduleAll Mutex 保护与 KEEP 策略]
        B1_3[P0-3: Alarm 注册失败可见性告警]
        B1_4[P1-2: AlarmReceiver 引入 10s WakeLock]
        B1_5[P2-2: 补响自增 offset 错开]
        B1_6[P2-3: BootReceiver 广播改时联动 refresh]
        B1_7[P2-5: 显式设置 VISIBILITY_PUBLIC]
    end

    subgraph 批次 2: 渠道升级与补响判据重构 (P1)
        B2_1[P1-4: dose_reminder_v2 渠道与 USAGE_ALARM 绑定]
        B2_2[P1-1: last_main_notified_ts 判据 + 删库重装 + 重写测试]
        B2_3[P1-5: PermissionCheckScreen 厂商自启跳转 Intent]
        B2_4[P2-1: 修正 ALARM_CLOCK 文案说谎问题]
    end

    subgraph 批次 3: 架构演进与全屏提醒 (P1/P2)
        B3_1[P1-3: 48小时滚动窗口 Rolling Horizon 评估落地]
        B3_2[P1-3: AlarmAlertActivity 锁屏全屏提醒支持]
        B3_3[P1-6: 通知 ID 与实体主键解耦]
        B3_4[P1-7: Maestro 补齐 PermissionCheckScreen 走查看图]
    end

    B1_1 & B1_2 & B1_3 & B1_4 & B1_5 & B1_6 & B1_7 --> B2_1 & B2_2 & B2_3 & B2_4
    B2_1 & B2_2 & B2_3 & B2_4 --> B3_1 & B3_2 & B3_3 & B3_4
```

### 批次 1：零争议与可靠性高危修复 (立即实施，不破坏已有测试)
1. **`AndroidManifest.xml`**：补全 `SCHEDULE_EXACT_ALARM (maxSdkVersion="32")` (P0-1)；
2. **`AlarmReconciler.kt`**：引入 `reconcileMutex` 互斥体，防止并发与打断 (P0-2)；
3. **`AlarmReconciler.kt`**：统计 `schedule` 失败数，失败时打 WARN 日志，严禁吞成“成功” (P0-3)；
4. **`AlarmReceiver.kt`**：增加 10 秒超时 `PARTIAL_WAKE_LOCK`，确保 Doze 下协程安全执行 (P1-2)；
5. **`AlarmReconciler.kt`**：补响排期循环传入 `catchupOffset += 5000L`，错开并发毫秒 (P2-2)；
6. **`BootReceiver.kt`**：收到 `TIME_CHANGED` 时主动调用 `CurrentDateHolder.refresh()` (P2-3)；
7. **`Notifications.kt`**：通知构建器显式添加 `.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)` (P2-5)。

### 批次 2：渠道升级与补响判据重构 (需更新 Schema 并重写测试)
1. **通知渠道升级**：升级为 `dose_reminder_v2` / `critical_v2`，配置 `AudioAttributes.USAGE_ALARM`，删除老渠道 (P1-4)；
2. **补响判据重构**：在 `DoseSlotEntity` 增加 `lastMainNotifiedTs`，重构 `AlarmReconciler` 补响分支，并显式重写 `AlarmPrecisionAndGraceTest.kt:209-237` 守门测试 (P1-1)；
3. **厂商自启动跳转**：为 `PermissionCheckScreen.kt` 封装 `VendorIntentHelper`，增加直达自启和兜底按钮 (P1-5)；
4. **自检文案修正**：将 `alarm_precision_alarm_clock` 文案修正为包含降级预警说明，透传真实执行档位 (P2-1)。

### 批次 3：工业级架构演进 (对标主流 App)
1. **滚动窗口机制 (Rolling Horizon)**：将 AlarmManager 注册窗口由 14 天收敛为 48 小时，并写清依赖 Worker 的取舍约束 (P0-3)；
2. **锁屏强提醒 (Full-Screen Intent)**：实现 `AlarmAlertActivity`，在 Android 14 适配 `canUseFullScreenIntent()` (P1-3)；
3. **通知 ID 解耦**：重构通知 ID 映射，规避备份恢复主键交叠风险 (P1-6)；
4. **测试与走查基建**：在 `.maestro/` 中登记权限自检页走查，亲眼看图验收 (P1-7)。

---

## 四、执行准则与红线备忘

1. **测试先行与守门防护**：任何修改均须通过 `./gradlew testDebugUnitTest`；涉及修改守门测试断言的，必须在提交记录与变更文档中明确说明理由，严禁静默打破已有测试。
2. **无迁移代码**：批次 2 涉及 `dose_slots` 表结构变更，直接卸载重装，不写 `MIGRATION_*`。
3. **实机看图看流程**：涉及 `PermissionCheckScreen` 按钮与 UI 的变动，必须通过 Maestro 脚本在 `emulator-5554` 上完成走查并亲眼核验截图。
