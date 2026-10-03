# pwdadd.ps1 - finish an entry that is waiting on the Password dialog, then continue creating.
param([string[]]$Names,[string]$Pw="keypass123",[string]$HostText="ssh -p 2222 sshtest@127.0.0.1")
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"
$DEL = 'i=0; while [ $i -lt 300 ]; do input keyevent 67; i=$((i+1)); done'
function FilledEt($x){ return @($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge 1000}|Sort-Object{(BNum $_.bounds)[1]}) }

function FinishPwd($nm){
  $x=Cap "PW_${nm}_pre"
  if((Find $x "Enter the password for this connection.").Count -eq 0){ Write-Output "  no pwd dialog"; return $true }
  $e=@(FilledEt $x)
  $pe=$e | Where-Object { $_.text -eq "" } | Select-Object -First 1
  if(-not $pe){ $pe=$e[-1] }
  $pc=Ctr $pe.bounds
  TapXY $pc[0] $pc[1] 800
  & $global:ADB -s $global:DEV shell input keyevent 123
  & $global:ADB -s $global:DEV shell $DEL
  Start-Sleep -Milliseconds 600
  & $global:ADB -s $global:DEV shell input text $Pw
  Start-Sleep -Milliseconds 1200
  $g=Cap "PW_${nm}_filled"
  $b=@(Find $g "Add server")[0]
  if(-not $b){ Write-Output "  no Add server on pwd dialog"; return $false }
  $bc=Ctr $b.bounds
  TapXY $bc[0] $bc[1] 2600
  Start-Sleep -Seconds 2
  return $true
}

function MakeEntry($nm){
  for($t=1;$t -le 2;$t++){
    $x=Cap "MK_${nm}_t${t}_pre"
    if((Find $x "Host or SSH command").Count -eq 0){
      if((Find $x "Add SSH server").Count -eq 0){ Write-Output "  not in panel t$t"; return $false }
      $a=@(Find $x "Add SSH server")[0]; $c=Ctr $a.bounds; TapXY $c[0] $c[1] 2000
      $x=Cap "MK_${nm}_t${t}_form"
    }
    $e=FilledEt $x
    if($e.Count -lt 2){ Write-Output "  no fields t$t"; return $false }
    $hc=Ctr $e[0].bounds; $nc=Ctr $e[1].bounds
    TapXY $hc[0] $hc[1] 700
    & $global:ADB -s $global:DEV shell input keyevent 123
    & $global:ADB -s $global:DEV shell $DEL
    Start-Sleep -Milliseconds 500
    & $global:ADB -s $global:DEV shell input text ($HostText -replace ' ','%s')
    Start-Sleep -Milliseconds 1000
    TapXY $nc[0] $nc[1] 700
    & $global:ADB -s $global:DEV shell input keyevent 123
    & $global:ADB -s $global:DEV shell $DEL
    Start-Sleep -Milliseconds 500
    & $global:ADB -s $global:DEV shell input text $nm
    Start-Sleep -Milliseconds 1100
    $g=Cap "MK_${nm}_t${t}_filled"
    $e2=FilledEt $g
    Write-Output "  FILL t$t host='$($e2[0].text)' name='$($e2[1].text)'"
    $b=@(Find $g "Add server")[0]
    if($b){ $bc=Ctr $b.bounds; TapXY $bc[0] $bc[1] 2600; Start-Sleep -Seconds 2 }
    $z=Cap "MK_${nm}_t${t}_post"
    if((Find $z "Enter the password for this connection.").Count -gt 0){
      Write-Output "  -> pwd dialog"
      if(FinishPwd $nm){ Write-Output "COMMITTED $nm"; return $true }
      continue
    }
    if((Find $z "Host or SSH command").Count -eq 0){ Write-Output "COMMITTED $nm (no pwd step)"; return $true }
  }
  return $false
}

foreach($nm in $Names){ if(MakeEntry $nm){ } else { Write-Output "FAILED $nm" } }
