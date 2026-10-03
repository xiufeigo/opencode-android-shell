. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY='C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
function GtTunnel { $c=(Q shell "run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_server_config.xml" 2>&1|Out-String); $m=[regex]::Match($c,'<string name="AXhs57KqmMxUXQA[^"]*">([^<]*)</string>'); if($m.Success){ L "  GT tunnelValLen=$($m.Groups[1].Value.Length)"; return $m.Groups[1].Value.Length }; return -1 }
function NT($tag){ $ErrorActionPreference='Continue'
  & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_k1.xml" 2>&1|Out-Null
  & $global:ADB -s $global:DEV pull /sdcard/_k1.xml (Join-Path $global:DIR "$tag.xml") 2>&1|Out-Null
  & $global:ADB -s $global:DEV shell "rm -f /sdcard/_k1.xml" 2>&1|Out-Null
  return @(python $NODESPY (Join-Path $global:DIR "$tag.xml")) }
function Shot($t){ Q shell "screencap -p /sdcard/_k1.png"|Out-Null; Q pull /sdcard/_k1.png (Join-Path $global:DIR "$t.png")|Out-Null; Q shell "rm -f /sdcard/_k1.png"|Out-Null }
function CenterOf($l){ if($l -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
function TapC($l,[string]$w){ $p=CenterOf $l; L "  TAP $w @ $($p[0]),$($p[1])"; Q shell "input tap $($p[0]) $($p[1])"|Out-Null; Start-Sleep -Seconds 1 }

L '=== K1: force-stop to clear stuck trust card, then DELETE the self-built row ==='
L "  (am force-stop of THIS app only; permitted by discipline)"
Q shell "am force-stop com.xiufeigo.opencodeshell" | Out-Null
Start-Sleep -Seconds 2
Q shell "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 5
L "  focus=$((Q shell 'dumpsys window | grep mCurrentFocus' 2>&1|Out-String).Trim())"
$n = NT 'K00'
L '  --- after relaunch (need to reopen Settings -> Servers) ---'
$s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
if($s){ TapC $s.Line 'Settings' }
Start-Sleep -Seconds 1
# scroll tab strip to the far end to reveal Servers
$ty=744
$g = $n | Select-String -Pattern '^(General|Appearance|Extensions)\t' | Select-Object -First 1
if($g -and $g.Line -match '\[(\d+),(\d+)\]'){ $ty=[int]$Matches[2] }
for($i=0;$i -lt 6;$i++){ Q shell "input swipe 200 $ty 960 $ty 250"|Out-Null; Start-Sleep -Milliseconds 350 }
$n = NT 'K01_tabs'
$srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
if($srv){ TapC $srv.Line 'Servers tab' } else { L '  Servers tab not found' }
Start-Sleep -Seconds 1
$n = NT 'K02_panel'
L '  --- panel after relaunch ---'
foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c|Server actions|SSH tunnels|Trust and connect|Connecting|Connected|Disconnected|Add SSH server')){ L "    $($x.Line)" }
Shot 'K02_panel'
$GTB = GtTunnel
$rowsBefore = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
L "  ROWS_BEFORE=$($rowsBefore -join ',')  cardUp=$(@($n | Select-String -Pattern 'Trust and connect').Count -gt 0)"
$sa = $n | Select-String -Pattern 'Server actions' | Select-Object -First 1
if(-not $sa){
    L '  no Server actions; scrolling panel'
    for($i=0;$i -lt 5;$i++){ Q shell 'input swipe 540 1600 540 1200 300'|Out-Null; Start-Sleep -Milliseconds 600; $n=NT "K03_s$i"; $sa=$n|Select-String -Pattern 'Server actions'|Select-Object -First 1; if($sa){break} }
}
if($sa){
    TapC $sa.Line 'Server actions'
    $n = NT 'K04_menu'
    L '  --- menu ---'
    foreach($x in @($n | Select-String -Pattern 'Delete|Edit|Connect|Reconnect|Forget|Copy|Close')){ L "    $($x.Line)" }
    Shot 'K04_menu'
    $del = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
    if($del){
        if($del.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  DELETE_ITEM bounds x=$($Matches[1])..$($Matches[3]) y=$($Matches[2])..$($Matches[4])" }
        TapC $del.Line 'Delete'
        $n = NT 'K05_confirm'
        L "  IME=$(ImeShown)"
        foreach($x in @($n | Select-String -Pattern 'Delete|Cancel|Are you sure|Remove|E2ECHK|Confirm|this')){ L "    C $($x.Line)" }
        Shot 'K05_confirm'
        $c = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
        $cc= $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
        if($c -and $c.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM_BAR Delete y-range=$($Matches[2])..$($Matches[4]) x=$($Matches[1])..$($Matches[3]) inViewport(0..2424)=$($Matches[2] -ge 0 -and [int]$Matches[4] -le 2424)" }
        if($cc -and $cc.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM_BAR Cancel y-range=$($Matches[2])..$($Matches[4]) x=$($Matches[1])..$($Matches[3])" }
        if($c){
            TapC $c.Line 'confirm Delete'
            Start-Sleep -Seconds 2
            $n = NT 'K06_after'
            $rowsAfter = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
            L "  ROWS_AFTER=[$($rowsAfter -join ',')] count=$($rowsAfter.Count)"
            foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c|Server actions|SSH tunnels|10.0.0.2:4095')){ L "    $($x.Line)" }
            $GTA = GtTunnel
            L "  GT tunnelValLen $GTB -> $GTA (delta $($GTA-$GTB))"
            Shot 'K06_after'
        }
    } else { L '  Delete not offered in menu' }
} else { L '  STILL no Server actions button' }
L 'DONE-K1'
