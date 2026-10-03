$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"
$global:NAVED=$false
function TabStrip($x){
  foreach($s in @($x.SelectNodes("//node")|Where-Object{$_.scrollable -eq "true"})){
    $n=BNum $s.bounds
    if(($n[3]-$n[1]) -lt 200 -and $n[1] -lt 1000){ return $s } }
  return $null
}
function Nav(){
  if(-not $global:NAVED){
    & $global:ADB -s $global:DEV shell am force-stop com.xiufeigo.opencodeshell; Start-Sleep -Milliseconds 900
    & $global:ADB -s $global:DEV shell monkey -p com.xiufeigo.opencodeshell -c android.intent.category.LAUNCHER 1|Out-Null
    Start-Sleep -Seconds 5; $global:NAVED=$true }
  $x=Dump "Z00"
  if(InServers $x){ return $x }
  if(-not (@($x.SelectNodes("//node")|ForEach-Object{$_.text}) -contains "Projects")){
    $h=@(Find $x "Home")[0]; if($h){ $c=Ctr $h.bounds; TapXY $c[0] $c[1] 3000 } }
  for($t=0;$t -lt 4;$t++){
    $x=Dump "Z01"
    if((Find $x "Language").Count -gt 0){ break }
    if((Find $x "Settings").Count -gt 0){ TapTxt $x "Settings" 0 2500|Out-Null } else { Start-Sleep -Milliseconds 700 } }
  $x=Dump "Z02"
  $ts=TabStrip $x
  if($ts){ $bn=BNum $ts.bounds; $ty=[int](($bn[1]+$bn[3])/2)
    Write-Host "TABSTRIP=$($ts.bounds) y=$ty"
    for($k=0;$k -lt 5;$k++){ & $global:ADB -s $global:DEV shell input swipe ([int]($bn[2]-30)) $ty ([int]($bn[0]+30)) $ty 200; Start-Sleep -Milliseconds 400 } }
  else { Write-Host "NO_TABSTRIP" }
  $x=Dump "Z03"
  if((Find $x "Servers").Count -gt 0){ TapTxt $x "Servers" 0 2200|Out-Null; $x=Dump "Z04" }
  return $x
}
function CleanIfDirty($x){
  $dirty=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -ne ""})
  if($dirty.Count -eq 0){ Write-Output "   FORM_CLEAN"; return }
  foreach($e in $dirty){
    Write-Output "   RESIDUE '{0}' @{1}" -f (TxtOf $e), $e.bounds
    $c=Ctr $e.bounds; TapXY $c[0] $c[1] 400
    & $global:ADB -s $global:DEV shell input keyevent 123; Start-Sleep -Milliseconds 120
    & $global:ADB -s $global:DEV shell 'i=0; while [ $i -lt 300 ]; do input keyevent 67; i=$((i+1)); done'; Start-Sleep -Milliseconds 400 } }
$x=Nav
Write-Output "NAV IN_SERVERS=$((InServers $x)) VP=$(PanelViewport $x)"
Cap "A00_panel"
Write-Output "ROWS_BEFORE = $((RowNames $x) -join ' ; ')"
Write-Output "SCROLLNODES: $((@($x.SelectNodes('//node')|Where-Object{$_.scrollable -eq 'true'}|ForEach-Object{$_.class+' '+$_.bounds})) -join ' || ')"
$vp=PanelViewport $x
Write-Output "TASKA_PANEL_SCROLLABLE=$($vp -ne $null) VP=$vp"
if($vp){ $vn=BNum $vp; $b4=@(RowNames $x)
  Flick $x ([int]($vn[3]-150)) ([int]($vn[1]+150))
  $x=Nav
  Write-Output "AFTER_FLICK = $((RowNames $x) -join ' ; ')"
  Write-Output "FLICK_CHANGED=$((@(RowNames $x)).Count -ne $b4.Count)" }
foreach($nm in $args){
  Write-Output "===== $nm ====="
  $x=Nav
  if(ConfirmBar $x){ TapTxt $x "Cancel" 0 1200|Out-Null; $x=Nav }
  if(AnyModal $x){ TapTxt $x "Cancel" 0 1200|Out-Null; $x=Nav }
  if(PwdDlg $x){
    $pe=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 700
      $h=Dump "B_${nm}_stale"; if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500|Out-Null } }
    $x=Nav }
  if(InForm $x){ CleanIfDirty $x; $x=Dump "B_${nm}_cleared" }
  if(-not (InForm $x)){
    if((Find $x "Add SSH server").Count -eq 0){ Write-Output "$nm NO_ADD_BTN"; break }
    TapTxt $x "Add SSH server" 0 2000|Out-Null
    $x=Dump "B_${nm}_form"; Cap "B_${nm}_form"
    CleanIfDirty $x; $x=Dump "B_${nm}_cleared"; Cap "B_${nm}_cleared" }
  if(-not (InForm $x)){ Write-Output "$nm FORM_NOT_OPEN"; break }
  $lbl=@(Find $x "Host or SSH command")[0]
  if(-not $lbl){ Write-Output "$nm NO_LABEL"; break }
  $ly=(BNum $lbl.bounds)[3]
  $he=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge $ly}|Sort-Object{(BNum $_.bounds)[1]})[0]
  if(-not $he){ Write-Output "$nm NO_HOST_ET"; break }
  $hc=Ctr $he.bounds; TapXY $hc[0] $hc[1] 800
  & $global:ADB -s $global:DEV shell input text "ssh%s-p%s2222%ssshtest@127.0.0.1"; Start-Sleep -Milliseconds 800
  & $global:ADB -s $global:DEV shell input keyevent 61; Start-Sleep -Milliseconds 700
  & $global:ADB -s $global:DEV shell input text $nm; Start-Sleep -Milliseconds 800
  $g=Dump "B_${nm}_filled"; Cap "B_${nm}_filled"
  $e=@($g.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -ne ""}|Sort-Object{(BNum $_.bounds)[1]})
  Write-Output "   FILL: $(($e|ForEach-Object{TxtOf $_}) -join ' ~ ')"
  foreach($er in @($g.SelectNodes("//node")|Where-Object{(TxtOf $_) -match "required|Enter the path|Invalid|must be|Please enter"})){
    Write-Output "   ERRTEXT [{0}] '{1}'" -f $er.bounds,(TxtOf $er) }
  if((Find $g "Add server").Count -eq 0){ Write-Output "$nm NO_SUBMIT_BTN"; break }
  TapTxt $g "Add server" 0 2500|Out-Null
  $z=Dump "B_${nm}_post1"; Cap "B_${nm}_post1"
  if(PwdDlg $z){
    $pe=@($z.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 700
      $h=Dump "B_${nm}_pwf"; Cap "B_${nm}_pwf"
      if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500|Out-Null } else { Write-Output "   PWD_NO_BTN" } }
    else { Write-Output "   PWD_NO_ET" } }
  Start-Sleep -Seconds 2
  $x=Nav; Cap "B_${nm}_post2"
  Write-Output "   POST2 form=$((InForm $x)) pwd=$((PwdDlg $x)) modal=$((AnyModal $x))"
  Write-Output "   ROWS_AFTER_$nm = $((@(RowNames $x)).Count) :: $((RowNames $x) -join ' ; ')" }
$x=Nav; Cap "A01_final"
Write-Output "===== FINAL D3C ROWS = $((@(RowNames $x)).Count) ====="
@(RowNames $x)|ForEach-Object{Write-Output "   $_"}
Write-Output "VIEWPORT=$(PanelViewport $x)"
