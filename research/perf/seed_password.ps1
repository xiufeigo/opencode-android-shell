# seed_password.ps1 — 在**新建 AVD** 上把后端口令写进壳的 SecretStore。
# 背景：交付的 web 壳在 onCreate 里硬门控 `SecretStore.KEY_SERVER_PASSWORD`，
# 空则弹原生 setup 对话框且**永不 loadUrl**（WebHostActivity.kt:220-224）。
# 上一轮设备是复用的、SecretStore 里已有口令，所以没暴露这个前置条件；
# 本轮新建 AVD 必然为空 ⇒ 必须先播种，否则四量全在测"口令对话框"。
param(
  [ValidateSet('web','native')][string]$Side = 'web',
  [string]$Dev = 'emulator-5830',
  [string]$Password = 'opencode',
  [string]$Backend = 'http://127.0.0.1:4095'
)
$ErrorActionPreference='Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

if ($Side -eq 'web') { $Pkg='com.xiufeigo.opencodeshell.web.debug'; $Act='com.xiufeigo.opencodeshell.web.WebHostActivity' }
else { $Pkg='com.xiufeigo.opencodeshell'; $Act='com.xiufeigo.opencodeshell.OcActivity' }

function DumpUi {
  A shell uiautomator dump /sdcard/_ui.xml | Out-Null
  A shell cat /sdcard/_ui.xml
}
function FindBounds {
  param([string]$Xml, [string]$Pattern)
  foreach ($m in [regex]::Matches($Xml, '<node[^>]*')) {
    $n = $m.Value
    if ($n -match $Pattern -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
    }
  }
  return $null
}
function TapB { param($b,[int]$ms=600) A shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null; Start-Sleep -Milliseconds $ms }

Say "=== seed $Side ==="
A shell am force-stop $Pkg; Start-Sleep -Seconds 1
A logcat -c | Out-Null
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 5
$xml = DumpUi
Write-Output "--- UI nodes (text/hint/class) ---"
foreach ($m in [regex]::Matches($xml, '<node[^>]*')) {
  $n=$m.Value
  $t=''; $c=''; $h=''
  if ($n -match 'text="([^"]*)"'){ $t=$Matches[1] }
  if ($n -match 'class="([^"]*)"'){ $c=$Matches[1] }
  if ($n -match 'hint="([^"]*)"'){ $h=$Matches[1] }
  if ($t -or $h) { Write-Output ("  text='{0}' hint='{1}' class={2}" -f $t,$h,$c) }
}
# 口令框：优先 password="true"，否则第一个 EditText
$pb = FindBounds $xml 'password="true"'
if (-not $pb) { $pb = FindBounds $xml 'class="android\.widget\.EditText"' }
if (-not $pb) { Say "!! no EditText found -> cannot seed"; exit 2 }
Say "  password field bounds = [$($pb[0]),$($pb[1])][$($pb[2]),$($pb[3])]"
TapB $pb 700
A shell input text $Password | Out-Null
Start-Sleep -Milliseconds 500
# 正按钮：**不要按 text 匹配**（PowerShell 控制台按 GBK 解 uiautomator 的 UTF-8，
# "保存并连接" 会变成乱码，正则必失配 —— 本轮已踩）。
# 改成：取所有 android.widget.Button 里**最靠右**的那个（Android 对话框正按钮在右）。
$btns = @()
foreach ($m in [regex]::Matches($xml, '<node[^>]*')) {
  $n=$m.Value
  if ($n -match 'class="android\.widget\.Button"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -notmatch 'enabled="false"') {
    $btns += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
  }
}
$ok = $null
if ($btns.Count -gt 0) {
  $ok = $btns | Sort-Object { ($_[0]+$_[2])/2 } | Select-Object -Last 1
}
if ($ok) { Say "  buttons found=$($btns.Count); tapping rightmost [$($ok[0]),$($ok[1])][$($ok[2]),$($ok[3])]"; TapB $ok 2000 }
else { Say "  no button found -> sending ENTER"; A shell input keyevent KEYCODE_ENTER | Out-Null; Start-Sleep -Seconds 2 }
Start-Sleep -Seconds 6
$lc = (A logcat -d -s OcWebHost:V OcPerf:V OcPerfJS:V) -join "`n"
$lc | Out-File -Encoding utf8 "$PSScriptRoot\out_p4\seed_$Side.logcat.txt"
Say "--- post-seed logcat ---"
$lc | Write-Output
if ($lc -match 'credentials stored') { Say "RESULT: SEEDED OK" }
elseif ($lc -match 'no password in SecretStore') { Say "RESULT: STILL EMPTY" }
else { Say "RESULT: UNKNOWN (see log)" }
