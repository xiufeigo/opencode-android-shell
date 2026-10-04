# T6 IME/insets：一次性完成"点击 -> 断言 mInputShown -> 抓 ime frame 原文 -> 抓页面 CSS 变量"
# 关键：中间不能插 am start（会把 IME 收掉），所以全部放同一条命令里。
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5636"
$pkg = "com.xiufeigo.opencodeshell.web.debug"

# 动态取我们窗口的 frame 原点（freeform 下窗口会移动，不能写死）
$all = & $adb -s $d shell dumpsys window windows
$idx = -1
for ($i = 0; $i -lt $all.Count; $i++) {
    if ($all[$i] -match "Window\{[0-9a-f]+ u0 $([regex]::Escape($pkg))/") { $idx = $i; break }
}
if ($idx -lt 0) { Write-Output "!! 找不到宿主窗口"; exit 1 }
$frameLine = ($all[$idx..([Math]::Min($idx + 60, $all.Count - 1))] |
    Where-Object { $_ -match 'Frames:.*frame=\[(\d+),(\d+)\]' } | Select-Object -First 1)
if (-not $frameLine) { Write-Output "!! 取不到 frame"; exit 1 }
Write-Output "=== window frame: $($frameLine.Trim()) ==="
$m = [regex]::Match($frameLine, 'frame=\[(\d+),(\d+)\]')
$OX = [int]$m.Groups[1].Value
$OY = [int]$m.Groups[2].Value

# 视口里 input 的中心
$IX = 165; $IY = 127
$sx = $OX + $IX; $sy = $OY + $IY
Write-Output "=== tap screen coords: $sx,$sy (origin $OX,$OY + $IX,$IY) ==="

& $adb -s $d shell input tap $sx $sy
Start-Sleep -Milliseconds 2500

Write-Output "=== [1] ASSERT mInputShown (读任何 ime 数值前必做) ==="
$shown = & $adb -s $d shell dumpsys input_method | Select-String 'mInputShown'
Write-Output $shown
if ($shown -notmatch 'mInputShown=true') {
    Write-Output "!! IME 未弹出，终止（不读 ime 数值）"
    exit 1
}

Write-Output ""
Write-Output "=== [2] RAW ime frame lines ==="
Write-Output "--- (a) dumpsys window insets ---"
& $adb -s $d shell dumpsys window insets 2>&1 | Select-String -Pattern 'ime|IME' | Select-Object -First 10
Write-Output "--- (b) our window insetsState (type=IME) ---"
$hit = $all | Select-String -Pattern 'type=IME|type=ime' | Select-Object -First 6
if ($hit) { $hit } else { Write-Output "(no type=IME line in dumpsys window windows)" }
Write-Output "--- (c) ime window frame ---"
& $adb -s $d shell dumpsys window windows | Select-String -Pattern 'InputMethod\}' -Context 0,30 | Select-String -Pattern 'Frames:' | Select-Object -First 1

Write-Output ""
Write-Output "=== [3] platform-reported insets to our window (navigationBars/systemGestures) ==="
$all2 = & $adb -s $d shell dumpsys window windows
$all2 | Select-String -Pattern 'InsetsSource\#[0-9]+ \{type=(ime|navigationBars|statusBars)' | Select-Object -First 8

Write-Output ""
Write-Output "=== [4] 页面拿到的 CSS 安全区变量（同一时刻读，与上面同帧对比）==="
& $adb -s $d logcat -c
$js = '(function(){var s=document.documentElement.style;var g=function(n){return s.getPropertyValue(n)};var i=window.__ocAndroid.getInsets();return JSON.stringify({css_imeBottom:g("--oc-ime-bottom"),css_safeBottom:g("--oc-safe-bottom"),css_imeVisible:g("--oc-ime-visible"),css_scheme:g("--oc-scheme"),native:i})})()'
& $adb -s $d shell "am start -n $pkg/com.xiufeigo.opencodeshell.web.WebHostActivity --es oc_debug_eval '$js'" | Out-Null
Start-Sleep -Milliseconds 3000
& $adb -s $d logcat -d | Select-String -Pattern 'OcWebHost/Eval:' | Select-Object -Last 1
