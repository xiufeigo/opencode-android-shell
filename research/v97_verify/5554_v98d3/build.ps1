# build.ps1 - finish the pending Password dialog, then create the remaining entries.
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"
$DEL  = 'i=0; while [ $i -lt 300 ]; do input keyevent 67; i=$((i+1)); done'   # single-quoted: no PS interpolation
$HOSTTXT = "ssh -p 2222 sshtest@127.0.0.1"
$PW = "keypass123"

function PwdDialogOpen($x){ return (Find $x "Enter the password for this connection.").Count -gt 0 }
function FormOpen($x){ return (Find $x "Host or SSH command").Count -gt 0 }

function WipeAt($cx,$cy){
  TapXY $cx $cy 700
  & $global:ADB -s $global:DEV shell input keyevent 123
  Start-Sleep -Milliseconds 150
  & $global:ADB -s $global:DEV shell $DEL
  Start-Sleep -Milliseconds 400
}
function FilledEt($x){
  return @($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge 1000}|Sort-Object{(BNum $_.bounds)[1]})
}
function CommitPwd(){
  $x=Cap "B_pwd_pre"
  if(-not (PwdDialogOpen $x)){ return $true }
  $ets=FilledEt $x
  $pe=$ets | Where-Object { $_.text -eq "" } | Select-Object -First 1
  if(-not $pe){ $pe=$ets[-1] }
  $pc=Ctr $pe.bounds
  WipeAt $pc[0] $pc[1]
  & $global:ADB -s $global:DEV shell input text $PW
  Start-Sleep -Milliseconds 1000
  $g=Cap "B_pwd_filled"
  $b=@(Find $g "Add server")[0]
  if(-not $b){ Write-Output "  pwd: no Add server"; return $false }
  $bc=Ctr $b.bounds
  TapXY $bc[0] $bc[1] 2600
  Start-Sleep -Seconds 3
  $h=Cap "B_pwd_done"
  Write-Output "  pwd committed -> form=$(FormOpen $h) pwd=$(PwdDialogOpen $h)"
  return $true
}
function NewEntry($nm){
  for($t=1;$t -le 2;$t++){
    $x=Cap "B_${nm}_t${t}_pre"
    if(PwdDialogOpen $x){ CommitPwd | Out-Null; $x=Cap "B_${nm}_t${t}_pre2" }
    if(-not (FormOpen $x)){
      if((Find $x "Add SSH server").Count -eq 0){ Write-Output "  ${nm}: not in panel t$t"; return $false }
      $a=@(Find $x "Add SSH server")[0]; $c=Ctr $a.bounds; TapXY $c[0] $c[1] 2000
      $x=Cap "B_${nm}_t${t}_form"
    }
    $e=FilledEt $x
    if($e.Count -lt 2){ Write-Output "  ${nm}: no fields t$t"; return $false }
    $hc=Ctr $e[0].bounds; $nc=Ctr $e[1].bounds
    WipeAt $hc[0] $hc[1]
    & $global:ADB -s $global:DEV shell input text ($HOSTTXT -replace ' ','%s')
    Start-Sleep -Milliseconds 900
    WipeAt $nc[0] $nc[1]
    & $global:ADB -s $global:DEV shell input text $nm
    Start-Sleep -Milliseconds 1000
    $g=Cap "B_${nm}_t${t}_filled"
    $e2=FilledEt $g
    $b=@(Find $g "Add server")[0]
    if(-not $b){ Write-Output "  ${nm}: no btn t$t"; continue }
    $bc=Ctr $b.bounds
    TapXY $bc[0] $bc[1] 2600
    Start-Sleep -Seconds 2
    $z=Cap "B_${nm}_t${t}_post"
    if(PwdDialogOpen $z){ Write-Output "  ${nm}: pwd step"; CommitPwd | Out-Null }
    $z2=Cap "B_${nm}_t${t}_final"
    if(-not (FormOpen $z2) -and -not (PwdDialogOpen $z2)){ Write-Output "COMMITTED $nm"; return $true }
  }
  return $false
}

CommitPwd | Out-Null
foreach($nm in @("E2ECHK5554d3c-02","E2ECHK5554d3c-03","E2ECHK5554d3c-04")){
  if(NewEntry $nm){ } else { Write-Output "FAILED $nm" }
}
$x=Cap "B_rows"
Write-Output "=== FINAL d3c nodes ==="
@($x.SelectNodes("//node")|Where-Object{$_.text -like "*E2ECHK5554d3c*"})|ForEach-Object{"  '$($_.text)' $($_.bounds)"}

