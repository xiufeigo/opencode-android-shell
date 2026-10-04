param([string]$Serial = 'emulator-6190')
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$out = "$PSScriptRoot\out_v9"; New-Item -ItemType Directory -Force -Path $out | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$logAll = "$out\sweep_raw.txt"
function Say($m) { Write-Host $m; Add-Content $logAll -Encoding UTF8 ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Raw($m) { Add-Content $logAll -Encoding UTF8 $m }
Set-Content $logAll ("# V9 form factor sweep v3 (lean, filtered logcat)  " + (Get-Date -Format s))
function Sh($c) { ((& $adb -s $Serial shell $c 2>&1) | Out-String).Trim() }
function DevTime { [double]([System.Diagnostics.Stopwatch]::GetTimestamp() / [System.Diagnostics.Stopwatch]::Frequency) }
function OcLog { ((& $adb -s $Serial logcat -d -s OcWebHost:* 2>&1) | Out-String) }
Add-Type -AssemblyName System.Drawing
function Cap($name) {
  & $adb -s $Serial shell screencap -p /sdcard/$name.png | Out-Null
  & $adb -s $Serial pull /sdcard/$name.png "$out\$name.png" 2>&1 | Out-Null
  $f = Get-Item "$out\$name.png" -ErrorAction SilentlyContinue
  if (-not $f) { return $null }
  $bmp = [System.Drawing.Bitmap]::FromFile($f.FullName); $white=0; $tot=0
  for ($y=0; $y -lt $bmp.Height; $y+=6) { for ($x=0; $x -lt $bmp.Width; $x+=6) { $c=$bmp.GetPixel($x,$y); $tot++; if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $white++ } } }
  $ratio=[Math]::Round(100.0*$white/[Math]::Max(1,$tot),1); $W=$bmp.Width; $H=$bmp.Height; $bmp.Dispose()
  return @{ file="$out\$name.png"; bytes=$f.Length; whitePct=$ratio; w=$W; h=$H }
}
# dialog: single dump, tap 继续加载 if present
function TryDismiss {
  & $adb -s $Serial shell uiautomator dump /sdcard/v9_ui.xml 2>&1 | Out-Null
  $xml = (& $adb -s $Serial shell cat /sdcard/v9_ui.xml) | Out-String
  $m = [regex]::Match($xml, 'text="继续加载"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($m.Success) {
    $cx=[int](([int]$m.Groups[1].Value+[int]$m.Groups[3].Value)/2); $cy=[int](([int]$m.Groups[2].Value+[int]$m.Groups[4].Value)/2)
    Raw "  DIALOG present: text=[$((([regex]::Matches($xml,'text="([^"]{1,24})"')|ForEach-Object{$_.Groups[1].Value})|Where-Object{$_ -ne ''}) -join '|')] bounds=$($m.Value) tap=($cx,$cy)"
    & $adb -s $Serial shell input tap $cx $cy | Out-Null
    return "($cx,$cy)"
  }
  Raw "  DIALOG absent (dumpBytes=$($xml.Length) nodes=$(([regex]::Matches($xml,'<node ')).Count))"
  return ''
}
function RowCensus($png,$imeTop,$w,$h) {
  $bmp=[System.Drawing.Bitmap]::FromFile($png)
  $bg=$bmp.GetPixel(4,[int]([Math]::Max(4,$imeTop*0.30))).R
  $last=-1
  for ($y=0; $y -lt $h; $y+=2) { $n=0
    for ($x=4; $x -lt $w; $x+=9) { $c=$bmp.GetPixel($x,$y)
      if ([Math]::Abs([int]$c.R-$bg) -gt 25 -or [Math]::Abs([int]$c.G-$bg) -gt 25 -or [Math]::Abs([int]$c.B-$bg) -gt 25) { $n++ } }
    if ($n -gt [int](($w/9)*0.05)) { $last=$y } }
  $bmp.Dispose()
  return @{ bgSample=$bg; lastContentRow=$last; imeTop=$imeTop; overlapPx=[Math]::Max(0,($last-$imeTop)) }
}
$geoms=@(
 @{id='phone360dp';size='720x1280';dens=320;dp=360},
 @{id='fold442dp';size='884x2208';dens=320;dp=442},
 @{id='fold708dp';size='1416x2208';dens=320;dp=708},
 @{id='bound700dp';size='1400x2208';dens=320;dp=700},
 @{id='bound820dp';size='1640x2208';dens=320;dp=820},
 @{id='tablet1366x768';size='1366x768';dens=160;dp=1366})
$results=@()
foreach ($g in $geoms) {
  Say ""; Say "######## CELL $($g.id) target $($g.dp)dp ########"
  Raw "  CMD wm size $($g.size)"; Raw "  CMD wm density $($g.dens)"
  Sh "wm size $($g.size)" | Out-Null; Sh "wm density $($g.dens)" | Out-Null
  Start-Sleep -Seconds 3
  foreach ($l in ((Sh "wm size") -split "`r?`n"))    { if($l.Trim()){ Raw "  READBACK wm size: $($l.Trim())" } }
  foreach ($l in ((Sh "wm density") -split "`r?`n")) { if($l.Trim()){ Raw "  READBACK wm density: $($l.Trim())" } }
  $cfg=((Sh "dumpsys activity activities | grep -oE 'sw[0-9]+dp w[0-9]+dp h[0-9]+dp [0-9]+dpi [a-z]+'") -split "`r?`n")[0]
  Raw "  SYSTEM CONFIG: $cfg"
  Raw "  DISPLAY: $(((Sh 'dumpsys window displays | head -3') -split "`r?`n" | Select-Object -Skip 2 -First 1).Trim())"
  $wPx=[int]($g.size.Split('x')[0]); $hPx=[int]($g.size.Split('x')[1])

  & $adb -s $Serial shell am force-stop $pkg | Out-Null; Start-Sleep -Seconds 2
  & $adb -s $Serial logcat -c | Out-Null
  $t0=DevTime
  $amw=((& $adb -s $Serial shell "am start -W -n $act" 2>&1) | Out-String)
  $amTotal=[int][regex]::Match($amw,'TotalTime:\s*(\d+)').Groups[1].Value
  Raw "  am start -W: $(($amw -replace "`r?`n",' | '))"
  Start-Sleep -Seconds 4
  $tap1=TryDismiss
  $ui=-1
  for ($k=0;$k -lt 8;$k++) {
    Start-Sleep -Seconds 4
    $oc=OcLog
    if ($oc -match 'OcPerfJS res #\d+ .*?/api/event') { $ui=[int]((((DevTime)) - $t0)*1000); break }
    $tap2=TryDismiss
    if ($tap2) { $tap1="$tap1|$tap2" }
  }
  $oc=OcLog
  $proxyGET=([regex]::Matches($oc,'OcWebHost/Proxy: GET ')).Count
  $perfN=([regex]::Matches($oc,'OcPerfJS res #')).Count
  foreach ($l in ($oc -split "`r?`n")) { if ($l -match 'F5 probe|AUTH FAILED|passwordPresent|home=') { Raw "  LOG $($l.Trim())" } }
  Raw "  UI_AVAILABLE_MS=$ui AM_TOTAL_MS=$amTotal PROXY_GET=$proxyGET OCRES=$perfN DIALOG_TAPS=$tap1"
  Say "  UI_AVAILABLE=$ui ms amTotal=$amTotal proxyGET=$proxyGET perfJs=$perfN taps=$tap1"
  $sock=Sh "cat /proc/net/unix | grep -c webview_devtools_remote"
  $flags=Sh "dumpsys package $pkg | grep -m1 'flags='"
  Raw "  CDP_EVIDENCE devtoolsSocketCount=$sock pkgFlags=$flags appPid=$(Sh "pidof $pkg")"
  $render=Cap "v9_$($g.id)_render"
  if ($render) { Raw "  RENDER file=$($render.file) bytes=$($render.bytes) size=$($render.w)x$($render.h) whitePct=$($render.whitePct) dialogLikely=$($render.whitePct -gt 15)"; Say "  render bytes=$($render.bytes) whitePct=$($render.whitePct)" }
  else { Raw "  RENDER=MISSING" }
  $shown='false'; $tapPt=''
  foreach ($c in @(@(0.50,0.93),@(0.50,0.88),@(0.50,0.97))) {
    $tx=[int]($wPx*$c[0]); $ty=[int]($hPx*$c[1])
    & $adb -s $Serial shell input tap $tx $ty | Out-Null; Start-Sleep -Seconds 3
    if ((Sh "dumpsys input_method | grep -m1 mInputShown") -match 'mInputShown=true') { $shown='true'; $tapPt="($tx,$ty)"; break }
  }
  $raw=Sh "dumpsys window | grep -E 'type=ime frame=|mInputShown=|mServedView=android.webkit.WebView'"
  foreach ($l in ($raw -split "`r?`n")) { if ($l.Trim()) { Raw "  IME_RAW $($l.Trim())" } }
  $imeTop=-1
  foreach ($l in ($raw -split "`r?`n")) { $mm=[regex]::Match($l,'type=ime frame=\[\d+,(-?\d+)\]'); if($mm.Success){$imeTop=[int]$mm.Groups[1].Value;break} }
  $im=Sh "dumpsys input_method | grep -m2 -E 'mInputShown|mServedView'"
  foreach ($l in ($im -split "`r?`n")) { if ($l.Trim()) { Raw "  IME_METHOD $($l.Trim())" } }
  $imeShot=Cap "v9_$($g.id)_ime"
  $ov=$null
  if ($shown -eq 'true' -and $imeTop -ge 0 -and $imeShot) { $ov=RowCensus $imeShot.file $imeTop $wPx $hPx; Raw "  OVERLAP screenshot row census: $(($ov|ConvertTo-Json -Compress))" }
  else { Raw "  OVERLAP=NOT_COMPUTED imeShown=$shown imeTop=$imeTop" }
  Say "  IME shown=$shown tap=$tapPt imeTop=$imeTop overlap=$(if($ov){$ov.overlapPx}else{'n/a'})"
  & $adb -s $Serial shell input keyevent 111 | Out-Null; Start-Sleep -Seconds 1
  $results+=[pscustomobject]@{cell=$g.id;targetDp=$g.dp;wmSize=$g.size;wmDensity=$g.dens;systemConfig=$cfg;uiAvailableMs=$ui;amTotalMs=$amTotal;dialogTaps=$tap1;proxyGET=$proxyGET;perfJs=$perfN;cdpSocketCount=$sock;renderBytes=$render.bytes;renderWhitePct=$render.whitePct;dialogLikely=($render.whitePct -gt 15);imeShown=$shown;imeTap=$tapPt;imeTopPx=$imeTop;overlapPx=$(if($ov){$ov.overlapPx}else{-1})}
}
$results|ConvertTo-Json -Depth 6|Set-Content "$out\formfactor.json" -Encoding UTF8
Say ""; Say "=== SWEEP DONE ==="
$results|Format-Table cell,targetDp,wmSize,wmDensity,uiAvailableMs,imeShown,imeTopPx,overlapPx,cdpSocketCount,dialogLikely -AutoSize|Out-String|Write-Host
