# mkentry.ps1 - create ONE SSH entry via the Add SSH server form.
# Robust: re-dumps before every tap; coordinates always parsed at runtime.
# Uses TAB (keyevent 61) to move between fields so IME geometry changes don't matter.
param([string]$Name,[string]$HostText,[int]$Retry=3)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"

for($try=1;$try -le $Retry;$try++){
  $x=Cap "MK_${Name}_t${try}_pre"
  if(-not (InServers $x)){
    Write-Output "NOT_IN_SERVERS (try $try)"; Start-Sleep -Seconds 1; continue
  }
  if((Find $x "Add SSH server").Count -eq 0){ Write-Output "NO_ADD_BTN (try $try)"; continue }
  if(TapTxt $x "Add SSH server" 0 1600){
    $f=Cap "MK_${Name}_t${try}_form"
    # the form's first EditText = Host or SSH command
    $ets=@($f.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" } |
           Sort-Object { (BNum $_.bounds)[1] })
    # pick the one inside the form area (below the panel header, y>1400 in form coords)
    $host=null
    foreach($e in $ets){ if((BNum $e.bounds)[1] -gt 1000){ $host=$e; break } }
    if(-not $host){ Write-Output "NO_HOST_FIELD (try $try)"; continue }
    $hc=Ctr $host.bounds
    TapXY $hc[0] $hc[1] 1200
    # escape spaces for `input text`; '/' and '@' are fine
    $ht=$HostText -replace ' ','%s'
    & $global:ADB -s $global:DEV shell input text $ht
    Start-Sleep -Milliseconds 1400
    # TAB to the name field, then type the entry name
    & $global:ADB -s $global:DEV shell input keyevent 61
    Start-Sleep -Milliseconds 900
    & $global:ADB -s $global:DEV shell input text $Name
    Start-Sleep -Milliseconds 1200
    $g=Cap "MK_${Name}_t${try}_filled"
    if((Find $g "Add server").Count -eq 0){
      Write-Output "NO_ADD_SERVER_BTN (try $try)"; continue
    }
    TapTxt $g "Add server" 0 2200
    Start-Sleep -Seconds 2
    $h=Cap "MK_${Name}_t${try}_done"
    $found=@($h.SelectNodes("//node") | Where-Object { $_.text -like "*$Name*" })
    if($found.Count -gt 0){
      Write-Output "CREATED $Name (visible=$($found.Count)) first='$($found[0].text)' $($found[0].bounds)"
      return $true
    } else {
      Write-Output "VERIFY_MISS $Name (try $try) - row not in tree yet"
    }
  }
}
Write-Output "FAILED $Name"
return $false
