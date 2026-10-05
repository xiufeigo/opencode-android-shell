$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6270"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$APK="C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f10"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p1.log" -Value $s -Encoding utf8; Write-Output $s }

L "=== F10 P1: install + seed ==="
# 装前 md5
$localMd5 = (Get-FileHash $APK -Algorithm MD5).Hash.ToLower()
L "PREINSTALL_MD5=$localMd5  size=$((Get-Item $APK).Length)"
L "INSTALL: $((A @('install','-r','-d',$APK)))"
Start-Sleep -Seconds 2
# 装后设备端 md5 复核
$pm = Sh "pm path $PKG"
L "PM_PATH=$pm"
$apkPath = ([regex]::Match($pm,'(/data/app/\S+base\.apk)')).Groups[1].Value
$devMd5 = (Sh "md5sum $apkPath")
L "POSTINSTALL_MD5_RAW=$devMd5"
$devMd5c = ([regex]::Match($devMd5,'([0-9a-f]{32})')).Groups[1].Value
L "MD5_MATCH_LOCAL_EQ_DEVICE=$($devMd5c -eq $localMd5) (local=$localMd5 device=$devMd5c)"

function FindSaveBtn($xml) {
  $cands = @()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $y1 -le 1150 -and $x1 -ge 600) { $cands += [pscustomobject]@{ cx=[int](($x1+$x2)/2); cy=[int](($y1+$y2)/2); x1=$x1; y1=$y1; x2=$x2; y2=$y2 } }
  }
  if ($cands.Count -eq 0) { return $null }
  return ($cands | Sort-Object x1 -Descending | Select-Object -First 1)
}
function TypePw($pw) {
  A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
  $d = Sh "cat /sdcard/d.xml"
  $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($et.Count -lt 1) { $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
  if ($et.Count -lt 1) { return $false }
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2)
  $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  L "  pw field at ($cx,$cy)"
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1400
  # F5 教训：整串 input text 会丢字符 => 逐字符
  foreach ($ch in $pw.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 300 }
  return $true
}
A @('shell','pm','clear',$PKG) | Out-Null
A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 8
$typed = TypePw "opencode"
A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
$d2 = Sh "cat /sdcard/d2.xml"
$b = FindSaveBtn $d2
L "typed=$typed btnFound=$($null -ne $b)"
if ($b) { Sh "input tap $($b.cx) $($b.cy)" | Out-Null; L "TAPPED SAVE ($($b.cx),$($b.cy)) bounds [$($b.x1),$($b.y1)][$($b.x2),$($b.y2)]" } else { L "SAVE_BTN_NOT_FOUND" }
Start-Sleep -Seconds 14

# ---- 前置断言：已鉴权 + 数据已到 ----
$log = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_seeded.txt" -Value $log -Encoding utf8
L "PROBE_OK=$($log -match 'probe OK code=200')"
L "AUTH_FAILED_COUNT=$(([regex]::Matches($log,'AUTH FAILED')).Count)"
L "CRED_STORED=$($log -match 'credentials stored')  PASSWORD_PRESENT=$($log -match 'passwordPresent=true')"
$g = ([regex]::Matches($log,'GET http://127\.0\.0\.1:4095/api/[^ ]*') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "API_GETS_UNIQUE=$($g.Count)"
foreach ($x in $g) { L "  API $x" }
$ses = ([regex]::Matches($log,'ses_[0-9a-zA-Z]+') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "SESSION_IDS=$($ses.Count): $($ses -join ',')"
foreach ($k in 'J.g1.preflightOk','M.g1.firstContent','probe OK') {
  $m=[regex]::Match($log,"(?m)^.*$([regex]::Escape($k)).*$")
  L "LINE[$k]: $(if($m.Success){$m.Value.Trim()}else{'ABSENT'})"
}
# DEVTOOLS socket 复核（内容级 CDP 是否可用）
$dt = Sh "cat /proc/net/unix | grep -E 'devtools|webview'"
L "DEVTOOLS_RAW=[$dt]"

A @('shell','uiautomator','dump','/sdcard/u1.xml') | Out-Null
$ui = Sh "cat /sdcard/u1.xml"
Add-Content -Path "$OUT\ui_home.xml" -Value $ui -Encoding utf8
$texts = (([regex]::Matches($ui,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' } | Select-Object -Unique) -join ' | ')
L "UI_VISIBLE_TEXTS=$texts"
A @('shell','screencap','-p','/sdcard/home.png') | Out-Null
A @('pull','/sdcard/home.png',"$OUT\home.png") | Out-Null
L "HOME_SCREENSHOT bytes=$((Get-Item "$OUT\home.png" -ErrorAction SilentlyContinue).Length)"
L "=== F10 P1 DONE ==="
