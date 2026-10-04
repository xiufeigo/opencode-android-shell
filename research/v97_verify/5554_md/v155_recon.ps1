# v155 recon: launch, populate composer, assert IME, map the node vocabulary
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null
$script:ART = "$W\artifacts_v155"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

$log = "$W\_v155_recon.log"
function W_ { param($m) $m | Tee-Object -FilePath $log -Append }

W_ "### R0 baseline window"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 2
W_ ("  " + ((adbx shell wm size 2>&1 | Select-String 'Override') -join ''))
W_ ("  " + ((adbx shell wm density 2>&1 | Select-String 'Override') -join ''))
W_ ("  appPid=" + (AppPid))
W_ ("  focus=" + ((Focus) -join ''))

W_ "### R1 launch (no BACK, no ESC)"
adbx shell am start -n com.xiufeigo.opencodeshell/.OcActivity 2>&1 | Out-String | ForEach-Object { W_ ("  " + $_) }
Start-Sleep -Seconds 4
W_ ("  focus=" + ((Focus) -join ''))
W_ ("  appPid=" + (AppPid))

# locate the composer EditText
$x = Dump 'R1_launch'
$ns = Nodes $x
W_ "### R2 node vocabulary (labels only)"
$lab = $ns | Where-Object { $_.text -or $_.desc } | ForEach-Object {
  $l = if ($_.text) { $_.text } else { $_.desc }
  "{0}  {1}  [{2},{3}][{4},{5}] {6}x{7} clickable={8}" -f $l, $_.cls, $_.x1, $_.y1, $_.x2, $_.y2, $_.w, $_.h, $_.clickable
}
$lab | ForEach-Object { W_ ("  " + $_) }

$et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if (-not $et) { W_ "  !! NO EditText found" }
else {
  W_ ("### R3 EditText = [$($et.x1),$($et.y1)][$($et.x2),$($et.y2)] $($et.w)x$($et.h) text='$($et.text)'")
  W_ "### R4 tap composer to raise IME"
  adbx shell input tap $et.cx $et.cy | Out-Null
  Start-Sleep -Seconds 2
  W_ ("  mInputShown=" + (ImeState))
}

W_ "### R5 type content (E2ECHK5554u1a prefixed)"
adbx shell input text "E2ECHK5554u1a%smontone" | Out-Null
Start-Sleep -Seconds 2
$ime = AssertIme "R5 after typing" | Select-Object -Last 1
W_ ("  mInputShown=$ime")
$x2 = Dump 'R5_typed'
$ns2 = Nodes $x2
W_ "### R6 post-typing labels"
($ns2 | Where-Object { $_.text -or $_.desc } | ForEach-Object {
  $l = if ($_.text) { $_.text } else { $_.desc }
  "{0}  {1}  [{2},{3}][{4},{5}] {6}x{7} clickable={8} enabled={9}" -f $l, $_.cls, $_.x1, $_.y1, $_.x2, $_.y2, $_.w, $_.h, $_.clickable, $_.enabled
}) | ForEach-Object { W_ ("  " + $_) }

$et2 = $ns2 | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
W_ ("  EditText text='" + $et2.text + "' bounds=[" + $et2.x1 + "," + $et2.y1 + "][" + $et2.x2 + "," + $et2.y2 + "]")
$sd = $ns2 | Where-Object { $_.desc -eq 'Send' -or $_.text -eq 'Send' } | Select-Object -First 1
$ot = $ns2 | Where-Object { $_.desc -eq 'Open terminal' -or $_.text -eq 'Open terminal' } | Select-Object -First 1
W_ ("  Send      = " + $(if($sd){"[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable) enabled=$($sd.enabled)"}else{"MISSING"}))
W_ ("  OpenTerm  = " + $(if($ot){"[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable)"}else{"MISSING"}))
W_ ("  appPid=" + (AppPid))
W_ "### recon done"
