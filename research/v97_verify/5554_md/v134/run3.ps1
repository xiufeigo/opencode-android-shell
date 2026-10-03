$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\a.ps1"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }
function TextCenter { param([string]$File,[string]$Text)
  $raw = Get-Content $File -Raw
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n=$m.Value
    if($n -match ('text="'+[regex]::Escape($Text)+'"')){
      if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){
        $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
        return @([int](($x1+$x2)/2), [int](($y1+$y2)/2), "[$x1,$y1][$x2,$y2]")
      }
    }
  }
  return $null
}

# 1) 点 Queue chip (text 型)
$q = TextCenter "a08_recheck" "Queue"
L "Queue chip(text)=$($q -join ',')"
A shell input tap $q[0] $q[1] | Out-Null
Start-Sleep -Milliseconds 1500
$f = DumpUI "a09_queueopen"
L "queueOpen: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') ime=$(ImeShown) composer='$(ComposerText $f)'"
$raw = Get-Content $f -Raw
foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
  $n=$m.Value; $cd=''; if($n -match 'content-desc="([^"]*)"'){ $cd=$Matches[1] }
  $tx=''; if($n -match 'text="([^"]*)"'){ $tx=$Matches[1] }
  $b=''; if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $b="[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]" }
  if($cd -match 'v134|Steer|Rem[o]ve' -or $tx -match 'v134|Steer|Rem[o]ve'){ L ("  cd='$cd' text='$tx' $b") }
}
Shot "a09_queueopen" | Out-Null

# 2) 清空 composer -> 期望 Stop 判据出现(running && empty)
A shell input tap 683 320 | Out-Null
Start-Sleep -Milliseconds 500
$f = DumpUI "a10_refocus"
ClearComposer
Start-Sleep -Milliseconds 800
$f = DumpUI "a11_cleared"
L "cleared: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') ime=$(ImeShown) composer='$(ComposerText $f)'"
Shot "a11_cleared" | Out-Null
