$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"
# clean the form completely before each attempt; report exactly what blocks submission
function ClearForm($x){
  $notes=@()
  foreach($e in @($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText"})){
    $t=(TxtOf $e); $c=Ctr $e.bounds
    if($t -ne ""){ $notes += "CLEAR[$c[0],$c[1]]was='$t'" }
    TapXY $c[0] $c[1] 500
    & $global:ADB -s $global:DEV shell input keyevent 123
    Start-Sleep -Milliseconds 200
    & $global:ADB -s $global:DEV shell 'i=0; while [ $i -lt 300 ]; do input keyevent 67; i=$((i+1)); done'
    Start-Sleep -Milliseconds 600
  }
  # auth-mode toggle kills keypath-only residue
  if((Find $x "Public key").Count -gt 0 -or (Find $x "Password").Count -gt 0){ $notes += "AUTOMODE_PRESENT" }
  return $notes
}
function Diagnose($x,$tag){
  $err=@($x.SelectNodes("//node")|Where-Object{(TxtOf $_) -ne "" -and ((TxtOf $_) -match "required|Enter the path|Invalid|error|must be|at least|Please")})
  foreach($e in $err){ Write-Output "   !!ERRTEXT[$tag] [{0}] {1}" -f $e.bounds,(TxtOf $e) }
  $btn=@(Find $x "Add server")[0]
  if($btn){ Write-Output "   BTN[$tag] bounds={0} enabled={1} clickable={2}" -f $btn.bounds,$btn.enabled,$btn.clickable }
  else { Write-Output "   BTN[$tag] NOT-FOUND" }
  return $err.Count
}

$x=GotoServers
Write-Output "NAV IN_SERVERS=$((InServers $x)) VIEWPORT=$(PanelViewport $x)"
Cap "B00_panel"
Write-Output "ROWS_BEFORE: $((RowNames $x) -join ' ; ')"
$host0=$x

foreach($nm in $args){
  Write-Output "===== $nm ====="
  $x=$host0
  if(-not (InServers $x)){ Write-Output "$nm PANEL_LOST"; break }
  # close any modal/confirm leftovers
  if(ConfirmBar $x){ TapTxt $x "Cancel" 0 1200 | Out-Null; $x=Dump "B_${nm}_x1" }
  if(AnyModal $x){ TapTxt $x "Cancel" 0 1200 | Out-Null; $x=Dump "B_${nm}_x2" }
  if(PwdDlg $x){
    Write-Output "$nm STALE_PWD_DIALOG -> completing with keypass123"
    $pe=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 800
      $h=Dump "B_${nm}_pwf"; if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2400 | Out-Null } }
    $x=Dump "B_${nm}_x3"
  }
  if(InForm $x){
    $pre=ClearForm $x
    foreach($p in $pre){ Write-Output "   $p" }
    $x=Dump "B_${nm}_cleared"
    Cap "B_${nm}_cleared"
    Diagnose $x "${nm}_cleared" | Out-Null
  }
  # open the add form
  if(-not (InForm $x)){
    if((Find $x "Add SSH server").Count -eq 0){ Write-Output "$nm NO_ADD_BTN"; break }
    TapTxt $x "Add SSH server" 0 2000 | Out-Null
    $x=Dump "B_${nm}_form"
    Cap "B_${nm}_form"
  }
  if(-not (InForm $x)){ Write-Output "$nm FORM_NOT_OPEN"; break }
  $lbl=@(Find $x "Host or SSH command")[0]
  if(-not $lbl){ Write-Output "$nm NO_LABEL"; break }
  $ly=(BNum $lbl.bounds)[3]
  $he=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge $ly}|Sort-Object{(BNum $_.bounds)[1]})[0]
  if(-not $he){ Write-Output "$nm NO_HOST_ET"; break }
  $hc=Ctr $he.bounds; TapXY $hc[0] $hc[1] 800
  & $global:ADB -s $global:DEV shell input text "ssh%s-p%s2222%ssshtest@127.0.0.1"
  Start-Sleep -Milliseconds 900
  & $global:ADB -s $global:DEV shell input keyevent 61
  Start-Sleep -Milliseconds 800
  & $global:ADB -s $global:DEV shell input text $nm
  Start-Sleep -Milliseconds 900
  $g=Dump "B_${nm}_filled"
  $e=@($g.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -ne ""}|Sort-Object{(BNum $_.bounds)[1]})
  Write-Output "   FILL: $(($e|ForEach-Object{TxtOf $_}) -join ' ~ ')"
  $nerr=Diagnose $g "${nm}_filled"
  Cap "B_${nm}_filled"
  if((Find $g "Add server").Count -eq 0){ Write-Output "$nm NO_ADD_BTN_AT_SUBMIT"; break }
  TapTxt $g "Add server" 0 2500 | Out-Null
  $z=Dump "B_${nm}_post1"
  Cap "B_${nm}_post1"
  if(PwdDlg $z){
    Write-Output "   PWD_DIALOG (expected 2nd Add server)"
    $pe=@($z.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 800
      $h=Dump "B_${nm}_pwf"
      Diagnose $h "${nm}_pwf" | Out-Null
      Cap "B_${nm}_pwf"
      if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500 | Out-Null }
      else { Write-Output "   PWD_DIALOG_NO_BTN" } }
    else { Write-Output "   PWD_DIALOG_NO_ET" }
  }
  Start-Sleep -Seconds 2
  $fin=Dump "B_${nm}_post2"
  Cap "B_${nm}_post2"
  Write-Output "   POST2: form=$((InForm $fin)) pwd=$((PwdDlg $fin)) modal=$((AnyModal $fin))"
  Write-Output "   ERRS=$nerr"
  $host0=$fin
  $rn=@(RowNames $fin)
  Write-Output "   ROWS_AFTER_$nm = $($rn.Count) :: $($rn -join ' ; ')"
}
$x=Dump "B99_final"
Cap "B99_final"
$rn=@(RowNames $x)
Write-Output "===== FINAL D3C ROWS = $($rn.Count) ====="
$rn | ForEach-Object { Write-Output "   $_" }
Write-Output "VIEWPORT=$(PanelViewport $x)"
