# v151 notify-race harness v2 — the race is exercised through the *focus-loss* branch,
# because on emulator-5554 (Medium_Desktop, freeform multi-resume) HOME/recents never fire
# onActivityPaused; another window covering the app does (OcNotify "focus: timeout expired",
# notify/SystemNotifier.kt:271 -> postWithheld, :437).
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [int]$CoverDelayMs = 2000,   # ms after send, before covering the app with Settings
  [int]$SettleSec = 25,
  [string]$Prompt = "count from 1 to 3000, one number per line"
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5554"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\race"
$log = "$dir\$Label.log"
$pkg = "com.xiufeigo.opencodeshell"
function W([string]$m) { $m | Tee-Object -FilePath $log -Append }
function OcNotify { & $adb -s $dev shell "logcat -d | grep OcNotify" 2>&1 }
function NotifCount {
  $raw = & $adb -s $dev shell "dumpsys notification --noredact 2>/dev/null" 2>&1
  return @($raw | Select-String -SimpleMatch "pkg=$pkg").Count
}
function Shot([string]$n) {
  & $adb -s $dev shell "screencap -p /sdcard/$n.png" 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$n.png" "$dir\$n.png" 2>&1 | Out-Null
}
function DumpUi([string]$n) {
  & $adb -s $dev shell uiautomator dump "/sdcard/$n.xml" 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$n.xml" "$dir\$n.xml" 2>&1 | Out-Null
}
function Center($file, $class) {
  $x = [xml](Get-Content "$dir\$file" -Raw)
  foreach ($n in $x.SelectNodes("//node")) {
    if ($n.GetAttribute("class") -match $class) {
      $p = ($n.GetAttribute("bounds") -replace "[\[\]]", "") -split ","
      return "$(([int]($p[0]) + [int]($p[2])) / 2),$(([int]($p[1]) + [int]($p[3])) / 2)"
    }
  }
  return $null
}
function CenterDesc($file, $desc) {
  $x = [xml](Get-Content "$dir\$file" -Raw)
  foreach ($n in $x.SelectNodes("//node")) {
    if ($n.GetAttribute("content-desc") -eq $desc) {
      $p = ($n.GetAttribute("bounds") -replace "[\[\]]", "") -split ","
      return "$(([int]($p[0]) + [int]($p[2])) / 2),$(([int]($p[1]) + [int]($p[3])) / 2)"
    }
  }
  return $null
}

# 0. bring the app to the front and make sure nothing is running
& $adb -s $dev shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 3
& $adb -s $dev shell input keyevent 3 2>&1 | Out-Null
& $adb -s $dev shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 3
DumpUi "$Label`_pre"
$st = CenterDesc "$Label`_pre.xml" "Stop"
if ($st) { W "[warn] a turn was still running; stopping it"; & $adb -s $dev shell input tap ($st -split ",")[0] ($st -split ",")[1] 2>&1 | Out-Null; Start-Sleep -Seconds 3; DumpUi "$Label`_pre2" }

$appPid = (& $adb -s $dev shell "pidof $pkg").Trim()
& $adb -s $dev shell "logcat -c" 2>&1 | Out-Null
W "=== trial $Label  appPid=$appPid  coverDelayMs=$CoverDelayMs ==="
W "pre-existing notification records: $(NotifCount)"

# 1. send the turn
$ce = Center "$Label`_pre.xml" "EditText"
if (-not $ce) { $ce = Center "$Label`_pre2.xml" "EditText" }
if (-not $ce) { W "NO COMPOSER"; exit 1 }
& $adb -s $dev shell input tap ($ce -split ",")[0] ($ce -split ",")[1] 2>&1 | Out-Null
Start-Sleep -Milliseconds 700
$full = "E2ECHK5554u1z $Label $Prompt"
& $adb -s $dev shell input text ($full -replace " ", "%s") 2>&1 | Out-Null
Start-Sleep -Milliseconds 600
$sd = CenterDesc "$Label`_pre.xml" "Send"
if (-not $sd) { $sd = CenterDesc "$Label`_pre2.xml" "Send" }
& $adb -s $dev shell input tap ($sd -split ",")[0] ($sd -split ",")[1] 2>&1 | Out-Null
W "[$(Get-Date -Format HH:mm:ss.fff)] sent: $full"
Shot "$Label`_01_sent"

# 2. the "user looks at something else" event, at CoverDelayMs
Start-Sleep -Milliseconds $CoverDelayMs
& $adb -s $dev shell am start -n com.android.settings/.Settings 2>&1 | Out-Null
W "[$(Get-Date -Format HH:mm:ss.fff)] COVER: Settings brought over the app"
Shot "$Label`_02_covered"

# 3. let the turn end + the guards run
Start-Sleep -Seconds $SettleSec
$idle = (OcNotify | Select-String "trigger idle" | Select-Object -Last 1)
W "[$(Get-Date -Format HH:mm:ss.fff)] trigger idle line: $(if($idle){$idle}else{'<none>'})"
W "[$(Get-Date -Format HH:mm:ss.fff)] NOTIFICATION RECORDS = $(NotifCount)"
Shot "$Label`_03_final"
W "---- OcNotify transcript ----"
OcNotify | Tee-Object -FilePath $log -Append
W "=== trial $Label done ==="
