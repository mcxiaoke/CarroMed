# CarroMed

> **纯本地离线 · 免登录 · 零广告 · 无网络权限**的个人用药提醒与库存管理 Android 应用。
>
> 到点一定响，记录永不丢，数据 100% 留在本机，随时完整导出带走。

CarroMed 面向需要长期或多药管理服药的个人用户（慢病患者、老人及其代管家属），单设备单用户。
核心承诺只有三条，其余设计都为它们让路：

1. **提醒可靠** —— 到点一定响，不静默漏提醒；
2. **记录真实** —— 吃过的药永不丢失、永不串改，改计划不冲掉历史；
3. **数据自主** —— 无云依赖、无账号体系，CSV / JSON 随时带走。

---

## 目录

- [功能特性](#功能特性)
- [界面一览](#界面一览)
- [技术栈](#技术栈)
- [架构要点](#架构要点)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [数据与隐私](#数据与隐私)
- [文档索引](#文档索引)
- [当前状态与已知限制](#当前状态与已知限制)

---

## 功能特性

### 今日清单

- 7 天日期条横向选择，回看任意一天的打卡全貌
- **待服药** / **今日已服** / **已跳过** 三区分离，各区独立计数
- 点按打勾即确认服药；**长按**卡片弹出「推迟 / 跳过」操作
- 已推迟、已逾期的槽位带独立徽标，不与正常待服混淆
- 低库存告警横幅：低于警戒线时置顶提示，可一键跳去补药
- 首启空态引导卡（与「有药品但今日无排班」严格区分）
- 右下角「手动补录」FAB，漏打卡随时补

### 药品管理（药箱）

- 药品卡片按「在服 / 已停药归档」分区
- 搜索：药名 / 别名 / 类别三字段模糊匹配
- 排序：名称、剩余量、依从率等维度
- 药品档案：名称、别名、分类、剂型、**单位**、颜色标识、默认剂量、
  医嘱说明、**注意事项标签**、通知简述、有效期至、是否为重要提醒

### 提醒计划

| 频次 | 说明 |
| :--- | :--- |
| 每天 | 每天固定 N 个时点 |
| 隔 N 天 | 隔日 / 每隔 3 天，自动规避跨月与闰年 2 月 29 日 |
| 每周 | 每周指定几天（周一 / 三 / 五…） |
| 周期 | 吃 N 天停 M 天 |
| 按需 (PRN) | 不设定时闹钟，仅在今日清单或手动补录中打卡 |

- 每个时点可独立设置剂量与**服药与用餐的关系**（餐前 / 随餐 / 餐后 / 空腹 / 睡前）
- 「每天 N 次」一键铺排 07:00 → 21:00 均匀分布
- 疗程三选项：**长期无限期** / 指定结束日期 / 「设置结束日期」开关
  （**永不强制填结束日期**）
- 提醒行为：是否重要提醒、推迟分钟数、提前多少分钟提醒、按药品单独覆盖全局设置

### 服药打卡闭环

- 打卡即扣库存、写服药事实、记库存流水，三件事在一个事务里完成
- **撤销**：误触打卡后反向冲正库存流水，账目可追溯
- **跳过**：主动跳过不扣库存，独立成区，不污染依从率分子
- **推迟**：按全局或药品专属分钟数推迟，requestCode 与主闹钟错开互不干扰
- **手动补录**：可指定过去时间（此刻 / 1 小时前 / 昨天此时 快捷键），
  可选是否自动扣减库存，拒绝未来时间
- 改计划只重排**未来**槽位，历史事实绝不改写

### 库存账本

- **不可变流水台账**：`balanceAfter = balanceBefore + changeAmount`，
  禁止业务代码直接 `UPDATE current_stock`
- 四种流水：打卡自动扣减 / 购药入库 / 撤销冲正 / 盘点校准
- 三件套展示：**剩余量 → 预计可用天数 → 补货提醒**
- 低库存预警：阈值 + 预计可用天数双重触发，阈值可按药品配置
- 有效期与临期提醒（国内特色，海外同类 App 少见）
- 库存追踪是**可选开关**，关闭后完全跳过扣减与预警
- 库存整体可关闭，库存账目可导出 CSV

### 进展与统计

- **进展追踪**：7 天打卡矩阵（区分全部完成 / 部分 / 跳过 / 逾期漏服 / 未来 / 未排班）
  + 今日服药流水
- **统计报表**：7 天 / 30 天 / 1 年周期切换、累计用量、服药依从率、
  依从率构成（已服 / 主动跳过 / 逾期漏服）、各药品累计消耗排行
- 统计口径：依从率基于 `dose_slots`（按 `scheduled_date` 归属，
  分母 = 已服 + 跳过 + 漏服，**待服不进分母**）；消耗量基于 `dose_records`
  （按 `actual_ts` 归属）
- 分母为 0 时 UI 显示「—」而非 100%，不制造虚假满分

### 提醒内核与保活

- `AlarmManager` 精确闹钟，未授权时自动降级 `setAlarmClock`
- 开机 / 换包 / 改时 / 改时区 全部触发全量闹钟对账（`AlarmReconciler`）
- 通知栏快捷操作：已吃 / 推迟 / 跳过
- **夜间免打扰**用独立通知渠道实现（Android 渠道重要性不可事后修改），
  标记为「重要提醒」的药品可穿透静音
- 系统特权自检页：精确闹钟、通知权限、电池优化白名单、锁屏与自启动逐项检测

### 数据导出与备份

- 服药明细 CSV（UTF-8 BOM，Excel / WPS 直接打开）
- 库存流水 CSV
- 全量 JSON 备份（带格式版本号）
- 覆盖式恢复：从备份还原
- 通过系统分享面板导出，文件不经过任何中间服务器

### 信息架构：三分离

参照 MyTherapy 基准并更进一步 —— **药品信息 / 提醒设置 / 库存**是三件互相独立的事，
各有各的编辑界面：

| 分节 | 落地页面 |
| :--- | :--- |
| 💊 药品信息 | `AddEditMedicationScreen`（`AddEditMode.INFO_ONLY`，**复用添加界面**） |
| ⏰ 提醒设置 | `ReminderSettingsScreen` |
| 📦 库存管理 | `InventoryScreen` |

- 「添加药品」把三者合在一次低门槛录入里（`AddEditMode.FULL`）；
- 「编辑」则必须分别进入对应子页，避免"想改个药名却要滚过一整套闹钟计划"。

---

## 界面一览

共 13 个全屏页面，4 个底部导航一级页 + 9 个二级全屏页：

| # | 页面 | 路由 |
| --- | :--- | :--- |
| 1 | 今日清单 | `today` |
| 2 | 我的药箱 | `cabinet` |
| 3 | 进展追踪 | `progress` |
| 4 | 统计报表 | `stats` |
| 5 | 添加药品 | `med_edit` |
| 6 | 药品详情（三段式） | `med_detail/{medId}` |
| 7 | 编辑药品信息 | `med_edit?medId={medId}` |
| 8 | 提醒设置 | `med_reminder/{medId}` |
| 9 | 库存管理 | `med_inventory/{medId}` |
| 10 | 补药入库 | `refill/{medId}` |
| 11 | 手动补录服药 | `manual_dose` |
| 12 | 系统设置 | `settings` |
| 13 | 系统特权自检与保活指引 | `permission_check` |

一键跑完全部页面并截图到 `temp/appscreenshots/`：

```powershell
python tools/app_screenshots.py --clear --seed
```

详见 [AGENTS.md](AGENTS.md#ui-截图验证与调优)。

---

## 技术栈

| 项 | 选型 |
| :--- | :--- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3（Compose BOM 2024.10.01） |
| 导航 | Navigation Compose 2.8.3 |
| 持久化 | Room 2.6.1（7 张表，schema v2） |
| 异步 | Coroutines + Flow |
| 调度 | AlarmManager（`setExactAndAllowWhileIdle` / `setAlarmClock`） |
| 构建 | AGP 8.11.1，Gradle Wrapper |
| 测试 | JUnit4 + Robolectric 4.14.1 + Truth + room-testing（**384 项，全绿**） |
| minSdk / targetSdk | 26 (Android 8.0) / 35 |

**无任何第三方运行时依赖**，无网络库、无图片加载库、无 DI 框架。

---

## 架构要点

```
ui/          Compose 界面 + ViewModel（StateFlow 单一状态源）
  screen/    today cabinet progress stats detail edit reminder inventory manual refill settings
  component/ DoseActionBottomSheet HomeTabHeader
  navigation/ Screen(路由) + AppNavigation(NavHost)
core/
  domain/
    engine/  SlotProjectionEngine  规则 → 槽位的前向投影（纯函数）
             StatsEngine            依从率、可用天数、日状态聚合
    service/ DoseTrackingService   打卡/撤销/跳过/补录/补货/盘点/重排
             MedicationAdminService 药品档案与提醒策略的写入入口
  data/      Room 实体 / DAO / 迁移 / CSV·JSON 导入导出
  alarm/     AlarmScheduler · AlarmReceiver · AlarmReconciler · ReminderSettings
```

四条贯穿全局的设计法则：

1. **三层时序解耦** —— 规则（`schedule_policies`）→ 槽位（`dose_slots`，可再生缓存）
   → 事实（`dose_records`，不可变真相）。**严禁把槽位当真相使用。**
2. **局部 UPDATE 而非整行覆盖** —— 编辑药品走 `MedicationDao.updateProfile` 等
   局部更新；用 `insert(REPLACE)` 会静默抹掉未在表单暴露的字段。
3. **库存事件溯源** —— 库存只通过流水变动，任何时候
   `SUM(change_amount) == currentStock`。
4. **迁移失败必须显式崩溃** —— **刻意不启用** `fallbackToDestructiveMigration`。
   服药历史是不可再生资产，静默清库不可接受。

---

## 快速开始

### 环境要求

- JDK 17
- Android SDK Platform 35 + Build-Tools
- `local.properties` 中配置 `sdk.dir`

### 构建

```powershell
# Debug
./gradlew assembleDebug

# Release（需要 key.properties 配置签名，未配置则产出未签名包）
./gradlew assembleRelease
```

APK 产出在 `app/build/outputs/apk/`。

### 安装与运行

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell pm grant com.mcxiaoke.carromed android.permission.POST_NOTIFICATIONS
adb shell am start -n com.mcxiaoke.carromed/.MainActivity
```

### 测试

```powershell
./gradlew testDebugUnitTest
```

384 项单元测试，全部基于**真实的内存 SQLite 数据库**（Robolectric），
不使用 mock 数据源 —— 领域层的数学守恒只有跑真库才验得出来。

---

## 项目结构

```
CarroMed/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/mcxiaoke/carromed/
│       │   ├── MainActivity.kt
│       │   ├── core/{alarm,data,domain}/
│       │   └── ui/{component,navigation,screen,theme}/
│       ├── debug/kotlin/.../DevSampleDataSeeder.kt   # 仅 debug：演示数据播种
│       ├── debug/kotlin/.../DevDataReceiver.kt       # 仅 debug：adb 遥控播种/清库
│       ├── debug/AndroidManifest.xml                 # 仅 debug：注册上面那个 receiver
│       └── test/kotlin/.../                          # 384 项单元测试
├── tools/app_screenshots.py    # 全屏页面自动走查截图
├── docs/                       # 设计与变更文档
├── temp/                       # 临时产物（git 忽略）：截图、数据库快照、脚本
├── build.gradle.kts
└── gradle.properties
```

> `app/src/debug` 里的播种器与遥控广播**只存在于 debug 源集，release 包内完全不存在这段代码**。
> 首启必须是干净空库 —— 凭空出现的服药记录会直接污染依从率、库存与统计报表。

---

## 数据与隐私

- **不申请 `INTERNET` 权限**，物理断网，无任何网络请求
- 不申请 `INTERNET` 意味着：无法上传、无法埋点、无法云备份
- `allowBackup=false`，显式关闭 Android Auto Backup
  （默认会把用药数据传给 Google 云，与产品承诺冲突）
- 单用户单设备，无账号、无设备标识
- 数据全部落在应用私有目录的 `carromed.db`，卸载即彻底清除

---

## 文档索引

| 文档 | 内容 |
| :--- | :--- |
| [docs/FINAL-PRODUCT.md](docs/FINAL-PRODUCT.md) | 产品定位、范围决策表（D-1 ~ D-16）、术语表 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 技术架构、数据库设计、六大边缘场景行为定义 |
| [docs/FINAL-ARCHITECTURE.md](docs/FINAL-ARCHITECTURE.md) | 架构终稿 |
| [docs/UI_DESIGN.md](docs/UI_DESIGN.md) | UI 设计规范 |
| [docs/FINAL-UI.md](docs/FINAL-UI.md) | UI 终稿 |
| [docs/APP_DESIGN_SPEC.md](docs/APP_DESIGN_SPEC.md) | App 设计规格 |
| [docs/PLAN-REVIEW-20260927-v2r.md](docs/PLAN-REVIEW-20260927-v2r.md) | 2026-09-27 全面代码与 UI/UX 审查报告（12 P0 缺陷 + 分步计划 + 实施结果） |
| [docs/CHANGES-20260927.md](docs/CHANGES-20260927.md) | 变更记录 |
| [AGENTS.md](AGENTS.md) | 开发 / 测试 / UI 走查流程 |

---

## 当前状态与已知限制

### 已完成

- 13 个全屏页面全部实现并实测通过
- 12 个 P0 缺陷全部修复（详见审查报告）
- 384 项单元测试全绿；`assembleDebug` / `compileReleaseKotlin` / `testDebugUnitTest` 通过
- 模拟器（Android 15 / API 35）逐页实测通过

### 明确未做

| 项 | 优先级 | 原因 |
| :--- | :---: | :--- |
| 重复提醒直到确认 | P1 | 需新增重复闹钟调度与会话状态 |
| 节假日 / 自定义例外日历 | P1 | 需内置节假日数据源 |
| 扫码 / OCR 录入药品 | P2 | 需引入相机与识别依赖 |
| 家人代管 / 漏服推送 | P2 | 与"纯本地零网络"定位冲突 |
| 桌面 Widget | P2 | 独立子系统 |
| ±N 分钟准时率口径 | P2 | 需先写《统计口径规范》再动统计代码 |
| 补药 / 服药记录的历史回改 | P2 | 当前仅支持撤销打卡与补录 |

### 已知待清理

- `app/src/main/.../core/data/SampleDataSeeder.kt` 是 `DevSampleDataSeeder` 的旧副本，
  已无任何引用（冷启动路径与调试路径都不再使用），但仍会打进 release 包。
  删除前需确认无外部依赖。

---

**内部项目。** 无网络权限，所有数据仅存于本机。
