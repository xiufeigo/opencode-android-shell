$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s    = 'emulator-5910'
$pkg  = 'com.xiufeigo.opencodeshell.web.debug'
$act  = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
$out  = Join-Path $root 'research\web\_g2_coldstart.txt'
Remove-Item $out -EA SilentlyContinue
function W($t){ $t | Tee-Object -FilePath $out -Append }

function Adb($a) { & $adb -s $s @a 2>&1 }

# ---- device fingerprint (recorded for this round) ----
W "################ G2 cold start: >=5 runs, UI-usable marker (NOT last _assets GET) ################"
W "== device fingerprint =="
W ("serial              = {0}" -f $s)
W ("avd                 = OC_Deliver_R2 (cloned from verified OC_FF_F4 combo)")
W ("port                = 5910")
W ("geometry            = {0}" -f ((Adb @('shell','wm','size') | Out-String).Trim() -replace "`r?`n",' | '))
W ("density             = {0}" -f ((Adb @('shell','wm','density') | Out-String).Trim() -replace "`r?`n",' | '))
W ("webview             = {0}" -f ((Adb @('shell','dumpsys','package','com.google.android.webview') | Select-String 'versionName' | Select-Object -First 1)))
W ("avd hw             = {0}" -f (Get-Content "$env:USERPROFILE\.android\avd\OC_Deliver_R2.avd\config.ini" -EA SilentlyContinue | Select-String 'hw.device.name|hw.lcd|disk.dataPartition' | ForEach-Object { $_.Line.Trim() } | Out-String).Trim() -replace "`r?`n",' ; ')
W ("apk under test      = research/deliver/opencode-shell-web.apk (debug, md5 b716cbeb4c66e55fcb0f497640ae5e0c)")
W ""

# ---- seed: make sure backend reverse is present ----
Adb @('reverse','tcp:4095','tcp:4095') | Out-Null

$runs = @()
foreach ($i in 1..5) {
  W "==================== RUN $i ===================="
  Adb @('shell','am','force-stop',$pkg) | Out-Null
  Start-Sleep -Seconds 3
  Adb @('logcat','-c') | Out-Null
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Adb @('shell','am','start','-n',"$pkg/$act") | Out-Null
  # wait for UI-usable markers, poll logcat
  $firstContent = $null; $firstDraw = $null; $pageFinished = @()
  for ($p=1; $p -le 60; $p++) {
    Start-Sleep -Milliseconds 500
    $raw = (Adb @('logcat','-d','-s','OcPerf:V','OcPerfJS:V','OcWebHost:V','*:S')) -join "`n"
    if ($firstDraw    -eq $null -and $raw -match 'Z\.firstDraw t=(\d+)ms')            { $firstDraw = [int]$Matches[1] }
    if ($firstContent -eq $null -and $raw -match 'M\.g1\.firstContent t=(\d+)ms')   { $firstContent = [int]$Matches[1] }
    if ($firstContent -ne $null -and $firstDraw -ne $null) { break }
  }
  Start-Sleep -Seconds 2
  $raw = (Adb @('logcat','-d','-s','OcPerf:V','OcPerfJS:V','OcWebHost:V','*:S')) -join "`n"
  $raw | Set-Content (Join-Path $root ("research\web\_g2_run{0}_logcat.txt" -f $i)) -Encoding UTF8
  W "--- marker lines (raw) ---"
  ($raw -split "`r?`n") | Where-Object { $_ -match 'Z\.firstDraw|M\.g1\.firstContent|L\.onPageFinished|P\.fwd|A\.onCreate\.enter' } | ForEach-Object { W ("  " + $_.Trim()) }
  W ("Z.firstDraw          = {0} ms" -f $(if($firstDraw -ne $null){$firstDraw}else{'MISSING'}))
  W ("M.g1.firstContent    = {0} ms   <== FCP / UI-usable marker (primary)" -f $(if($firstContent -ne $null){$firstContent}else{'MISSING'}))
  $fwd = ([regex]::Matches($raw,'P\.fwd')).Count
  W ("P.fwd asset GET count= {0}  (explicitly NOT used as first-screen)" -f $fwd)
  W ""
  $runs += [pscustomobject]@{ run=$i; firstDraw=$firstDraw; firstContent=$firstContent; fwd=$fwd }
}

W "################ SUMMARY (UI-usable marker = M.g1.firstContent) ################"
$fc = @($runs | Where-Object { $_.firstContent -ne $null } | ForEach-Object { $_.firstContent })
$fd = @($runs | Where-Object { $_.firstDraw -ne $null } | ForEach-Object { $_.firstDraw })
$fcSorted = $fc | Sort-Object
$fdSorted = $fd | Sort-Object
function Median($a){ if($a.Count -eq 0){return $null}; $n=$a.Count; if($n%2 -eq 1){return $a[[int]($n/2)]} else { return [math]::Round(($a[$n/2-1]+$a[$n/2])/2,1) } }
W ("runs captured       = {0} / 5" -f $fc.Count)
W ("firstContent values = {0}" -f ($fc -join ', '))
W ("firstContent median = {0} ms" -f (Median $fcSorted))
W ("firstContent min/max= {0} / {1} ms" -f $fcSorted[0], $fcSorted[-1])
W ("firstDraw    values = {0}" -f ($fd -join ', '))
W ("firstDraw    median = {0} ms" -f (Median $fdSorted))
W ("firstDraw    min/max= {0} / {1} ms" -f $fdSorted[0], $fdSorted[-1])
W ("R2 single-run value (for side-by-side): firstContent=1945 ms, firstDraw=1124 ms  <-- R2 §5")
$runs | ConvertTo-Json | Set-Content (Join-Path $root 'research\web\_g2_coldstart.json') -Encoding UTF8
W "DONE"
