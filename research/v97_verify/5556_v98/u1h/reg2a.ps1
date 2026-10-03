# u1h regression 2: does the queue bar / Queue chip exist at all?
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h\helper.ps1

Assert-Focus "R2-pre" | Out-Null
Write-Output "wm: $(A shell wm size | Select-String Override) | pid=$(Get-Pidof) | inputShown=$(Get-InputShown)"

$p = Dump "q00_base"
$n = Get-Nodes $p
Write-Output "nodes=$($n.Count)"

Write-Output ""
Write-Output "=== [1] FULL-TREE GREP for queue vocabulary (raw XML) ==="
$raw = [IO.File]::ReadAllText($p)
foreach ($kw in 'Steer', 'Queue', 'queued', 'Remove', 'Clear', '×', 'x22', 'dequeue') {
  $c = ([regex]::Matches($raw, [regex]::Escape($kw), 'IgnoreCase')).Count
  Write-Output ("  '{0,-8}' case-insensitive hits = {1}" -f $kw, $c)
}

Write-Output ""
Write-Output "=== [2] ALL nodes with text or content-desc (full tree) ==="
$n | Where-Object { $_.text -ne '' -or $_.'content-desc' -ne '' } | ForEach-Object {
  $lbl = if ($_.text -ne '') { "t='" + $_.text + "'" } else { '' }
  $d = if ($_.'content-desc' -ne '') { "d='" + $_.'content-desc' + "'" } else { '' }
  "{0,-22} {1,-28} clickable={2,-5} {3} {4}" -f $_.class, $_.bounds, $_.clickable, $lbl, $d
}

Write-Output ""
Write-Output "=== [3] composer chip-row band (y 258..286) ==="
$n | Where-Object { $b = Bnd $_.bounds; $b -and $b.T -ge 255 -and $b.B -le 290 } | ForEach-Object {
  "  {0,-22} {1,-28} clickable={2,-5} t='{3}' d='{4}'" -f $_.class, $_.bounds, $_.clickable, $_.text, $_.'content-desc'
}
