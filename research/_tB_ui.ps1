# tB_ui.ps1 — B-12~B-19 抽查用 UI 读取/操作/截图封装
# 目的：adbui.ps1 的 summary/texts 在 Git Bash 下中文被当成 GBK 显示，这里强制 UTF-8 输出；
# 截图直接落到 research/v30_verify/tB_ui_spot/，避免再手工搬文件。
param(
    [ValidateSet('summary', 'texts', 'shot', 'tap', 'desc', 'key', 'swipe', 'size')]
    [string]$Action = 'summary',
    [string]$Text = '',
    [string]$Name = 'shot',
    [string]$From = '',
    [string]$To = '',
    [int]$Code = 0,
    [int]$Duration = 400,
    [switch]$Contains,
    [int]$MinY = 0,
    [int]$MaxY = 99999,
    [switch]$Tap
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$serial = 'emulator-5558'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp = Join-Path $env:TEMP 'tB_ui.xml'
$dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v30_verify\tB_ui_spot'

function Get-Ui {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & $adb -s $serial shell rm -f /sdcard/tB_ui.xml 2>&1 | Out-Null
    $dump = (& $adb -s $serial shell uiautomator dump /sdcard/tB_ui.xml 2>&1) -join ' '
    if ($LASTEXITCODE -ne 0 -or $dump -notmatch 'dumped to') { throw "dump failed: $dump" }
    & $adb -s $serial pull /sdcard/tB_ui.xml $tmp 2>&1 | Out-Null
    if (-not (Test-Path $tmp)) { throw 'pull failed' }
    return [xml](Get-Content $tmp -Raw -Encoding UTF8)
}

function Tap-Node($n, [string]$label) {
    # XmlElement 自身支持 [0] 索引（取子元素），单命中时会索引到子节点而不是节点本身，
    # 所以先强制成数组再取首个。
    $n = @($n)[0]
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "BAD BOUNDS $b" }
    $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $adb -s $serial shell input tap $cx $cy | Out-Null
    "tapped '$label' @ $cx,$cy"
}

switch ($Action) {
    'summary' { (Get-Ui).SelectNodes('//node[@text!=""]') | ForEach-Object { $_.text } | ForEach-Object { "  $_" } }
    'texts' {
        (Get-Ui).SelectNodes('//node[@text!=""]') | ForEach-Object { "  $($_.text)  @$($_.bounds)" }
        '--- content-desc ---'
        (Get-Ui).SelectNodes('//node[@content-desc!=""]') | ForEach-Object { "  $($_.GetAttribute('content-desc'))  @$($_.bounds)" }
    }
    'shot' {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & $adb -s $serial shell screencap -p /sdcard/$Name.png
        & $adb -s $serial pull /sdcard/$Name.png (Join-Path $dir "$Name.png") | Out-Null
        "saved research/v30_verify/tB_ui_spot/$Name.png"
    }
    'tap' {
        # 抽屉/设置页常有同名节点（首页标题 "Projects" vs 设置页 tab "Projects"），
        # 所以按 Y 区间挑，避免点到被遮住的背景节点。
        $hit = (Get-Ui).SelectNodes('//node') | Where-Object {
            $b = $_.GetAttribute('bounds')
            if ($b -notmatch '\[(\d+),(\d+)\]') { return $false }
            $y = [int]$Matches[2]
            if ($y -lt $MinY -or $y -gt $MaxY) { return $false }
            if ($Contains) { $_.text -and $_.text.Contains($Text) } else { $_.text -eq $Text }
        }
        if (-not $hit) { "NOT FOUND (text='$Text')"; exit 2 }
        "found '$($hit[0].text)' count=$(@($hit).Count)"
        Tap-Node $hit $Text
    }
    'desc' {
        $hit = (Get-Ui).SelectNodes('//node') | Where-Object { $_.GetAttribute('content-desc') -eq $Text }
        if (-not $hit) { "NOT FOUND (desc='$Text')"; exit 2 }
        "found desc '$Text' count=$(@($hit).Count)"
        if ($Tap) { Tap-Node $hit $Text }
    }
    'key' { & $adb -s $serial shell input keyevent $Code; "keyevent $Code" }
    'swipe' { & $adb -s $serial shell input swipe $From $To $Duration; "swiped" }
    'size' {
        "wm size: $((& $adb -s $serial shell wm size) -join ' ')"
        "density : $((& $adb -s $serial shell wm density) -join ' ')"
    }
}
