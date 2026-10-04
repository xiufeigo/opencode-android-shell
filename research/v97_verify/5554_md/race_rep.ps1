# v151 repetition batch for the notify race.
#   mode=pre   : cover BEFORE the turn ends   (expect the "B wins" branch, posted via=raise)
#   mode=post  : cover AFTER  the turn ends   (expect the "A wins" branch, posted via=background)
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [Parameter(Mandatory = $true)][ValidateSet("pre", "post")][string]$Mode,
  [int]$AfterMs = 6000
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5554"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\race"
$log = "$dir\$Label.log"
$pkg = "com.xiufeigo.opencodeshell"
function W([string]$m) { $m | Tee-Object -FilePath $log -Append }
function OcNotify { & $adb -s $dev shell "logcat -d | grep OcNotify" 2>&1 }
function Shot([string]$n) {
  & $adb -s $dev shell "screencap -p /sdcard/$n.png" 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$n.png" "$dir\$n.png" 2>&1 | Out-Null
}
function TurnRunning {
  & $adb -s $dev shell uiautomator dump /sdcard/rr.xml 2>&1 | Out-Null
  & $adb -s $dev pull /sdcard/rr.xml "$dir\rr.xml" 2>&1 | Out-Null
  $x = [xml](Get-Content "$dir\rr.xml" -Raw)
  foreach ($n in $x.SelectNodes("//node")) { if ($n.GetAttribute("content-desc") -eq "Stop") { return $true } }
  return $false
}
function Center($file, $class) {
  $x = [xml](Get-Content "$dir\$file" -Raw)
  foreach ($n in $x.SelectNodes("//node")) {
    if ($n.GetAttribute("class") -match $class) {
      $p = ($n.GetAttribute("bounds") -replace "[\[\]]", "") -split ","
      return "$(([int]$p[0] + [int]$p[2]) / 2),$(([int]$p[1] + [int]$p[3]) / 2)"
    }
  }
  return $null
}
function CenterDesc($file, $d0) {
  $x = [xml](Get-Content "$dir\$file" -Raw)
  foreach ($n in $x.SelectNodes("//node")) {
    if ($n.GetAttribute("content-desc") -eq $d0) {
      $p = ($n.GetAttribute("bounds") -replace "[\[\]]", "") -split ","
      return "$(([int]$p[0] + [int]$p[2]) / 2),$(([int]$p[1] + [int]$p[3]) / 2)"
    }
  }
  return $null
}
function Cover { & $adb -s $dev shell am start -n com.android.settings/.Settings 2>&1 | Out-Null }
function Uncover {
  & $adb -s $dev shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
  Start-Sleep -Seconds 5
}

W "=== trial $Label mode=$Mode afterMs=$AfterMs ==="
& $adb -s $dev shell "logcat -c" 2>&1 | Out-Null
# make sure the shell is the one on screen (previous trial leaves Settings on top)
Uncover
& $adb -s $dev shell "logcat -c" 2>&1 | Out-Null

if ($Mode -eq "pre") {
  # send, then cover mid-turn, before the turn ends
  & $adb -s $dev shell uiautomator dump /sdcard/$Label.xml 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$Label.xml" "$dir\$Label.xml" 2>&1 | Out-Null
  $ce = Center "$Label.xml" "EditText"; $sc = CenterDesc "$Label.xml" "Send"
  if (-not $ce -or -not $sc) { W "MISSING composer/send"; exit 1 }
  & $adb -s $dev shell input tap ($ce -split ",")[0] ($ce -split ",")[1] 2>&1 | Out-Null
  Start-Sleep -Milliseconds 800
  & $adb -s $dev shell input text "E2ECHK5554u1z%sv151$Label%sreply%sin%sthree%swords:%sPRE" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 700
  & $adb -s $dev shell input tap ($sc -split ",")[0] ($sc -split ",")[1] 2>&1 | Out-Null
  Start-Sleep -Seconds 2
  W "[$(Get-Date -Format HH:mm:ss.fff)] sent; running=$(TurnRunning)"
  Start-Sleep -Milliseconds 3000
  Cover
  W "[$(Get-Date -Format HH:mm:ss.fff)] COVERED mid-turn"
} else {
  # let the turn end while VISIBLE, wait out the 2s recheck, then cover
  & $adb -s $dev shell uiautomator dump /sdcard/$Label.xml 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$Label.xml" "$dir\$Label.xml" 2>&1 | Out-Null
  $ce = Center "$Label.xml" "EditText"; $sc = CenterDesc "$Label.xml" "Send"
  if (-not $ce -or -not $sc) { W "MISSING composer/send"; exit 1 }
  & $adb -s $dev shell input tap ($ce -split ",")[0] ($ce -split ",")[1] 2>&1 | Out-Null
  Start-Sleep -Milliseconds 800
  & $adb -s $dev shell input text "E2ECHK5554u1z%sv151$Label%sreply%sin%sthree%swords:%sPOST" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 700
  & $adb -s $dev shell input tap ($sc -split ",")[0] ($sc -split ",")[1] 2>&1 | Out-Null
  Start-Sleep -Seconds 2
  W "[$(Get-Date -Format HH:mm:ss.fff)] sent; running=$(TurnRunning)"
  $idle = $null
  for ($i = 0; $i -lt 45; $i++) {
    $m = OcNotify | Select-String "trigger idle"
    if ($m) { $idle = ($m | Select-Object -Last 1); break }
    Start-Sleep -Seconds 2
  }
  W "[$(Get-Date -Format HH:mm:ss.fff)] turn end while VISIBLE"
  Start-Sleep -Milliseconds $AfterMs
  Cover
  W "[$(Get-Date -Format HH:mm:ss.fff)] COVERED after the re-check had a chance to run"
}

Start-Sleep -Seconds 16
Shot "${Label}_final"
$posted = OcNotify | Select-String "posted:|withheld:|skip:|recheck: id="
W "---- decision transcript ----"
$posted | Tee-Object -FilePath $log -Append
W "=== trial $Label done ==="
