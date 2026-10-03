$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u11_lib.ps1"
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$log=New-Object System.Collections.Generic.List[string]
function L([string]$s){ $log.Add($s); Write-Host $s }

function TapFind([string]$tag,[string]$pat){
  if(-not (Dump "${tag}_tf")){ L "  TAP '$pat' -> DUMP FAILED"; return $false }
  $h = Find "${tag}_tf" $pat | Select-Object -First 1
  if($h -match 'c=(\d+),(\d+)'){
    & $adb -s $S shell input tap $Matches[1] $Matches[2]
    L ("  TAP '" + $pat + "' -> " + $Matches[1] + "," + $Matches[2])
    return $true
  }
  L "  TAP '$pat' -> NOTFOUND"
  return $false
}

L "=== cleanup: delete self-made session E2ECHK5562v-U11 ==="
L ("ime=" + (ImeShown))
L ("focus=" + ((& $adb -s $S shell "dumpsys window | grep mCurrentFocus")))
if(-not (Dump "cl_00")){ L "dump failed"; exit }
L ("  session_visible=" + $(if((Find "cl_00" 'E2ECHK5562v-U11')){'yes'}else{'no'}))

if(-not (Find "cl_00" 'E2ECHK5562v-U11')){
  L "  not on this session; opening it from Home"
  TapFind "cl_01" 'Home' | Out-Null
  Start-Sleep -Seconds 3
  if(-not (Dump "cl_02")){ L "dump failed"; exit }
  $h = Find "cl_02" 'E2ECHK5562v-U11' | Select-Object -First 1
  if($h -match 'c=(\d+),(\d+)'){ & $adb -s $S shell input tap $Matches[1] $Matches[2]; L ("  TAP session -> " + $Matches[1] + "," + $Matches[2]) } else { L "  session row NOTFOUND on Home" }
  Start-Sleep -Seconds 3
}

L "--- open More options ---"
TapFind "cl_03" 'More options' | Out-Null
Start-Sleep -Seconds 2
L "--- Delete ---"
if(TapFind "cl_04" 'Delete'){
  Start-Sleep -Seconds 2
  if(-not (Dump "cl_05")){ L "confirm dump failed" }
  L ("  dialog_nodes=" + (((Find "cl_05" '.') -join ' ;; ')))
  # Confirm dialog: tap the affirmative button (runtime-parsed, no ESC/BACK)
  $ok = (Find "cl_05" 'Delete') | Select-Object -First 1
  if($ok -match 'c=(\d+),(\d+)'){ & $adb -s $S shell input tap $Matches[1] $Matches[2]; L ("  TAP confirm Delete -> " + $Matches[1] + "," + $Matches[2]) }
  else {
    $ok2 = (Find "cl_05" 'Cancel') | Select-Object -First 1
    L ("  no second Delete button; nodes=" + (((Find "cl_05" '.') -join ' ;; ')))
  }
  Start-Sleep -Seconds 3
}
if(Dump "cl_06"){ L ("  session_still_open=" + $(if((Find "cl_06" 'E2ECHK5562v-U11')){'yes'}else{'no'})) }
L ("ime_after=" + (ImeShown))
$log | Out-File -FilePath "$d\_u11_cleanup.txt" -Encoding utf8
L "DONE"
