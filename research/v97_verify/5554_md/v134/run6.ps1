$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\a.ps1"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m"; Add-Content "$ev\_poll.log" "$(Get-Date -Format 'HH:mm:ss') $m" }
Set-Content "$ev\_poll.log" ""
$f0 = "$ev\c06_pre_stop.xml"
$sp = DescCenter $f0 "Stop"
L "PRE : stop=$(DescCount $f0 'Stop') working=$(DescCount $f0 'Working') composer='$(ComposerText $f0)' stopBtn=$($sp -join ',')"
A shell input tap $sp[0] $sp[1] | Out-Null
L "tapped Stop"
Start-Sleep -Milliseconds 900
for($i=1; $i -le 24; $i++){
  $f = DumpUI ("c07_poll{0:d2}" -f $i)
  $s = DescCount $f "Stop"; $w = DescCount $f "Working"; $c = ComposerText $f
  $q = ""
  $raw = Get-Content $f -Raw
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n=$m.Value; $cd=''; if($n -match 'content-desc="([^"]*)"'){ $cd=$Matches[1] }
    $tx=''; if($n -match 'text="([^"]*)"'){ $tx=$Matches[1] }
    $b=''; if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $b="[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]" }
    if($cd -match 'v134|Steer|Rem[o]ve' -or $tx -match 'v134|Steer|Rem[o]ve'){ $q += "   cd='$cd' text='$tx' $b`n" }
  }
  L "poll#$i stop=$s working=$w ime=$(ImeShown) composer='$c'"
  if($q -ne ''){ L $q.TrimEnd() }
  if($s -eq 0 -and $w -eq 0 -and $c -eq ''){ L ">>> IDLE CONFIRMED at poll#$i"; Shot ("c07_idle_poll{0:d2}" -f $i) | Out-Null; break }
  Start-Sleep -Milliseconds 1400
}
