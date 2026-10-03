# mk3.ps1 - create entries. All field text cleared first; shell loop in a single-quoted var.
param([string[]]$Names,[string]$HostText="ssh -p 2222 sshtest@127.0.0.1")
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"

$DEL = 'i=0; while [ $i -lt 300 ]; do input keyevent 67; i=$((i+1)); done'

function FilledEt($x){
  return @($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge 1000 } |
           Sort-Object { (BNum $_.bounds)[1] })
}
function Wipe($cx,$cy){
  TapXY $cx $cy 800
  & $global:ADB -s $global:DEV shell input keyevent 123
  Start-Sleep -Milliseconds 200
  & $global:ADB -s $global:DEV shell $DEL
  Start-Sleep -Milliseconds 700
}

foreach($nm in $Names){
  $done=$false
  for($t=1;$t -le 2 -and -not $done;$t++){
    $x=Cap "M3_${nm}_t${t}_pre"
    if((Find $x "Host or SSH command").Count -eq 0){
      if((Find $x "Add SSH server").Count -eq 0){ Write-Output "SKIP $nm t$t not-in-panel"; break }
      $a=@(Find $x "Add SSH server")[0]; $c=Ctr $a.bounds; TapXY $c[0] $c[1] 2000
      $x=Cap "M3_${nm}_t${t}_form"
    }
    $e=FilledEt $x
    if($e.Count -lt 2){ Write-Output "SKIP $nm t$t no-fields"; break }
    $hc=Ctr $e[0].bounds; $nc=Ctr $e[1].bounds
    Wipe $hc[0] $hc[1]
    & $global:ADB -s $global:DEV shell input text ($HostText -replace ' ','%s')
    Start-Sleep -Milliseconds 1100
    Wipe $nc[0] $nc[1]
    & $global:ADB -s $global:DEV shell input text $nm
    Start-Sleep -Milliseconds 1300
    $g=Cap "M3_${nm}_t${t}_filled"
    $e2=FilledEt $g
    $h=$e2[0].text; $n2=$e2[1].text
    Write-Output "  FILL(t$t) host='$h' name='$n2'"
    if($h -ne $HostText -or $n2 -ne $nm){ Write-Output "  MISMATCH t$t"; continue }
    $btn=@(Find $g "Add server")[0]
    if(-not $btn){ Write-Output "  NO_BTN t$t"; continue }
    $bc=Ctr $btn.bounds
    Write-Output "  TAP_ADD_SERVER $($bc[0]),$($bc[1])"
    TapXY $bc[0] $bc[1] 2800
    Start-Sleep -Seconds 2
    $h2=Cap "M3_${nm}_t${t}_done"
    $fg=(Find $h2 "Host or SSH command").Count
    $rn=@($h2.SelectNodes("//node")|Where-Object{$_.text -eq $nm}).Count
    Write-Output "  SUBMIT(t$t) formGone=$([bool]($fg -eq 0)) exactNodes=$rn"
    if($fg -eq 0 -and $rn -gt 0){ Write-Output "CREATED $nm"; $done=$true }
  }
  if(-not $done){ Write-Output "FAILED $nm" }
}
