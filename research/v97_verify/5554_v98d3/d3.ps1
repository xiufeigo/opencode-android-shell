. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
function CenterOf($line){ if($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }

L '=== D3: submit entry #1 (form already filled) ==='
L "  IME=$(ImeShown)"
$n = NodesNow 'D31_ready'
L '  --- form EditTexts ---'
foreach($x in @($n | Select-String -Pattern 'EditText\t')){ L "    $($x.Line)" }
L "  EDITTEXT_COUNT=$(@($n | Select-String -Pattern 'EditText\t').Count)"
L "  PW_NODES=$(@($n | Select-String -Pattern 'pw=true').Count)"
L "  NAME_PRESENT=$(@($n | Select-String -Pattern 'E2ECHK5554d3c-1').Count)"
foreach($x in @($n | Select-String -Pattern 'Host or SSH command|Server name|^Authentication|^Password\t|^Cancel|^Add server')){ L "    LBL $($x.Line)" }
ShotN 'D31_ready'

$GTB = GtCount
GtRaw | Out-File (Join-Path $global:DIR 'gt_before_submit.xml.txt') -Encoding utf8
L "  GT_BEFORE=$GTB"
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if(-not $sub){ L '  FAIL: Add server not visible' }
else {
    L "  ADD_SERVER_NODE=$($sub.Line)"
    $p = CenterOf $sub.Line
    L "  TAP @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1] 1800
    Start-Sleep -Seconds 2
    L "  IME after submit=$(ImeShown)"
    $n2 = NodesNow 'D32_after'
    $still = @($n2 | Select-String -Pattern 'Host or SSH command')
    L "  FORM_STILL_OPEN=$($still.Count -gt 0)"
    $errs = @($n2 | Select-String -Pattern 'required|Please|Enter connection|invalid')
    L "  ERROR_LINES=$($errs.Count)"
    foreach($x in $errs){ L "    ERR $($x.Line)" }
    ShotN 'D32_after'
    $GTA = GtCount
    GtRaw | Out-File (Join-Path $global:DIR 'gt_after_submit.xml.txt') -Encoding utf8
    L "  GT_AFTER=$GTA  GT_DELTA=$($GTA-$GTB)"
    L '  --- panel rows ---'
    $sa = @($n2 | Select-String -Pattern 'Server actions')
    L "  SERVER_ACTIONS_VISIBLE=$($sa.Count)"
    foreach($x in $sa){ L "    SA $($x.Line)" }
    $hit = @($n2 | Select-String -Pattern 'E2ECHK5554d3c|sshtest|127.0.0.1')
    L "  NEW_ROW_MATCHES=$($hit.Count)"
    foreach($x in $hit){ L "    ROW $($x.Line)" }
}
L 'DONE-D3'
