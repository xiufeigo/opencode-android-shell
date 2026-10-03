$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"

# 找本轮自建消息的 a11y 节点
$d = DumpUI "p01_find"
$rows = New-Object System.Collections.Generic.List[object]
foreach($n in (NodeList $d.Path)){
  if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
    $rows.Add([pscustomobject]@{x1=[int]$Matches[1];y1=[int]$Matches[2];x2=[int]$Matches[3];y2=[int]$Matches[4];text=$n.text;desc=$n.desc;cls=$n.class})
  }
}
$mine = $rows | Where-Object { ($_.text -match 'u1v134') -or ($_.desc -match 'u1v134') } | Where-Object { $_.cls -eq 'android.widget.TextView' -and $_.text -match 'u1v134' }
L "自建消息文本节点数 = $($mine.Count)"
$mine | ForEach-Object { L "  '$($_.text)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)]" }

$target = $mine | Select-Object -First 1
if($target){
  $cx = [int](($target.x1+$target.x2)/2); $cy = [int](($target.y1+$target.y2)/2)
  L "长按目标 center=($cx,$cy) —— 按住 1400ms"
  # 长按 >= 1200ms
  & $Adb -s $Serial shell "input swipe $cx $cy $cx $cy 1400" | Out-Null
  Start-Sleep -Seconds 1
  $d2 = DumpUI "p02_afterlong"
  L "长按后节点数=$($d2.Nodes)"
  L "--- 弹层候选项（Delete/Copy/Edit/菜单）---"
  $hit = $false
  foreach($n in (NodeList $d2.Path)){
    $lbl = if($n.text){$n.text}else{$n.desc}
    if($lbl -and $lbl -match 'Delete|Rem[o]ve|Copy|Edit|Cancel|menu'){ L "   '$lbl' $($n.bounds) $($n.class)"; $hit = $true }
  }
  if(-not $hit){ L "   (未发现任何删除/编辑菜单项)" }
  ShotV2 "p02_afterlong" | Out-Null

  # 弹层归属核验
  L "--- dumpsys window windows 归属核验 ---"
  $win = & $Adb -s $Serial shell "dumpsys window windows"
  $win | Out-File "$ev\_p_windows.txt" -Encoding utf8
  $own = $win | Select-String -Pattern 'Window\{' | Select-Object -First 25
  $own | ForEach-Object { L "   $($_.ToString().Trim())" }
  $mineWin = $win | Select-String -Pattern 'com.xiufeigo.opencodeshell'
  L "   含本 app 的 window 记录数 = $($mineWin.Count)"
  $foreign = $win | Select-String -Pattern 'Window\{' | Where-Object { $_.ToString() -notmatch 'com.xiufeigo.opencodeshell|StatusBar|InputMethod|NotificationShade|HeadsUp|SystemUI|WindowManager|ActivityRecord|InputMethod' }
  L "   疑似外来弹层记录数 = $($foreign.Count)"
  $foreign | Select-Object -First 8 | ForEach-Object { L "   FOREIGN? $($_.ToString().Trim())" }
} else { L "本轮自建消息未在时间线可见（可能已被回合消耗/滚动出屏）" }
