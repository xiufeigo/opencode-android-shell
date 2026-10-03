# fast.ps1 - create entries on a FRESH form (fields start empty, so no wipe needed).
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"
$HOSTTXT = "ssh -p 2222 sshtest@127.0.0.1"
$PW = "keypass123"
function FilledEt($x){ return @($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge 900}|Sort-Object{(BNum $_.bounds)[1]}) }

foreach($nm in $args){
  $x=Cap "F_${nm}_pre"
  if((Find $x "Enter the password for this connection.").Count -gt 0){
    $pe=(FilledEt $x | Where-Object{$_.text -eq ""})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text $PW; Start-Sleep -Milliseconds 900
      $g=Cap "F_${nm}_pw"; $b=@(Find $g "Add server")[0]
      if($b){ $bc=Ctr $b.bounds; TapXY $bc[0] $bc[1] 2400; Start-Sleep -Seconds 2 } }
    $x=Cap "F_${nm}_pre2"
  }
  if((Find $x "Add SSH server").Count -gt 0){
    $a=@(Find $x "Add SSH server")[0]; $c=Ctr $a.bounds; TapXY $c[0] $c[1] 1900
    $x=Cap "F_${nm}_form"
  }
  $e=FilledEt $x
  if($e.Count -lt 2){ Write-Output "$nm NO_FIELDS"; continue }
  $hc=Ctr $e[0].bounds; $nc=Ctr $e[1].bounds
  TapXY $hc[0] $hc[1] 800
  & $global:ADB -s $global:DEV shell input text ($HOSTTXT -replace ' ','%s')
  Start-Sleep -Milliseconds 900
  TapXY $nc[0] $nc[1] 800
  & $global:ADB -s $global:DEV shell input text $nm
  Start-Sleep -Milliseconds 1000
  $g=Cap "F_${nm}_filled"
  $e2=FilledEt $g
  Write-Output "$nm FILL host='$($e2[0].text)' name='$($e2[1].text)'"
  $b=@(Find $g "Add server")[0]
  if($b){ $bc=Ctr $b.bounds; TapXY $bc[0] $bc[1] 2400; Start-Sleep -Seconds 2 }
  $z=Cap "F_${nm}_post"
  if((Find $z "Enter the password for this connection.").Count -gt 0){
    $pe=(FilledEt $z | Where-Object{$_.text -eq ""})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text $PW; Start-Sleep -Milliseconds 900
      $h=Cap "F_${nm}_pwf"
      $b2=@(Find $h "Add server")[0]
      if($b2){ $bc2=Ctr $b2.bounds; TapXY $bc2[0] $bc2[1] 2400; Start-Sleep -Seconds 2 } }
  }
  $fin=Cap "F_${nm}_fin"
  Write-Output "$nm DONE form=$((Find $fin 'Host or SSH command').Count) pwd=$((Find $fin 'Enter the password for this connection.').Count)"
}
