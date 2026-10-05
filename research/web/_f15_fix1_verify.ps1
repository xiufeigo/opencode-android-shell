# F15 item1 (part 2): verify the FIXED L()/KeepState() as they exist in the real
# research/web/_f13_uitap.ps1 on disk, against a real device, then print the
# VERDICT line the fixed script would now produce.
$ErrorActionPreference='Stop'
$src  = "C:\sensorsdata\main\program\opencode-android-shell\research\web\_f13_uitap.ps1"
$LOG  = "C:\sensorsdata\main\program\opencode-android-shell\research\web\_f15_fix1_live.txt"
$S    = "emulator-6370"
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (Test-Path $LOG) { Remove-Item $LOG -Force }

function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }

$raw = Get-Content $src -Raw
# pull the L() block (comment + function) and the KeepState() block verbatim
$lBlock  = [regex]::Match($raw, '(?s)# F15 FIX:.*?Write-Host \$s \}\r?\n').Value
$ksBlock = [regex]::Match($raw, '(?s)function KeepState\(\$label\) \{.*?\r?\n\}\r?\n').Value

"EXTRACTED_FROM_DISK ($src)"
"  L() block chars      : $($lBlock.Length)"
"  L() uses Write-Host  : $($lBlock -match 'Write-Host')"
"  L() has Write-Output : $($lBlock -match 'Write-Output')   <- must be False"
"  KeepState chars      : $($ksBlock.Length)"
""
Invoke-Expression $lBlock
Invoke-Expression $ksBlock

$seg = KeepState "F15_LIVE"
"RETURN_CONTRACT (on real device $S):"
"  type      : $($seg.GetType().FullName)"
"  count     : $(@($seg).Count)      <- F13 buggy version returned 4"
"  value     : $seg"
"  -eq false : $($seg -eq 'false')"
"  VERDICT_line = VERDICT_F15 raised=$seg release=$($seg -eq 'false') plus90=false"
""
"LOG_FILE=$LOG  LINES=$((Get-Content $LOG).Count)"
Get-Content $LOG
