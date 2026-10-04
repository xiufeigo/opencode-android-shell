# v155 C: pixel criterion 3 + criterion 5, round-trip finish, logcat, restore, cleanup
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v155_lib.ps1"
$log = "$W\_v155_C.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $m | Tee-Object -FilePath $log -Append }
function EnsureIme {
  param([string]$tag)
  if (ImeState) { return $true }
  $x = Dump "${tag}_rec"; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($et) { adbx shell input tap $et.cx $et.cy | Out-Null }
  Start-Sleep -Seconds 2
  return (ImeState)
}
function FormOf { param($ns)
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return 'NO_ET' }
  if ($et.w -lt 800) { 'ONEROW' } else { 'STACKED' }
}
Add-Type -AssemblyName System.Drawing
# real pixel scan of the chip rectangle, minus anything covered by Send
function PixChip {
  param([string]$png, $chip, $send)
  $img = New-Object System.Drawing.Bitmap($png)
  $res = [pscustomobject]@{ Total=0; Covered=0; Visible=0; ChipSurface=0; Dark=0 }
  for ($y=$chip.y1; $y -lt $chip.y2; $y++) {
    for ($x=$chip.x1; $x -lt $chip.x2; $x++) {
      if ($x -ge 0 -and $y -ge 0 -and $x -lt $img.Width -and $y -lt $img.Height) {
        $res.Total++
        $c = $img.GetPixel($x,$y)
        if ($c.R -ge 245 -and $c.G -ge 245 -and $c.B -ge 245 -and $c.R -le 253 -and $c.G -le 253 -and $c.B -le 253) { $res.ChipSurface++ }
        if ($c.R -lt 120 -and $c.G -lt 120 -and $c.B -lt 120) { $res.Dark++ }
        if ($send -and $x -ge $send.x1 -and $x -lt $send.x2 -and $y -ge $send.y1 -and $y -lt $send.y2) { $res.Covered++ }
      }
    }
  }
  $res.Visible = $res.Total - $res.Covered
  $img.Dispose(); return $res
}

W_ "### C1 criterion 3 (chipVisiblePixelCount) + criterion 5, per tier"
foreach ($H in @(600,768,580)) {
  SetDisp "1366x$H" 4
  $ok = EnsureIme "C1@$H"
  $x = Dump "C1_$H"; $ns = Nodes $x
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  $form = FormOf $ns
  $png = "$script:ART\pix_$H.png"
  adbx shell "screencap -p /sdcard/__e2e.png" | Out-Null
  adbx pull /sdcard/__e2e.png $png 2>&1 | Out-Null
  $r = PixChip $png $ot $sd
  W_ ("  C1_$H FORM={0,-8} chip=[{1},{2}][{3},{4}] {5}x{6}" -f $form,$ot.x1,$ot.y1,$ot.x2,$ot.y2,$ot.w,$ot.h)
  W_ ("        send=[{0},{1}][{2},{3}]" -f $sd.x1,$sd.y1,$sd.x2,$sd.y2)
  W_ ("        chipArea={0}  coveredBySend={1}  chipVisiblePixelCount={2} -> {3}" -f $r.Total,$r.Covered,$r.Visible,$(if($r.Visible -gt 0){'PASS'}else{'FAIL'}))
  W_ ("        chipSurfacePx(250-ish)={0}  darkTextPx={1}" -f $r.ChipSurface,$r.Dark)
  # criterion 5: 768 tier must have 0 occlusion -> vertical separation
  if ($ot -and $sd) {
    $vy = if ($sd.y1 -ge $ot.y2) { "send is BELOW chip by $($sd.y1 - $ot.y2)px" } else { "vertically OVERLAPPING" }
    $vx = [Math]::Min($ot.x2,$sd.x2) - [Math]::Max($ot.x1,$sd.x1)
    W_ ("        C5 vertical: {0}; horizontal overlap={1}px" -f $vy, $(if($vx -gt 0){$vx}else{0}))
  }
}

W_ ""
W_ "### C2 logcat scan (FATAL|IllegalArgumentException|maxHeight) filtered by appPid, with -t"
$appPid = AppPid
W_ "  appPid=$appPid"
if ($appPid) {
  $lg = & adb -s emulator-5554 logcat -t 5000 -v brief 2>&1 | Out-String
  $mine = ($lg -split "`n") | Where-Object { $_ -match "\s$appPid\s" }
  $hits = $mine | Select-String -Pattern 'FATAL|IllegalArgumentException|maxHeight'
  W_ "  app lines=$($mine.Count)"
  if ($hits) { W_ "  !! MATCHES:"; $hits | Select-Object -First 20 | ForEach-Object { W_ "    $_" } } else { W_ "  FATAL|IllegalArgumentException|maxHeight = EMPTY (clean)" }
  $mine | Out-File -Encoding utf8 "$script:ART\_v155_logcat_apppid.log"
}
$anr = (adbx shell dumpsys window 2>&1 | Select-String 'Not Responding')
if ($anr) { W_ "  !! ANR: $anr" } else { W_ "  ANR: none" }

W_ ""
W_ "### C3 restore wm explicitly to 1366x768 / 160"
adbx shell wm size 1366x768 | Out-Null
adbx shell wm density 160 | Out-Null
Start-Sleep -Seconds 3
W_ ("  " + ((adbx shell wm size 2>&1 | Select-String 'Override') -join ''))
W_ ("  " + ((adbx shell wm density 2>&1 | Select-String 'Override') -join ''))
$restored = EnsureIme 'C3'
if ($restored) {
  $x = Dump 'C3_restored'; $ns = Nodes $x
  $g = $null
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  W_ ("  restored FORM={0} chip.h={1} send.h={2} clickable={3}/{4}" -f (FormOf $ns), $(if($ot){$ot.h}else{'NA'}), $(if($sd){$sd.h}else{'NA'}), $(if($ot){$ot.clickable}else{'NA'}), $(if($sd){$sd.clickable}else{'NA'}))
}
W_ ("  focus=" + ((adbx shell dumpsys window 2>&1 | Select-String 'mCurrentFocus') -join ''))
W_ ("  appPid=" + (AppPid))

W_ ""
W_ "### C4 /sdcard residue check"
$sdcard = adbx shell ls -la /sdcard 2>&1 | Out-String
W_ ($sdcard -split "`n" | Where-Object { $_ -match 'E2ECHK|__e2e' } | ForEach-Object { "  " + $_.Trim() })
W_ "### C done"
