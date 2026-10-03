$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
function L { param($m) Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"
$cx = 664; $cy = 172
L "长按 ($cx,$cy) 1400ms（>=1200ms 纪律）"
& $Adb -s $Serial shell "input swipe $cx $cy $cx $cy 1400" | Out-Null
Start-Sleep -Seconds 1
$d = DumpUI "r01_afterlong"
L "长按后 nodes=$($d.Nodes)"
$hit=$false
foreach($n in (NodeList $d.Path)){
  $lbl = if($n.text){$n.text}else{$n.desc}
  if($lbl -and $lbl -match 'Delete|Rem[o]ve|Copy|Edit|Cancel|Retry|Branch'){ L "  MENU '$lbl' $($n.bounds) $($n.class)"; $hit=$true }
}
if(-not $hit){ L "  (未出现任何菜单项)" }
ShotV2 "r01_afterlong" | Out-Null
L "--- 弹层归属：dumpsys window windows ---"
$win = & $Adb -s $Serial shell "dumpsys window windows"
$win | Out-File "$ev\_r_windows.txt" -Encoding utf8
$wins = $win | Select-String -Pattern '^\s*Window\{'
L "  window 记录数 = $($wins.Count)"
$wins | ForEach-Object { L "   $($_.ToString().Trim())" }
L "  --- 焦点 ---"
& $Adb -s $Serial shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
