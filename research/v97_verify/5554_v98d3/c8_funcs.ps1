$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"

# ---- C helpers ----
# tap a row's kebab (Server actions) nearest to a named row, then a labelled action
function RowKebab { param($xml,[string]$name)
  $tgt=$null; foreach ($r in (PanelRows $xml)) { if ($r.v -eq $name) { $tgt=$r } }
  if (-not $tgt) { return $null }
  $cands=@(); foreach ($z in (Nodes $xml)) { if ($z.'content-desc' -eq 'Server actions') { $c=Center $z; $cands += [pscustomobject]@{x=$c.x;y=$c.y;b=$z.bounds} } }
  if ($cands.Count -eq 0) { return $null }
  return ($cands | Sort-Object { [math]::Abs($_.y - ($tgt.cy + 100)) } | Select-Object -First 1) }
# tap an inline row action by label (Connect / Reconnect / Disconnect)
function RowAction { param([string]$label)
  $x = Dump "RA_$label"
  $h=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq $label -or $z.'content-desc' -eq $label) { $h=$z } }
  if (-not $h) { Log "  [Act] label '$label' NOT FOUND"; return $false }
  $c=Center $h; Log "  [Act] tap '$label' @ $($c.x),$($c.y) [$($h.bounds)]"; TapAt $c.x $c.y
  return $true }
# read the row state string for a specific name
function StateOf { param($xml,[string]$name)
  $tgt=$null; foreach ($r in (PanelRows $xml)) { if ($r.v -eq $name) { $tgt=$r } }
  if (-not $tgt) { return '(row not visible)' }
  $best=$null; $bd=99999
  foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]'); $l=[int]$b.Groups[1].Value
    if ($v -match '^(Connecting|Connected|Disconnected|Authentication required|Could not)' -and $l -ge 190 -and $l -le 210) {
      $c=Center $z; $d=[math]::Abs($c.y - ($tgt.cy + 30)); if ($d -lt $bd) { $bd=$d; $best=$v } } }
  if (-not $best) { return '(no state node)' }
  return $best }
# find a row by state (for the Connecting fast-path)
function FindRowByState { param($xml,[string]$pat)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match $pat) { $r += $v } }; return $r }
# answer an auth card: type a value into its password field and tap Continue
function AnswerAuth { param([string]$val)
  $x = Dump "AU0"
  if (-not (AuthCardUp $x)) { Log "  [Auth] no auth card up"; return $false }
  $ets = GetFormET $x
  foreach ($e in $ets) { Log "  [Auth] card ET pw=$($e.pw) h=$($e.h) [$($e.b)]" }
  $pw = $ets | Sort-Object h -Descending | Select-Object -First 1
  if (-not $pw) { Log "  [Auth] no field in card"; return $false }
  Log "  [Auth] tap field @ $($pw.cx),$($pw.cy)"; TapAt $pw.cx $pw.cy; Start-Sleep -Milliseconds 800
  $f = GetFormET (Dump "AU1") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $f) { Log "  [Auth] field not focused; typing anyway" }
  A @("shell","input","text",$val) | Out-Null; Start-Sleep -Milliseconds 900
  $x = Dump "AU2"
  $ct=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Continue' -or $z.'content-desc' -eq 'Continue') { $ct=$z } }
  if (-not $ct) { Log "  [Auth] no Continue button"; return $false }
  $c=Center $ct; Log "  [Auth] tap Continue @ $($c.x),$($c.y)"; TapAt $c.x $c.y; Start-Sleep -Milliseconds 3000
  return $true }
