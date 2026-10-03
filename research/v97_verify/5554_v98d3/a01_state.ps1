$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"
& $global:ADB -s $global:DEV shell am force-stop com.xiufeigo.opencodeshell
Start-Sleep -Seconds 1
& $global:ADB -s $global:DEV shell monkey -p com.xiufeigo.opencodeshell -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 4
$x=GotoServers
Cap "A00_panel"
Write-Output "IN_SERVERS=$((InServers $x))  IN_FORM=$((InForm $x))  PWD=$((PwdDlg $x))  MODAL=$((AnyModal $x))"
Write-Output "VIEWPORT=$(PanelViewport $x)"
Write-Output "--- scrollable nodes ---"
foreach($s in @($x.SelectNodes("//node") | Where-Object { $_.scrollable -eq "true" })){
  Write-Output ("  {0} scrollable bounds={1}" -f $s.class, $s.bounds)
}
Write-Output "--- ALL visible text nodes (y-ordered, first 60) ---"
$all=@($x.SelectNodes("//node") | Where-Object { (TxtOf $_) -ne "" } | Sort-Object { (BNum $_.bounds)[1] })
Write-Output "TOTAL_TEXT_NODES=$($all.Count)"
foreach($n in $all){ Write-Output ("  [{0}] {1}" -f $n.bounds, (TxtOf $n)) }
