# N1：通知"点击回到会话"闭环脚本（真机）
#
# 用法（只对自己的设备）：
#   .\n1-notify-click.ps1 -Serial emulator-5870
#
# 参数：
#   -Serial     目标 serial（默认 emulator-5870）
#   -Stage      warm | cold | both（默认 both）
#   -SessionId  通知里带的 sessionId（默认 n1-session-<时间戳>）
#
# 纪律：
#   * 只对本脚本 -Serial 指定的设备操作，绝不用 -a / 通配符。
#   * 不 kill adb server，不起停后端（后端由 Lead 起在 127.0.0.1:4095）。
param(
    [string]$Serial = 'emulator-5870',
    [ValidateSet('warm', 'cold', 'both')][string]$Stage = 'both',
    [string]$SessionId = ''
)

$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$ACT = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"

function A { & $adb -s $Serial @args }

function Section($t) { Write-Host "`n================ $t ================" }

if (-not $SessionId) { $SessionId = "n1-session-$([DateTimeOffset]::Now.ToUnixTimeSeconds())" }
Write-Host "serial=$Serial  sessionId=$SessionId  stage=$Stage"

Section 'ENV'
A shell getprop sys.boot_completed
A reverse tcp:4095 tcp:4095
A reverse --list

# ---------------------------------------------------------------------------
# 1. 触发通知（注入手段，**非**用户自然路径 —— 官方 UI 不会自己调 postMessage）
# ---------------------------------------------------------------------------
Section 'TRIGGER (injected call, not a user-driven UI path)'
A shell am force-stop $PKG
Start-Sleep -Seconds 2
A logcat -c

# 先起 app 走完启动链（要代理 + 页面），否则 evaluateJavascript 会落在 about:blank
A shell am start -n $ACT | Out-Null
Start-Sleep -Seconds 12

$js = 'JSON.stringify({href:location.href,facade:!!(window.__ocAndroid),ret:window.__ocAndroid.postMessage("N1-CLICK","N1 点击回会话 正文","' + $SessionId + '"),perm:window.__ocAndroid.hasNotificationPermission()})'
A shell am start -n $ACT --es oc_debug_eval $js | Out-Null
Start-Sleep -Seconds 6
Write-Host '--- OcWebHost/Eval (触发结果) ---'
A logcat -d -s OcWebHost/Eval | Select-String 'eval='

Section 'NOTIFICATION REALLY POSTED (dumpsys notification --noredact)'
$dump = A shell dumpsys notification --noredact
$rec = $dump | Select-String -Pattern "pkg=$PKG" -Context 0,6
if (-not $rec) {
    Write-Host "!! 没找到 $PKG 的通知记录"
    $dump | Select-String -Pattern 'N1-CLICK' -Context 3,3
    exit 1
}
Write-Host "RAW 记录片段："
$dump | Select-String -Pattern 'N1-CLICK|oc_session_id|oc_from_notification|channel=oc_turns|contentIntent=' -Context 1,1 | Select-Object -First 40

# ---------------------------------------------------------------------------
# 2. 展开通知栏 + uiautomator dump 取坐标（每轮重新解析，bounds 会跳变）
# ---------------------------------------------------------------------------
Section 'EXPAND SHADE + PARSE BOUNDS (uiautomator)'
A shell cmd statusbar expand-notifications
Start-Sleep -Seconds 3
A shell uiautomator dump /sdcard/n1_shade.xml | Out-Null
Start-Sleep -Seconds 1
$xml = (A shell cat /sdcard/n1_shade.xml) -join ''
A shell rm /sdcard/n1_shade.xml
if (-not $xml) { Write-Host '!! uiautomator dump 空'; exit 1 }

# 找含标题的节点，取它的 bounds
$m = [regex]::Match($xml, '<node[^>]*text="N1-CLICK"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $m.Success) {
    $m = [regex]::Match($xml, '<node[^>]*content-desc="N1-CLICK[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
}
if (-not $m.Success) {
    Write-Host '!! dump 里搜不到 N1-CLICK 节点，把含 N1 的行抄出来：'
    [regex]::Matches($xml, '<node[^>]*N1[^>]*>') | ForEach-Object { $_.Value }
    exit 2
}
$x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
$x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
$cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
Write-Host "NODE 原文: $($m.Value)"
Write-Host "BOUNDS=[$x1,$y1][$x2,$y2]  -> TAP $cx $cy"

# ---------------------------------------------------------------------------
# 3. 真点（input tap）。**不用** BACK(4) 关面板。
# ---------------------------------------------------------------------------
Section 'REAL TAP on the notification card'
A logcat -c
Write-Host "命令: input tap $cx $cy"
A shell input tap $cx $cy
Start-Sleep -Seconds 6

Section 'EVIDENCE A: foreground activity'
A shell dumpsys window | Select-String 'mCurrentFocus|mFocusedApp'
A shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 2

Section 'EVIDENCE B: intent extra (host log + dumpsys activity intents)'
Write-Host '--- 宿主消费日志 ---'
A logcat -d -s OcWebHost | Select-String 'opened from notification'
Write-Host '--- dumpsys activity intents ---'
A shell dumpsys activity intents | Select-String 'oc_session_id|oc_from_notification|WebHostActivity' -Context 1,1 | Select-Object -First 25

Section 'EVIDENCE C: page state (CDP / eval)'
$probe = 'JSON.stringify({href:location.href,facade:!!(window.__ocAndroid),text:(document.body?document.body.innerText.slice(0,400):"")})'
A shell am start -n $ACT --es oc_debug_eval $probe | Out-Null
Start-Sleep -Seconds 5
A logcat -d -s OcWebHost/Eval | Select-String 'eval=' | Select-Object -Last 2

Section 'SCREENSHOT'
A shell screencap -p /sdcard/n1_after_tap.png
A pull /sdcard/n1_after_tap.png (Join-Path $PSScriptRoot 'n1_after_tap.png') | Out-Null
Write-Host "saved: $(Join-Path $PSScriptRoot 'n1_after_tap.png')"

Section 'DONE'
Write-Host "sessionId=$SessionId"
