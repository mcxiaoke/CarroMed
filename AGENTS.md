# AGENTS.md —— CarroMed 开发与测试流程

> 目标是让每一次改动都能被**编译 → 跑测试 → 模拟器实测 → 看图复核**四步闭环验证，
> 与本文件冲突时，以用户当场明确指令为准。
> 本项目内部开发中，还未公开发布，不需要任何迁移或兼容旧版本的代码。
---

## 0. 环境前置

| 项 | 值 | 备注 |
| :--- | :--- | :--- |
| JDK | 17 | `compileOptions` / `jvmTarget` 都是 17 |
| Android SDK | Platform 35 + Build-Tools | `local.properties` 里配 `sdk.dir` |
| 设备 | **模拟器 `emulator-5554`**（Android 15 / API 35 / 1080×2424） | 见下方红线 |
| Python | 3.10+ | 走查截图脚本用，无第三方库 |

### 红线

- **不要 `git commit` / `git push`**，除非用户明确要求。不要碰 `.git`。
- **不要用 PowerShell 重定向写二进制**（`adb ... > x.png` 会把 PNG 写坏）。
  走 `subprocess` 管道或 `cmd /c "adb ... > file"`。

---

## 1. 命令速查

```powershell
# ---- UI 全屏走查截图 ----
python tools\app_screenshots.py --clear --seed

# ---- 数据库直查 ----
cmd /c "adb -s emulator-5554 exec-out run-as com.mcxiaoke.carromed cat databases/carromed.db > temp\carromed.db"
python temp\dbdump.py
```

> 不授予 `POST_NOTIFICATIONS` 也能跑，只是看不到通知横幅，

---

## 2. 分层改动流程

先判断改动落在哪一层，再决定要连带验证什么。

| 改动位置 | 必须连带做的事 |
| :--- | :--- |
| `core/data/entity/*`（加字段） | ① `AppDatabase` 升 `version`；② 在 `MIGRATION_*` 里加 `ADD COLUMN`；③ `MigrationTest` 补一条；④ 检查所有 `insert(REPLACE)` 路径 |
| `core/data/dao/*` | 补 DAO 层聚合测试（`StatsDaoAggregationTest` 那种真库查询） |
| `core/domain/engine/*` | 补边界测试（跨月、闰年 2/29、0/0、负库存） |
| `core/domain/service/*` | 补事务守恒测试（`SUM(change_amount) == currentStock`） |
| `core/alarm/*` | 模拟器实测闹钟是否真响；`requestCode` 分段别撞车 |
| `ui/screen/*` | **必须跑一次 UI 走查截图并看图**（第 4 节） |
| `ui/navigation/*` | 新路由要同步登记到 `tools/app_screenshots.py` 的 `PROGRAM` |

### 三个最容易踩的架构坑

1. **整行覆盖毁数据**。更新药品档案必须走局部 `UPDATE`
   （`MedicationDao.updateProfile` / `updateReminderBehavior` / `updateStockTracking`）。
   写成 `insert(REPLACE)` 会静默抹掉表单没暴露的字段
   （`precautions` / `alias` / `isPaused` / `isArchived` / `createdAt`）。
   改表单时先确认字段是否齐全 —— 上一轮就是先把表单补齐，才敢开放局部更新。

2. **库存只走流水**。任何时候 `SUM(change_amount) == currentStock`。
   业务代码里出现 `UPDATE medications SET current_stock = ...` 就是 bug。

3. **改计划不冲历史**。`reconcileSchedule` 只重排未来槽位，
   已存在的 `dose_records` 一行都不能动。`saveReminderPolicy` 在缺省时沿用历史
   `startDate` / `endDate` 并递增 `version`，不要无脑把起始日设成今天
   （会导致隔日用药相位漂移）。

---

## 3. 单元测试流程

```powershell
./gradlew testDebugUnitTest
```

- **全绿是提交前的硬门槛，但门禁不是"项数"而是"不变量"。**
  当前 118 项，覆盖 12 条不变量（见 `docs/REMINDER-DOMAIN-REDESIGN.md` §4）。
  新增测试会推高项数，删掉无用测试会降低项数 —— 两者都不该改变门禁强度。
  改动不变量时，**先确认守它的那条测试还在**。
- 测试跑在 **Robolectric + 真实内存 SQLite** 上，不是 mock 数据源。
  领域层的数学守恒只有跑真库才验得出来，别为了图省事改成 mock。
- 测试跑在 **JUnit 5 Platform** 上（`useJUnitPlatform()`），两套并存：
  - **JUnit 4 + Robolectric**：需要 Android 环境的测试（DAO、服务、Room）
  - **jqwik `@Property`**：纯逻辑的属性化测试（槽位投影引擎的不变量 I5–I8）
  不要为了"统一风格"把 Robolectric 测试迁到 JUnit 5 —— 支持不完整，风险大于收益。
- 新增测试放 `app/src/test/kotlin/com/mcxiaoke/carromed/`，按被测类同名建文件。
  断言辅助（毫单位 ↔ 展示值换算）统一用 `core.testing.DoseAsserts.kt` 里的
  `assertLedgerBalance` / `assertDoseValue` / `assertBalanceAfter`，
  别手写 `Dose(x).asFloat`（A1b 改造中手写漏了 4 处，报错信息还看不出意图）。
- 断言用 Truth（`assertThat`），不要裸 `assertTrue`。
- **每条测试都要能失败。** 加完测试做一次变异验证：故意改坏实现，确认对应测试变红。
  本项目已有两例真实事故是"测试全绿但实现是错的"：
  1. `DoseTrackingServiceTest` 三项测试从未建立库存台账，却断言"库存未被改变" ——
     恒真断言，`takeDose` 的扣减逻辑即使整个坏掉也测不出来。
  2. `AppDatabaseRealTest` 断言 `finalMed.currentStock == ledgerSum`，
     而 `current_stock` 恰恰是那个会被 `coerceAtLeast(0f)` 打破的列 ——
     测试选的断言点恰好是唯一不成立的那一个。
  写测试时先问："实现坏成什么样能让它变红？"

常见失败与定位：

| 现象 | 多半是 |
| :--- | :--- |
| `MigrationTest` 挂 | 该文件已随"不需要迁移代码"的决策删除，见 `docs/CHANGES-20260928.md` |
| 库存守恒测试挂 | 某条路径绕过了 `DoseTrackingService.appendLedger` 直接写表 |
| 断言差 1000 倍 | 毫单位（`Int`）与展示值（`Float`）量纲混用，见 `DoseAsserts.kt` |
| jqwik 属性测试报 `should have no parameters` | JUnit 4 不允许带参，别写进 `@Test` 里 |
| 统计聚合测试挂 | DAO 聚合 SQL 与 `StatsEngine` 口径不一致 |
| Robolectric 报找不到资源 | `testOptions.unitTests.isIncludeAndroidResources` 被删了 |

---

## 4. UI 截图验证与调优

**这是本项目最重要的一节。** Compose 界面改完不看成图，等于没改。

### 4.1 一键走查全部 13 个全屏页

```powershell
# 清库 → 冷启动 → 灌演示数据 → 逐页截图
python tools\app_screenshots.py --clear --seed

# 首启空态（验证空页面引导文案）
python tools\app_screenshots.py --clear

# 顺带编译安装
python tools\app_screenshots.py --install --clear --seed

# 只重看某几页
python tools\app_screenshots.py --only today,stats,med_inventory

# 同时导出语义树 XML（量化间距用）
python tools\app_screenshots.py --dump-ui
```

产物在 `temp/appscreenshots/`：

```
01_today.png        02_manual_dose.png    03_settings.png ...
01_today_s2.png     （同页下滑后的第二屏）
manifest.md         走查清单：每页截图、导航断言结果、失败原因
```

脚本的五条设计约定（改脚本前先读 `tools/app_screenshots.py` 头部的 docstring）：

1. **按语义定位，不写死坐标**：`uiautomator dump` → 找 `text` / `content-desc`
   → 向上找最近的可点击祖先 → 点它的中心。UI 微调不会让脚本立刻失效。
2. **每次跳转带断言**：跳过去以后必须能看到预期的页面标题文本，
   否则记进 `manifest.md` 的"断言结果"段。**不会静默截到走错页面的图。**
3. **二进制一律走管道**：`screencap` 用 `subprocess` 收字节再落盘。
4. **多屏截图后必须滚回顶部**：`LazyColumn` 会记住滚动偏移，
   不复位的话从该页返回时页头标题已在视口外，后续按标题/图标定位的步骤**连环失败**
   （真实踩过：一次失败让后面 20 步全部报错，最后把 App 按退出键按回了桌面）。
5. **空库自动跳步**：靠今日页首启引导卡的「药箱还是空的」判断库是否为空，
   依赖药品的 5 个页面（药品详情 / 编辑药品信息 / 提醒设置 / 库存管理 / 补药入库）
   会被跳过并记进清单，**不会**被误报成断言失败。

新增一个全屏页面的 checklist：

- [ ] `ui/navigation/Screen.kt` 加路由 + `createRoute()`
- [ ] `ui/navigation/AppNavigation.kt` 注册 `composable`
- [ ] `tools/app_screenshots.py` 的 `PROGRAM` 加对应 `Step`（带 `expect` 断言）
- [ ] 跑一次走查，确认 `manifest.md` 里没有新的失败项

### 4.2 复核清单（每张图都要过一遍）

- 文字有没有截断、溢出、重叠
- 对比度够不够（浅色主题下 secondary text 最容易糊）
- 留白节奏：卡片间距、段间距、屏幕边距是否一致
- 空态文案是否说清"下一步做什么"，而不是只说"暂无数据"
- 错别字与术语一致性（药品通用名尤其重要）
- 长文案 / 大字体 / 大字号下会不会炸（可临时改模拟器字号验证）
- 有历史数据的形态和空态形态**都要看**

### 4.3 量化调优：用语义树量 bounds

肉眼判断间距不可靠。`--dump-ui` 会把每屏的 `uiautomator` XML 一起存下来：

```powershell
# 辅助脚本：把当前屏的 uiautomator XML 摊平成 "文本 / desc / bounds / 中心点" 列表
python temp\dumpui.py
python temp\dumpui.py --out temp\uixml\x.xml
```

典型用法：

- 对比 4 个主 Tab 标题的 `bounds`，找出垂直基线差了几个 dp
- 确认某个元素在所有页面上的 `bounds` 边距一致
- 判断两个控件是否真的重叠（bounds 相交）

> 历史上的真实 bug：4 个主 Tab 没用统一的 `TopAppBar`，
> `TodayScreen` 挂 `statusBarsPadding()` + `top=12.dp`，
> 另外三个挂 `.statusBarsPadding()` + `top=16.dp`，
> 加上"今日清单"是两行文本（标题 + 日期副标题）与其他三个单行标题的排版差异，
> 导致切换 Tab 时标题高度肉眼可见地跳动。
> 修法是抽 `HomeTabHeader` 组件统一规格。**这类问题只有看图 + 量 bounds 才能发现。**

### 4.4 交互验证（截图之外）

截图只能验证静态呈现。以下必须手动或脚本点一遍：

- 长按卡片 → 推迟 / 跳过菜单
- 打卡 → 撤销 → 库存回补
- 新建药品时，若当前时间已过默认时点，`startDate` 应自动顺延到明天，
  且表单有明确提示（首日体验，红字告警会吓跑新用户）
- 跳过 / 推迟后徽标是否正确
- 低库存横幅是否在打卡扣减后实时更新

临时交互脚本用 `temp/emu.py`（已绕开 PowerShell 二进制重定向问题）：

```powershell
python temp\emu.py shot out.png
python temp\emu.py tap 540 1200
python temp\emu.py swipe 540 2000 540 800 400
python temp\emu.py shotscript tap 540 1200 sleep 1.0 shot temp\a.png
```

---

## 5. 数据形态验证（演示数据 vs 空态）

App 内不提供"一键造数据"入口。开发期通过 **debug 源集**的广播遥控：

```powershell
# 灌入演示数据（4 个药品 + 当日打卡事实）
adb -s emulator-5554 shell am broadcast -a com.mcxiaoke.carromed.dev.SEED  -n com.mcxiaoke.carromed/.DevDataReceiver

# 清空全部业务数据（回到首启空库）
adb -s emulator-5554 shell am broadcast -a com.mcxiaoke.carromed.dev.CLEAR -n com.mcxiaoke.carromed/.DevDataReceiver
```

> ⚠️ **必须先让 App 进程活着**（`am start` 一下再广播）。
> 被 `force-stop` 的应用收不到广播，而且广播要显式带 `-n` 指定组件更稳。
> 这也是走查脚本里 `Driver.dev_broadcast()` 先 `launch(cold=False)` 的原因。

### 数据库直查

模拟器里**没有 `sqlite3` 可执行文件**，只能把库拉回本地查。

> ⚠️ **必须连 `-wal` / `-shm` 一起拉，否则会看到过期数据。**
>
> Room 默认开 WAL（`PRAGMA journal_mode=WAL`），最近的写入还留在 `carromed.db-wal` 里，
> 尚未 checkpoint 回主库。只拉 `carromed.db` 看到的是**上一次 checkpoint 的快照**。
>
> 这个坑真实踩过：A2 改完暂停功能，UI 上明明显示「提醒已暂停，2 天后恢复」，
> 而只拉主库查 `reminder_settings` 得到**空表** —— 差点误判成"写入没生效"。
> **结论：查不到刚写的数据时，先怀疑 WAL，不要先怀疑代码。**

```powershell
cmd /c "adb -s emulator-5554 exec-out run-as com.mcxiaoke.carromed cat databases/carromed.db     > temp\carromed.db"
cmd /c "adb -s emulator-5554 exec-out run-as com.mcxiaoke.carromed cat databases/carromed.db-wal > temp\carromed.db-wal"
cmd /c "adb -s emulator-5554 exec-out run-as com.mcxiaoke.carromed cat databases/carromed.db-shm > temp\carromed.db-shm"
python temp\dbdump.py     # 表行数 + 全量关键字段
```

更省事的替代方案：**先 force-stop App 再拉**。进程退出时 Room 会做一次 checkpoint，
主库就是最新的（`-wal` 会被清空）。但注意 `force-stop` 之后广播也收不到，
要重新 `am start`。

必须用 `cmd /c` 包一层。PowerShell 的 `>` 会破坏 db 文件。

常用断言（括号内是不变量编号，见 `docs/REMINDER-DOMAIN-REDESIGN.md` §4）：

- `SUM(inventory_transactions.change_amount)` 就是该药余额，**`medications` 表没有这一列**（I1）
- 首启空库时业务表**全为 0 行**
- 编辑药品后 `precautions` / `alias` / `is_archived` 原值不变，
  且 `reminder_settings` 四列**结构上就碰不到**（I9）
- 每个药品在 `reminder_settings` 里**恰好一行**（A2 建立的不变量）
- `paused_until` 非空的药，在 `paused_until < 今天` 时**不再**出现在可排闹钟列表里

---

## 6. 代码规范

- **注释与 KDoc 用简体中文**，标识符用英文。标准 log 用英文。
- KDoc 写"**为什么**这么设计"，不要复述代码在做什么。
  尤其要写清楚被刻意排除的方案和排除理由。
- 领域层（`core/domain`）保持**无 `android.*` 框架依赖**（Room 的 `withTransaction`
  扩展除外），这样才能在 JVM 单测里跑。
- 新增枚举值要同步检查所有 `when` 是否穷尽，避免静默漏分支。
- 字符串**暂不强制走 `strings.xml`**（当前代码里仍有硬编码），
  但新增页面文案要集中在该 Screen 顶部，便于后续抽资源。

---

## 7. 提交与文档

- **commit message 用英文**，格式参考既有历史：
  `fix(core): ...` / `refactor(ui): ...` / `feat(alarm,export,ui): ...` / `docs: ...`
- **分步提交**：大规模改动先列分步计划，每步独立编译 + 测试通过才提交。
  每步都要能单独回滚。
- **重要代码变动**在 `docs/CHANGES-YYYYMMDD.md` **顶部追加**摘要，
  时间取本机真实 GMT+8：

  ```powershell
  Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"
  ```

- 文档放 `docs/`，临时产物放 `temp/`。
  **新建文档禁止覆盖同名文件**，重名追加日期时间戳与序号。
- 编辑或删除未提交的文件前，先备份到 `temp/backups/`。

---

## 8. 常见坑速查

| 坑 | 症状 | 解法 |
| :--- | :--- | :--- |
| PowerShell 重定向写二进制 | PNG / db 文件损坏、打不开 | `subprocess` 管道，或 `cmd /c "... > file"` |
| `adb shell input text` | 中文被 IME 吞掉、丢字 | 改用逐字符 `input keyevent`（`temp/emu.py` 的 `text`） |
| 隐式广播被丢弃 | 调试广播没反应，日志无输出 | App 被 force-stop 了；先 `am start`，并显式 `-n` 指定组件 |
| `uiautomator dump` 偶发失败 | `could not get idle state` | 重试 3 次并逐次加退避（脚本已实现） |
| 列表页滚下去没复位 | 从该页返回后页头标题在视口外，后续按标题/图标定位的步骤**连环失败** | 每次多屏截图后 `scroll_to_top()`；断言找不到时也先试复位 |
| 按标题断言"没跳到" | 其实跳到了，只是标题被滚出视口 | 断言失败时先滚回顶部再找（`expect_text()`） |
| `0/0` 渲染成 100% | 新用户看到"依从率 100%"的假满分 | UI 显示「—」，领域层保留 `1.0f` 约定避免 NaN |
| 混淆模拟器与真机 | 截图分辨率 / 行为对不上 | 每条 adb 命令都带 `-s emulator-5554` |
| 看不到通知横幅 | 权限没授予 | `pm grant ... android.permission.POST_NOTIFICATIONS` |
| Room 迁移静默清库 | 用户历史被抹 | 项目**已刻意禁用** `fallbackToDestructiveMigration`；迁移失败要显式崩溃 |

---

## 9. 收口自检

改动收口前逐条过：

- [ ] `./gradlew clean assembleDebug testDebugUnitTest` 全绿
- [ ] `./gradlew compileReleaseKotlin` 通过（debug 能编不代表 release 能编）
- [ ] 模拟器实测过改动涉及的流程
- [ ] `python tools\app_screenshots.py --clear --seed` 跑通，
      `manifest.md` 无新增失败项
- [ ] 改动涉及的页面**亲眼看过图**
- [ ] 数据库守恒断言成立（改了库存 / 打卡 / 补录就必须查）
- [ ] `docs/CHANGES-YYYYMMDD.md` 已追加摘要
