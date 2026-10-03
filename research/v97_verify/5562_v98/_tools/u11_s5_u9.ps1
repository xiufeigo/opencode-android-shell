$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u11_lib.ps1"
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$log=New-Object System.Collections.Generic.List[string]
function L([string]$s){ $log.Add($s); Write-Host $s }

$FAKE = 'No uncommitted changes yet'
$HONEST = 'The diff request timed out or failed'

function Sample([string]$tag){
  if(-not (Dump $tag)){ L "  [$tag] DUMP FAILED"; return }
  $x=Get-Content "$d\$tag.xml" -Raw
  $fake = if($x -match [regex]::Escape($FAKE)){'TRUE'}else{'false'}
  $hon  = if($x -match [regex]::Escape($HONEST)){'TRUE'}else{'false'}
  $sz   = if($x -match 'Override size: ([0-9]+x[0-9]+)'){$Matches[1]}else{'n/a'}
  L ("  [$tag] fakeEmpty='No uncommitted changes yet'=$fake  honestFail=$hon  overridesize=$sz")
}
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

L "=== U9 REGRESSION (narrow window honest-failure) ==="
L ("before wm size    = " + ((& $adb -s $S shell wm size) -join ' '))
L ("before wm density = " + ((& $adb -s $S shell wm density) -join ' '))

& $adb -s $S shell wm size 560x768 | Out-Null
& $adb -s $S shell wm density 160 | Out-Null
Start-Sleep -Seconds 4
L ("AFTER  wm size    = " + ((& $adb -s $S shell wm size) -join ' '))
L ("AFTER  wm density = " + ((& $adb -s $S shell wm density) -join ' '))

# Make sure the review panel is open, then land on the Changes tab.
Sample "u9_00_narrow_panel"
if(-not (Find "u9_00_narrow_panel" 'Changes')){
  L "  Changes tab not visible; toggling review panel"
  TapFind "u9_01" 'Toggle review panel' | Out-Null
  Start-Sleep -Seconds 3
  Sample "u9_02_narrow_afterpanel"
}
Sample "u9_03_before_changes"
TapFind "u9_04" 'Changes' | Out-Null
Start-Sleep -Seconds 2

L "--- sampling ---"
$sw=[Diagnostics.Stopwatch]::StartNew()
Sample "u9_05_t0"
foreach($t in @(1,3,10,20)){
  while($sw.Elapsed.TotalSeconds -lt $t){ Start-Sleep -Milliseconds 200 }
  Sample ("u9_06_t" + $t)
}
L "--- post-Refresh (still must not fake-empty) ---"
TapFind "u9_07" 'Refresh' | Out-Null
$sw2=[Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds 4
Sample "u9_08_postrefresh_t4"
while($sw2.Elapsed.TotalSeconds -lt 12){ Start-Sleep -Milliseconds 300 }
Sample "u9_09_postrefresh_t12"

L "--- reset ---"
& $adb -s $S shell wm size reset | Out-Null
& $adb -s $S shell wm density reset | Out-Null
Start-Sleep -Seconds 4
L ("RESET wm size    = " + ((& $adb -s $S shell wm size) -join ' '))
L ("RESET wm density = " + ((& $adb -s $S shell wm density) -join ' '))
Shot "u9_10_after_reset" | Out-Null
$log | Out-File -FilePath "$d\_u11_u9.txt" -Encoding utf8
L "DONE"
