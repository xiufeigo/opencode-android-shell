$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser = '-s'; $dev = 'emulator-5554'
function A { & $adb $ser $dev @args }

function Dump-App {
    $ww = A shell dumpsys window windows 2>&1
    $hit = $ww | Select-String 'com\.xiufeigo\.opencodeshell/com\.xiufeigo\.opencodeshell\.OcActivity\}' |
           Where-Object { $_.Line -match '^\s+Window\{' }
    $lines = $ww
    $idx = @()
    for ($i=0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^\s+Window\{[0-9a-f]+ u0 com\.xiufeigo\.opencodeshell') { $idx += $i } }
    if ($idx.Count -eq 0) { Write-Host "  NO APP WINDOW BLOCK FOUND"; return }
    $i = $idx[-1]
    Write-Host "  --- app window block (line $($i+1)) ---"
    $blk = $lines[$i..([Math]::Min($i+38, $lines.Count-1))]
    foreach ($pat in @('mFrame=','mVisibleFrame=','mCompatFrame=','softInputMode=','mAttrs=','fitsSystemWindows','mGivenContentInsets')) {
        $m = $blk | Select-String $pat | Select-Object -First 1
        if ($m) { Write-Host ("  " + $m.Line.Trim()) }
    }
    $sf = $blk | Select-String 'softInputMode' | Select-Object -First 1
    if ($sf) { Write-Host ("  RAW-SOFT: " + $sf.Line.Trim()) }
}

Write-Host "########## EXP-4: is ADJUST_RESIZE actually resizing the app window? ##########"
foreach ($H in @(768, 600, 599)) {
    A shell wm size "1366x$H" | Out-Null
    Start-Sleep -Milliseconds 2200
    Write-Host "== H=$H =="
    $w = A shell dumpsys window 2>&1
    $f = $w | Select-String 'type=ime frame=' | Select-Object -First 1
    Write-Host ("  ime: " + $f.Line.Trim())
    Dump-App
}

Write-Host ""
Write-Host "########## EXP-5: logcat pairing for ONE wm change (600 -> 599) ##########"
A shell wm size "1366x600" | Out-Null
Start-Sleep -Seconds 3
$mark = (A shell date '+%m-%d %H:%M:%S.000' 2>&1).Trim()
Write-Host "  device-time marker before the single 600->599 change: $mark"
A shell wm size "1366x599" | Out-Null
Start-Sleep -Seconds 4
$lg = A shell logcat -d -T $mark 2>&1
Write-Host "  ---- total log lines since marker: $(($lg).Count) ----"
$pat = 'config|Insets|insets|relayout|resize|Resiz|ime|IME|WindowManager'
$hit = $lg | Select-String $pat
Write-Host "  ---- matched lines: $(($hit).Count) ----"
$hit | ForEach-Object { $_.Line } | Set-Content -Encoding UTF8 "$env:TEMP\_v160_logcat.txt"
$hit | Select-Object -First 70 | ForEach-Object { $_.Line }
Write-Host ""
Write-Host "  ---- app-side (xiufeigo) lines ----"
($lg | Select-String 'xiufeigo|opencodeshell' | Select-Object -First 40 | ForEach-Object { $_.Line })
