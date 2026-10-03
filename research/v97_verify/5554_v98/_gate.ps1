$ErrorActionPreference='Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg="com.xiufeigo.opencodeshell"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98"
Set-Location $dir

function GetFoc { (& $adb -s emulator-5554 shell dumpsys window | Select-String "mCurrentFocus").ToString() }

# Grab {i} : returns $true only if focus is ours right now
function Grab($i){
  $f = GetFoc
  if($f -match "opencodeshell"){ return $true }
  & $adb -s emulator-5554 shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 900
  $f2 = GetFoc
  return ($f2 -match "opencodeshell")
}
# Guarded tap: retried until focus is ours AND the tap actually lands
function GuardedTap($x,$y,$label,$max=14){
  for($i=1;$i -le $max;$i++){
    if(Grab $i){
      & $adb -s emulator-5554 shell input tap $x $y
      Write-Output "TAP_OK $label attempt=$i ($x,$y)"
      return $true
    }
    Start-Sleep -Milliseconds 500
  }
  Write-Output "TAP_FAIL $label"
  return $false
}
function Dump($n){
  for($i=1;$i -le 10;$i++){
    if(Grab $i){
      & $adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/w.xml 2>&1 | Out-Null
      $sz = & $adb -s emulator-5554 shell "stat -c %s /sdcard/w.xml" 2>$null
      if([int]$sz -gt 2000){
        & $adb -s emulator-5554 pull /sdcard/w.xml "$dir\$n.xml" 2>&1 | Out-Null
        & $adb -s emulator-5554 shell screencap -p /sdcard/w.png
        & $adb -s emulator-5554 pull /sdcard/w.png "$dir\$n.png" 2>&1 | Out-Null
        & $adb -s emulator-5554 shell rm -f /sdcard/w.xml /sdcard/w.png
        Write-Output "DUMP_OK $n try=$i sz=$sz"
        return $true
      }
      & $adb -s emulator-5554 shell rm -f /sdcard/w.xml
    }
    Start-Sleep -Milliseconds 700
  }
  Write-Output "DUMP_FAIL $n"
  return $false
}
function Zone($f,$minY){ powershell -NoProfile -ExecutionPolicy Bypass -File .\_zone.ps1 -File $f -MinY $minY }

Write-Output "=== A) state now ==="
Dump "e_00_state" | Out-Null
Zone "e_00_state.xml" 1380
$raw = Get-Content "e_00_state.xml" -Raw
$stop = ([regex]::Matches($raw,'Stop')).Count
$send = ([regex]::Matches($raw,'Send')).Count
$que  = ([regex]::Matches($raw,'Queue')).Count
Write-Output "A) Stop=$stop Send=$send Queue=$que"

# If turn NOT running: send a new long turn to create a running window
if($stop -eq 0){
  Write-Output "=== B) no running turn -> typing a long turn ==="
  GuardedTap 449 1314 "editor" | Out-Null
  Start-Sleep -Milliseconds 700
  & $adb -s emulator-5554 shell input text "E2ECHK5554q-05_LONGTURN_Write_a_very_long_detailed_explanation_of_railway_history_covering_every_era_with_full_detail"
  Start-Sleep -Seconds 1
  GuardedTap 990 1462 "send-longrun" | Out-Null
  Start-Sleep -Seconds 3
  Dump "e_01_running" | Out-Null
  Zone "e_01_running.xml" 1380
  $raw = Get-Content "e_01_running.xml" -Raw
  Write-Output ("B) Stop=" + ([regex]::Matches($raw,'Stop')).Count)
}

Write-Output "=== C) type 2nd message while running ==="
GuardedTap 449 1314 "editor2" | Out-Null
Start-Sleep -Milliseconds 700
& $adb -s emulator-5554 shell input text "E2ECHK5554q-06_QUEUEPROBE2"
Start-Sleep -Seconds 2
Dump "e_02_chip" | Out-Null
Zone "e_02_chip.xml" 1380
$raw = Get-Content "e_02_chip.xml" -Raw
Write-Output ("C) Queue=" + ([regex]::Matches($raw,'Queue')).Count + " Stop=" + ([regex]::Matches($raw,'Stop')).Count)

Write-Output "=== D) TAP the Queue chip ==="
GuardedTap 846 1462 "QUEUE-CHIP" | Out-Null
Start-Sleep -Seconds 3
Dump "e_03_queuebar" | Out-Null
Write-Output "--- e_03 composer+queue zone y<1620 ---"
Zone "e_03_queuebar.xml" 1100
$raw = Get-Content "e_03_queuebar.xml" -Raw
foreach($k in @('Queued','Steer','Remove','Queue','Stop','Send')){ Write-Output ("D) " + $k + " = " + ([regex]::Matches($raw,[regex]::Escape($k))).Count) }
