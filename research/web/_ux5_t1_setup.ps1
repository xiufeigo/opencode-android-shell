# _ux5_t1_setup.ps1 - UX5 平板档：首启灰态 -> 配置对话框 -> 连上 4095 -> 开新会话进 session 页
$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\web\_ux5_common.ps1"
$D="emulator-6590"
$log="$OUT\_ux5_t1_setup.log"
L "=== UX5 T1 setup start ==="
A shell am force-stop $Pkg | Out-Null; Start-Sleep -Seconds 1
A logcat -c | Out-Null
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 8
$xml = Dump
$grey = FindB $xml '去设置服务器|设置服务器'
if ($grey) { L "grey state shown; tapping 去设置服务器"; Tap $grey 1500 }
else { L "no grey-state entry found; dump head:"; L ($xml.Substring(0,[Math]::Min(300,$xml.Length))) }
$xml = Dump
$et = @()
foreach($m in [regex]::Matches($xml,'<node[^>]*')){ $n=$m.Value
  if($n -match 'class="android\.widget\.EditText"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $et += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } }
L "editTextCount=$($et.Count)"
if($et.Count -gt 0){
  $pb = $et[0]
  L "tap password field"
  Tap $pb 800
  foreach($ch in "opencode".ToCharArray()){ A shell input text "$ch" | Out-Null; Start-Sleep -Milliseconds 250 }
  L "typed password char-by-char"
  $xml2 = Dump
  $btns=@()
  foreach($m in [regex]::Matches($xml2,'<node[^>]*')){ $n=$m.Value
    if($n -match 'class="android\.widget\.Button"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -notmatch 'enabled="false"'){ $btns += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } }
  if($btns.Count -gt 0){ $ok=$btns | Sort-Object { ($_[0]+$_[2])/2 } | Select-Object -Last 1
    L "tapping rightmost button (count=$($btns.Count))"; Tap $ok 4000 }
  else { L "no button -> ENTER"; A shell input keyevent KEYCODE_ENTER | Out-Null; Start-Sleep -Seconds 3 }
}
Start-Sleep -Seconds 12
Shot "ux5_t1_home_after_setup.png"
$lc = (A logcat -d -s OcWebHost:V) -join "`n"
$lc | Out-File -Encoding utf8 "$OUT\_ux5_t1_setup.logcat.txt"
$lc -split "`n" | Where-Object { $_ -match 'listening|credentials|proxy|error|fail' } | Select-Object -First 20 | ForEach-Object { L ("  LOG " + $_) }
L "=== UX5 T1 setup done ==="
