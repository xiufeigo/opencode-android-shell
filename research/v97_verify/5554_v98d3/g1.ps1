. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY='C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
function GtTunnel { 
    $c = (Q shell "run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_server_config.xml" 2>&1 | Out-String)
    $m = [regex]::Match($c,'<string name="AXhs57KqmMxUXQA[^"]*">([^<]*)</string>')
    if($m.Success){ L "  GT tunnelValLen=$($m.Groups[1].Value.Length)"; return $m.Groups[1].Value.Length }
    L "  GT tunnel key NOT FOUND"; return -1 }
function NT($tag){
    $ErrorActionPreference='Continue'
    & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_g1.xml" 2>&1 | Out-Null
    & $global:ADB -s $global:DEV pull /sdcard/_g1.xml (Join-Path $global:DIR "$tag.xml") 2>&1 | Out-Null
    & $global:ADB -s $global:DEV shell "rm -f /sdcard/_g1.xml" 2>&1 | Out-Null
    return @(python $NODESPY (Join-Path $global:DIR "$tag.xml")) }
function Shot($t){ Q shell "screencap -p /sdcard/_g1.png" | Out-Null; Q pull /sdcard/_g1.png (Join-Path $global:DIR "$t.png") | Out-Null; Q shell "rm -f /sdcard/_g1.png" | Out-Null }
function CenterOf($l){ if($l -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
function TapC($l,[string]$w){ $p=CenterOf $l; L "  TAP $w @ $($p[0]),$($p[1])"; Q shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 1 }

L '=== G1: dismiss trust card, then DELETE flow on the self-built row (cleanup + D3 evidence) ==='
$n = NT 'G00'
L "  IME=$(ImeShown)"
foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c|Server actions|Trust and connect|Connecting|Cancel|Add SSH server|SSH tunnels')){ L "    $($x.Line)" }
# dismiss the trust card if up
if(@($n | Select-String -Pattern 'Trust and connect').Count -gt 0){
    $c = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if($c){ TapC $c.Line 'Cancel(trust card)'; Start-Sleep -Seconds 1 }
    $n = NT 'G01_aftercancel'
    L "  trust card still up=$(@($n | Select-String -Pattern 'Trust and connect').Count -gt 0)"
}
Shot 'G01_panel'
# inventory rows
L '  --- row inventory ---'
L "  E2ECHK5554d3c rows=$(@($n | Select-String -Pattern 'E2ECHK5554d3c-\d').Count)"
foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c-\d|Server actions|10.0.0.2:4095|10.2.2:4095')){ L "    $($x.Line)" }
$before = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
L "  ROWS_BEFORE=$($before -join ',')"
$GTB = GtTunnel
# open the row's overflow menu
$sa = $n | Select-String -Pattern 'Server actions' | Select-Object -First 1
if(-not $sa){ L '  FAIL: no Server actions button'; }
else{
    TapC $sa.Line 'Server actions'
    $n = NT 'G02_menu'
    L '  --- menu ---'
    foreach($x in @($n | Select-String -Pattern 'Delete|Edit|Connect|Reconnect|Forget|Menu|Close')){ L "    $($x.Line)" }
    Shot 'G02_menu'
    $del = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
    if(-not $del){ L '  FAIL: Delete not offered' }
    else{
        TapC $del.Line 'Delete'
        $n = NT 'G03_confirm'
        L "  IME=$(ImeShown)"
        L '  --- confirm bar ---'
        foreach($x in @($n | Select-String -Pattern 'Delete|Cancel|confirm|Are you sure|SSH server|Remove|E2ECHK')){ L "    $($x.Line)" }
        Shot 'G03_confirm'
        # viewport = the dialog ScrollView bounds, for the y-range claim
        $vp = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
        if($vp){
            $r = CenterOf $vp.Line
            $x = $vp.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'
            L "  CONFIRM_BAR Delete y-range = $($Matches[2])..$($Matches[4])  x=$($Matches[1])..$($Matches[3])"
            L "  SCREEN_H=2424  => Delete label inside viewport: $($Matches[2] -ge 0 -and [int]$Matches[4] -le 2424)"
            $keep = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
            if($keep -and $keep.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM_BAR Cancel y-range = $($Matches[2])..$($Matches[4])" }
        }
        $n = NT 'G03b_confirm'
        foreach($x in @($n | Select-String -Pattern 'Delete|Cancel|Are you sure|Remove|E2ECHK')){ L "    C $($x.Line)" }
        $n = NT 'G04_after'
        L "  E2ECHK rows after=$(@($n | Select-String -Pattern 'E2ECHK5554d3c-\d').Count)"
        foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c-\d|Server actions|SSH tunnels')){ L "    $($x.Line)" }
        $GTA = GtTunnel
        L "  GT tunnelValLen $GTB -> $GTA (delta $($GTA-$GTB))"
        Shot 'G04_after'
    }
}
L 'DONE-G1'
