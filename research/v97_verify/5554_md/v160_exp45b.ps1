$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser = '-s'; $dev = 'emulator-5554'
function A { & $adb $ser $dev @args }

function Dump-App {
    $ww = A shell dumpsys window windows 2>&1
    $idx = @()
    for ($i=0; $i -lt $ww.Count; $i++) { if ($ww[$i] -match 'Window #\d+ Window\{[0-9a-f]+ u0 com\.xiufeigo\.opencodeshell') { $idx += $i } }
    if ($idx.Count -eq 0) { Write-Host "  NO APP WINDOW BLOCK"; return }
    $i = $idx[-1]
    Write-Host "  --- block: $($ww[$i].Trim())"
    $blk = $ww[$i..([Math]::Min($i+40, $ww.Count-1))]
    foreach ($pat in @('mFrame=','mVisibleFrame=','mCompatFrame=','softInputMode','mGivenContentInsets','mViewVisibility=','isReadyForDisplay')) {
        $m = $blk | Select-String $pat | Select-Object -First 1
        if ($m) { Write-Host ("    " + $m.Line.Trim()) }
    }
}

Write-Host "########## EXP-4: is the APP WINDOW being resized (ADJUST_RESIZE)? ##########"
foreach ($H in @(768, 620, 600, 599)) {
    A shell wm size "1366x$H" | Out-Null
    Start-Sleep -Milliseconds 2200
    Write-Host "== H=$H =="
    $w = A shell dumpsys window 2>&1
    Write-Host ("    ime: " + (($w | Select-String 'type=ime frame=' | Select-Object -First 1).Line).Trim())
    Dump-App
}

Write-Host ""
Write-Host "########## EXP-5: logcat for ONE wm change (600 -> 599) ##########"
A shell wm size "1366x600" | Out-Null
Start-Sleep -Seconds 3
$raw = (A shell "date +%m-%d_%H:%M:%S" 2>&1).Trim()
$mark = ($raw -replace '_',' ') + '.000'
Write-Host "  marker = $mark"
A shell wm size "1366x599" | Out-Null
Start-Sleep -Seconds 4
$lg = A shell logcat -d -T $mark 2>&1
Write-Host "  ---- total lines since marker: $(($lg).Count) ----"
$hit = $lg | Select-String 'config|Insets|insets|relayout|resize|Resiz|WindowManager|InputMethod|ime'
Write-Host "  ---- matched: $(($hit).Count) ----"
$hit | ForEach-Object { $_.Line } | Set-Content -Encoding UTF8 "$env:TEMP\_v160_logcat.txt"
$hit | Select-Object -First 60 | ForEach-Object { $_.Line }
