$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"

function Nav(){
  $x=Dump "N00"
  if(InServers $x){ return $x }
  # 1) Settings from Home
  if((Find $x "Settings").Count -gt 0){ TapTxt $x "Settings" 0 2500 | Out-Null; $x=Dump "N01" }
  for($i=0;$i -lt 8;$i++){
    if(InServers $x){ return $x }
    $ts=@($x.SelectNodes("//node")|Where-Object{$_.scrollable -eq "true" -and ((BNum $_.bounds)[3]-(BNum $_.bounds)[1]) -lt 200})|Select-Object -First 1
    if($ts){ $ty=[int](((BNum $ts.bounds)[1]+(BNum $ts.bounds)[3])/2)
      for($k=0;$k -lt 5;$k++){ & $global:ADB -s $global:DEV shell input swipe 950 $ty 150 $ty 200; Start-Sleep -Milliseconds 320 } }
    $x=Dump "N1$i"
    if((Find $x "Servers").Count -gt 0){ TapTxt $x "Servers" 0 1800 | Out-Null; $x=Dump "N2$i" }
    if((Find $x "Add SSH server").Count -gt 0){ return $x }
    if((Find $x "SSH tunnels").Count -gt 0){ return $x }
  }
  return $x
}
function ClearForm($x){
  foreach($e in @($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText"})){
    $c=Ctr $e.bounds; TapXY $c[0] $c[1] 450
    & $global:ADB -s $global:DEV shell input keyevent 123; Start-Sleep -Milliseconds 150
    & $global:ADB -s $global:DEV shell 'i=0; while [ $i -lt 300 ]; do input keyevent 67; i=$((i+1)); done'
    Start-Sleep -Milliseconds 500
  }
}
function Report($x,$tag){
  $err=@($x.SelectNodes("//node")|Where-Object{(TxtOf $_) -ne "" -and ((TxtOf $_) -match "required|Enter the path|Invalid|must be|Please enter")})
  foreach($e in $err){ Write-Output "   ERRTEXT[$tag] [{0}] '{1}'" -f $e.bounds,(TxtOf $e) }
  $b=@(Find $x "Add server")[0]
  if($b){ Write-Output "   BTN[$tag] {0} enabled={1}" -f $b.bounds,$b.enabled } else { Write-Output "   BTN[$tag] MISSING" }
}

$x=Nav
Write-Output "NAV IN_SERVERS=$((InServers $x)) VIEWPORT=$(PanelViewport $x)"
Cap "B00_panel"
Write-Output "ROWS_BEFORE = $((RowNames $x) -join ' ; ')"
# --- TASK A #2: is the panel body scrollable? ---
$vp=PanelViewport $x
Write-Output "SCROLLNODES: $((@($x.SelectNodes('//node')|Where-Object{$_.scrollable -eq 'true'}|ForEach-Object{$_.class+' '+$_.bounds})) -join ' || ')"
Write-Output "SCROLLABLE_IN_PANEL=$($vp -ne $null)"

foreach($nm in $args){
  Write-Output "===== $nm ====="
  $x=Nav
  if(ConfirmBar $x){ TapTxt $x "Cancel" 0 1200|Out-Null; $x=Nav }
  if(AnyModal $x){ TapTxt $x "Cancel" 0 1200|Out-Null; $x=Nav }
  if(PwdDlg $x){
    $pe=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 800
      $h=Dump "B_${nm}_stale"; if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500|Out-Null } }
    $x=Nav
  }
  if(InForm $x){ ClearForm $x; $x=Dump "B_${nm}_cleared"; Report $x "${nm}_cleared" }
  if(-not (InForm $x)){
    if((Find $x "Add SSH server").Count -eq 0){ Write-Output "$nm NO_ADD_BTN"; break }
    TapTxt $x "Add SSH server" 0 2000|Out-Null
    $x=Dump "B_${nm}_form"; Cap "B_${nm}_form"
    ClearForm $x
    $x=Dump "B_${nm}_cleared"; Report $x "${nm}_cleared"; Cap "B_${nm}_cleared"
  }
  if(-not (InForm $x)){ Write-Output "$nm FORM_NOT_OPEN"; break }
  $lbl=@(Find $x "Host or SSH command")[0]
  if(-not $lbl){ Write-Output "$nm NO_LABEL"; break }
  $ly=(BNum $lbl.bounds)[3]
  $he=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge $ly}|Sort-Object{(BNum $_.bounds)[1]})[0]
  if(-not $he){ Write-Output "$nm NO_HOST_ET"; break }
  $hc=Ctr $he.bounds; TapXY $hc[0] $hc[1] 800
  & $global:ADB -s $global:DEV shell input text "ssh%s-p%s2222%ssshtest@127.0.0.1"; Start-Sleep -Milliseconds 900
  & $global:ADB -s $global:DEV shell input keyevent 61; Start-Sleep -Milliseconds 800
  & $global:ADB -s $global:DEV shell input text $nm; Start-Sleep -Milliseconds 900
  $g=Dump "B_${nm}_filled"; Cap "B_${nm}_filled"
  $e=@($g.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -ne ""}|Sort-Object{(BNum $_.bounds)[1]})
  Write-Output "   FILL: $(($e|ForEach-Object{TxtOf $_}) -join ' ~ ')"
  Report $g "${nm}_filled"
  if((Find $g "Add server").Count -eq 0){ Write-Output "$nm NO_SUBMIT_BTN"; break }
  TapTxt $g "Add server" 0 2500|Out-Null
  $z=Dump "B_${nm}_post1"; Cap "B_${nm}_post1"
  if(PwdDlg $z){
    $pe=@($z.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 800
      $h=Dump "B_${nm}_pwf"; Cap "B_${nm}_pwf"; Report $h "${nm}_pwf"
      if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500|Out-Null } else { Write-Output "   PWD_DIALOG_NO_BTN" }
    } else { Write-Output "   PWD_DIALOG_NO_ET" }
  }
  Start-Sleep -Seconds 2
  $x=Nav; Cap "B_${nm}_post2"
  Write-Output "   POST2 form=$((InForm $x)) pwd=$((PwdDlg $x)) modal=$((AnyModal $x))"
  $rn=@(RowNames $x)
  Write-Output "   ROWS_AFTER_$nm = $($rn.Count) :: $($rn -join ' ; ')"
}
$x=Nav; Cap "B99_final"
$rn=@(RowNames $x)
Write-Output "===== FINAL D3C ROWS = $($rn.Count) ====="
$rn|ForEach-Object{Write-Output "   $_"}
Write-Output "VIEWPORT=$(PanelViewport $x)"
