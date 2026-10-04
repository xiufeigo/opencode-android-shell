# Decisive baseline: does a turn that completes while the app is COVERED post a notification?
# Without this, any "0 records" reading is uninterpretable.
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [int]$CoverDelayMs = 1200,
  [int]$PollSec = 110,
  [string]$Prompt = "reply with exactly one word: DONE"
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

# clean slate: kill the stuck turn (allowed for this app), relaunch
& $adb -s $dev shell am force-stop $pkg 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $dev shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
$appPid = (& $adb -s $dev shell "pidof $pkg").Trim()
& $adb -s $dev shell "logcat -c" 2>&1 | Out-Null
W "=== trial $Label  appPid=$appPid  coverDelayMs=$CoverDelayMs ==="
W "pre-existing notification records: $(NotifCount)"

DumpUi "$Label`_pre"
$ce = Center "$Label`_pre.xml" "EditText"
if (-not $ce) { W "NO COMPOSER"; exit 1 }
& $adb -s $dev shell input tap ($ce -split ",")[0] ($ce -split ",")[1] 2>&1 | Out-Null
Start-Sleep -Milliseconds 800
$full = "E2ECHK5554u1z $Label $Prompt"
& $adb -s $dev shell input text ($full -replace " ", "%s") 2>&1 | Out-Null
Start-Sleep -Milliseconds 600
$sd = CenterDesc "$Label`_pre.xml" "Send"
& $adb -s $dev shell input tap ($sd -split ",")[0] ($sd -split ",")[1] 2>&1 | Out-Null
W "[$(Get-Date -Format HH:mm:ss.fff)] sent: $full"
Shot "$Label`_01_sent"

Start-Sleep -Milliseconds $CoverDelayMs
& $adb -s $dev shell am start -n com.android.settings/.Settings 2>&1 | Out-Null
W "[$(Get-Date -Format HH:mm:ss.fff)] COVER: Settings over the app"
Shot "$Label`_02_covered"

# poll for the turn end
$deadline = (Get-Date).AddSeconds($PollSec)
$idle = $null
while ((Get-Date) -lt $deadline) {
  $m = OcNotify | Select-String "trigger idle"
  if ($m) { $idle = ($m | Select-Object -Last 1); break }
  Start-Sleep -Seconds 2
}
W "[$(Get-Date -Format HH:mm:ss.fff)] trigger idle: $(if($idle){$idle}else{'<NONE within ' + $PollSec + 's>'})"
Start-Sleep -Seconds 8
W "[$(Get-Date -Format HH:mm:ss.fff)] NOTIFICATION RECORDS = $(NotifCount)"
Shot "$Label`_03_final"
W "---- OcNotify transcript ----"
OcNotify | Tee-Object -FilePath $log -Append
W "=== trial $Label done ==="
