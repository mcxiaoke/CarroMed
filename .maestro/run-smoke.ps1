# Maestro 试点驱动脚本（PLAN-UI-TEST-20260929.md P3）
#
# 用法：powershell -File .maestro\run-smoke.ps1
#
# 为什么需要驱动：Maestro flow 里没有稳定的 adb 通道，种子数据必须
# 在 flow 启动前通过 dev.SEED 广播灌入（AGENTS.md §5：App 进程要先活着）。
#
# 输出：截图按 flow 里的 takeScreenshot 名字落在当前目录
#（Maestro 默认行为），报告走 `maestro test` 的 stdout。

$ErrorActionPreference = "Stop"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$serial = "emulator-5554"

adb -s $serial shell am start -n com.mcxiaoke.carromed/.MainActivity | Out-Null
Start-Sleep -Seconds 2
# 先清后灌：保证每次跑都是同一份数据形态
adb -s $serial shell am broadcast -a com.mcxiaoke.carromed.dev.CLEAR -n com.mcxiaoke.carromed/.DevDataReceiver | Out-Null
Start-Sleep -Seconds 2
adb -s $serial shell am broadcast -a com.mcxiaoke.carromed.dev.SEED -n com.mcxiaoke.carromed/.DevDataReceiver | Out-Null
Start-Sleep -Seconds 2

maestro test "$PSScriptRoot\smoke-seeded.yaml"
