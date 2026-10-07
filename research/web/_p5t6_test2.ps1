$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$SHOTS = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\p5t6_shots'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\t6.log" -Value $s -Encoding utf8; Write-Host $s }
function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}
function Shot($n){ & $adb -s $S shell "screencap -p /sdcard/$n.png" 2>&1|Out-Null; & $adb -s $S pull "/sdcard/$n.png" "$SHOTS\$n.png" 2>&1|Out-Null; L "shot $n" }

L "=== D: NOTIFICATION SHADE + REAL TAP ==="
L ("fg: " + (Sh "dumpsys activity activities | grep -m1 topResumedActivity"))
# open shade
Sh "cmd statusbar expand-notifications" | Out-Null
Start-Sleep -Seconds 3
Shot '35-shade'
L ("shade nodes: " + (Sh "uiautomator dump /sdcard/shade.xml"))
$xml = Sh "cat /sdcard/shade.xml"
foreach ($m in [regex]::Matches($xml,'<node[^>]*>')) {
  $n=$m.Value
  $t=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
  $cd=[regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
  $b=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"').Value
  if ($t -or $cd) { L ("  node text='$t' desc='$cd' $b") }
}
L "--- shade text ---"
L (($xml | Select-String -Pattern 'text="[^"]+"' -AllMatches).Matches.Value -join ' ')
