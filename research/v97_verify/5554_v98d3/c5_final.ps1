. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1
$ErrorActionPreference='Continue'

function GTRead {
  $raw = (Sh @('run-as',$script:PKG,'cat','shared_prefs/oc_server_config.xml')) -join "`n"
  $ms = [regex]::Matches($raw, '<string name="([^"]+)">([^<]*)</string>')
  $rows = @()
  foreach ($m in $ms) { $rows += [pscustomobject]@{ Name=$m.Groups[1].Value; ValLen=$m.Groups[2].Value.Length } }
  return @($rows | Where-Object { $_.Name -notlike '__androidx*' })
}
# Row title: nearest text above the kebab, inside the PANEL BODY only (y>=900 kills the
# y~441/y~744 tab labels, x>=190 kills the x=148 panel header, R<=kebab.R kills wide labels).
# x=201 => SSH tunnel row (SSH tunnels section), x=254 => WebSocket server row.
function RowTitleFor($doc, $keb) {
  $best=$null; $bestD=1e9
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n; if (-not $t) { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.T -lt 900) { continue }
    if ($b.L -lt 190 -or $b.L -gt 260) { continue }
    if ($b.R -gt $keb.Bnd.R) { continue }
    if ($b.B -gt $keb.Bnd.T + 5) { continue }
    $d = $keb.Bnd.T - $b.B
    if ($d -ge 0 -and $d -lt $bestD) { $bestD=$d; $best=[pscustomobject]@{T=$t;L=$b.L} }
  }
  if ($null -eq $best) { return $null }
  return $best
}
function ScrollToTop { for ($i=0;$i -lt 4;$i++){ A @('shell','input','swipe','540','1000','540','1900','80') | Out-Null; Start-Sleep -Milliseconds 250 } }

"### ===== close the form (its own Cancel, resolved at runtime) ====="
$doc = Dump 'fin_close'
$as = FindByLabel $doc 'Add server'
if (@($as).Count -gt 0) {
  $an = @($as)[0]
  $cands = @()
  foreach ($c in (FindByLabel $doc 'Cancel')) {
    $cb = NBnd $c
    # the form's Cancel is the one on the same row as Add server (same y band, x < 760)
    $ab = NBnd $an
    if ([math]::Abs($cb.T - $ab.T) -lt 40 -and $cb.R -lt 760) { $cands += ,$cb }
  }
  if (@($cands).Count -gt 0) {
    $cb = @($cands)[0]
    $s = "tap form Cancel @($([int](($cb.L+$cb.R)/2)),$([int](($cb.T+$cb.B)/2)))"
    [Console]::Out.WriteLine($s)
    TapXY ([int](($cb.L+$cb.R)/2)) ([int](($cb.T+$cb.B)/2))
    Start-Sleep -Seconds 2
  } else { "NO_FORM_CANCEL_CANDIDATE" }
} else { "form already closed" }

ScrollToTop
"### ===== B: UI ROW COUNT (scroll traversal) ====="
$seen = [ordered]@{}
for ($i=0; $i -lt 7; $i++) {
  $doc = Dump ("fin_s{0}" -f $i)
  $groups = @()
  foreach ($g in (GroupHeadings $doc)) { $groups += $g }
  foreach ($k in (KebabList $doc)) {
    $r = RowTitleFor $doc $k
    if ($null -ne $r) {
      $kind = if ($r.L -le 220) { 'SSH' } else { 'WS' }
      $seen[("{0}|{1}" -f $kind,$r.T)] = @($kind,$r.T,$k.CY)
    }
  }
  $s = "  step{0}: groups=[{1}]  rows so far: {2}" -f $i,($groups -join '/'),((@($seen.Keys) | ForEach-Object { $p=$seen[$_]; "$($p[0]):'$($p[1])'" }) -join ', ')
  [Console]::Out.WriteLine($s)
  A @('shell','input','swipe','540','1850','540','1750','80') | Out-Null
  Start-Sleep -Milliseconds 320
}
$ssh = @($seen.Values | Where-Object { $_[0] -eq 'SSH' })
$ws  = @($seen.Values | Where-Object { $_[0] -eq 'WS' })
$s = "UI_ROWS_TOTAL = {0}   (SSH={1}, WS={2})" -f @($seen.Keys).Count,$ssh.Count,$ws.Count
[Console]::Out.WriteLine($s)
foreach ($v in $ssh) { $s="   SSH row: '{0}'" -f $v[1]; [Console]::Out.WriteLine($s) }
foreach ($v in $ws)  { $s="   WS  row: '{0}'" -f $v[1]; [Console]::Out.WriteLine($s) }

"### ===== A: GROUND TRUTH (final) ====="
$g = GTRead
$s = "GT_DATA_KEYS = {0}" -f $g.Count
[Console]::Out.WriteLine($s)
foreach ($r in $g) { $s="   key {0,-46} vlen={1}" -f $r.Name,$r.ValLen; [Console]::Out.WriteLine($s) }

"### ===== residual self-made entries anywhere in the panel? ====="
$hit = FindTextContains (Dump 'fin_res') 'E2ECHK5554d3c'
$s = "E2ECHK5554d3c* nodes visible in panel = {0}" -f @($hit).Count
[Console]::Out.WriteLine($s)
foreach ($h in $hit) { $hb=NBnd $h; $s="   [{0},{1}][{2},{3}] '{4}'" -f $hb.L,$hb.T,$hb.R,$hb.B,(NText $h); [Console]::Out.WriteLine($s) }
Cap 'fin_panel'

"### ===== D: CLEANUP ====="
A @('shell','rm','-rf','/sdcard/c5') | Out-Null
$chk = (Sh @('ls','-d','/sdcard/c5')) -join ' '
$s = "/sdcard/c5 after rm -rf -> '{0}'" -f $chk.Trim()
[Console]::Out.WriteLine($s)
$sd = (Sh @('ls','/sdcard')) -join ' '
$s = "/sdcard listing -> {0}" -f $sd.Trim()
[Console]::Out.WriteLine($s)
$s = "final GT key count = {0}" -f (GTRead).Count
[Console]::Out.WriteLine($s)
