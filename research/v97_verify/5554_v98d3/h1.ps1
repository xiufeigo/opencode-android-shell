. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY='C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
function GtTunnel { $c=(Q shell "run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_server_config.xml" 2>&1|Out-String); $m=[regex]::Match($c,'<string name="AXhs57KqmMxUXQA[^"]*">([^<]*)</string>'); if($m.Success){ L "  GT tunnelValLen=$($m.Groups[1].Value.Length)"; return $m.Groups[1].Value.Length }; return -1 }
function NT($tag){ $ErrorActionPreference='Continue'
  & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_h1.xml" 2>&1|Out-Null
  & $global:ADB -s $global:DEV pull /sdcard/_h1.xml (Join-Path $global:DIR "$tag.xml") 2>&1|Out-Null
  & $global:ADB -s $global:DEV shell "rm -f /sdcard/_h1.xml" 2>&1|Out-Null
  return @(python $NODESPY (Join-Path $global:DIR "$tag.xml")) }
function Shot($t){ Q shell "screencap -p /sdcard/_h1.png"|Out-Null; Q pull /sdcard/_h1.png (Join-Path $global:DIR "$t.png")|Out-Null; Q shell "rm -f /sdcard/_h1.png"|Out-Null }
function CenterOf($l){ if($l -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
$STATES = @('Connected','Connecting','Authentication required','Disconnected','Could not establish','left running','SSH authentication','Passphrase','Unreachable','failed')

L '=== H1: drive D3-4 states, then delete the self-built row ==='
$n = NT 'H00'
L "  card up=$(@($n | Select-String -Pattern 'Trust and connect').Count -gt 0)"
if(@($n | Select-String -Pattern 'Trust and connect').Count -gt 0){
    $t = $n | Select-String -Pattern 'Trust and connect' | Select-Object -First 1
    $p = CenterOf $t.Line
    L "  TAP Trust and connect @ $($p[0]),$($p[1])"
    Q shell "input tap $($p[0]) $($p[1])" | Out-Null
}
L '  --- state sampling (D3-4) ---'
$seen = @{}
for($s=1;$s -le 14;$s++){
    Start-Sleep -Seconds 2
    $n = NT "H1_state$s"
    $bits=@()
    foreach($w in $STATES){ if(@($n | Select-String -Pattern ([regex]::Escape($w))).Count -gt 0){ $bits += $w; $seen[$w]=$s } }
    L ("  s{0,-2} {1}  cardUp={2}" -f $s, ($(if($bits.Count){$bits -join ' | '}else{'(none)'})), (@($n | Select-String -Pattern 'Trust and connect').Count -gt 0))
    if($bits -contains 'Connected' -or $bits -contains 'Authentication required'){
        Shot "H1_state${s}_$($bits[0] -replace ' ','_')"
        break
    }
}
L "  STATES_SEEN=$(($seen.Keys | Sort-Object) -join ',')"
$n = NT 'H02_settled'
L '  --- settled panel ---'
foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c|Server actions|SSH tunnels|Connected|Connecting|Authentication|Disconnected|Trust and connect|Cancel')){ L "    $($x.Line)" }
Shot 'H02_settled'

# ---- delete attempt ----
$GTB = GtTunnel
$rowsBefore = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
L "  ROWS_BEFORE=$($rowsBefore -join ',')"
$sa = $n | Select-String -Pattern 'Server actions' | Select-Object -First 1
if(-not $sa){ L '  no Server actions button visible yet' }
else{
    $p = CenterOf $sa.Line
    L "  TAP Server actions @ $($p[0]),$($p[1])"
    Q shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Seconds 1
    $n = NT 'H03_menu'
    L '  --- menu items ---'
    foreach($x in @($n | Select-String -Pattern 'Delete|Edit|Connect|Reconnect|Forget|Copy|Close')){ L "    $($x.Line)" }
    Shot 'H03_menu'
    $del = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
    if($del){
        if($del.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  DELETE_ITEM bounds y=$($Matches[2])..$($Matches[4)] x=$($Matches[1])..$($Matches[3])" }
        $p = CenterOf $del.Line
        L "  TAP Delete @ $($p[0]),$($p[1])"
        Q shell "input tap $($p[0]) $($p[1])" | Out-Null
        Start-Sleep -Seconds 1
        $n = NT 'H04_confirm'
        L "  IME=$(ImeShown)"
        foreach($x in @($n | Select-String -Pattern 'Delete|Cancel|Are you sure|Remove|E2ECHK|Confirm')){ L "    C $($x.Line)" }
        Shot 'H04_confirm'
        $c = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
        $cc = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
        if($c -and $c.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM Delete y=$($Matches[2])..$($Matches[4])  inViewport(0..2424)=$($Matches[2] -ge 0 -and [int]$Matches[4] -le 2424)" }
        if($cc -and $cc.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM Cancel y=$($Matches[2])..$($Matches[4])" }
        if($c){
            $p = CenterOf $c.Line
            L "  TAP confirm Delete @ $($p[0]),$($p[1])"
            Q shell "input tap $($p[0]) $($p[1])" | Out-Null
            Start-Sleep -Seconds 2
            $n = NT 'H05_after'
            $rowsAfter = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
            L "  ROWS_AFTER=$($rowsAfter -join ',')  count=$($rowsAfter.Count)"
            foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c-\d|Server actions|SSH tunnels')){ L "    $($x.Line)" }
            $GTA = GtTunnel
            L "  GT $GTB -> $GTA (delta $($GTA-$GTB))"
            Shot 'H05_after'
        }
    } else { L '  Delete not offered' }
}
L 'DONE-H1'
