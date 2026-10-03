$ErrorActionPreference='Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg="com.xiufeigo.opencodeshell"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98"
Set-Location $dir
function FocusOurs(){
  $f=(& $adb -s emulator-5554 shell dumpsys window | Select-String "mCurrentFocus").ToString()
  if($f -match "opencodeshell"){return $true}
  & $adb -s emulator-5554 shell am start -n "$pkg/.OcActivity" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 1500
  $f2=(& $adb -s emulator-5554 shell dumpsys window | Select-String "mCurrentFocus").ToString()
  return ($f2 -match "opencodeshell")
}
function Shot($n,$t){ powershell -NoProfile -ExecutionPolicy Bypass -File .\_shot.ps1 -Name $n -Tries $t }

# STEP 1: send the long turn
if(-not (FocusOurs)){ "BLOCKED_FOREIGN"; exit 1 }
& $adb -s emulator-5554 shell input tap 990 1462
Start-Sleep -Seconds 3
if(-not (FocusOurs)){ "BLOCKED_FOREIGN_2"; exit 1 }
Shot "d_02_working" 5
"--- d_02 composer zone ---"
powershell -NoProfile -ExecutionPolicy Bypass -File .\_zone.ps1 -File d_02_working.xml -MinY 1380

# STEP 2: while running, type a 2nd message
& $adb -s emulator-5554 shell input tap 449 1314
Start-Sleep -Milliseconds 700
& $adb -s emulator-5554 shell input text "E2ECHK5554q-04_SECOND_MSG"
Start-Sleep -Seconds 2
Shot "d_03_chip" 5
"--- d_03 composer zone ---"
powershell -NoProfile -ExecutionPolicy Bypass -File .\_zone.ps1 -File d_03_chip.xml -MinY 1380

# STEP 3: tap the Queue chip (clickable parent [766,1399][927,1525] -> ctr 846,1462)
& $adb -s emulator-5554 shell input tap 846 1462
Start-Sleep -Seconds 3
Shot "d_04_queuebar" 5
"--- d_04 FULL TREE (queue bar hunt) ---"
powershell -NoProfile -ExecutionPolicy Bypass -File .\_zone.ps1 -File d_04_queuebar.xml -MinY 0 -MaxY 99999
