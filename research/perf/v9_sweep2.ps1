param([string]$Serial = 'emulator-6190')
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$out = "$PSScriptRoot\out_v9"; New-Item -ItemType Directory -Force -Path $out | Out-Null
$logAll = "$out\sweep_raw.txt"
function Say($m) { Write-Host $m; Add-Content $logAll ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Raw($m) { Add-Content $logAll $m }
Set-Content $logAll ("# V9 form factor sweep raw (v2 hardened dialog)  " + (Get-Date -Format s))
function Sh($c) { ((& $adb -s $Serial shell $c 2>&1) | Out-String).Trim() }
function DevTime { [double](((& $adb -s $Serial shell "date +%s.%N") | Out-String).Trim()) }
function UiXml { & $adb -s $Serial shell uiautomator dump /sdcard/v9_ui.xml 2>&1 | Out-Null; (& $adb -s $Serial shell cat /sdcard/v9_ui.xml) | Out-String }
# wait for the native F5-401 dialog, then tap 继续加载. returns tap point or ''
function Dismiss-Dialog($waitSec) {
  for ($i = 0; $i -lt [int]($waitSec*2); $i++) {
    $xml = UiXml
    $m = [regex]::Match($xml, 'text="继续加载"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
      $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value)/2); $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value)/2)
      Say "  F5-401 dialog up -> tap 继续加载 device($cx,$cy)  [bounds $($m.Value)]"
      Raw "  dialog_bounds_raw=$($m.Value) tap=($cx,$cy) dumpBytes=$($xml.Length)"
      & $adb -s $Serial shell input tap $cx $cy | Out-Null
      return "($cx,$cy)"
    }
    Start-Sleep -Milliseconds 500
  }
  return ''
}
function Wait-UiAvailable($timeoutSec) {
  $t0 = DevTime
  for ($i = 0; $i -lt [int]($timeoutSec*3); $i++) {
    $l = (& $adb -s $Serial logcat -d 2>&1) | Out-String
    if ($l -match 'OcPerfJS res #\d+ .*?/api/event') { return [int](((DevTime - $t0)*1000)) }
    Start-Sleep -Milliseconds 300
  }
  return -1
}
Add-Type -AssemblyName System.Drawing
function Cap($name) {
  & $adb -s $Serial shell screencap -p /sdcard/$name.png | Out-Null
  & $adb -s $Serial pull /sdcard/$name.png "$out\$name.png" 2>&1 | Out-Null
  $f = Get-Item "$out\$name.png" -ErrorAction SilentlyContinue
  if (-not $f) { return $null }
  # dialog detection: the native AlertDialog is a big bright card => high fraction of near-white pixels
  $bmp = [System.Drawing.Bitmap]::FromFile($f.FullName)
  $white = 0; $tot = 0
  for ($y = 0; $y -lt $bmp.Height; $y += 4) { for ($x = 0; $x -lt $bmp.Width; $x += 4) {
    $c = $bmp.GetPixel($x,$y); $tot++
    if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $white++ } } }
  $ratio = [Math]::Round(100.0*$white/[Math]::Max(1,$tot),1)
  $bmp.Dispose()
  return @{ file = $f.FullName; bytes = $f.Length; whitePct = $ratio }
}
# row census: last row with app content, to compare against IME top edge
function RowCensus($png, $imeTop, $w, $h) {
  $bmp = [System.Drawing.Bitmap]::FromFile($png)
  $bg = $bmp.GetPixel(4, [int]([Math]::Max(4,$imeTop*0.30))).R
  $last = -1
  for ($y = 0; $y -lt $h; $y++) {
    $n = 0
    for ($x = 4; $x -lt $w; $x += 5) {
      $c = $bmp.GetPixel($x,$y)
      if ([Math]::Abs([int]$c.R-$bg) -gt 25 -or [Math]::Abs([int]$c.G-$bg) -gt 25 -or [Math]::Abs([int]$c.B-$bg) -gt 25) { $n++ }
    }
    if ($n -gt [int](($w/5)*0.05)) { $last = $y }
  }
  $bmp.Dispose()
  return @{ bgSample = $bg; lastContentRow = $last; imeTop = $imeTop; contentVsIme = ($last - $imeTop); overlapPx = [Math]::Max(0, ($last - $imeTop)) }
}

$geoms = @(
  @{ id='phone360dp'; size='720x1280';  dens=320; dp=360  },
  @{ id='fold442dp';  size='884x2208';  dens=320; dp=442  },
  @{ id='fold708dp';  size='1416x2208'; dens=320; dp=708  },
  @{ id='bound700dp'; size='1400x2208'; dens=320; dp=700  },
  @{ id='bound820dp'; size='1640x2208'; dens=320; dp=820  },
  @{ id='tablet1366x768'; size='1366x768'; dens=160; dp=1366 }
)
$results = @()
foreach ($g in $geoms) {
  Say ""; Say "############ CELL $($g.id) target $($g.dp)dp ############"
  Raw "  `$(wm size $($g.size)) => " + (Sh "wm size $($g.size)")
  Raw "  `$(wm density $($g.dens)) => " + (Sh "wm density $($g.dens)")
  Start-Sleep -Seconds 4
  foreach ($l in ((Sh "wm size") -split "`r?`n"))    { Raw "  READBACK wm size: $l" }
  foreach ($l in ((Sh "wm density") -split "`r?`n")) { Raw "  READBACK wm density: $l" }
  $cfg = (Sh "dumpsys activity activities | grep -oE 'sw[0-9]+dp w[0-9]+dp h[0-9]+dp [0-9]+dpi [a-z]+ [a-z]+'") -split "`r?`n" | Select-Object -First 1
  Raw "  SYSTEM CONFIG: $cfg"
  $disp = (Sh "dumpsys window displays | head -3") -split "`r?`n"
  foreach ($l in $disp) { Raw "  DISPLAY: $($l.Trim())" }
  $wPx = [int]($g.size.Split('x')[0]); $hPx = [int]($g.size.Split('x')[1])

  & $adb -s $Serial shell am force-stop $pkg | Out-Null; Start-Sleep -Seconds 2
  & $adb -s $Serial logcat -c | Out-Null
  $amw = (& $adb -s $Serial shell "am start -W -n $act" 2>&1) | Out-String
  $amTotal = [int][regex]::Match($amw,'TotalTime:\s*(\d+)').Groups[1].Value
  Raw "  am start -W: $(($amw -replace "`r?`n",' | '))"
  $tap1 = Dismiss-Dialog 25
  $ui = Wait-UiAvailable 55
  # dialog can re-raise on re-probe: clear it again, then re-check
  $tap2 = ''
  if ($ui -lt 0 -or (Sh "dumpsys window | grep -c 'mCurrentFocus.*AlertDialog'") -ne '0') { $tap2 = Dismiss-Dialog 12; if ($tap2) { $ui = Wait-UiAvailable 40 } }
  Say "  UI_AVAILABLE=$ui ms (amTotal=$amTotal) taps: '$tap1' '$tap2'"
  Raw "  UI_AVAILABLE_MS=$ui AM_TOTAL_MS=$amTotal TAP1=$tap1 TAP2=$tap2"

  $log = (& $adb -s $Serial logcat -d 2>&1) | Out-String
  $proxyGET = ([regex]::Matches($log,'OcWebHost/Proxy: GET ')).Count
  $perfN = ([regex]::Matches($log,'OcPerfJS res #')).Count
  $c401 = ([regex]::Matches($log,'401')).Count
  Raw "  LOGCAT proxyGET=$proxyGET OcPerfJS_res=$perfN lines_with_401=$c401"
  foreach ($l in ($log -split "`r?`n")) { if ($l -match 'F5 probe|AUTH FAILED|passwordPresent|no password in SecretStore') { Raw "    LOG $($l.Trim())" } }

  $sock = Sh "cat /proc/net/unix | grep -c webview_devtools_remote"
  $flags = Sh "dumpsys package $pkg | grep -m1 'flags='"
  $appPid = Sh "pidof $pkg"
  Raw "  CDP_EVIDENCE appPid=$appPid devtoolsSocketCount=$sock pkgFlags=$flags  (socket=0 => CDP unavailable on this build)"

  $render = Cap "v9_$($g.id)_render"
  if ($render) { Say "  render $($render.file | Split-Path -Leaf) bytes=$($render.bytes) whitePct=$($render.whitePct) (dialog would be >15)"; Raw "  RENDER bytes=$($render.bytes) whitePct=$($render.whitePct) dialogLikely=$($render.whitePct -gt 15)" }
  else { Say "  render MISSING"; Raw "  RENDER=MISSING" }

  $xml2 = UiXml
  $nNodes = ([regex]::Matches($xml2,'<node ')).Count
  $txts = @(([regex]::Matches($xml2,'text="([^"]{1,30})"') | ForEach-Object { $_.Groups[1].Value }) | Where-Object { $_ -ne '' })
  Raw "  UIAUTOMATOR dumpBytes=$($xml2.Length) nodes=$nNodes visibleTexts=$($txts.Count) texts=[$($txts -join ' | ')]"

  # ---- IME: tap ladder to focus composer ----
  $shown='false'; $tapPt=''
  foreach ($c in @(@(0.50,0.93),@(0.50,0.90),@(0.50,0.96),@(0.35,0.93),@(0.65,0.93),@(0.50,0.86))) {
    $tx=[int]($wPx*$c[0]); $ty=[int]($hPx*$c[1])
    & $adb -s $Serial shell input tap $tx $ty | Out-Null; Start-Sleep -Seconds 3
    if ((Sh "dumpsys input_method | grep -m1 mInputShown") -match 'mInputShown=true') { $shown='true'; $tapPt="($tx,$ty)" ; break }
  }
  $imeLines = @()
  foreach ($l in ((Sh "dumpsys window") -split "`r?`n")) { if ($l -match 'type=ime frame=|mInputShown=|type=ime mSurfacePosition|mServedView=android.webkit.WebView') { $imeLines += $l.Trim() } }
  Raw "  IME RAW:"; foreach ($l in $imeLines) { Raw "    $l" }
  $imeTop = -1
  foreach ($l in $imeLines) { $mm=[regex]::Match($l,'type=ime frame=\[\d+,(-?\d+)\]\[\d+,-?\d+\]'); if ($mm.Success) { $imeTop=[int]$mm.Groups[1].Value; break } }
  $imeShot = Cap "v9_$($g.id)_ime"
  $ov = $null
  if ($shown -eq 'true' -and $imeTop -ge 0 -and $imeShot) { $ov = RowCensus $imeShot.file $imeTop $wPx $hPx ; Raw "  OVERLAP(screenshot row census): $(($ov | ConvertTo-Json -Compress))" }
  else { Raw "  OVERLAP=NOT_COMPUTED shown=$shown imeTop=$imeTop shot=$($imeShot -ne $null)" }
  Say "  IME shown=$shown tap=$tapPt imeTop=$imeTop overlapPx=$(if($ov){$ov.overlapPx}else{'n/a'})"
  & $adb -s $Serial shell input keyevent 111 | Out-Null; Start-Sleep -Seconds 2

  $results += [pscustomobject]@{
    cell=$g.id; targetDp=$g.dp; wmSize=$g.size; wmDensity=$g.dens; systemConfig=$cfg
    uiAvailableMs=$ui; amTotalMs=$amTotal; dialogTaps="$tap1 $tap2"; proxyGET=$proxyGET; perfJs=$perfN
    cdpSocketCount=$sock; uiNodes=$nNodes; uiVisibleTexts=$txts.Count; uiTexts=($txts -join '|')
    renderBytes=$render.bytes; renderWhitePct=$render.whitePct; dialogLikely=($render.whitePct -gt 15)
    imeShown=$shown; imeTap=$tapPt; imeTopPx=$imeTop
    overlapPx=$(if($ov){$ov.overlapPx}else{-1}); overlapDetail=$(if($ov){($ov|ConvertTo-Json -Compress)}else{'n/a'})
  }
}
$results | ConvertTo-Json -Depth 6 | Set-Content "$out\formfactor.json" -Encoding UTF8
Say ""; Say "=== SWEEP DONE ==="
$results | Format-Table cell,targetDp,wmSize,wmDensity,uiAvailableMs,imeShown,imeTopPx,overlapPx,cdpSocketCount,dialogLikely -AutoSize | Out-String | Write-Host
