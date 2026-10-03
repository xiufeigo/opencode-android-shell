. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY='C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
function GtTunnel { $c=(Q shell "run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_server_config.xml" 2>&1|Out-String); $m=[regex]::Match($c,'<string name="AXhs57KqmMxUXQA[^"]*">([^<]*)</string>'); if($m.Success){ L "  GT tunnelValLen=$($m.Groups[1].Value.Length)"; return $m.Groups[1].Value.Length }; return -1 }
function NT($tag){ $ErrorActionPreference='Continue'
  & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_n1.xml" 2>&1|Out-Null
  & $global:ADB -s $global:DEV pull /sdcard/_n1.xml (Join-Path $global:DIR "$tag.xml") 2>&1|Out-Null
  & $global:ADB -s $global:DEV shell "rm -f /sdcard/_n1.xml" 2>&1|Out-Null
  $p=Join-Path $global:DIR "$tag.xml"; if(-not(Test-Path $p)){ return @() }
  return @(python $NODESPY $p) }
function Shot($t){ Q shell "screencap -p /sdcard/_n1.png"|Out-Null; Q pull /sdcard/_n1.png (Join-Path $global:DIR "$t.png")|Out-Null; Q shell "rm -f /sdcard/_n1.png"|Out-Null }
function CenterOf($l){ if($l -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
function TapC($l,[string]$w){ $p=CenterOf $l; L "  TAP $w @ $($p[0]),$($p[1])"; Q shell "input tap $($p[0]) $($p[1])"|Out-Null; Start-Sleep -Seconds 2 }
$TABS = '^(General|Appearance|Notifications|Shortcuts|Extensions|Servers|Shell|Experimental|About)\t'

L '=== N1: reach Servers tab, DELETE the self-built row ==='
$n = NT 'N00'
if(@($n | Select-String -Pattern $TABS).Count -eq 0){
  $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
  if($s){ TapC $s.Line 'Settings' } else { L '  no Settings affordance' }
  $n = NT 'N01_settings'
}
# scan the tab strip, swiping right until Servers is visible
$ty=744
$g = $n | Select-String -Pattern $TABS | Select-Object -First 1
if($g -and $g.Line -match '\[(\d+),(\d+)\]'){ $ty=[int]$Matches[2] }
L "  tab strip y=$ty"
$found=$false
for($i=0;$i -lt 8;$i++){
  $n = NT "N02_scan$i"
  $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
  L "  scan ${i}: tabs=[$((@($n|Select-String -Pattern $TABS)|ForEach-Object{($_.Line -split "`t")[0]}) -join ',')] Servers=$($null -ne $srv)"
  if($srv){ $found=$true; break }
  Q shell "input swipe 950 $ty 150 $ty 350"|Out-Null; Start-Sleep -Milliseconds 700
}
if(-not $found){ L '  Servers tab NOT reachable'; }
else {
  TapC $srv.Line 'Servers tab'
  $n = NT 'N03_panel'
  L '  --- Servers panel ---'
  foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c|Server actions|SSH tunnels|Trust and connect|Connecting|Connected|Disconnected|Add SSH server|10.0.0.2:4095|4095')){ L "    $($x.Line)" }
  Shot 'N03_panel'
  $GTB = GtTunnel
  $rowsBefore = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
  L "  ROWS_BEFORE=[$($rowsBefore -join ',')] cardUp=$(@($n | Select-String -Pattern 'Trust and connect').Count -gt 0)"
  $sa = $n | Select-String -Pattern 'Server actions' | Select-Object -First 1
  if(-not $sa){
    for($i=0;$i -lt 5;$i++){
      Q shell 'input swipe 540 1750 540 1150 300'|Out-Null; Start-Sleep -Milliseconds 800
      $n = NT "N04_s$i"; $sa = $n | Select-String -Pattern 'Server actions' | Select-Object -First 1
      L "    scroll ${i}: ServerActions=$(@($n|Select-String -Pattern 'Server actions').Count) E2ECHK=$(@($n|Select-String -Pattern 'E2ECHK5554d3c').Count)"
      if($sa){ break } }
  }
  if($sa){
    TapC $sa.Line 'Server actions'
    $n = NT 'N05_menu'
    L '  --- row menu ---'
    foreach($x in @($n | Select-String -Pattern 'Delete|Edit|Connect|Reconnect|Forget|Copy|Close|Remove')){ L "    $($x.Line)" }
    Shot 'N05_menu'
    $del = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
    if($del){
      if($del.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  DELETE_ITEM bounds x=$($Matches[1])..$($Matches[3]) y=$($Matches[2])..$($Matches[4])" }
      TapC $del.Line 'Delete'
      $n = NT 'N06_confirm'
      L "  IME=$(ImeShown)"
      foreach($x in @($n | Select-String -Pattern 'Delete|Cancel|Are you sure|Remove|E2ECHK|Confirm|this|server')){ L "    C $($x.Line)" }
      Shot 'N06_confirm'
      $c = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
      $cc= $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
      if($c -and $c.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM_BAR Delete y=$($Matches[2])..$($Matches[4]) x=$($Matches[1])..$($Matches[3]) inViewport0_2424=$($Matches[2] -ge 0 -and [int]$Matches[4] -le 2424)" }
      if($cc -and $cc.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ L "  CONFIRM_BAR Cancel y=$($Matches[2])..$($Matches[4]) x=$($Matches[1])..$($Matches[3])" }
      if($c){
        TapC $c.Line 'confirm Delete'
        Start-Sleep -Seconds 2
        $n = NT 'N07_after'
        $rowsAfter = @($n | Select-String -Pattern 'E2ECHK5554d3c-\d' | ForEach-Object { ($_.Line -split "`t")[0] })
        L "  ROWS_AFTER=[$($rowsAfter -join ',')] count=$($rowsAfter.Count)"
        foreach($x in @($n | Select-String -Pattern 'E2ECHK5554d3c|Server actions|SSH tunnels|4095')){ L "    $($x.Line)" }
        $GTA = GtTunnel
        L "  GT tunnelValLen $GTB -> $GTA (delta $($GTA-$GTB))"
        Shot 'N07_after'
      }
    } else { L '  Delete NOT offered in menu' }
  } else { L '  STILL no Server actions' }
}
L 'DONE-N1'
