# AGENTS.md —— 编码助手入口

---

## 一、这个项目是什么

**CarroMed** —— Android 原生（Kotlin + Jetpack Compose + Room）的
个人用药提醒与库存管理应用。

核心承诺：

1. **提醒可靠** —— 到点一定响，不静默漏提醒。
2. **记录真实** —— 吃过的药永不丢失、永不串改；改计划不冲掉历史。

### 技术底账

| 项 | 值 |
| :--- | :--- |
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose（Material 3）· Navigation Compose |
| 持久化 | Room 2.6.1（SQLite，WAL 模式） |
| 调度 | AlarmManager（精确闹钟）+ WorkManager（周期对账兜底） |
| 环境 | JDK 17 · AGP 8.11.1 · Gradle 8.14 · compileSdk / targetSdk 35 · minSdk 26 |
| 测试 | JUnit 5 Platform 上并存 JUnit 4 + Robolectric（真内存库）与 jqwik 属性测试 |
| 分层 | `core/{alarm,data,domain,time}` + `ui/{component,navigation,screen,theme}` |
| 状态 | 内部项目，未公开发布 —— **不写数据库迁移代码**，改 schema 直接删库重装；`AppDatabase.version` 恒为 1 |

### 两条架构铁律

`core/domain` 保持**零 `android.*` 依赖**（Room 的 `withTransaction` 扩展是唯一例外），
这是领域层数学守恒能在 JVM 单测里真跑起来的前提，别破坏它。

数据更新一律**局部 `UPDATE`**。整行覆盖会静默抹掉表单没暴露的字段
（注意事项、别名、归档标记、创建时间），且没有任何报错。

---

## 二、开发与测试流程

| 步骤 | 做什么 | 详见 |
| :--- | :--- | :--- |
| 1 编译 | `./gradlew clean assembleDebug` | DEVGUIDE §1 |
| 2 跑测试 | `./gradlew testDebugUnitTest` | DEVGUIDE §3 |
| 3 实测 | 模拟器 `emulator-5554` 上把改动涉及的流程点一遍 | DEVGUIDE §4.4 |
| 4 看图 | 改动涉及界面时，跑 UI 走查截图并**亲眼看图** | DEVGUIDE §4 |

> **为什么第 4 步不是形式主义：** 「编译过 + 单测全绿」覆盖不到界面接线。
> 视图模型工厂的反射路径崩了、按钮没接上、点击没反应 —— 这类缺陷只有真机点开
> 和走查看图才能发现，而它们恰恰是最伤用户信任的一类。

新增全屏页面时，除导航注册外还要把页面登记进走查脚本，
否则它永远不会被自动验证到（DEVGUIDE §4.1 checklist）。

---

## 三、任务验收标准

- [ ] 编译通过，**且 release 也能编译** —— debug 能编不代表 release 能编
- [ ] 单元测试全绿；改了什么不变量，确认守它的那条测试还在
- [ ] 涉及界面的，**亲自看过图** —— Compose 改完不看成图等于没改
- [ ] 变更记录文档追加了本次摘要

---

## 四、动手前必须知道的红线

- **不执行 `git commit` / `git push`**，不碰 `.git`，除非用户明确要求。
- **不写迁移代码** —— 项目未公开发布，数据库改动直接删库重装。
- **给 ViewModel 加构造参数前先想清楚** —— Kotlin 默认参数不生成单参 Java 构造器，
  编译和单测都会骗过你，真机点开才崩。可测逻辑抽成普通类。
