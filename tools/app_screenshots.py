#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CarroMed —— 全屏页面自动走查截图脚本
====================================

自动驱动 Android 模拟器把 CarroMed 的**每一个全屏页面**都走一遍，
每页截图存到 `temp/appscreenshots/`，并生成一份 `manifest.md` 走查清单。

设计要点
--------
1. **按语义定位，不写死坐标**：通过 `uiautomator dump` 拿到 Compose 的语义树，
   用 `text` / `content-desc` 找目标节点，再向上找到最近的可点击祖先，
   点它的中心。这样 UI 微调不会让脚本立刻失效。
2. **自带校验**：每次跳转都带一个 `expect` 断言文本，页面没跳到就记为失败并写进清单，
   不会静默截到一张"看起来有内容其实走错页面"的图。
3. **数据可注入**：`--seed` / `--clear` 走 debug 源集里的
   `DevDataReceiver` 广播（仅 debug 包存在），首启空库和有数据两种形态都能截。
   空库时依赖药品的 5 个页面（详情 / 编辑信息 / 提醒设置 / 库存 / 补药）
   会**自动跳过并记进清单**，不会被误报成"断言失败"。
4. **不用 PowerShell 重定向**：`screencap` 一律用 `subprocess` 收二进制再落盘，
   避免 `>` 把 PNG 写坏（本项目踩过的坑）。
5. **多屏截图后必须滚回顶部**：Compose 的 `LazyColumn` 会记住滚动偏移，
   不复位的话从该页返回时页头标题已在视口外，后续所有按标题/图标定位的步骤连环失败。
   断言找不到目标时也会先尝试复位再找。
6. **一次失败不许扩散**：下滑手势有可能被系统判给 StatusBar 而拉下通知栏，
   它会一直留在那里，于是后面二十几步全部截到通知栏。
   每次 swipe 之后都检查一次焦点，发现通知栏就收起来、必要时把 App 拉回前台
   （见 `Driver.collapse_shade_if_open`）。
7. **`--only` 保留导航步骤**：只丢掉非目标页的 shot，不丢导航与断言。
   否则会在**当前页**截图，而清单仍写"全部导航断言通过"。

用法
----
```powershell
# 最常用：清库 → 冷启动 → 灌演示数据 → 逐页截图
python tools/app_screenshots.py --clear --seed

# 截首启空态（不灌数据，验证空页面引导）
python tools/app_screenshots.py --clear

# 顺带编译安装 debug 包
python tools/app_screenshots.py --install --clear --seed

# 只截某几页 / 换输出目录 / 指定设备
#   `--only` 保留到达目标页所需的**全部导航步骤**，断言照跑。
#   它不是"在当前页截几张图" —— 那样出的图是错的而报告说一切正常。
python tools/app_screenshots.py --only today,stats --out temp/shots/round2
python tools/app_screenshots.py --serial emulator-5556

# 看看脚本会截哪些页
python tools/app_screenshots.py --list
```

产物
----
    temp/appscreenshots/
        01_today.png ...          # 按执行顺序编号
        12_today_s2.png           # 同页滚动后的第二屏
        12_today.xml              # --dump-ui 时附带的语义树快照
        manifest.md               # 走查清单：页面 / 截图 / 断言结果 / 失败原因
"""

from __future__ import annotations

import argparse
import platform
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from pathlib import Path

PKG = "com.mcxiaoke.carromed.dev"
ACTIVITY = f"{PKG}/com.mcxiaoke.carromed.MainActivity"
# 锁屏全屏提醒页的走查入口：debug 源集的 activity-alias（详见 src/debug/AndroidManifest.xml）
ALERT_ACTIVITY = f"{PKG}/com.mcxiaoke.carromed.DevAlertEntry"
DEV_SEED_ACTION = "com.mcxiaoke.carromed.dev.SEED"
DEV_CLEAR_ACTION = "com.mcxiaoke.carromed.dev.CLEAR"
DEV_RECEIVER = f"{PKG}/com.mcxiaoke.carromed.DevDataReceiver"


def set_package(pkg: str) -> None:
    global PKG, ACTIVITY, ALERT_ACTIVITY, DEV_SEED_ACTION, DEV_CLEAR_ACTION, DEV_RECEIVER
    PKG = pkg
    ACTIVITY = f"{PKG}/com.mcxiaoke.carromed.MainActivity"
    ALERT_ACTIVITY = f"{PKG}/com.mcxiaoke.carromed.DevAlertEntry"
    DEV_SEED_ACTION = "com.mcxiaoke.carromed.dev.SEED"
    DEV_CLEAR_ACTION = "com.mcxiaoke.carromed.dev.CLEAR"
    DEV_RECEIVER = f"{PKG}/com.mcxiaoke.carromed.DevDataReceiver"

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "temp" / "appscreenshots"

# 底部导航栏文字（同时也是四个一级页面的校验锚点）
TABS = ("今日", "药箱", "记录", "统计")


# --------------------------------------------------------------------------- #
# 步骤定义
# --------------------------------------------------------------------------- #
@dataclass
class Step:
    action: str                     # home/tab/text/desc/back/scroll/shot/wait/present_desc/absent_desc/alert
    arg: str = ""                   # 定位用的 text 或 content-desc
    index: int = 0                  # 同名节点取第几个
    expect: str = ""                # 跳转后必须出现的文本（断言）
    note: str = ""                  # 走查清单里的一句话说明
    shots: int = 1                  # shot 步骤：截几张（>1 时中间自动下滑）
    optional: bool = False          # 失败是否只告警
    contains: bool = False          # 用「包含」而非「完全相等」匹配
    key: str = ""                   # shot 步骤的文件名主干
    requires_data: bool = False     # 库为空时跳过（药品详情及其下游页面）


# 空库探针：今日页首启引导卡的标题，只有"一个药品都没有"时才会出现
EMPTY_DB_MARKER = "药箱还是空的"


# 14 个全屏页面的走查路径。顺序经过优化，尽量少来回跳。
PROGRAM: list[Step] = [
    Step("home", note="冷启动，回到今日用药"),

    # ---- 1. 今日用药 ------------------------------------------------------- #
    Step("shot", key="today", shots=2, note="待服 / 已服分区、库存告警横幅、手动补录 FAB"),

    # ---- 1a. 未来日 = 只读预览（PLAN-FUTURE-SLOT-20260929）----------------- #
    Step("desc", "未来用药预览", index=0, contains=True, note="切到明天（未来日只读预览）",
         expect="未来用药预览 · 到达当天方可记录"),
    Step("shot", key="today_future", note="未来日：待服卡无 ✓，改为「明天 10:30 服用」只读说明"),
    Step("absent_desc", "确认服药", note="未来日**不该**存在任何「确认服药」节点"),
    Step("text", "明天", index=0, contains=True, note="点开未来日的待服卡（只读预览详情）",
         expect="记录详情", requires_data=True),
    Step("shot", key="dose_detail_future",
         note="未来槽位详情：计划日期+剂量+余量，无确认/推迟/跳过，页脚说明原因",
         requires_data=True),
    Step("absent_desc", "确认服用", note="未来槽位详情页**不该**有「确认服用」按钮"),
    # ⚠️ 这里**不能**用 back 回今日用药：该页就在根路由上，back 会把整个 App
    # 退到桌面，而桌面上的 swipe 会拉下通知栏，之后所有步骤全拍通知栏。
    # 用 home（冷启动）复位，代价是两秒，换的是"失败不会扩散"。
    Step("home", note="冷启动回到今日用药（不用 back：会退出 App）", expect="今日用药"),
    Step("desc", "，今天", contains=True, note="切回今天",
         expect="点按可推迟或跳过"),
    Step("present_desc", "确认服药", note="回到今天后 ✓ 又回来了（防修过头：不能把今天也锁死）"),

    # ---- 1b. 记录详情页（统一承载待服 / 已服 / 已跳过，非 sheet）----------- #
    # 点的是**卡片本体**（药名文本所在的可点击祖先），不是右侧的 ✓ 快捷打卡 ——
    # 点 ✓ 会真的写库，走查不该改动数据。
    Step("text", "环孢素", note="第一张待服卡（演示数据里 10:30 那味）",
         expect="记录详情", requires_data=True),
    Step("shot", key="dose_detail_expired",
         note="已逾期形态：确认服用 / 跳过本次（逾期不给「推迟」）",
         requires_data=True),
    Step("back", note="返回今日用药", expect="今日用药"),

    # 第二张"环孢素"是当晚 22:00 那条 —— 只要走查不在深夜跑，它就是 PENDING，
    # 用来覆盖"待服形态含推迟档位"这条唯一没被上面两张图拍到的分支。
    Step("text", "环孢素", index=1, note="第二张环孢素卡（22:00，通常仍是 PENDING）",
         expect="记录详情", requires_data=True),
    Step("shot", key="dose_detail_pending",
         note="待服形态：确认服用 / 推迟档位 / 跳过本次 / 备注（随确认落库）",
         requires_data=True),
    Step("back", note="返回今日用药", expect="今日用药"),

    Step("text", "羟氯喹", note="第一张已服卡（演示数据里 09:00 那味）",
         expect="记录详情", requires_data=True),
    Step("shot", key="dose_detail_completed",
         note="已服形态：跳过（改判）+ 撤销（仅当天）", requires_data=True),
    Step("back", note="返回今日用药", expect="今日用药"),

    # ---- 2. 手动补录服药 --------------------------------------------------- #
    Step("desc", "补记一次已服用的药", contains=True, note="今日页 FAB", expect="单次服药"),
    Step("shot", key="manual_dose", shots=2, note="补录时间选择器、是否扣库存"),
    Step("back", note="返回今日用药", expect="今日用药"),

    # ---- 3. 系统设置 ------------------------------------------------------- #
    Step("desc", "系统设置", note="今日页右上角齿轮", expect="设置"),
    Step("shot", key="settings", shots=2, note="推迟时长 / 夜间静音 / 导出备份"),
    Step("text", "后台保活与防漏提醒指引", note="进入系统自检",
         expect="防漏提醒指南", contains=True),
    Step("shot", key="permission_check", shots=2, note="4 项系统特权与保活指引"),
    Step("back", note="返回设置", expect="设置"),
    Step("back", note="返回今日用药", expect="今日用药"),

    # ---- 4. 我的药箱 ------------------------------------------------------- #
    Step("tab", "药箱", expect="我的药箱"),
    Step("shot", key="cabinet", shots=2, note="在服 / 已归档分区、搜索排序"),

    # ---- 5. 添加药品（新增模式：药品信息 + 提醒计划 + 初始库存）------------ #
    Step("desc", "添加药品", note="药箱页右上角", expect="添加药品"),
    Step("shot", key="med_add", shots=3, note="新增药品三段一次填完"),
    Step("back", note="返回我的药箱", expect="我的药箱"),

    # ---- 6. 药品详情（三段式分节入口）------------------------------------- #
    # 以下 5 段都依赖"至少有一个药品"，空库走查时自动跳过
    Step("text", "钙和维生素D", note="第一张药品卡片", expect="药品详情", requires_data=True),
    Step("shot", key="med_detail", shots=3, note="药品信息 / 提醒设置 / 库存管理三段入口 + 用药统计",
         requires_data=True),

    # ---- 7. 编辑药品信息（复用添加页，仅药品信息维度）--------------------- #
    Step("text", "药品信息", note="详情页第一段", expect="编辑药品信息", requires_data=True),
    Step("shot", key="med_edit", shots=2, note="编辑模式：只暴露药品信息字段", requires_data=True),
    Step("back", note="返回药品详情", expect="药品详情", requires_data=True),

    # ---- 8. 提醒设置（独立页）--------------------------------------------- #
    Step("text", "提醒设置", note="详情页第二段", expect="提醒设置", requires_data=True),
    Step("shot", key="med_reminder", shots=3, note="频次 / 疗程 / 时点 / 提醒行为", requires_data=True),
    Step("back", note="返回药品详情", expect="药品详情", requires_data=True),

    # ---- 9. 库存管理（独立页）--------------------------------------------- #
    Step("text", "库存管理", note="详情页第三段", expect="库存管理", requires_data=True),
    Step("shot", key="med_inventory", shots=3, note="余量 / 可用天数 / 预警线 / 有效期 / 盘点",
         requires_data=True),

    # ---- 10. 补药入库 ------------------------------------------------------ #
    Step("text", "补充余药", note="库存页按钮", expect="补充余药", requires_data=True),
    Step("shot", key="refill", shots=2, note="入库数量 / 批号 / 有效期 / 备注", requires_data=True),
    Step("back", note="返回库存管理", expect="库存管理", requires_data=True),
    Step("back", note="返回药品详情", expect="药品详情", requires_data=True),
    Step("back", note="返回我的药箱", expect="我的药箱", requires_data=True),

    # ---- 11. 服药记录 ------------------------------------------------------ #
    Step("tab", "记录", expect="服药记录"),
    Step("shot", key="progress", shots=2, note="7 天服药日历"),

    # 服药时间线是第二个 tab
    Step("text", "服药时间线", note="记录页第二个 tab", expect="服药时间线", index=-1),
    Step("shot", key="progress_timeline", shots=2, note="服药时间线：按日分组 / 倒序 / 触底加载"),

    # 单个药品的历史：点第一张日历卡进详情
    Step("text", "7 天服药日历", note="切回日历 tab", expect="7 天服药日历", index=0),
    Step("desc", "查看详情", note="日历卡可点进单药历史", expect="服药历史",
         contains=True, requires_data=True),
    Step("shot", key="med_history", shots=2, note="单药历史：按月分组 / 每行可进详情", requires_data=True),
    Step("back", note="返回记录页", expect="服药记录"),

    # ---- 12. 统计报表 ------------------------------------------------------ #
    Step("tab", "统计", expect="用药统计"),
    Step("shot", key="stats", shots=2, note="周期切换 / 依从率构成 / 各药消耗"),

    # ---- 13. 锁屏全屏提醒页（AlarmAlertActivity）-------------------------- #
    # 该页只在闹钟触发 / 锁屏时由系统拉起，界面里点不到 —— 走查只能"模拟系统拉起"：
    # 经 debug 专属的 activity-alias（DevAlertEntry）用 am start 直接打开。
    # 若不登记，这一页就是"编译过 + 单测绿"却从未被看过的那类页面（AGENTS §4.1）。
    Step("alert", note="拉起锁屏全屏提醒页（debug 专属入口）", expect="确认已服"),
    Step("shot", key="alarm_alert",
         note="全屏提醒：药名 / 剂量 / 注意事项 + 确认已服 / 推迟 / 跳过"),
    # 该页在独立 task（taskAffinity=""），按 back 会退回桌面，所以用冷启动复位。
    Step("home", note="冷启动复位（全屏页独立 task，不能按 back）", expect="今日用药"),
]


# --------------------------------------------------------------------------- #
# ADB 驱动
# --------------------------------------------------------------------------- #
class AdbError(RuntimeError):
    pass


class Driver:
    """对 adb 的薄封装：只做本脚本需要的事，不做通用框架。"""

    def __init__(self, serial: str, verbose: bool = False) -> None:
        self.serial = serial
        self.verbose = verbose
        self._adb = shutil.which("adb")
        if not self._adb:
            raise AdbError(
                "找不到 adb。请把 Android SDK platform-tools 加入 PATH，"
                "或设置环境变量 ANDROID_HOME 后重试。"
            )

    # -- 基础调用 ---------------------------------------------------------- #
    def _run(self, args: list[str], timeout: int = 90) -> bytes:
        cmd = [self._adb, "-s", self.serial] + args
        if self.verbose:
            print("   $", " ".join(cmd))
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if proc.returncode != 0:
            raise AdbError(
                f"命令失败({proc.returncode}): adb {' '.join(args)}\n"
                + proc.stderr.decode("utf-8", "replace")
            )
        return proc.stdout

    def shell(self, *args: str, timeout: int = 90) -> str:
        return self._run(["shell", *args], timeout=timeout).decode("utf-8", "replace")

    # -- 设备信息 ---------------------------------------------------------- #
    def device_model(self) -> str:
        try:
            model = self.shell("getprop", "ro.product.model").strip()
            release = self.shell("getprop", "ro.build.version.release").strip()
            sdk = self.shell("getprop", "ro.build.version.sdk").strip()
            w, h = self.screen_size()
            return f"{model} · Android {release} (API {sdk}) · {w}×{h}"
        except AdbError:
            return "unknown"

    def screen_size(self) -> tuple[int, int]:
        out = self.shell("wm", "size")
        m = re.search(r"(\d+)x(\d+)", out)
        if not m:
            raise AdbError(f"无法解析屏幕尺寸: {out!r}")
        return int(m.group(1)), int(m.group(2))

    # -- 应用生命周期 ------------------------------------------------------ #
    def is_installed(self) -> bool:
        pkgs = self.shell("pm", "list", "packages")
        if "package:com.mcxiaoke.carromed.dev" in pkgs:
            set_package("com.mcxiaoke.carromed.dev")
            return True
        if "package:com.mcxiaoke.carromed" in pkgs:
            set_package("com.mcxiaoke.carromed")
            return True
        return False

    def install(self, apk: Path) -> None:
        print(f"   安装 {apk}")
        self._run(["install", "-r", "-t", str(apk)], timeout=300)

    def clear_data(self) -> None:
        print("   pm clear（回到首启空库）")
        self.shell("pm", "clear", PKG)

    def grant_notifications(self) -> None:
        # 不授予也不影响截图，但横幅/通知相关页面自检会更真实
        self.shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")

    def force_stop(self) -> None:
        self.shell("am", "force-stop", PKG)

    def launch(self, cold: bool = True) -> None:
        if cold:
            self.force_stop()
        self._run(["shell", "am", "start", "-W", "-n", ACTIVITY], timeout=120)
        time.sleep(1.8)  # 等 Compose 首帧 + Room 打开

    def dev_broadcast(self, action: str) -> None:
        # 必须先让进程活着：被 force-stop 的应用收不到广播
        self.launch(cold=False)
        self._run(
            ["shell", "am", "broadcast", "-a", action, "-n", DEV_RECEIVER], timeout=60
        )
        time.sleep(1.2)

    # -- 输入 -------------------------------------------------------------- #
    def tap(self, x: int, y: int, settle: float = 0.9) -> None:
        self.shell("input", "tap", str(x), str(y))
        time.sleep(settle)

    def back(self, settle: float = 0.9) -> None:
        self.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(settle)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 350) -> None:
        self.shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
        time.sleep(1.0)
        # ⚠️ 下滑有可能把**通知栏**拉下来而不是滚动列表。
        #
        # 从屏幕顶部附近起手下滑时，系统会把手势判给 StatusBar；
        # 一旦通知栏被拉开，它会**一直留在那里**，于是后面每一步的截图
        # 拍到的都是通知栏，而按 text/desc 定位的断言又会连环失败 ——
        # 一次手势失败毁掉后面二十几步（2026-09-29 真实踩过：
        # 第 17 步 med_edit 之后，第 18~35 步全部截到通知栏）。
        #
        # 每步都收一次状态：这不是在跟"正常情况"较劲，
        # 而是承认这条路径一定会偶发失败，失败时**立刻自愈**，
        # 不让它扩散到后面的步骤。
        self.collapse_shade_if_open()

    def focus_window(self) -> str:
        """当前焦点窗口那一行（`mCurrentFocus=Window{...}`）。

        ⚠️ **必须把管道写成一条 shell 命令**，不能写成
        `self.shell("dumpsys", "window", "grep", "mCurrentFocus")`：
        adb 会把参数拼成一条命令，`dumpsys window` 把 `grep` 当成**窗口过滤条件**，
        直接回 `Bad window command, or no windows match: grep` ——
        于是"App 是否在前台"**永远为假**、通知栏也**永远收不起来**
        （2026-09-30 实测：`collapse_shade_if_open` 的自愈分支因此是死代码，
        而新加的返回键兜底把每次正常返回都误报成"App 已退出"）。
        """
        return self.shell("dumpsys window | grep mCurrentFocus")

    def collapse_shade_if_open(self) -> None:
        """通知栏/快捷设置被拉开时收起来，App 失焦时重新拉起。"""
        focus = self.focus_window()
        if "StatusBar" not in focus and "NotificationShade" not in focus:
            return
        self.shell("cmd", "statusbar", "collapse")
        time.sleep(0.6)
        # 收起来之后系统可能停在桌面，把 App 拉回前台再继续，
        # 否则后续每一步都会在桌面上"找不到任何控件"。
        if "carromed" not in self.focus_window():
            self.shell("am", "start", "-n", ACTIVITY)
            time.sleep(2.0)

    def app_in_foreground(self) -> bool:
        """App 是否仍在焦点窗口上。

        存在的理由：`back` 在**根路由**上会把 App 直接退到桌面，
        而桌面上的 swipe 会拉下通知栏 —— 一次误按就能让后面二十几步
        全部拍到通知栏/桌面，且断言失败的原因看起来与真正的原因毫无关系
        （2026-09-30 真实踩过）。返回键之后立刻查一次焦点，就能把这种
        "静默漂移到错误界面"变成一条**看得见的失败**。
        """
        return "carromed" in self.focus_window()

    def scroll_down(self, ratio: float = 0.55) -> None:
        w, h = self.screen_size()
        self.swipe(w // 2, int(h * 0.72), w // 2, int(h * ratio), 350)

    def scroll_to_top(self, times: int = 3) -> None:
        """回到页面顶部。

        懒加载列表会保留滚动偏移。不复位的话，回到该页时页头标题已经在视口外，
        后续所有按标题 / 顶部图标定位的步骤会连环失败。
        """
        w, h = self.screen_size()
        for _ in range(times):
            self.swipe(w // 2, int(h * 0.30), w // 2, int(h * 0.88), 260)
        time.sleep(0.5)

    # -- 截图（必须走二进制管道）-------------------------------------------- #
    def screenshot(self, path: Path) -> int:
        data = self._run(["exec-out", "screencap", "-p"], timeout=120)
        if not data.startswith(b"\x89PNG"):
            raise AdbError(f"截图数据不是 PNG（前 8 字节 {data[:8]!r}）")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return len(data)

    # -- 语义树 ------------------------------------------------------------ #
    def dump_ui(self, retries: int = 3) -> ET.Element:
        last: Exception | None = None
        for i in range(retries):
            try:
                self._run(["shell", "uiautomator", "dump", "/sdcard/_carromed_ui.xml"], timeout=90)
                raw = self._run(["exec-out", "cat", "/sdcard/_carromed_ui.xml"], timeout=90)
                return ET.fromstring(raw.decode("utf-8", "replace"))
            except Exception as exc:  # noqa: BLE001 - uiautomator 偶发 "could not get idle state"
                last = exc
                # ⚠️ 专门治 `mCurrentFocus=null` 这一种失败：窗口没有焦点时，
                # uiautomator 拿不到 active window，会一直报
                # `null root node returned by UiTestAutomationBridge` ——
                # 重试多少次都没用，而**屏幕上 App 明明好好地显示着**。
                # 现场是"从根页面按了返回 → App 退到桌面 → 桌面上下滑拉出通知栏"，
                # 之后每一步都在跟桌面打交道（2026-09-30 真实踩过）。
                # 自愈动作：把 App 拉回前台，再重试。
                if i == 0 and not self.app_in_foreground():
                    print("      !! 焦点不在 App 上（疑似被返回键退出/通知栏遮住），冷启动复位")
                    self.collapse_shade_if_open()
                    self.launch(cold=True)
                time.sleep(1.0 + i)
        raise AdbError(f"uiautomator dump 连续 {retries} 次失败: {last}")


# --------------------------------------------------------------------------- #
# 语义树查询
# --------------------------------------------------------------------------- #
def iter_nodes(root: ET.Element):
    for node in root.iter("node"):
        yield node


def node_text(node: ET.Element) -> str:
    return (node.get("text") or "").strip()


def node_desc(node: ET.Element) -> str:
    return (node.get("content-desc") or "").strip()


def center(node: ET.Element) -> tuple[int, int] | None:
    nums = re.findall(r"-?\d+", node.get("bounds") or "")
    if len(nums) != 4:
        return None
    x1, y1, x2, y2 = map(int, nums)
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def clickable_ancestor(root: ET.Element, node: ET.Element) -> ET.Element:
    """向上找到最近的可点击祖先；找不到就用节点自身（点它自己的中心也通常有效）。"""
    parent = {c: p for p in root.iter() for c in p}
    cur = node
    while cur is not None:
        if cur.get("clickable") == "true":
            return cur
        cur = parent.get(cur)
    return node


def find_by_text(root: ET.Element, value: str, index: int = 0, contains: bool = False,
                 bottom_most: bool = False) -> ET.Element | None:
    hits = []
    for node in iter_nodes(root):
        t = node_text(node)
        if not t:
            continue
        ok = value in t if contains else t == value
        if ok and center(node):
            hits.append(node)
    if not hits:
        return None
    if bottom_most:
        # 底部导航栏：'今日' 这类文案在页面内容里也会出现，取最靠下的那个
        hits.sort(key=lambda n: center(n)[1], reverse=True)
    elif index:
        hits.sort(key=lambda n: (center(n)[1], center(n)[0]))
    return hits[index]


def find_by_desc(
    root: ET.Element, value: str, index: int = 0, contains: bool = False
) -> ET.Element | None:
    """按 content-desc 定位节点。

    `contains=True` 时做**子串**匹配。

    为什么需要它：可访问性节点上写的 desc 往往是一整句
    （"环孢素，近 7 天按时服药 2/2 次，查看详情"），
    精确匹配会让走查脚本永远找不到它 —— 于是该页面的断言被跳过，
    截图拍到哪一页都没人管。
    """
    def match(d: str) -> bool:
        if not d:
            return False
        return value in d if contains else d == value

    hits = [n for n in iter_nodes(root) if match(node_desc(n)) and center(n)]
    if not hits:
        return None
    if index:
        hits.sort(key=lambda n: (center(n)[1], center(n)[0]))
    return hits[index]


def any_text_present(root: ET.Element, value: str) -> bool:
    return any(node_text(n) == value for n in iter_nodes(root))


def expect_text(driver: "Driver", value: str, attempts: int = 4) -> bool:
    """断言页面上能看到某段文本；找不到时先尝试把页面滚回顶部再找。

    滚动复位只做 swipe，不点任何东西，因此不会掩盖真实的导航失败。
    """
    for i in range(attempts):
        if any_text_present(driver.dump_ui(), value):
            return True
        if i == 0:
            driver.scroll_to_top(times=2)
        else:
            time.sleep(0.8)
    return False


# --------------------------------------------------------------------------- #
# 走查执行
# --------------------------------------------------------------------------- #
@dataclass
class Report:
    shots: list[dict] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)


def run(driver: Driver, out: Path, only: set[str], dump_ui: bool,
        keep: bool, strict: bool, has_data: bool) -> Report:
    report = Report()
    # ⚠️ `--only` 不能简单地把非 shot 步骤全丢掉（2026-09-29 修）。
    #
    # 旧写法 `[s for s in PROGRAM if not only or (s.action == "shot" and s.key in only)]`
    # 只保留目标 shot，把**导航步骤**（tab / text / desc / back）一起丢了。
    # 于是 `python tools/app_screenshots.py --only today,stats` 实际是
    # "在**当前停留的那一页**截 today 和 stats 两张图" ——
    # 而当前页是上一次跑剩下的页面。清单还会写"全部导航断言通过"，
    # 因为**根本没有断言被执行过**。
    #
    # 真实后果：截图拍的是错的页面，而报告说一切正常。
    # 2026-09-29 亲眼看到 `--only ... progress,stats` 的 8 张图里
    # 6 张内容完全一样（都是今日清单），`med_reminder` / `med_inventory`
    # 明明写着自己的页名。
    #
    # 正确做法：保留**每一个 shot 之前的全部导航步骤**（它们是到达目标页的路径），
    # 只丢掉其它页的 shot 本身。断言因此仍然真的跑了。
    if only:
        keep_shots = [s.key for s in PROGRAM if s.action == "shot" and s.key in only]
        first_wanted = min(
            (i for i, s in enumerate(PROGRAM) if s.action == "shot" and s.key in only),
            default=0,
        )
        steps = [
            s for i, s in enumerate(PROGRAM)
            # 目标 shot 之前的导航全留（含其它页的导航，那是路径的一部分）
            if i <= first_wanted or s.action != "shot" or s.key in keep_shots
        ]
    else:
        steps = list(PROGRAM)

    if not keep and out.exists():
        for old in out.glob("*.png"):
            old.unlink()
    out.mkdir(parents=True, exist_ok=True)

    def fail(step: Step, reason: str) -> None:
        msg = f"[{step.action}{'/' + step.arg if step.arg else ''}] {reason}"
        report.problems.append(msg)
        print(f"   !! {msg}")
        if strict:
            raise SystemExit("严格模式：遇到步骤失败即中止。")

    print(f"\n开始走查 -> {out}")
    if not has_data:
        print("   提示：药箱为空，依赖药品的页面将自动跳过（这是空态走查的预期行为）")
    shot_seq = 0
    for i, step in enumerate(steps, start=1):
        print(f"[{i:02d}/{len(steps):02d}] {step.action:<6} {step.arg or step.key}")

        if step.requires_data and not has_data:
            label = step.key or step.arg or step.action
            report.skipped.append(f"`{label}` —— 药箱为空，无药品可进入")
            print(f"      -- 跳过（药箱为空）")
            continue

        # ---------- 定位并点击 ----------
        if step.action in ("tab", "text", "desc", "wait"):
            target = None
            for _ in range(3):
                root = driver.dump_ui()
                if step.action == "tab":
                    target = find_by_text(root, step.arg, bottom_most=True)
                elif step.action == "text":
                    target = find_by_text(root, step.arg, step.index, step.contains)
                elif step.action == "desc":
                    target = find_by_desc(root, step.arg, step.index, step.contains)
                else:  # wait
                    target = True
                if target is not None:
                    break
                time.sleep(0.8)

            if target is None:
                fail(step, f"界面上找不到 {step.action}={step.arg!r}")
                continue
            if step.action == "wait":
                if step.expect and not expect_text(driver, step.expect):
                    fail(step, f"等待的文本 {step.expect!r} 未出现")
                continue

            node = clickable_ancestor(root, target)
            pos = center(node)
            if pos is None:
                fail(step, "目标节点没有有效 bounds")
                continue
            driver.tap(*pos)

            if step.expect and not expect_text(driver, step.expect):
                fail(step, f"点击后未跳到含 {step.expect!r} 的页面")
                continue

        elif step.action == "back":
            driver.back()
            if step.expect and not expect_text(driver, step.expect):
                fail(step, f"返回后未回到含 {step.expect!r} 的页面")
            # 兜底：从根页面按返回键会把 App 退到桌面，后续步骤就在桌面上
            # 乱点、乱滑（会把通知栏一路拉下来）。这里立刻查焦点，
            # 把"静默漂移到错误界面"变成一条看得见的失败 + 冷启动复位。
            if not driver.app_in_foreground():
                fail(step, "返回后 App 已不在前台（多半是从根页面按了返回）；已冷启动复位")
                driver.launch(cold=True)

        elif step.action in ("present_desc", "absent_desc"):
            # 负向断言是"未来不可操作"唯一守得住的形式：
            # 断言"看到了只读文案"并不能证明 ✓ 没被渲染出来（两者可以同时存在）。
            # present_desc 是它的对偶，用来防"修过头"——
            # 把今天也一起锁死时，只读文案照样在，只有这条会红。
            want_present = step.action == "present_desc"
            found = None
            for _ in range(3):
                root = driver.dump_ui()
                found = find_by_desc(root, step.arg, step.index, step.contains)
                if (found is not None) == want_present:
                    break
                time.sleep(0.8)
            if want_present and found is None:
                fail(step, f"本该出现的 {step.arg!r} 没有出现")
            if not want_present and found is not None:
                fail(step, f"本不该出现的 {step.arg!r} 出现了")

        elif step.action == "alert":
            # 锁屏全屏提醒页由系统在闹钟触发时拉起，界面里**点不到**它 ——
            # 用 debug 专属的 activity-alias（DevAlertEntry）模拟系统拉起，
            # 并带上与真实通知同源的 extras（药名 / 剂量 / 注意事项 / 推迟分钟）。
            # ⚠️ 别名只在 debug 包存在（走查脚本本来就只跑 `<pkg>.dev`）；若有人把它
            # 指向 release 包，这一步会失败 —— 那是预期行为，release 不该有走查入口。
            # 带空格的取值必须**显式加单引号**：`adb shell` 只把参数拼成一条命令串，
            # 不加引号会在设备侧被 shell 按空格切开（剂量会变成 "1"、注意事项整段丢掉）。
            driver.shell(
                "am", "start", "-n", ALERT_ACTIVITY,
                "--es", "extra_med_name", "环孢素",
                "--es", "extra_dose_text", "'1 片'",
                "--es", "extra_scheduled_time", "10:30",
                "--es", "extra_notice", "'随餐服用，避免空腹'",
                "--ez", "extra_is_critical", "false",
                "--ei", "extra_snooze_minutes", "30",
            )
            if step.expect and not expect_text(driver, step.expect):
                fail(step, f"全屏提醒页未出现 {step.expect!r}（debug 专属入口是否可用？）")
            continue

        elif step.action == "home":
            driver.launch(cold=True)
            if step.expect and not expect_text(driver, step.expect):
                fail(step, f"冷启动后未出现 {step.expect!r}")

        elif step.action == "scroll":
            driver.scroll_down()
            continue

        elif step.action == "shot":
            shot_seq += 1
            w, h = driver.screen_size()
            for n in range(step.shots):
                if n:
                    driver.scroll_down()
                name = f"{shot_seq:02d}_{step.key}.png" if n == 0 else f"{shot_seq:02d}_{step.key}_s{n + 1}.png"
                try:
                    size = driver.screenshot(out / name)
                except AdbError as exc:
                    fail(step, f"截图失败: {exc}")
                    break
                if dump_ui:
                    stem = f"{shot_seq:02d}_{step.key}" if n == 0 else f"{shot_seq:02d}_{step.key}_s{n + 1}"
                    root = driver.dump_ui()
                    (out / (stem + ".xml")).write_bytes(
                        ET.tostring(root, encoding="utf-8", xml_declaration=True)
                    )
                report.shots.append(
                    {
                        "file": name,
                        "page": step.key,
                        "note": step.note,
                        "scroll": n,
                        "size": f"{w}x{h}",
                        "bytes": size,
                    }
                )
                print(f"      -> {name}  ({size // 1024} KB)")
            if step.shots > 1:
                # 列表会记住滚动位置。不复位的话，从本页返回后页头标题在视口外，
                # 后续按标题/顶部图标定位的步骤会连环失败。
                driver.scroll_to_top(times=step.shots)
            continue

        else:  # pragma: no cover
            fail(step, f"未知动作 {step.action}")

    return report


# --------------------------------------------------------------------------- #
# 清单
# --------------------------------------------------------------------------- #
def write_manifest(out: Path, report: Report, device: str, serial: str,
                   mode: str) -> None:
    now = datetime.now(timezone(timedelta(hours=8)))
    lines = [
        "# CarroMed 全屏走查截图清单",
        "",
        f"- 生成时间：{now:%Y-%m-%d %H:%M:%S} (GMT+8)",
        f"- 设备：`{serial}` · {device}",
        f"- 数据形态：{mode}",
        f"- 脚本：`tools/app_screenshots.py`",
        "",
        "## 截图",
        "",
        "| # | 页面 | 截图 | 走查重点 |",
        "| --- | :--- | :--- | :--- |",
    ]
    for i, s in enumerate(report.shots, start=1):
        suffix = f"（第 {s['scroll'] + 1} 屏，已下滑）" if s["scroll"] else ""
        lines.append(
            f"| {i} | `{s['page']}`{suffix} | [{s['file']}]({s['file']}) | {s['note']} |"
        )
    lines += ["", "## 断言结果", ""]
    if report.problems:
        lines += ["以下步骤未通过（对应页面可能没截到或截错了）：", ""]
        lines += [f"- {p}" for p in report.problems]
    else:
        lines.append("全部导航断言通过，每张截图都来自预期页面。")
    if report.skipped:
        lines += ["", "以下页面因缺少前置数据被跳过：", ""]
        lines += [f"- {s}" for s in report.skipped]
    lines += [
        "",
        "## 复核提示",
        "",
        "1. 逐张看图，检查：文字截断、重叠、对比度、留白、空态文案、错别字。",
        "2. 对照 `docs/UI_DESIGN.md` 与 `docs/FINAL-UI.md` 的设计稿核对偏差。",
        "3. 界面有疑问时用 `--dump-ui` 再跑一次，会同时导出语义树 XML，"
        "可直接量 bounds 判断间距问题。",
        "",
    ]
    (out / "manifest.md").write_text("\n".join(lines), encoding="utf-8")


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def pick_device(explicit: str | None) -> str:
    if explicit:
        return explicit
    adb = shutil.which("adb")
    if not adb:
        raise AdbError("找不到 adb，请把 platform-tools 加入 PATH。")
    out = subprocess.run([adb, "devices"], stdout=subprocess.PIPE, text=True).stdout
    online = [
        line.split("\t")[0]
        for line in out.splitlines()[1:]
        if "\tdevice" in line
    ]
    if not online:
        raise AdbError("没有在线设备。请先启动模拟器（emulator -list-avds）。")
    # 优先模拟器：本脚本面向 UI 走查，真机息屏/分辨率差异会干扰断言
    for serial in online:
        if serial.startswith("emulator-"):
            return serial
    if len(online) > 1:
        print(f"   提示：检测到多台设备 {online}，未指定 --serial 时取第一台")
    return online[0]


def main() -> int:
    ap = argparse.ArgumentParser(
        description="CarroMed 全屏页面自动走查截图",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("--serial", help="目标设备序列号，默认自动挑选在线模拟器")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help=f"截图输出目录（默认 {DEFAULT_OUT}）")
    ap.add_argument("--only", help="只截指定页，逗号分隔，如 today,stats")
    ap.add_argument("--clear", action="store_true", help="跑之前 pm clear，回到首启空库")
    ap.add_argument("--seed", action="store_true", help="灌入演示数据（需 debug 包）")
    ap.add_argument("--install", action="store_true", help="先 ./gradlew assembleDebug 再安装")
    ap.add_argument("--dump-ui", action="store_true", help="同时导出每屏的 uiautomator XML")
    ap.add_argument("--keep", action="store_true", help="保留输出目录里的旧截图")
    ap.add_argument("--strict", action="store_true", help="任何步骤失败立即中止")
    ap.add_argument("--list", action="store_true", help="列出全部走查页面后退出")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印每条 adb 命令")
    args = ap.parse_args()

    if args.list:
        seen: list[tuple[str, str, bool]] = []
        for s in PROGRAM:
            if s.action == "shot" and s.key not in [x[0] for x in seen]:
                seen.append((s.key, s.note, s.requires_data))
        print("走查页面：")
        for key, note, needs_data in seen:
            mark = "  （需要至少一个药品，空库时跳过）" if needs_data else ""
            print(f"  {key:<18} {note}{mark}")
        return 0

    only = {x.strip() for x in args.only.split(",") if x.strip()} if args.only else set()
    unknown = only - {s.key for s in PROGRAM if s.action == "shot"}
    if unknown:
        print(f"未知的页面 key: {sorted(unknown)}", file=sys.stderr)
        return 2

    try:
        serial = pick_device(args.serial)
        driver = Driver(serial, verbose=args.verbose)
    except AdbError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1

    print(f"设备：{serial}")
    device_desc = driver.device_model()
    print(f"      {device_desc}")

    if args.install:
        print("编译 debug 包 ...")
        gradlew = "gradlew.bat" if platform.system() == "Windows" else "./gradlew"
        rc = subprocess.run(
            [gradlew, "assembleDebug"], cwd=REPO_ROOT
        ).returncode
        if rc != 0:
            print("编译失败", file=sys.stderr)
            return rc
        driver.install(REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk")

    if not driver.is_installed():
        print(f"设备上没装 {PKG}。先跑 --install，或手动安装 debug APK。", file=sys.stderr)
        return 1

    try:
        if args.clear:
            driver.clear_data()
        driver.grant_notifications()
        if args.seed:
            print("灌入演示数据（DevDataReceiver，仅 debug 包可用）")
            driver.dev_broadcast(DEV_SEED_ACTION)
        driver.launch(cold=True)

        # 空库探针：今日页首启引导卡的标题只有"一个药品都没有"时才会出现
        has_data = any_text_present(driver.dump_ui(), EMPTY_DB_MARKER) is False
        print(f"数据形态：{'有药品' if has_data else '空药箱'}")

        out = Path(args.out).resolve()
        report = run(driver, out, only, args.dump_ui, args.keep, args.strict, has_data)
    except AdbError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1

    mode = "有药品数据" if has_data else "首启空库（药箱为空）"
    write_manifest(out, report, device_desc, serial, mode)

    print(f"\n共 {len(report.shots)} 张截图 -> {out}")
    print(f"走查清单 -> {out / 'manifest.md'}")
    if report.skipped:
        print(f"因药箱为空跳过了 {len(report.skipped)} 个依赖药品的页面（见 manifest.md）")
    if report.problems:
        print(f"有 {len(report.problems)} 处断言未通过，见 manifest.md")
        return 3
    print("全部导航断言通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
