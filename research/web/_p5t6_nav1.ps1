$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$SHOTS = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\p5t6_shots'
New-Item -ItemType Directory -Force -Path $SHOTS | Out-Null
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'

function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Tap($x,$y){ Sh "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds 900 }
function Ev([string]$js){ $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1; return (($o | Out-String) | Select-String -NotMatch 'VoidTaskResult|socket=' | ForEach-Object { $_.Line }) -join '' }
function Shot($n){ & $adb -s $S shell "screencap -p /sdcard/$n.png" 2>&1 | Out-Null; & $adb -s $S pull "/sdcard/$n.png" "$SHOTS\$n.png" 2>&1 | Out-Null; L "shot $n" }
function DumpXml($n){ Sh "uiautomator dump /sdcard/$n.xml" | Out-Null; Start-Sleep -Milliseconds 500 }

L "=== NAV START ==="
# 1. drawer button (full width, NOT 67)
Tap 532 121
Shot '01-drawer'
L ("after drawer: " + (Ev "document.querySelector('h1')?.innerText + ' | ' + location.href"))
DumpXml 'd1'

# 2. find Settings entry
$xml = Sh "cat /sdcard/d1.xml"
L "--- drawer nodes ---"
foreach ($m in [regex]::Matches($xml,'<node[^>]*>')) {
  $n = $m.Value
  $t = [regex]::Match($n,'text="([^"]*)"').Groups[1].Value
  $cd = [regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
  $b = [regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (($t -or $cd) -and $b.Success) {
    L ("  text='$t' desc='$cd' bounds=" + $b.Value)
  }
}
