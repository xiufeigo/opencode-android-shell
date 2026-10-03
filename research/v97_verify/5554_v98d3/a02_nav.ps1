$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"
# tap Settings on the Home screen (parsed from the a01 dump)
$x=Dump "A01_home"
$s=@(Find $x "Settings")[0]
if($s){ $c=Ctr $s.bounds; TapXY $c[0] $c[1] 2500 } else { Write-Output "NO_SETTINGS" }
$x=GotoServers
if(-not (InServers $x)){
  # tab strip may need more aggressive swiping
  for($i=0;$i -lt 5;$i++){
    $ts=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.ScrollView" -and $_.scrollable -eq "true" }) |
         Where-Object { ((BNum $_.bounds)[3]-(BNum $_.bounds)[1]) -lt 200 } | Select-Object -First 1
    Write-Output "TABSTRIP=$($ts.bounds)"
    if($ts){ $n=BNum $ts.bounds; $ty=[int](($n[1]+$n[3])/2)
      for($k=0;$k -lt 6;$k++){ & $global:ADB -s $global:DEV shell input swipe 950 $ty 150 $ty 200; Start-Sleep -Milliseconds 350 } }
    $x=Dump "A02_tab$i"
    $tabs=@($x.SelectNodes("//node")|Where-Object{(TxtOf $_) -ne ""}|ForEach-Object{TxtOf $_})
    Write-Output "TABS[$i]=$(($tabs|Select-Object -First 14) -join ' | ')"
    if((Find $x "Servers").Count -gt 0){ TapTxt $x "Servers" 0 1800 | Out-Null; $x=Dump "A03_srv$i"
      Write-Output "  -> after tap Servers IN_SERVERS=$((InServers $x))"; break }
  }
}
Cap "A04_servers"
Write-Output "=== FINAL ==="
Write-Output "IN_SERVERS=$((InServers $x))  VIEWPORT=$(PanelViewport $x)"
Write-Output "--- scrollable ---"
foreach($s2 in @($x.SelectNodes("//node")|Where-Object{$_.scrollable -eq "true"})){ Write-Output ("  {0} {1}" -f $s2.class,$s2.bounds) }
Write-Output "--- all text ---"
foreach($n in @($x.SelectNodes("//node")|Where-Object{(TxtOf $_) -ne ""}|Sort-Object{(BNum $_.bounds)[1]})){ Write-Output ("  [{0}] {1}" -f $n.bounds,(TxtOf $n)) }
