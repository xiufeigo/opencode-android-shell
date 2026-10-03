# tab.ps1 - the PROVEN flow (this is what created E2ECHK5554d3c-01):
# tap Add SSH server -> tap the EditText directly under the "Host or SSH command" label ->
# type host -> keyevent 61 (TAB) -> type name -> Add server -> password dialog -> Add server.
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"
$HOSTTXT = "ssh -p 2222 sshtest@127.0.0.1"
$PW = "keypass123"

foreach($nm in $args){
  $x=Cap "T_${nm}_pre"
  # a leftover password dialog? finish it first
  if((Find $x "Enter the password for this connection.").Count -gt 0){
    $pe=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and $_.text -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text $PW; Start-Sleep -Milliseconds 900
      $g=Cap "T_${nm}_pwf"; $b=@(Find $g "Add server")[0]
      if($b){ $bc=Ctr $b.bounds; TapXY $bc[0] $bc[1] 2400; Start-Sleep -Seconds 2 } }
    $x=Cap "T_${nm}_pre2"
  }
  if((Find $x "Host or SSH command").Count -eq 0){
    if((Find $x "Add SSH server").Count -eq 0){ Write-Output "$nm NOT_IN_PANEL"; continue }
    $a=@(Find $x "Add SSH server")[0]; $c=Ctr $a.bounds; TapXY $c[0] $c[1] 1900
    $x=Cap "T_${nm}_form"
  }
  # host field = first EditText strictly below the "Host or SSH command" label
  $lbl=@(Find $x "Host or SSH command")[0]
  if(-not $lbl){ Write-Output "$nm NO_LABEL"; continue }
  $ly=(BNum $lbl.bounds)[3]
  $he=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge $ly}|Sort-Object{(BNum $_.bounds)[1]})[0]
  if(-not $he){ Write-Output "$nm NO_HOST_ET"; continue }
  $hc=Ctr $he.bounds
  TapXY $hc[0] $hc[1] 900
  & $global:ADB -s $global:DEV shell input text ($HOSTTXT -replace ' ','%s')
  Start-Sleep -Milliseconds 1000
  & $global:ADB -s $global:DEV shell input keyevent 61     # TAB -> name field
  Start-Sleep -Milliseconds 900
  & $global:ADB -s $global:DEV shell input text $nm
  Start-Sleep -Milliseconds 1100
  $g=Cap "T_${nm}_filled"
  $e=@($g.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and $_.text -ne "" -and (BNum $_.bounds)[1] -ge 900}|Sort-Object{(BNum $_.bounds)[1]})
  Write-Output "$nm FILL: $(($e|%{$_.text}) -join ' ~ ')"
  $b=@(Find $g "Add server")[0]
  if(-not $b){ Write-Output "$nm NO_BTN"; continue }
  $bc=Ctr $b.bounds
  TapXY $bc[0] $bc[1] 2400
  Start-Sleep -Seconds 2
  $z=Cap "T_${nm}_post"
  if((Find $z "Enter the password for this connection.").Count -gt 0){
    $pe=@($z.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and $_.text -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text $PW; Start-Sleep -Milliseconds 900
      $h=Cap "T_${nm}_pwsub"
      $b2=@(Find $h "Add server")[0]
      if($b2){ $bc2=Ctr $b2.bounds; TapXY $bc2[0] $bc2[1] 2400; Start-Sleep -Seconds 2 } }
  }
  $fin=Cap "T_${nm}_fin"
  Write-Output "$nm DONE form=$((Find $fin 'Host or SSH command').Count) pwd=$((Find $fin 'Enter the password for this connection.').Count)"
}
