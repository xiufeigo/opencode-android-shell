param([string]$Serial = 'emulator-6190')
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$out = "$PSScriptRoot\out_v9"; New-Item -ItemType Directory -Force -Path $out | Out-Null
$logAll = "$out\sweep_raw.txt"
function Say($m) { Write-Host $m; Add-Content $logAll ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Raw($m) { Add-Content $logAll $m }
Set-Content $logAll ("# V9 form factor sweep raw  " + (Get-Date -Format s))

function Sh($c) { ((& $adb -s $Serial shell $c 2>&1) | Out-String).Trim() }
function DevTime { [double](((& $adb -s $Serial shell "date +%s.%N") | Out-String).Trim()) }
function Cap($name) {
  & $adb -s $Serial shell screencap -p /sdcard/$name.png | Out-Null
  & $adb -s $Serial pull /sdcard/$name.png "$out\$name.png" 2>&1 | Out-Null
  $f = Get-Item "$out\$name.png" -ErrorAction SilentlyContinue
  if ($f) { "  screenshot $name.png bytes=$($f.Length)" } else { "  screenshot $name.png MISSING" }
}
# dismiss the F5-401 dialog by tapping the native 继续加载 button (bounds from uiautomator)
function Dismiss-Dialog {
  for ($i = 0; $i -lt 10; $i++) {
    & $adb -s $Serial shell uiautomator dump /sdcard/v9_ui.xml 2>&1 | Out-Null
    $xml = (& $adb -s $Serial shell cat /sdcard/v9_ui.xml) | Out-String
    $m = [regex]::Match($xml, 'text="继续加载"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
      $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value)/2)
      $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value)/2)
      Say "  F5-401 dialog present; tapping 继续加载 at device($cx,$cy) [bounds $($m.Value)]"
      Raw ("  dialog_dump_len=" + $xml.Length + "  raw_dialog_text=" + (([regex]::Matches($xml,'text="([^"]{1,24})"') | ForEach-Object { $_.Groups[1].Value }) -join '|'))
      & $adb -s $Serial shell input tap $cx $cy | Out-Null
      return $true
    }
    if ($xml -notmatch '登录没通过') { return $false }
    Start-Sleep -Seconds 2
  }
  return $false
}
# UI-available marker: poll logcat for the SPA's live event poll (NOT the last _assets GET)
function Wait-UiAvailable($timeoutSec) {
  $t0 = DevTime
  for ($i = 0; $i -lt [int]($timeoutSec*2); $i++) {
    $l = (& $adb -s $Serial logcat -d 2>&1) | Out-String
    $m = [regex]::Match($l, 'OcPerfJS res #\d+ .*?/api/event')
    if ($m.Success) {
      $mTs = [regex]::Match($l.Substring(0, $m.Index), '(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})(?=[^\r\n]*$)')
      return @{ ms = [int](((DevTime - $t0)*1000)); marker = $m.Value }
    }
    Start-Sleep -Milliseconds 400
  }
  return @{ ms = -1; marker = 'NOT_FOUND' }
}
function ImeLine {
  $w = (& $adb -s $Serial shell "dumpsys window" 2>&1) | Out-String
  $lines = @()
  foreach ($l in ($w -split "`r?`n")) { if ($l -match 'type=ime|mInputShown=|mServedView=|type=ime frame=') { $lines += $l.Trim() } }
  return $lines
}
# pixel probe: last content row above IME, and any row under the IME that is non-background
function PixelOverlap($png, $imeTop, $w, $h) {
  if (-not (Test-Path $png)) { return @{ err = 'no_png' } }
  Add-Type -AssemblyName System.Drawing
  $bmp = [System.Drawing.Bitmap]::FromFile($png)
  $res = @{ imgW = $bmp.Width; imgH = $bmp.Height }
  $bgLum = $bmp.GetPixel(6, [int]($imeTop*0.35)).R
  $res.bgSample = $bgLum
  $lastContent = -1
  for ($y = 0; $y -lt $h; $y++) {
    $n = 0
    for ($x = 4; $x -lt $w; $x += 7) {
      $c = $bmp.GetPixel($x, $y)
      if ([Math]::Abs([int]$c.R - $bgLum) -gt 22 -or [Math]::Abs([int]$c.G - $bgLum) -gt 22 -or [Math]::Abs([int]$c.B - $bgLum) -gt 22) { $n++ }
    }
    if ($n -gt [int](($w/7)*0.04)) { $lastContent = $y }
  }
  $res.lastContentRow = $lastContent
  $res.imeTop = $imeTop
  $res.contentBottom_vs_imeTop = $lastContent - $imeTop
  $res.overlapPx = [Math]::Max(0, $lastContent - $imeTop)
  $bmp.Dispose()
  return $res
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
  Say ""
  Say "################ CELL $($g.id)  (target $($g.dp)dp) ################"
  Say "--- raw wm size/density set ---"
  $set1 = Sh "wm size $($g.size)";  Raw "  \$ wm size $($g.size)  => $set1"
  $set2 = Sh "wm density $($g.dens)"; Raw "  \$ wm density $($g.dens)  => $set2"
  Start-Sleep -Seconds 4
  $sz = Sh "wm size"; $dn = Sh "wm density"
  Raw "  READBACK wm size:"; foreach ($l in ($sz -split "`r?`n")) { Raw "    $l" }
  Raw "  READBACK wm density:"; foreach ($l in ($dn -split "`r?`n")) { Raw "    $l" }
  Say "  readback: $(($sz -replace "`r?`n",' | '))  //  $(($dn -replace "`r?`n",' | '))"
  $cfg = Sh "dumpsys activity activities | grep -oE 'sw[0-9]+dp w[0-9]+dp h[0-9]+dp [0-9]+dpi [a-z]+ [a-z]+'"
  Raw "  SYSTEM CONFIG: $cfg"
  $disp = Sh "dumpsys window displays | head -3"
  Raw "  DISPLAY: $(($disp -replace "`r?`n",' | '))"

  # --- cold launch to official UI ---
  & $adb -s $Serial logcat -c | Out-Null
  & $adb -s $Serial shell am force-stop $pkg | Out-Null; Start-Sleep -Seconds 2
  $tLaunch = DevTime
  $amw = (& $adb -s $Serial shell "am start -W -n $act" 2>&1) | Out-String
  Raw "  am start -W:"; foreach ($l in ($amw -split "`r?`n")) { Raw "    $l" }
  $amTotal = [regex]::Match($amw,'TotalTime:\s*(\d+)').Groups[1].Value
  Start-Sleep -Seconds 3
  $dlg = Dismiss-Dialog
  $ui = Wait-UiAvailable 60
  Say "  UI-available(first /api/event poll) = $($ui.ms) ms   [am start TotalTime=$amTotal ms; dialog=$dlg]"
  Raw "  UI_AVAILABLE_MS=$($ui.ms)  marker=$($ui.marker)"
  $log = (& $adb -s $Serial logcat -d 2>&1) | Out-String
  $firstAsset = [regex]::Match($log, 'OcWebHost/Proxy: GET http://127\.0\.0\.1:4095/_assets/')
  $assetCount = ([regex]::Matches($log,'OcWebHost/Proxy: GET ')).Count
  $nPerf = ([regex]::Matches($log,'OcPerfJS res #')).Count
  Say "  logcat: _assets first-hit present=$($firstAsset.Success)  proxyGET=$assetCount  OcPerfJS=$nPerf"
  Raw "  LOGCAT proxyGET=$assetCount OcPerfJS=$nPerf firstAssetHit=$($firstAsset.Success)"
  Raw "  401/400 lines:"; foreach ($l in ($log -split "`r?`n")) { if ($l -match '401|AUTH FAILED') { Raw "    $($l.Trim())" } }

  # --- CDP attempt (per-cell documentation of availability) ---
  $cdpOut = (& pwsh -NoProfile -File "$PSScriptRoot\..\env\cdp_run_e6.ps1" -Serial $Serial -Js 'document.title' 2>&1) | Out-String
  Raw "  CDP ATTEMPT: $(($cdpOut -replace "`r?`n",' | '))"
  $cdpPid = Sh "pidof $pkg"
  $sock = Sh "cat /proc/net/unix | grep -c webview_devtools_remote"
  $flags = Sh "dumpsys package $pkg | grep -m1 'flags='"
  Raw "  CDP evidence: appPid=$cdpPid devtoolsSocketCount=$sock pkgFlags=$flags"
  Say "  CDP: socket=$sock (0 => no devtools socket on this build)"

  # --- uiautomator node/text census (WebView text is normally 0) ---
  & $adb -s $Serial shell uiautomator dump /sdcard/v9_ui2.xml 2>&1 | Out-Null
  $xml2 = (& $adb -s $Serial shell cat /sdcard/v9_ui2.xml) | Out-String
  $nNodes = ([regex]::Matches($xml2,'<node ')).Count
  $txts = ([regex]::Matches($xml2,'text="([^"]{1,30})"') | ForEach-Object { $_.Groups[1].Value }) | Where-Object { $_ -ne '' }
  Raw "  UIAUTOMATOR: dumpBytes=$($xml2.Length) nodes=$nNodes visibleTexts=$($txts.Count)"
  Raw "  UIAUTOMATOR texts: $($txts -join ' | ')"
  Say "  uiautomator: nodes=$nNodes visibleTexts=$($txts.Count)"
  $capN = Cap "v9_$($g.id)_render"

  # --- touch target census from the render screenshot (proxy; no CDP) ---
  # --- raise IME: tap ladder of candidate composer locations ---
  $wPx = [int]($g.size.Split('x')[0]); $hPx = [int]($g.size.Split('x')[1])
  $shown = 'false'; $tapPt = ''
  $cands = @(@(0.50,0.90), @(0.50,0.86), @(0.50,0.94), @(0.35,0.92), @(0.65,0.92), @(0.50,0.80))
  foreach ($c in $cands) {
    $tx = [int]($wPx * $c[0]); $ty = [int]($hPx * $c[1])
    & $adb -s $Serial shell input tap $tx $ty | Out-Null
    Start-Sleep -Seconds 3
    $im = Sh "dumpsys input_method | grep -m1 mInputShown"
    if ($im -match 'mInputShown=true') { $shown = 'true'; $tapPt = "($tx,$ty) frac=$($c[0]),$($c[1])"; break }
  }
  Say "  IME raised: $shown at $tapPt"
  $imeLines = ImeLine
  Raw "  IME RAW LINES (type=ime frame= / mInputShown / mServedView):"
  foreach ($l in $imeLines) { Raw "    $l" }
  $imeTop = -1
  foreach ($l in $imeLines) {
    $mm = [regex]::Match($l, 'type=ime frame=\[(\d+),(-?\d+)\]\[(\d+),(\d+)\]')
    if ($mm.Success) { $imeTop = [int]$mm.Groups[2].Value; break }
  }
  $capI = Cap "v9_$($g.id)_ime"
  $ov = PixelOverlap "$out\v9_$($g.id)_ime.png" $imeTop $wPx $hPx
  Raw "  OVERLAY PIXEL PROBE: $(($ov | ConvertTo-Json -Compress))"
  Say "  imeTop=$imeTop  overlapPx=$($ov.overlapPx)  (method: screenshot row census, NOT CDP)"
  # release IME
  & $adb -s $Serial shell input keyevent 111 | Out-Null; Start-Sleep -Seconds 2

  $results += [pscustomobject]@{
    cell = $g.id; targetDp = $g.dp; wmSize = $g.size; wmDensity = $g.dens
    systemConfig = $cfg; amTotalMs = $amTotal; uiAvailableMs = $ui.ms
    dialogDismissed = $dlg; proxyGET = $assetCount; perfJs = $nPerf
    cdpSocketCount = $sock; uiNodes = $nNodes; uiVisibleTexts = $txts.Count
    imeShown = $shown; imeTapPoint = $tapPt; imeTopPx = $imeTop
    overlapPx = $ov.overlapPx; probe = ($ov | ConvertTo-Json -Compress)
    renderShot = "v9_$($g.id)_render.png"; imeShot = "v9_$($g.id)_ime.png"
  }
}
$results | ConvertTo-Json -Depth 6 | Set-Content "$out\formfactor.json" -Encoding UTF8
Say ""
Say "=== SWEEP DONE ==="
$results | Format-Table cell,targetDp,wmSize,wmDensity,uiAvailableMs,imeShown,imeTopPx,overlapPx,cdpSocketCount -AutoSize | Out-String | Write-Host
