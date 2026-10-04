param([string]$Serial = 'emulator-6190', [int]$ColdStarts = 5)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$out = "$PSScriptRoot\out_v9"; New-Item -ItemType Directory -Force -Path $out | Out-Null
$logAll = "$out\perf_raw.txt"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
function Say($m) { Write-Host $m; Add-Content $logAll -Encoding UTF8 ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Raw($m) { Add-Content $logAll -Encoding UTF8 $m }
Set-Content $logAll ("# V9 perf raw  " + (Get-Date -Format s))
function Sh($c) { ((& $adb -s $Serial shell $c 2>&1) | Out-String).Trim() }
function DevTime { [double]([System.Diagnostics.Stopwatch]::GetTimestamp() / [System.Diagnostics.Stopwatch]::Frequency) }
function Dismiss-Dialog {
  for ($i = 0; $i -lt 10; $i++) {
    & $adb -s $Serial shell uiautomator dump /sdcard/v9_ui.xml 2>&1 | Out-Null
    $xml = (& $adb -s $Serial shell cat /sdcard/v9_ui.xml) | Out-String
    $m = [regex]::Match($xml, 'text="继续加载"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
      $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value)/2); $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value)/2)
      Say "  F5-401 dialog: tap 继续加载 at ($cx,$cy)"
      & $adb -s $Serial shell input tap $cx $cy | Out-Null; return $true
    }
    if ($xml -notmatch '登录没通过') { return $false }
    Start-Sleep -Seconds 2
  }
  return $false
}
function Wait-UiAvailable($timeoutSec) {
  for ($i = 0; $i -lt [int]($timeoutSec*2); $i++) {
    $l = (& $adb -s $Serial logcat -d -s OcWebHost:* 2>&1) | Out-String
    if ($l -match 'OcPerfJS res #\d+ .*?/api/event') { return $true }
    Start-Sleep -Milliseconds 400
  }
  return $false
}

# ---------- geometry = historical perf axis (P4/P5/B1) ----------
Say "=== [0] set perf geometry 1080x2400@420 (411dp wide; historical P4/P5/B1 axis) ==="
Raw "  `$(wm size 1080x2400)  => " + (Sh "wm size 1080x2400")
Raw "  `$(wm density 420)     => " + (Sh "wm density 420")
Start-Sleep -Seconds 4
Raw "  READBACK wm size:";     foreach ($l in ((Sh "wm size")     -split "`r?`n")) { Raw "    $l" }
Raw "  READBACK wm density:";  foreach ($l in ((Sh "wm density")  -split "`r?`n")) { Raw "    $l" }
$cfg = Sh "dumpsys activity activities | grep -oE 'sw[0-9]+dp w[0-9]+dp h[0-9]+dp [0-9]+dpi [a-z]+ [a-z]+'"
Raw "  SYSTEM CONFIG: $cfg"
Say "  $cfg"

# ---------- [1] cold starts ----------
$cold = @()
for ($i = 1; $i -le $ColdStarts; $i++) {
  Say ""
  Say "=== [1.$i] cold start #$i ==="
  & $adb -s $Serial shell am force-stop $pkg | Out-Null; Start-Sleep -Seconds 3
  & $adb -s $Serial logcat -c | Out-Null
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $amw = (& $adb -s $Serial shell "am start -W -n $act" 2>&1) | Out-String
  $amTotal = [int][regex]::Match($amw,'TotalTime:\s*(\d+)').Groups[1].Value
  Raw "  RUN#$i am start -W:"; foreach ($l in ($amw -split "`r?`n")) { Raw "    $l" }
  Start-Sleep -Seconds 3
  $dlg = Dismiss-Dialog
  $hit = Wait-UiAvailable 70
  $sw.Stop()
  $uiMs = if ($hit) { [int]$sw.ElapsedMilliseconds } else { -1 }
  $log = (& $adb -s $Serial logcat -d 2>&1) | Out-String
  # first _assets GET timestamp vs launch (for contrast only; NOT the criterion)
  $assetFirst = -1
  $am = [regex]::Match($log,'OcWebHost/Proxy: GET http://127\.0\.0\.1:4095/_assets/')
  $lastAsset = -1
  $all = [regex]::Matches($log,'OcWebHost/Proxy: GET ')
  if ($all.Count -gt 0) { $lastAsset = $all.Count }
  $perfN = ([regex]::Matches($log,'OcPerfJS res #')).Count
  $c401 = ([regex]::Matches($log,'401')).Count
  Say "  RUN#$i  UI_AVAILABLE=$uiMs ms  amTotalTime=$amTotal ms  dialog=$dlg  proxyGET=$lastAsset  OcPerfJS=$perfN"
  Raw "  RUN#$i UI_AVAILABLE_MS=$uiMs AM_TOTAL_MS=$amTotal DIALOG=$dlg PROXY_GET=$lastAsset OCRES=$perfN LOG401=$c401"
  $cold += [pscustomobject]@{ run = $i; uiAvailableMs = $uiMs; amTotalMs = $amTotal; dialogDismissed = $dlg; proxyGET = $lastAsset }
}
$vals = @($cold | Where-Object { $_.uiAvailableMs -gt 0 } | ForEach-Object { [int]$_.uiAvailableMs })
$sorted = $vals | Sort-Object
$median = if ($sorted.Count -gt 0) { $sorted[[int][Math]::Floor(($sorted.Count-1)/2)] } else { -1 }
$min = if ($sorted.Count) { $sorted[0] } else { -1 }
$max = if ($sorted.Count) { $sorted[-1] } else { -1 }
$avg = if ($sorted.Count) { [int](($sorted | Measure-Object -Average).Average) } else { -1 }
Say ""
Say "=== COLD START SUMMARY (UI-available = first SPA /api/event poll) ==="
Say "  samples = $($sorted -join ', ')"
Say "  median=$median ms  min=$min ms  max=$max ms  mean=$avg ms  n=$($sorted.Count)"
Raw "COLD_SUMMARY samples=[$($sorted -join ',')] median=$median min=$min max=$max mean=$avg n=$($sorted.Count)"
$cold | ConvertTo-Json -Depth 4 | Set-Content "$out\coldstart.json" -Encoding UTF8

# ---------- [2] input latency proxy ----------
Say ""
Say "=== [2] input latency proxy: inject keys -> frame completed (framestats HandleInputStart->FrameCompleted) ==="
$wPx = 1080; $hPx = 2400
$focused = 'false'; $fpt = ''
foreach ($c in @(@(0.50,0.93),@(0.50,0.90),@(0.50,0.96),@(0.35,0.93),@(0.65,0.93),@(0.50,0.85),@(0.50,0.80))) {
  $tx = [int]($wPx*$c[0]); $ty = [int]($hPx*$c[1])
  & $adb -s $Serial shell input tap $tx $ty | Out-Null; Start-Sleep -Seconds 3
  $im = Sh "dumpsys input_method | grep -m1 mInputShown"
  if ($im -match 'mInputShown=true') { $focused='true'; $fpt="($tx,$ty)"; break }
}
Say "  composer focus(IME shown)=$focused at $fpt"
Raw "  FOCUS imeShown=$focused at=$fpt"
$lines = (Sh "dumpsys input_method") -split "`r?`n"
foreach ($l in $lines) { if ($l -match 'mInputShown|mServedView|mCurFocusedWindow') { Raw "  IME: $($l.Trim())" } }
if ($focused -eq 'true') {
  & $adb -s $Serial shell "dumpsys gfxinfo $pkg reset" | Out-Null
  Start-Sleep -Seconds 2
  $tA = DevTime
  & $adb -s $Serial shell input text "opencode" | Out-Null
  $tB = DevTime
  Start-Sleep -Seconds 6
  $fs = (& $adb -s $Serial shell "dumpsys gfxinfo $pkg framestats" 2>&1) | Out-String
  $fs | Set-Content "$out\framestats_raw.txt" -Encoding UTF8
  Raw "  framestats bytes=$($fs.Length)  injectWall=$([int](($tB-$tA)*1000))ms"
  $prof = $fs -split "`r?`n"
  $hdrIdx = -1
  for ($i=0;$i -lt $prof.Count;$i++){ if ($prof[$i] -match 'Flags,.*FrameCompleted|Flags,.*SwapBuffersCompleted') { $hdrIdx=$i; break } }
  $hdr = @()
  if ($hdrIdx -ge 0) { $hdr = $prof[$hdrIdx].Trim().Split(',') | ForEach-Object { $_.Trim() } ; Raw "  FRAMESTATS HEADER: $($hdr -join ',')" }
  $iH = [array]::IndexOf($hdr,'HandleInputStart'); $iF = [array]::IndexOf($hdr,'FrameCompleted'); $iS = [array]::IndexOf($hdr,'SwapBuffersCompleted')
  $iO = [array]::IndexOf($hdr,'OldestInputEvent'); $iN = [array]::IndexOf($hdr,'NewestInputEvent')
  $i2f=@(); $i2s=@()
  for ($i=$hdrIdx+1;$i -lt $prof.Count;$i++) {
    $ln = $prof[$i].Trim()
    if ($ln -notmatch '^[\d,\.]+,[\d,\.]+,') { continue }
    $c = $ln.Split(',') | ForEach-Object { $_.Trim() }
    if ($c.Count -lt $hdr.Count) { continue }
    if ($iH -ge 0 -and $iF -ge 0) {
      $h = $c[$iH]; $f = $c[$iF]
      if ($h -ne '0' -and $f -ne '0' -and $f -ne '18446744073709551615') { $i2f += ([double]$f - [double]$h) }
      if ($iS -ge 0) { $s = $c[$iS]; if ($h -ne '0' -and $s -ne '0' -and $s -ne '18446744073709551615') { $i2s += ([double]$s - [double]$h) } }
    }
  }
  $i2f = $i2f | Where-Object { $_ -ge 0 -and $_ -lt 20000 } | Sort-Object
  $i2s = $i2s | Where-Object { $_ -ge 0 -and $_ -lt 20000 } | Sort-Object
  function Pct($a,$p){ if($a.Count -eq 0){return -1}; $k=[int][Math]::Ceiling($p*$a.Count)-1; if($k -lt 0){$k=0}; if($k -ge $a.Count){$k=$a.Count-1}; return [int][Math]::Round($a[$k]) }
  $p50=Pct $i2f 0.50; $p90=Pct $i2f 0.90; $p95=Pct $i2f 0.95; $p99=Pct $i2f 0.99
  $s50=Pct $i2s 0.50; $s90=Pct $i2s 0.90
  Say "  input frames n=$($i2f.Count)  i2f p50=$p50 p90=$p90 p95=$p95 p99=$p99 ms | i2s p50=$s50 p90=$s90 ms"
  Raw "  I2F samples=[$($i2f -join ',')]"
  Raw "  I2F n=$($i2f.Count) p50=$p50 p90=$p90 p95=$p95 p99=$p99  I2S n=$($i2s.Count) p50=$s50 p90=$s90"
  [pscustomobject]@{ n=$i2f.Count; i2f_p50=$p50; i2f_p90=$p90; i2f_p95=$p95; i2f_p99=$p99; i2f_samples=($i2f -join ','); i2s_p50=$s50; i2s_p90=$s90; focusPoint=$fpt } |
    ConvertTo-Json -Depth 4 | Set-Content "$out\inputlatency.json" -Encoding UTF8
} else {
  Say "  !! composer not focused -> input latency NOT MEASURABLE"
  Raw "  INPUT_LATENCY=NOT_MEASURABLE reason=composer_never_focused_no_CDP"
  '{"measured":false,"reason":"composer never focused; no CDP to locate it"}' | Set-Content "$out\inputlatency.json" -Encoding UTF8
}
& $adb -s $Serial shell input keyevent 111 | Out-Null
Say "=== PERF DONE ==="
