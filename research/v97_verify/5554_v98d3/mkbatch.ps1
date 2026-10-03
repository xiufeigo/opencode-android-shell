# mkbatch.ps1 - create N entries, robustly. Re-opens the form from a verified Servers panel each time.
param([string[]]$Names,[string]$HostText="ssh -p 2222 sshtest@127.0.0.1",[string]$Suffix="")
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"

function EnsurePanel($tag){
  $x=Cap "${tag}_pre"
  if(InServers $x){
    # is the add-form already open? (it has the Host label)
    if((Find $x "Host or SSH command").Count -gt 0){ return $x }
    return $x
  }
  return $null
}

foreach($nm in $Names){
  $done=$false
  for($t=1;$t -le 3 -and -not $done;$t++){
    $x=Cap "MB_${nm}_t${t}_pre"
    if((Find $x "Host or SSH command").Count -gt 0){
      # form already open
    } elseif((Find $x "Add SSH server").Count -gt 0){
      $a=@(Find $x "Add SSH server")[0]
      $c=Ctr $a.bounds; TapXY $c[0] $c[1] 2000
      $x=Cap "MB_${nm}_t${t}_form"
    } else {
      Write-Output "SKIP $nm (t$t): not in panel"; break
    }
    # host field = the EditText whose text is empty and is directly under the 'Host or SSH command' label
    $lbl=@(Find $x "Host or SSH command")[0]
    if(-not $lbl){ Write-Output "SKIP $nm (t$t): no host label"; break }
    $ly=(BNum $lbl.bounds)[3]
    $ets=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" } |
           ForEach-Object { [pscustomobject]@{b=$_.bounds; y=(BNum $_.bounds)[1]; t=$_.text} } |
           Where-Object { $_.y -ge $ly } | Sort-Object y)
    $he=$ets[0]
    if(-not $he){ Write-Output "SKIP $nm (t$t): no host ET"; break }
    $hc=Ctr $he.b; TapXY $hc[0] $hc[1] 1300
    $ht=$HostText -replace ' ','%s'
    & $global:ADB -s $global:DEV shell input text $ht
    Start-Sleep -Milliseconds 1500
    & $global:ADB -s $global:DEV shell input keyevent 61
    Start-Sleep -Milliseconds 1000
    & $global:ADB -s $global:DEV shell input text $nm
    Start-Sleep -Milliseconds 1500
    $g=Cap "MB_${nm}_t${t}_filled"
    $etv=@($g.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" -and $_.text -ne "" })
    Write-Output "  FILL(t$t) $($etv | ForEach-Object { $_.text } | Join-String -Separator ' | ')"
    if((Find $g "Add server").Count -eq 0){ Write-Output "  SKIP $nm (t$t): no Add server btn"; continue }
    TapTxt $g "Add server" 0 2400
    Start-Sleep -Seconds 2
    $h=Cap "MB_${nm}_t${t}_done"
    if(@($h.SelectNodes("//node") | Where-Object { $_.text -like "*$nm*" }).Count -gt 0){
      Write-Output "CREATED $nm"; $done=$true
    } else { Write-Output "  VERIFY_MISS $nm (t$t)" }
  }
  if(-not $done){ Write-Output "FAILED $nm" }
}
