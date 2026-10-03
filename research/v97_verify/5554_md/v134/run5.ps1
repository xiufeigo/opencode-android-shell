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
function EditCenter { param([string]$File)
  $raw = Get-Content $File -Raw
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n=$m.Value
    if($n -match 'class="android\.widget\.EditText"'){
      if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){
        $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
        return @([int](($x1+$x2)/2), [int](($y1+$y2)/2))
      }
    }
  }
  return $null
}
function ClearComposerNow {
  $f = DumpUI "tmp_cc"
  $c = EditCenter $f
  if($c){ A shell input tap $c[0] $c[1] | Out-Null; Start-Sleep -Milliseconds 400 }
  A shell input keyevent 123 | Out-Null
  A shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70 71 72 73 74 75 76 77 78 79 80; do input keyevent 67; done" | Out-Null
  Start-Sleep -Milliseconds 500
}
function ShowQueue { param([string]$File,[string]$tag)
  $raw = Get-Content $File -Raw
  L "  -- $tag rows:"
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n=$m.Value; $cd=''; if($n -match 'content-desc="([^"]*)"'){ $cd=$Matches[1] }
    $tx=''; if($n -match 'text="([^"]*)"'){ $tx=$Matches[1] }
    $b=''; if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $b="[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]" }
    if($cd -match 'v134|Steer|Rem[o]ve' -or $tx -match 'v134|Steer|Rem[o]ve'){ L ("     cd='$cd' text='$tx' $b") }
  }
}

# 1) 清空 + 发超长回合
ClearComposerNow
$f = DumpUI "c01_typed_pre"
$c = EditCenter $f
A shell input tap $c[0] $c[1] | Out-Null; Start-Sleep -Milliseconds 400
A shell "input text 'E2ECHK5554u1v134%scount%sfrom%s1%sto%s500000,%sone%snumber%sper%sline,%snever%sstop%searly'" | Out-Null
Start-Sleep -Milliseconds 800
$f = DumpUI "c02_typed"
$sc = DescCenter $f "Send"
L "typed='$(ComposerText $f)' sendBtn=$($sc -join ',')"
A shell input tap $sc[0] $sc[1] | Out-Null
Start-Sleep -Seconds 3
$f = DumpUI "c03_running"
L "RUNNING: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') ime=$(ImeShown)"

# 2) followup + 开队列
$c = EditCenter $f
A shell input tap $c[0] $c[1] | Out-Null; Start-Sleep -Milliseconds 400
A shell "input text 'E2ECHK5554u1v134%sparkbeta'" | Out-Null
Start-Sleep -Milliseconds 900
$f = DumpUI "c04_followup"
$q = TextCenter $f "Queue"
L "followup='$(ComposerText $f)' QueueChip=$($q -join ',') working=$(DescCount $f 'Working')"
if($q){
  A shell input tap $q[0] $q[1] | Out-Null
  Start-Sleep -Milliseconds 1300
  $f = DumpUI "c05_queueopen"
  L "QUEUEOPEN: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working')"
  ShowQueue $f "open"; Shot "c05_queueopen" | Out-Null
  # 3) 清 composer -> 期望 Stop 出现
  ClearComposerNow
  $f = DumpUI "c06_pre_stop"
  L "PRE_STOP: stop=$(DescCount $f 'Stop') working=$(DescCount $f 'Working') ime=$(ImeShown) composer='$(ComposerText $f)'"
  ShowQueue $f "pre"; Shot "c06_pre_stop" | Out-Null
}
