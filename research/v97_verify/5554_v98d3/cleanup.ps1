$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"
. "$W\b_funcs.ps1"
function Prows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { if ($z.text -match '^E2ECHK5554d3d-\d+$') { $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
      $c=Center $z; $r += [pscustomobject]@{v=$z.text; l=[int]$b.Groups[1].Value; b=$z.bounds; cy=$c.cy; t=[int]$b.Groups[2].Value} } }; return $r }
function Sweep { param([string]$tag)
  $seen=@{}
  for ($i=0; $i -le 5; $i++) { $x = Dump "$tag`_s$i"; foreach ($p in (Prows $x)) { if ($p.l -ge 190 -and $p.l -le 210) { $seen[$p.v]=$p } }; Swipe 540 1600 540 1100 400 }
  return $seen }
function CloseCard { param([string]$tag)
  for ($k=0; $k -lt 4; $k++) {
    $x = Dump "$tag`_c$k"
    if (CardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
      if ($cc) { Log "  [$tag] card Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue } }
    if (FindTxt $x "Add server") { $fc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $fc=$c } } }
      if ($fc) { Log "  [$tag] footer Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 2000; continue } }
    return $true }
  return $false }
function DeleteRow { param([string]$name)
  $dots=$null
  for ($i=0; $i -le 6; $i++) {
    $x = Dump "CR$i"
    $row = (Prows $x) | Where-Object { $_.v -eq $name -and $_.l -ge 190 -and $_.l -le 210 } | Select-Object -First 1
    if ($row) { foreach ($d in ((Nodes $x) | Where-Object { $_.'content-desc' -eq 'Server actions' -and $_.clickable -eq 'true' })) { $dc=Center $d; if ($dc.y -gt $row.t -and $dc.y -lt ($row.t + 300)) { $dots=$d; break } }
      if ($dots) { break } }
    Swipe 540 1600 540 1100 400 }
  if (-not $dots) { Log "  cleanup: dots for $name not found"; return $false }
  $dc=Center $dots; Log "  cleanup: dots for $name @ $($dc.x),$($dc.y)"; Tap $dc; Start-Sleep -Milliseconds 1700
  $x = Dump "CRm"
  $del = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -First 1
  if (-not $del) { Log "  cleanup: no Delete for $name"; return $false }
  $dlc=Center $del; Tap $dlc; Start-Sleep -Milliseconds 1700
  $x = Dump "CRc"
  $db = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -Last 1
  if (-not $db) { Log "  cleanup: no confirm for $name"; return $false }
  $dbc=Center $db; Log "  cleanup: confirm $name @ $($dbc.x),$($dbc.y)"; Tap $dbc; Start-Sleep -Milliseconds 2200
  return $true }

Log "########## CLEANUP: delete all E2ECHK5554d3d* ##########"
CloseCard "cl0" | Out-Null
for ($pass=1; $pass -le 5; $pass++) {
  $seen = Sweep "CL$pass"
  $keys = @($seen.Keys | Sort-Object)
  Log "pass $pass rows=[$($keys -join '|')] count=$($keys.Count)"
  if ($keys.Count -eq 0) { break }
  DeleteRow $keys[0] | Out-Null
}
$fin = Sweep "CLfin"
$fk = @($fin.Keys | Sort-Object)
Log "=== CLEANUP RESULT: rows=[$($fk -join '|')] count=$($fk.Count) ==="
TunnelLen "cleanup_final" | Out-Null
$x = Dump "CLz"
Log "--- panel neighbours (must be intact) ---"
foreach ($z in (Nodes $x)) { $c=Center $z; if ($c.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  '" + $z.text + "' cd=" + $z.'content-desc' + " [" + $z.bounds + "]") } }
Shot "CL_final"
Log "--- private dir residue (E2ECHK5554d3d) ---"
$h = A @("shell","run-as","com.xiufeigo.opencodeshell","sh","-c","grep -rl E2ECHK5554d3d . 2>/dev/null")
Log "grep result: [$(($h | Out-String).Trim())]"
Log "--- /sdcard temp files ---"
$sd = A @("shell","ls","/sdcard")
Log "sdcard: $(($sd | Out-String).Trim() -replace "`n",' ')"
Log "--- adb reverse ---"
$r = A @("reverse","--list")
Log "reverse: $(($r | Out-String).Trim() -replace "`n",' ')"
