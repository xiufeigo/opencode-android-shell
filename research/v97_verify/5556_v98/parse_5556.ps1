# parse_5556.ps1 —— v106 真机复验 dump 解析器（运行期从 dump 解析坐标）
# 用法: pwsh -NoProfile -File parse_5556.ps1 -Dump <xml 路径> -Label "<档位标签>" [-ScreenH 768]
param(
  [Parameter(Mandatory=$true)][string]$Dump,
  [string]$Label = "unnamed",
  [int]$ScreenH = 768
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Test-Path $Dump)) { Write-Output "NO_DUMP: $Dump"; exit 2 }
$content = Get-Content -LiteralPath $Dump -Raw -Encoding UTF8

# 按 <node 边界切分（--compressed 会把整棵树压成一行）
$chunks = $content -split '(?=<node )'

$nodes = New-Object System.Collections.Generic.List[object]
foreach ($chunk in $chunks) {
  if ($chunk -notmatch 'bounds=') { continue }
  if ($chunk -notmatch 'bounds="') { continue }

  $t = ''
  if ($chunk -match 'text="(?<v>(?:[^"\\]|\\.)*)"') { $t = $Matches['v'] }
  elseif ($chunk -match "text='(?<v2>[^']*)'") { $t = $Matches['v2'] }
  $t = $t -replace '\\"','"' -replace '\\\\','\'

  $c = ''
  if ($chunk -match 'content-desc="(?<v>(?:[^"\\]|\\.)*)"') { $c = $Matches['v'] }
  elseif ($chunk -match "content-desc='(?<v2>[^']*)'") { $c = $Matches['v2'] }
  $c = $c -replace '\\"','"' -replace '\\\\','\'

  $rid = ''
  if ($chunk -match 'resource-id="(?<v>[^"]*)"') { $rid = $Matches['v'] }
  $cls = ''
  if ($chunk -match 'class="(?<v>[^"]*)"') { $cls = $Matches['v'] }
  $clk = ''
  if ($chunk -match 'clickable="(?<v>[^"]*)"') { $clk = $Matches['v'] }

  $b = [regex]::Match($chunk, 'bounds="\[(?<l>-?\d+),(?<t>-?\d+)\]\[(?<r>-?\d+),(?<b>-?\d+)\]"')
  if (-not $b.Success) { continue }

  $L=[int]$b.Groups['l'].Value; $T=[int]$b.Groups['t'].Value
  $R=[int]$b.Groups['r'].Value; $B=[int]$b.Groups['b'].Value
  $nodes.Add([pscustomobject]@{
    Text=$t; Desc=$c; Rid=$rid; Cls=$cls; Click=$clk
    L=$L; T=$T; R=$R; B=$B; W=($R-$L); H=($B-$T)
  })
}

Write-Output "===== LABEL: $Label  (ScreenH=$ScreenH) ====="
Write-Output "NODE_COUNT: $($nodes.Count)"

function Show($title, $sel) {
  Write-Output ""
  Write-Output "---- $title ----"
  $i = 0
  foreach ($n in $sel) {
    $i++
    $lab = if ($n.Text) { $n.Text } elseif ($n.Desc) { $n.Desc } else { $n.Rid }
    if ($null -eq $lab) { $lab = '' }
    if ($lab.Length -gt 44) { $lab = $lab.Substring(0,44) + '~' }
    Write-Output ("[{0,2}] L={1,5} T={2,5} R={3,5} B={4,5} W={5,5} H={6,4} clk={7,-5} {8,-24} | {9}" -f `
      $i,$n.L,$n.T,$n.R,$n.B,$n.W,$n.H,$n.Click,($n.Cls -replace '^.*\.',''),$lab)
  }
}

Show "ALL NODES" $nodes

Write-Output ""
Write-Output "---- KEYWORD (editor|add|model|stop|queue|steer|terminal|open|send|thinking|readable|ime) ----"
Show "kw" ($nodes | Where-Object { ($_.Text + ' ' + $_.Desc) -match '(?i)editor|add|model|stop|queue|steer|terminal|open|send|thinking|readable|ime|new session|search' })

Write-Output ""
Write-Output "---- BOTTOM BAND (T >= ScreenH-320) ----"
Show "bottom" ($nodes | Where-Object { $_.T -ge ($ScreenH-320) -and $_.H -ge 4 })
