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
function ShowQueue { param([string]$File,[string]$tag)
  $raw = Get-Content $File -Raw
  L "  -- $tag queue nodes:"
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n=$m.Value; $cd=''; if($n -match 'content-desc="([^"]*)"'){ $cd=$Matches[1] }
    $tx=''; if($n -match 'text="([^"]*)"'){ $tx=$Matches[1] }
    $b=''; if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $b="[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]" }
    if($cd -match 'v134|Steer|Rem[o]ve' -or $tx -match 'v134|Steer|Rem[o]ve'){ L ("     cd='$cd' text='$tx' $b") }
  }
}
$flag = "parkbeta"
# 清空 composer 并发超长回合
A shell input tap 683 320 | Out-Null; Start-Sleep -Milliseconds 400
ClearComposer; Start-Sleep -Milliseconds 500
A shell "input text 'E2ECHK5554u1v134%scount%sfrom%s1%sto%s300000,%sone%snumber%sper%sline,%sdo%snot%sstop'" | Out-Null
Start-Sleep -Milliseconds 700
$f = DumpUI "b01_typed"
$sc = TextCenter $f "Send"
L "typed composer='$(ComposerText $f)' send=$($sc -join ',')"
A shell input tap $sc[0] $sc[1] | Out-Null
Start-Sleep -Seconds 3
$f = DumpUI "b02_running"
L "RUNNING: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') ime=$(ImeShown)"

# 立刻 followup
A shell "input text 'E2ECHK5554u1v134%s$flag'" | Out-Null
Start-Sleep -Milliseconds 900
$f = DumpUI "b03_followup"
$q = TextCenter $f "Queue"
L "followup composer='$(ComposerText $f)' QueueChip=$($q -join ',') working=$(DescCount $f 'Working')"
if($q){
  A shell input tap $q[0] $q[1] | Out-Null
  Start-Sleep -Milliseconds 1200
  $f = DumpUI "b04_queueopen"
  L "QUEUEOPEN: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working')"
  ShowQueue $f "open"
  Shot "b04_queueopen" | Out-Null

  # 清空 composer -> 期望 Stop 出现 (running && empty)
  ClearComposer
  Start-Sleep -Milliseconds 700
  $f = DumpUI "b05_pre_stop"
  L "PRE_STOP: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') ime=$(ImeShown) composer='$(ComposerText $f)'"
  ShowQueue $f "pre"
  Shot "b05_pre_stop" | Out-Null
  $sp = DescCenter $f "Stop"
  L "StopButton=$($sp -join ',')"
}
