# Inspect a saved dump: print EditText nodes, and for a given y-band print the node tree
# with clickable flags so we can see whether a row has a clickable ancestor.
param(
  [string]$File = "q_multi.xml",
  [int]$YMin = -1,
  [int]$YMax = 99999,
  [int]$Indent = 0
)
$ErrorActionPreference = 'Continue'
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\evidence"
$raw = Get-Content "$ev\$File" -Raw -Encoding UTF8

"=== EditText nodes in $File ==="
foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
  $n = $m.Value
  if ($n -notmatch 'class="[^"]*EditText"') { continue }
  $tx=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
  $fo=[regex]::Match($n,'focused="([^"]*)"').Groups[1].Value
  $b=[regex]::Match($n,'bounds="([^"]*)"').Groups[1].Value
  "  focused=$fo b=$b text='$tx'"
}

"=== clickable nodes with y in [$YMin,$YMax] ==="
foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
  $n = $m.Value
  $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $b.Success) { continue }
  $t=[int]$b.Groups[2].Value
  if ($t -lt $YMin -or $t -gt $YMax) { continue }
  $ck=[regex]::Match($n,'clickable="([^"]*)"').Groups[1].Value
  if ($ck -ne 'true') { continue }
  $tx=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
  $cd=[regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
  $cl=[regex]::Match($n,'class="([^"]*)"').Groups[1].Value -replace '.*\.',''
  $lb=$b.Groups[1].Value+','+$b.Groups[2].Value+'-'+$b.Groups[3].Value+','+$b.Groups[4].Value
  if ($tx.Length -gt 34) { $tx=$tx.Substring(0,34)+'~' }
  if ($cd.Length -gt 34) { $cd=$cd.Substring(0,34)+'~' }
  "  b=$lb $cl text='$tx' desc='$cd'"
}
