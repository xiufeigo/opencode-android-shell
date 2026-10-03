# Parse uiautomator dump -> four numbers (timeline box / queue bar / overlap / readable)
# Matches both text="..." and text='...' ; coordinates parsed at runtime.
param([string]$File)
$ErrorActionPreference = 'Continue'
[xml]$x = Get-Content $File -Raw -Encoding utf8
$nodes = @()
function Walk($n) {
  foreach ($c in $n.ChildNodes) {
    if ($c.Name -eq 'node') {
      $script:nodes += [pscustomobject]@{
        text = $c.GetAttribute('text'); desc = $c.GetAttribute('content-desc')
        cls  = $c.GetAttribute('class');  clickable = $c.GetAttribute('clickable')
        enabled = $c.GetAttribute('enabled'); bounds = $c.GetAttribute('bounds')
      }
      Walk $c
    }
  }
}
Walk $x.hierarchy.node
"TOTAL NODES = $($nodes.Count)"
"CLICKABLE   = $(($nodes | Where-Object { $_.clickable -eq 'true' }).Count)"
"EDITTEXT    = $(($nodes | Where-Object { $_.cls -match 'EditText' }).Count)"
"SCROLL      = $(($nodes | Where-Object { $_.cls -match 'ScrollView|AbsListView|RecyclerView' }).Count)"
""
"=== ALL NODES (idx | class | text/desc | bounds | clk) ==="
$i = 0
foreach ($n in $nodes) {
  $t = $n.text; if (-not $t) { $t = $n.desc }
  if ($t -and $t.Length -gt 60) { $t = $t.Substring(0,60) + '…' }
  $i++
  "{0,3} | {1} | {2} | {3} | clk={4}" -f $i, ($n.cls -replace 'android\.widget\.','' -replace 'android\.view\.',''), $t, $n.bounds, $n.clickable
}
