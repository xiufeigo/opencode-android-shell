# v134 队列行解析器 —— 统一口径
# 用法: pwsh -File parse.ps1 <dump.xml> [label]
param([Parameter(Mandatory=$true)][string]$File, [string]$Label = "n/a")

$ErrorActionPreference = 'Continue'
$raw = Get-Content $File -Raw
$rx = [regex]'<node[^>]*>'
$rows = New-Object System.Collections.Generic.List[object]
$pat = 'E2ECHK|Steer|Send|Rem[o]ve|Working|Stop|Queue|Paused|Error'

foreach($m in $rx.Matches($raw)){
  $n = $m.Value
  $cd = ''; if($n -match 'content-desc="([^"]*)"'){ $cd = $Matches[1] }
  $tx = ''; if($n -match 'text="([^"]*)"'){ $tx = $Matches[1] }
  $cl = ''; if($n -match 'class="([^"]*)"'){ $cl = $Matches[1] }
  $b = ''
  if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){
    $b = "[" + $Matches[1] + "," + $Matches[2] + "][" + $Matches[3] + "," + $Matches[4] + "]"
  }
  if($cd -match $pat -or $tx -match 'E2ECHK'){
    $rows.Add([pscustomobject]@{ desc = $cd; text = $tx; bounds = $b; class = $cl })
  }
}

Write-Output ("=== PARSE file=$File label=$Label ===")
Write-Output ("nodes_total=" + $rx.Matches($raw).Count)
$rows | Format-Table -AutoSize -Wrap | Out-String -Width 200 | Write-Output
