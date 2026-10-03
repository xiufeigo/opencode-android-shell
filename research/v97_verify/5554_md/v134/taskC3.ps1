$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"

# 跳到最新
$d = DumpUI "q01"
$j = DescCenter $d.Path "Jump to latest"
L "JumpToLatest=$($j -join ',')"
if($j){ & $Adb -s $Serial shell input tap $j[0] $j[1] | Out-Null; Start-Sleep -Seconds 2 }
$d = DumpUI "q02_latest"
L "after jump: nodes=$($d.Nodes)"
$rows = New-Object System.Collections.Generic.List[object]
foreach($n in (NodeList $d.Path)){
  if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
    $rows.Add([pscustomobject]@{x1=[int]$Matches[1];y1=[int]$Matches[2];x2=[int]$Matches[3];y2=[int]$Matches[4];text=$n.text;desc=$n.desc;cls=$n.class;clickable=$n.clickable})
  }
}
$mine = $rows | Where-Object { $_.text -match 'u1v134' }
L "本轮自建文本节点数 = $($mine.Count)"
$mine | ForEach-Object { L "  '$($_.text)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] $($_.cls)" }
ShotV2 "q02_latest" | Out-Null
$rows | Where-Object { $_.cls -eq 'android.widget.TextView' -and $_.text -ne '' } | ForEach-Object { L "  TXT '$($_.text)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)]" }
