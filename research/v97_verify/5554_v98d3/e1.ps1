. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
# proven path: adb dump -> pull -> python node table (no PowerShell XML cast)
function NT($tag){
    $ErrorActionPreference='Continue'
    & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_e1.xml" 2>&1 | Out-Null
    & $global:ADB -s $global:DEV pull /sdcard/_e1.xml (Join-Path $global:DIR "$tag.xml") 2>&1 | Out-Null
    & $global:ADB -s $global:DEV shell "rm -f /sdcard/_e1.xml" 2>&1 | Out-Null
    $ErrorActionPreference='Continue'
    return @(python $NODESPY (Join-Path $global:DIR "$tag.xml"))
}
function CenterOf($line){ if($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }

L '=== E1: submit entry #1 via proven python parser ==='
L "  IME=$(ImeShown)"
$n = NT 'E01_ready'
L "  NODE_LINES=$($n.Count)"
foreach($x in @($n | Select-String -Pattern 'EditText')){ L "    $($x.Line)" }
L "  EDITTEXT_COUNT=$(@($n | Select-String -Pattern 'EditText\t').Count)"
L "  PW_NODES=$(@($n | Select-String -Pattern 'pw=true').Count)"
L "  NAME_PRESENT=$(@($n | Select-String -Pattern 'E2ECHK5554d3c-1').Count)"
$formOpen = @($n | Select-String -Pattern 'Host or SSH command').Count -gt 0
L "  FORM_OPEN=$formOpen"
Q shell "screencap -p /sdcard/_e1.png" | Out-Null
Q pull /sdcard/_e1.png (Join-Path $global:DIR 'E01_ready.png') | Out-Null
Q shell "rm -f /sdcard/_e1.png" | Out-Null

$GTB = GtCount
GtRaw | Out-File (Join-Path $global:DIR 'gt_before_submit.xml.txt') -Encoding utf8
L "  GT_BEFORE=$GTB"
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if(-not $sub){
    L '  Add server NOT in this dump; list buttons:'
    foreach($x in @($n | Select-String -Pattern 'Cancel|Add server|Add SSH server')){ L "    $($x.Line)" }
} else {
    L "  ADD_SERVER_NODE=$($sub.Line)"
    $p = CenterOf $sub.Line
    L "  TAP Add server @ $($p[0]),$($p[1])"
    Q shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Seconds 2
    L "  IME after=$(ImeShown)"
    $n2 = NT 'E02_after'
    L "  FORM_STILL_OPEN=$(@($n2 | Select-String -Pattern 'Host or SSH command').Count -gt 0)"
    L "  ERROR_LINES=$(@($n2 | Select-String -Pattern 'required|Please|Enter connection|invalid').Count)"
    foreach($x in @($n2 | Select-String -Pattern 'required|Please|Enter connection|invalid')){ L "    ERR $($x.Line)" }
    Q shell "screencap -p /sdcard/_e1.png" | Out-Null
    Q pull /sdcard/_e1.png (Join-Path $global:DIR 'E02_after.png') | Out-Null
    Q shell "rm -f /sdcard/_e1.png" | Out-Null
    $GTA = GtCount
    GtRaw | Out-File (Join-Path $global:DIR 'gt_after_submit.xml.txt') -Encoding utf8
    L "  GT_AFTER=$GTA  GT_DELTA=$($GTA-$GTB)"
    L "  SERVER_ACTIONS_VISIBLE=$(@($n2 | Select-String -Pattern 'Server actions').Count)"
    foreach($x in @($n2 | Select-String -Pattern 'Server actions')){ L "    SA $($x.Line)" }
    L "  NEW_ROW_MATCHES=$(@($n2 | Select-String -Pattern 'E2ECHK5554d3c|sshtest|127.0.0.1').Count)"
    foreach($x in @($n2 | Select-String -Pattern 'E2ECHK5554d3c|sshtest|127.0.0.1')){ L "    ROW $($x.Line)" }
}
L 'DONE-E1'
