. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
function CenterOf($line){ if($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
function Rows($f){
    $t = New-Object System.Collections.ArrayList
    $x = [xml](Get-Content $f -Raw)
    function W($n){
        $tx = $n.text
        if(-not $tx){ $tx = $n.'content-desc' }
        $nm = "$($n.class)"
        if($tx -or $nm -eq 'android.widget.EditText'){ [void]$t.Add("$tx`t$($n.bounds)`tpw=$($n.password)`tfoc=$($n.focused)`t$nm`tclk=$($n.clickable)`ten=$($n.enabled)") }
        foreach($c in $n.node){ W $c }
    }
    W $x.hierarchy
    return $t
}
function Dump($tag){ $null = Uidump $tag; return (Join-Path $global:DIR "$tag.xml") }
function ShowRows($r,$pat){
    $m = @($r | Select-String -Pattern $pat)
    foreach($x in $m){ L "    $($x.Line)" }
    return $m
}

L '=== D2: complete entry #1 submit ==='
L "  IME before collapse = $(ImeShown)"
CollapseIme
L "  IME after collapse = $(ImeShown)"
$f = Dump 'D21_ready'
$r = Rows $f
L '  --- form fields (3 expected) ---'
$e = @($r | Select-String -Pattern 'EditText\t')
foreach($x in $e){ L "    $($x.Line)" }
L "  EDITTEXT_COUNT=$($e.Count)"
$pwNodes = @($r | Select-String -Pattern 'pw=true')
L "  PW_MASKED_COUNT=$($pwNodes.Count)"
foreach($x in $pwNodes){ L "    PW $($x.Line)" }
$named = @($r | Select-String -Pattern 'E2ECHK5554d3c-1')
L "  NAME_PRESENT=$($named.Count -gt 0)"
ShotN 'D21_ready'

$sub = $r | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if(-not $sub){ L '  FAIL: Add server not visible'; }
else {
    L "  ADD_SERVER_NODE=$($sub.Line)"
    $p = CenterOf $sub.Line
    $GTB = GtCount
    GtRaw | Out-File (Join-Path $global:DIR 'gt_before_submit.xml.txt') -Encoding utf8
    L "  GT_BEFORE=$GTB  tap @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1] 1800
    Start-Sleep -Seconds 2
    $f2 = Dump 'D22_after'
    $r2 = Rows $f2
    $still = @($r2 | Select-String -Pattern 'Host or SSH command')
    L "  FORM_STILL_OPEN=$($still.Count -gt 0)"
    $errs = @($r2 | Select-String -Pattern 'required|Please|Enter connection|invalid|Enter the')
    L "  ERROR_LINES=$($errs.Count)"
    foreach($x in $errs){ L "    ERR $($x.Line)" }
    ShotN 'D22_after'
    $GTA = GtCount
    GtRaw | Out-File (Join-Path $global:DIR 'gt_after_submit.xml.txt') -Encoding utf8
    L "  GT_AFTER=$GTA  GT_DELTA=$($GTA-$GTB)"
    L '  --- panel rows now ---'
    $sa = @($r2 | Select-String -Pattern 'Server actions')
    L "  SERVER_ACTIONS_VISIBLE=$($sa.Count)"
    foreach($x in $sa){ L "    SA $($x.Line)" }
    $hit = @($r2 | Select-String -Pattern 'E2ECHK5554d3c-1|sshtest|127.0.0.1')
    L "  NEW_ROW_MATCHES=$($hit.Count)"
    foreach($x in $hit){ L "    ROW $($x.Line)" }
}
L 'DONE-D2'
