$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\a.ps1"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }

# 运行中键入 followup -> Queue chip
A shell "input text 'E2ECHK5554u1v134%sparked%srow%salpha'" | Out-Null
Start-Sleep -Milliseconds 1200
$f = DumpUI "a06_followup"
L "followup: composer='$(ComposerText $f)' imeShown=$(ImeShown) stopDesc=$(DescCount $f 'Stop') working=$(DescCount $f 'Working')"
$q = DescCenter $f "Queue"
L "Queue chip=$($q -join ',')"
Shot "a06_followup" | Out-Null
if(-not $q){ L "NO QUEUE CHIP"; exit 1 }

# 点 Queue 开队列条
A shell input tap $q[0] $q[1] | Out-Null
Start-Sleep -Milliseconds 1500
$f = DumpUI "a07_queueopen"
L "queueOpen: stopDesc=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') imeShown=$(ImeShown) composer='$(ComposerText $f)'"
$raw = Get-Content $f -Raw
foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
  $n=$m.Value; $cd=''
  if($n -match 'content-desc="([^"]*)"'){ $cd=$Matches[1] }
  $b=''; if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $b="[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]" }
  if($cd -match 'v134|Steer|Send|Rem[o]ve|Stop|Working|Queue'){ L ("  CD='$cd' $b") }
}
Shot "a07_queueopen" | Out-Null
