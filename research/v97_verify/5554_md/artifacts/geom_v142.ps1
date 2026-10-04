# v142 — a11y reachability sampler: is the 48 dp target actually REACHABLE?
# Read-only over the archived real-device dumps. No device needed, no adb.
# Outputs, per frame:
#   * every clickable node: bounds, size, content-desc
#   * 48 dp >= ? , pairwise overlap among the band's controls, containment in the pane box
$ErrorActionPreference = 'Stop'
$root = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\artifacts'
$frames = @('v137_BARON_M.xml','v140_600off.xml','v140_768.xml','v140_A_bar.xml')

foreach ($f in $frames) {
  $p = Join-Path $root $f
  if (-not (Test-Path $p)) { Write-Host "=== $f : MISSING"; continue }
  $xml = Get-Content $p -Raw
  $nodes = [regex]::Matches($xml, '<node\b[^>]*>') | ForEach-Object {
    $t = $_.Value
    $b = [regex]::Match($t, 'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
    if (-not $b.Success) { return }
    [pscustomobject]@{
      cls   = ([regex]::Match($t, 'class="([^"]*)"')).Groups[1].Value
      desc  = ([regex]::Match($t, 'content-desc="([^"]*)"')).Groups[1].Value
      clk   = (([regex]::Match($t, 'clickable="([^"]*)"')).Groups[1].Value -eq 'true')
      l = [int]$b.Groups[1].Value; t = [int]$b.Groups[2].Value
      r = [int]$b.Groups[3].Value; b = [int]$b.Groups[4].Value
    }
  }
  Write-Host ""
  Write-Host "=========== FRAME $f  (nodes=$($nodes.Count)) ==========="
  $ed = @($nodes | Where-Object { $_.cls -eq 'android.widget.EditText' })
  foreach ($e in $ed) { Write-Host ("  EDITOR       [{0},{1}][{2},{3}]  {4}x{5}" -f $e.l,$e.t,$e.r,$e.b,($e.r-$e.l),($e.b-$e.t)) }
  # screen width = widest node
  $sw = ($nodes | ForEach-Object { $_.r - $_.l } | Measure-Object -Maximum).Maximum
  Write-Host "  SCREENWIDTH  $sw"
  # pane = tightest full-width View strictly containing the band top
  if ($ed.Count -ge 1) {
    $e0 = $ed[0]; $bt = $e0.t
    $pane = $nodes | Where-Object { $_.cls -eq 'android.view.View' -and ($_.r - $_.l) -eq $sw -and $_.t -lt $bt -and $_.b -gt $bt } | Sort-Object b
    if ($pane) {
      Write-Host ("  PANE         [{0},{1}][{2},{3}]  paneBottom={4}" -f $pane[0].l,$pane[0].t,$pane[0].r,$pane[0].b,$pane[0].b)
    }
  }
  Write-Host "  -- all clickable nodes, sorted by top --"
  $nodes | Where-Object { $_.clk } | Sort-Object t, l | ForEach-Object {
    $w = $_.r - $_.l; $h = $_.b - $_.t
    $ok48 = if ($h -ge 48) { 'OK48' } else { 'SHORT' }
    Write-Host ("   {0,-6} [{1},{2}][{3},{4}] {5}x{6}  {7,-22} {8}" -f $ok48,$_.l,$_.t,$_.r,$_.b,$w,$h,$_.cls,$_.desc)
  }
  # pairwise overlap among clickable nodes in the lower (band) region
  Write-Host "  -- pairwise overlap among clickable nodes (any overlap, area px^2) --"
  $clk = @($nodes | Where-Object { $_.clk })
  for ($i=0; $i -lt $clk.Count; $i++) {
    for ($j=$i+1; $j -lt $clk.Count; $j++) {
      $a=$clk[$i]; $b=$clk[$j]
      $ow = [Math]::Min($a.r,$b.r) - [Math]::Max($a.l,$b.l)
      $oh = [Math]::Min($a.b,$b.b) - [Math]::Max($a.t,$b.t)
      if ($ow -gt 0 -and $oh -gt 0) {
        Write-Host ("   OVERLAP {0}x{1}  [{2},{3}][{4},{5}] '{6}'  vs  [{7},{8}][{9},{10}] '{11}'" -f $ow,$oh,$a.l,$a.t,$a.r,$a.b,$a.desc,$b.l,$b.t,$b.r,$b.b,$b.desc)
      }
    }
  }
}
