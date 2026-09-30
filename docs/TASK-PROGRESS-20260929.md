# TASK-PROGRESS-20260929 —— 双任务进度跟踪

> 用户委派的两项任务，按顺序自主实施，每完成一步回来勾一次。
> 方案文档：任务1 → `docs/PLAN-LOGGING-20260929.md`；任务2 → `docs/PLAN-UI-TEST-20260929.md`

---

## 任务 1：日志系统（PLAN-LOGGING-20260929.md §5）—— ✅ 全部完成

- [x] **S1 门面**：`core/domain/AppLog.kt` + `core/data/LogFileSink.kt`
      + `core/alarm/AndroidLogging.kt`（LogcatSink/崩溃钩子/AppLogging.install）
      + CarroMedApp 接线 + DevDataReceiver `CRASH` 广播 + 14 条纯 JVM 单测
      ✅ 全量单测绿；变异验证 2 处（环形驱逐→红；清理判据放宽→首次存活，
      加强测试判据后→红）；模拟器：日志落盘 ✓、崩溃演练 ✓、logcat ✓
- [x] **S2 alarm 包**：裸 `Log` → `AppLog`；`AlarmScheduler.schedule/cancelAll`
      成功路径与档位、`DoseActionReceiver` 成功动作；tag 统一
      ✅ 实测：`scheduled uri=... precision=EXACT` 档位可见
- [x] **S3 记账服务**：`DoseTrackingService` 事务出入口 + 幂等 false 分支 +
      `appendLedger` 审计线；`MedicationAdminService` 写路径
      ✅ 实测：takeDose→ledger(TAKEN_DEDUCT -1000)→undo→REVERT_ROLLBACK(+1000)，
      守恒在日志可见
- [x] **S4 UI/Data**：ViewModel catch 补 `w`（4 处）；`DataExporter` 备份/恢复/
      快照/撤闹钟留痕；设置页「导出诊断日志」（Screen 层直连，不碰构造器）
      ✅ 实测：分享面板弹出 `CarroMed_诊断日志_*.txt`
- [x] **S5 收口**：`clean assembleDebug testDebugUnitTest` 全绿 +
      `compileReleaseKotlin` 通过 + UI 走查 37 张全通过 + 设置页亲眼看图 +
      `CHANGES-20260929.md` 摘要 + AGENTS.md（§1 命令速查 + §8 坑表两行）

## 任务 2：UI 测试系统（PLAN-UI-TEST-20260929.md §5）

- [x] **阶段 1**：TestTags.kt（14 个 tag + 3 个参数化工厂）+ `testTagsAsResourceId`
      (仅 debug, OptIn) + `SmokeNavigationTest` 三测（四 tab / 全路由 10 页 /
      打卡→撤销守恒）+ 变异验证（StatsViewModel 加默认参数 → allRoutes 红
      `Cannot create an instance... AndroidViewModelFactory`，还原后 3/3 绿）
      ✅ connectedDebugAndroidTest 全绿；AGENTS.md §9 已追加该门槛
      📝 实战踩坑（已写进测试注释）：FAB 合并语义后 Text 不可见要用 desc、
      SectionCard 标题带 "N. " 前缀用 substring、记录详情撤销后自动弹回不能再
      pressBack、refill 返回需 3 跳用 pressBackUntilTag 兜底、
      LazyColumn 未滚到的项不在语义树（clickTextScrolling 上滑重试）
- [x] **阶段 2**：Roborazzi 1.40.1（⚠️ 1.72.0 是 Kotlin 2.3 元数据，本项目
      Kotlin 2.0.21 拒绝编译——插件版本上限定死 ≤2.1，注释已写）+ 8 张基线
      （今日有数据/空态、药箱、统计、设置、药品详情 + HomeTabHeader×2）
      + record/verify 流程 + 变异验证（Header 64→72dp → 4 张快照红，还原绿）
      📝 关键坑（均已写进代码注释）：AppDatabase 单例跨 Robolectric 测试方法
      失效（新增 `AppDatabase.resetForTest()`，与 CurrentDateHolder.resetForTest
      同模式）；快照测试改为"真 VM+真库手工组装"而非启动 Activity（clearAllTables
      与活动对账协程在同一写连接上踩事务）；Robolectric 时钟跟随真实日期且
      SystemClock.setCurrentTimeMillis 无效——用种子器 today 参数 +
      `CurrentDateHolder.setTodayForTest`（M3-2 测试钩子）钉死 2026-09-30
- [x] **阶段 3**：Maestro 2.11.0（scoop 安装，**Windows CLI 可用**——方案最大
      不确定项解除）+ `.maestro/smoke-seeded.yaml` 26 步全绿（4 tab 断言 +
      药品详情 testTag 定位 + 打卡→撤销闭环）+ `.maestro/run-smoke.ps1` 驱动
      （SEED 广播播种在 flow 外做）
      📝 踩坑：`pressBack` 不存在用 `back`；assertVisible 全等匹配，计数标题
      用 `'待服药 \(\d+\)'` 单引号正则（YAML 双引号转义 `\(` 直接 parse error）；
      ⚠️ 曾用 PowerShell Set-Content 改 yaml 写入 BOM+双倍反斜杠——红线再犯，
      已用 Write 工具重写修复
- [ ] **阶段 4**：两周试点观察期后决定是否迁移走查（P3 决策点，按方案保留
      Python 脚本为现行门槛；试点结论记回 PLAN-UI-TEST-20260929.md §4.4）

---

## 实施记录（倒序追加）

- 2026-09-29 23:30 任务1（日志系统）S1–S5 全部完成，收口自检通过。
- 2026-09-29 23:30 开始任务2（UI 测试）阶段 1。
