# T6-b：复现"IME 高度随窗口变矮而塌缩"——改 wm size 取两级 ime 高度实测
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5636"
$pkg = "com.xiufeigo.opencodeshell.web.debug"

function Probe([string]$label) {
    # 重新定位窗口原点（wm size 改过，freeform 窗口会重排）
    $all = & $adb -s $d shell dumpsys window windows
    $idx = -1
    for ($i = 0; $i -lt $all.Count; $i++) {
        if ($all[$i] -match "Window\{[0-9a-f]+ u0 $([regex]::Escape($pkg))/") { $idx = $i; break }
    }
    $fl = ($all[$idx..([Math]::Min($idx + 60, $all.Count - 1))] |
        Where-Object { $_ -match 'Frames:.*frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]' } | Select-Object -First 1)
    $fm = [regex]::Match($fl, 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $x1 = [int]$fm.Groups[1].Value; $y1 = [int]$fm.Groups[2].Value
    $y2 = [int]$fm.Groups[4].Value
    $winH = $y2 - $y1
    Write-Output ""
    Write-Output "########## $label ##########"
    Write-Output "window frame=[$x1,$y1][$($fm.Groups[3].Value),$y2]  height=${winH}px"

    # 先问页面 input 现在的视口中心（布局会随窗口高度变）
    & $adb -s $d logcat -c
    $jsPos = '(function(){var e=document.querySelector("input:not([disabled])");if(!e)return "NOINPUT";var r=e.getBoundingClientRect();return Math.round(r.x+r.width/2)+","+Math.round(r.y+r.height/2)})()'
    & $adb -s $d shell "am start -n $pkg/com.xiufeigo.opencodeshell.web.WebHostActivity --es oc_debug_eval '$jsPos'" | Out-Null
    Start-Sleep -Milliseconds 2800
    $posLine = & $adb -s $d logcat -d | Select-String -Pattern 'OcWebHost/Eval:' | Select-Object -Last 1
    $pm = [regex]::Match(($posLine -join ''), '-> "(\d+),(\d+)"')
    if (-not $pm.Success) { Write-Output "!! 拿不到 input 坐标: $posLine"; return }
    $IX = [int]$pm.Groups[1].Value; $IY = [int]$pm.Groups[2].Value
    Write-Output "input viewport center = $IX,$IY"

    # 点 input（视口中心 + 窗口原点）
    & $adb -s $d shell input tap ($x1 + $IX) ($y1 + $IY)
    Start-Sleep -Milliseconds 2500

    # [断言优先]
    $shown = & $adb -s $d shell dumpsys input_method | Select-String 'mInputShown'
    Write-Output "[ASSERT] $shown"
    if ($shown -notmatch 'true') { Write-Output "!! IME 未弹出，跳过本级数值"; return }

    # 平台原始 ime frame
    $imeLine = & $adb -s $d shell "dumpsys window | grep -m1 'InsetsSource id=.* type=ime'"
    Write-Output "RAW: $imeLine"
    $im = [regex]::Match(($imeLine -join ''), 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $iTop = [int]$im.Groups[2].Value; $iBot = [int]$im.Groups[4].Value
    Write-Output "  ime frame top=$iTop bottom=$iBot  => ime height=$($iBot - $iTop)px"

    # 平台给本窗口的 ime bottom hint
    $hint = & $adb -s $d shell "dumpsys window | grep -m1 'InsetsSourceControl: {.* mType=ime'"
    Write-Output "RAW: $hint"

    # 页面 CSS 变量 + 宿主上报的 native 值
    & $adb -s $d logcat -c
    $js = '(function(){var s=document.documentElement.style;var i=window.__ocAndroid.getInsets();return JSON.stringify({css_imeBottom:s.getPropertyValue("--oc-ime-bottom"),css_safeBottom:s.getPropertyValue("--oc-safe-bottom"),css_winH:s.getPropertyValue("--oc-window-height"),native:i})})()'
    & $adb -s $d shell "am start -n $pkg/com.xiufeigo.opencodeshell.web.WebHostActivity --es oc_debug_eval '$js'" | Out-Null
    Start-Sleep -Milliseconds 3000
    $ev = & $adb -s $d logcat -d | Select-String -Pattern 'OcWebHost/Eval:' | Select-Object -Last 1
    Write-Output "PAGE: $ev"
}

Write-Output "=== level 1: baseline wm size 1366x768 ==="
& $adb -s $d shell wm size 1366x768
& $adb -s $d shell wm density 160
Start-Sleep -Seconds 3
Probe "LEVEL-1 (1366x768)"

Write-Output ""
Write-Output "=== 改窗口高度：560（明显变矮），重启 app 让 freeform 窗口重排 ==="
& $adb -s $d shell wm size 1366x560
Start-Sleep -Seconds 2
& $adb -s $d shell am force-stop $pkg
Start-Sleep -Seconds 1
& $adb -s $d shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
Start-Sleep -Seconds 8
Probe "LEVEL-2 (1366x560)"

Write-Output ""
Write-Output "=== 改窗口高度：420（更矮），同样重启 ==="
& $adb -s $d shell wm size 1366x420
Start-Sleep -Seconds 2
& $adb -s $d shell am force-stop $pkg
Start-Sleep -Seconds 1
& $adb -s $d shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
Start-Sleep -Seconds 8
Probe "LEVEL-3 (1366x420)"
