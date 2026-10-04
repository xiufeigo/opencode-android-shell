# v155 Task A forensic: is 580/560 a real reverse jump, or a clipped/bad reading?
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v155_lib.ps1"
$log = "$W\_v155_A_forensic.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $m | Tee-Object -FilePath $log -Append }

function EnsureIme {
  param([string]$tag)
  if (ImeState) { return $true }
  W_ "  !! IME collapsed at $tag -> re-tap composer"
  $x = Dump "${tag}_rec"; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($et) { adbx shell input tap $et.cx $et.cy | Out-Null }
  Start-Sleep -Seconds 2
  return (ImeState)
}

# Enumerate EVERY node near the composer band, so I can see the real container,
# instead of trusting one selector.
function Band {
  param([string]$Tag, [string]$file)
  [xml]$d = Get-Content $file -Raw -Encoding UTF8
  $rows = @()
  foreach ($n in $d.SelectNodes('//node')) {
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
    $x1=[int]$Matches[1]; $y1=[int]$Matches[2]; $x2=[int]$Matches[3]; $y2=[int]$Matches[4]
    $t = $n.GetAttribute('text'); $ds = $n.GetAttribute('content-desc')
    $cn = $n.GetAttribute('class').Split('.')[-1]
    if (-not $t -and -not $ds) { $lbl = "<$cn>" } else { $lbl = (($t,$ds) | Where-Object { $_ } | Select-Object -First 1) }
    $rows += [pscustomobject]@{
      Lbl=$lbl; Cls=$cn; X1=$x1; Y1=$y1; X2=$x2; Y2=$y2; W=($x2-$x1); H=($y2-$y1)
      Click=$n.GetAttribute('clickable'); En=$n.GetAttribute('enabled'); Scr=$n.GetAttribute('scrollable')
      Focus=$n.GetAttribute('focused')
    }
  }
  return $rows
}

foreach ($H in @('768','700','660','620','600','580','560')) {
  $sz = "1366x$H"
  SetDisp $sz 4
  $ok = EnsureIme "F@$H"
  $szr = ((adbx shell wm size 2>&1 | Select-String 'Override') -join '').Trim()
  W_ ""
  W_ "########## F_$H  ime=$ok  $szr  appPid=$(AppPid)"
  $x = Dump "F_$H"
  adbx shell "screencap -p /sdcard/__e2e.png" | Out-Null
  adbx pull /sdcard/__e2e.png "$script:ART\F_$H.png" 2>&1 | Out-Null

  # screen height actually in effect
  $phys = (adbx shell wm size 2>&1 | Out-String)
  W_ "  raw wm size: $((($phys -split "`n") | Where-Object { $_.Trim() } ) -join ' | ')"

  $all = Band "F_$H" $x
  W_ "  --- nodes in the lower half (y > 100), full list ---"
  ($all | Where-Object { $_.Y1 -gt 100 } | Sort-Object { $_.Y1 }, { $_.X1 }) | ForEach-Object {
    W_ ("   {0,-22} {1,-18} [{2,4},{3,4}][{4,4},{5,4}] {6,4}x{7,-4} clk={8,-5} en={9,-5} scr={10} foc={11}" -f `
        $_.Lbl, $_.Cls, $_.X1,$_.Y1,$_.X2,$_.Y2, $_.W,$_.H, $_.Click,$_.En,$_.Scr,$_.Focus)
  }
  # widest full-width containers (the pane host candidates)
  W_ "  --- full-width containers (x1<=2 and x2>=1364) ---"
  ($all | Where-Object { $_.X1 -le 2 -and $_.X2 -ge 1364 } | Sort-Object { $_.H }) | ForEach-Object {
    W_ ("   {0,-22} {1,-18} [{2},{3}][{4},{5}] {6}x{7}" -f $_.Lbl,$_.Cls,$_.X1,$_.Y1,$_.X2,$_.Y2,$_.W,$_.H)
  }
}
W_ "### forensic done"
