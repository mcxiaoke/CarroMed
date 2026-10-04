$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot
$files = Get-ChildItem -Recurse "$root\app\src\main\kotlin", "$root\app\src\debug\kotlin", "$root\app\src\androidTest\kotlin" -Filter *.kt
$out = New-Object System.Text.StringBuilder
$total = 0
$totalUnique = @()
foreach ($f in $files) {
    $inBlock = $false
    $hits = @()
    foreach ($line in Get-Content $f.FullName -Encoding UTF8) {
        $t = $line.Trim()
        $isComment = $false
        if ($inBlock) { $isComment = $true }
        if ($t -match '^\*') { $isComment = $true }
        if ($t -match '^//') { $isComment = $true }
        if ($t -match '/\*') { $inBlock = $true; $isComment = $true }
        if ($t -match '\*/') { $inBlock = $false }
        if (-not $isComment -and $t -match '"([^"]*[\u4e00-\u9fff][^"]*)"') {
            $hits += $Matches[1]
        }
    }
    if ($hits.Count -gt 0) {
        $rel = $f.FullName.Replace($root + '\', '')
        [void]$out.AppendLine(('{0,4}  {1}' -f $hits.Count, $rel))
        foreach ($h in ($hits | Select-Object -First 4)) {
            [void]$out.AppendLine(('        ' + $h))
        }
        $total += $hits.Count
        $totalUnique += $hits
    }
}
[void]$out.AppendLine('')
[void]$out.AppendLine(('TOTAL literals: ' + $total))
[void]$out.AppendLine(('TOTAL unique:   ' + ($totalUnique | Sort-Object -Unique).Count))
# 报告落到 temp/：项目根目录只放正式产物，临时/中间产物一律进 temp（见 SOUL §3）
$reportDir = Join-Path $root 'temp'
if (-not (Test-Path $reportDir)) { New-Item -ItemType Directory -Path $reportDir | Out-Null }
$out.ToString() | Out-File (Join-Path $reportDir 'hardcoded_strings_report.txt') -Encoding UTF8
Write-Output "done, total=$total"
