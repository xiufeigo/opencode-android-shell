# Tap by content-desc / text, coordinates parsed at runtime from a fresh dump.
param([string]$Match, [int]$Index = 0, [string]$Dump = "tmpxml")
$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
$f = Dump-UI $Dump
[xml]$x = Get-Content $f -Raw -Encoding utf8
$nodes = @()
function Walk($n) { foreach ($c in $n.ChildNodes) { if ($c.Name -eq 'node') { $script:nodes += $c; Walk $c } } }
Walk $x.hierarchy.node
$hits = @($nodes | Where-Object {
  $_.GetAttribute('content-desc') -like "*$Match*" -or $_.GetAttribute('text') -like "*$Match*"
})
"matches='$Match' count=$($hits.Count)"
if ($hits.Count -le $Index) { "NOT FOUND"; exit 5 }
$h = $hits[$Index]
$b = $h.GetAttribute('bounds')
"target bounds=$b clickable=$($h.GetAttribute('clickable')) enabled=$($h.GetAttribute('enabled'))"
if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { "BAD BOUNDS"; exit 6 }
$x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
$cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
"tap at $cx,$cy"
A shell input tap $cx $cy
Start-Sleep -Milliseconds 900
