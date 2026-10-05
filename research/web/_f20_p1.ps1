# _f20_p1.ps1 - F20 ① 认证代理面 / ② isSecureContext：播种口令 + 起壳
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$Pkg='com.xiufeigo.opencodeshell.web.debug'
$Act='com.xiufeigo.opencodeshell.web.WebHostActivity'
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
function A { & $adb -s $D @args }
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p1.log" -Value $s -Encoding utf8 }
function Dump { A shell uiautomator dump /sdcard/_f20.xml | Out-Null; (A shell cat /sdcard/_f20.xml) -join '' }
function FindB { param($X,$Pat) foreach($m in [regex]::Matches($X,'<node[^>]*')){ $n=$m.Value; if($n -match $Pat -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } } return $null }
function Tap { param($b,[int]$ms=600) A shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null; Start-Sleep -Milliseconds $ms }

L "=== F20 P1 start (dev=$D pkg=$Pkg) ==="
A shell am force-stop $Pkg | Out-Null; Start-Sleep -Seconds 1
A logcat -c | Out-Null
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 6
$xml = Dump
$et = @()
foreach($m in [regex]::Matches($xml,'<node[^>]*')){ $n=$m.Value
  if($n -match 'class="android\.widget\.EditText"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $et += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } }
L "editTextCount=$($et.Count)"
if($et.Count -eq 0){ L "NO_SETUP_DIALOG (maybe password already present) -> dump head:"; L ($xml.Substring(0,[Math]::Min(400,$xml.Length))); }
else {
  $pb = $et[0]
  L "tap password field [$($pb[0]),$($pb[1])][$($pb[2]),$($pb[3])]"
  Tap $pb 800
  # F5 教训：input text 整串会丢字/错位 => 逐字符
  foreach($ch in "opencode".ToCharArray()){ A shell input text "$ch" | Out-Null; Start-Sleep -Milliseconds 300 }
  L "typed 8 chars one-by-one"
  $xml2 = Dump
  $btns=@()
  foreach($m in [regex]::Matches($xml2,'<node[^>]*')){ $n=$m.Value
    if($n -match 'class="android\.widget\.Button"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -notmatch 'enabled="false"'){ $btns += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } }
  if($btns.Count -gt 0){ $ok=$btns | Sort-Object { ($_[0]+$_[2])/2 } | Select-Object -Last 1
    L "tapping rightmost button [$($ok[0]),$($ok[1])][$($ok[2]),$($ok[3])] (count=$($btns.Count))"; Tap $ok 3000 }
  else { L "no button -> ENTER"; A shell input keyevent KEYCODE_ENTER | Out-Null; Start-Sleep -Seconds 3 }
}
Start-Sleep -Seconds 10
$lc = (A logcat -d -s OcWebHost:V OcPerf:V) -join "`n"
$lc | Out-File -Encoding utf8 "$OUT\_f20_p1.logcat.txt"
L "--- proxy/auth log lines ---"
$lc -split "`n" | Where-Object { $_ -match 'credentials stored|no password in SecretStore|listening on|auth rejected|probe|GET http|POST http|401' } | Select-Object -First 40 | ForEach-Object { L ("  LOG " + $_) }
L "=== F20 P1 done ==="
