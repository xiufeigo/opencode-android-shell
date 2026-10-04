# v151 notify-race harness.  One trial:
#   send a long turn, press HOME at -D ms before the expected turn end,
#   then measure whether a system notification was ever posted.
# usage: pwsh race_trial.ps1 -Label T1 -DelayMs 3000 -WaitIdleMs 90000
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [int]$DelayMs = 3000,          # ms after send, before pressing HOME
  [int]$WaitIdleMs = 120000,     # max ms to wait for the turn to finish
  [string]$Prompt = "count from 1 to 20000, one number per line"
)
$ErrorActionPreference = 'Stop'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev  = "emulator-5554"
$dir  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\race"
$pkg  = "com.xiufeigo.opencodeshell"

function Dump {
  param([string]$name)
  & $adb -s $dev shell uiautomator dump "/sdcard/$name.xml" 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$name.xml" "$dir\$name.xml" 2>&1 | Out-Null
}
# find a node by content-desc, return its center as "x,y"
function FindDesc {
  param([string]$Desc, [string]$file)
  $x = [xml](Get-Content "$dir\$file" -Raw)
  foreach ($n in $x.SelectNodes("//node")) {
    if ($n.GetAttribute("content-desc") -eq $Desc) {
      $b = $n.GetAttribute("bounds") -replace "[\\[\\]]", ""
      $p = $b -split ","
      $cx = [int](($p[0] -as [int]) + ($p[2] -as [int])) / 2
      $cy = [int](($p[1] -as [int]) + ($p[3] -as [int])) / 2
      return "$cx,$cy"
    }
  }
  return $null
}
function HasDesc {
  param([string]$Desc, [string]$file)
  $x = [xml](Get-Content "$dir\$file" -Raw)
  foreach ($n in $x.SelectNodes("//node")) {
    if ($n.GetAttribute("content-desc") -eq $Desc) { return $true }
  }
  return $false
}
function NotifCount {
  $raw = & $adb -s $dev shell "dumpsys notification --noredact 2>/dev/null" 2>&1
  $hits = @($raw | Select-String -SimpleMatch "pkg=com.xiufeigo.opencodeshell")
  return $hits.Count
}
function Shot {
  param([string]$name)
  & $adb -s $dev shell "screencap -p /sdcard/$name.png" 2>&1 | Out-Null
  & $adb -s $dev pull "/sdcard/$name.png" "$dir\$name.png" 2>&1 | Out-Null
}

$appPid = (& $adb -s $dev shell "pidof $pkg").Trim()
$log    = "$dir\$Label.log"
"[{0}] === trial {1} appPid={2} delayMs={3} ===" -f (Get-Date -Format "HH:mm:ss.fff"), $Label, $appPid, $DelayMs |
  Tee-Object -FilePath $log

# 1. baseline: anything already posted?
$pre = NotifCount
"[{0}] pre-existing notification records: {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $pre | Tee-Object -FilePath $log -Append

# 2. send the prompt
Dump "$Label`_pre"
$composer = FindDesc "" "$Label`_pre.xml"
# composer EditText: locate by class instead
$x = [xml](Get-Content "$dir\$Label`_pre.xml" -Raw)
$et = $null
foreach ($n in $x.SelectNodes("//node")) { if ($n.GetAttribute("class") -match "EditText") { $et = $n; break } }
if (-not $et) { "NO COMPOSER" | Tee-Object -FilePath $log -Append; exit 1 }
$b = $et.GetAttribute("bounds") -replace "[\\[\\]]", ""
$p = $b -split ","
$cxx = [int](($p[0] -as [int]) + ($p[2] -as [int])) / 2
$cyy = [int](($p[1] -as [int]) + ($p[3] -as [int])) / 2
& $adb -s $dev shell input tap $cxx $cyy 2>&1 | Out-Null
Start-Sleep -Milliseconds 700
$full = "E2ECHK5554u1z $Label $Prompt"
& $adb -s $dev shell input text ($full -replace " ", "%s") 2>&1 | Out-Null
Start-Sleep -Milliseconds 600
$send = FindDesc "Send" "$Label`_pre.xml"
if ($send) { & $adb -s $dev shell input tap ($send -split ",")[0] ($send -split ",")[1] 2>&1 | Out-Null }
"[{0}] sent: {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $full | Tee-Object -FilePath $log -Append

# 3. press HOME at DelayMs after the send
Start-Sleep -Milliseconds $DelayMs
& $adb -s $dev shell input keyevent 3 2>&1 | Out-Null
"[{0}] HOME pressed" -f (Get-Date -Format "HH:mm:ss.fff") | Tee-Object -FilePath $log -Append

# 4. wait for the turn to end: poll the logcat for the idle trigger
$deadline = (Get-Date).AddMilliseconds($WaitIdleMs)
$idleSeen = $false
while ((Get-Date) -lt $deadline) {
  $l = & $adb -s $dev shell "logcat -d -t 40 --pid=$appPid -s OcNotify" 2>&1
  if ($l -match "trigger idle") { $idleSeen = $true; break }
  Start-Sleep -Milliseconds 1500
}
"[{0}] turn-end seen in log: {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $idleSeen | Tee-Object -FilePath $log -Append
Shot "$Label`_after_home"

# 5. let the guards run (recheck 2s, withheld TTL), then count
Start-Sleep -Seconds 12
$n = NotifCount
"[{0}] notification records for pkg: {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $n | Tee-Object -FilePath $log -Append
Shot "$Label`_final"

& $adb -s $dev shell "logcat -d -t 120 --pid=$appPid -s OcNotify" 2>&1 |
  Tee-Object -FilePath $log -Append
"[{0}] trial {1} done" -f (Get-Date -Format "HH:mm:ss.fff"), $Label | Tee-Object -FilePath $log -Append
