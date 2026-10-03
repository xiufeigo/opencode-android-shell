# mk2.ps1 - create N entries. Clears both form fields before typing (garbage text broke validation).
param([string[]]$Names,[string]$HostText="ssh -p 2222 sshtest@127.0.0.1")
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"

function ClearField($cx,$cy,$max=260){
  TapXY $cx $cy 900
  & $global:ADB -s $global:DEV shell input keyevent 123          # MOVE_END
  Start-Sleep -Milliseconds 250
  # one adb session, many DELs - far faster than N adb round-trips
  # NOTE: single-quoted so PowerShell never interpolates $i / $(( ))
  $loop = 'i=0; while [ $i -lt ' + $max + ' ]; do input keyevent 67; i=$((i+1)); done'
  & $global:ADB -s $global:DEV shell $loop
  Start-Sleep -Milliseconds 900
}

foreach($nm in $Names){
  $done=$false
  for($t=1;$t -le 2 -and -not $done;$t++){
    $x=Cap "MC_${nm}_t${t}_pre"
    if((Find $x "Host or SSH command").Count -gt 0){
      # form already open
    } elseif((Find $x "Add SSH server").Count -gt 0){
      $a=@(Find $x "Add SSH server")[0]; $c=Ctr $a.bounds; TapXY $c[0] $c[1] 2000
      $x=Cap "MC_${nm}_t${t}_form"
    } else { Write-Output "SKIP $nm t$t (not in panel)"; break }

    $lbl=@(Find $x "Host or SSH command")[0]
    if(-not $lbl){ Write-Output "SKIP $nm t$t (no label)"; break }
    $ly=(BNum $lbl.bounds)[3]
    $ets=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" } |
           ForEach-Object { [pscustomobject]@{b=$_.bounds; y=(BNum $_.bounds)[1]} } |
           Where-Object { $_.y -ge $ly } | Sort-Object y)
    $he=$ets[0]; $ne=$ets[1]
    $hc=Ctr $he.b; $nc=Ctr $ne.b
    ClearField $hc[0] $hc[1]
    & $global:ADB -s $global:DEV shell input text ($HostText -replace ' ','%s')
    Start-Sleep -Milliseconds 1200
    ClearField $nc[0] $nc[1]
    & $global:ADB -s $global:DEV shell input text $nm
    Start-Sleep -Milliseconds 1200
    $g=Cap "MC_${nm}_t${t}_filled"
    $etv=@($g.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" -and $_.text -ne "" -and (BNum $_.bounds)[1] -ge 900 })
    Write-Output "  FILL(t$t): $(($etv|%{$_.text}) -join ' ~ ')"
    # sanity: the form must still be open and the values must be EXACT
    $stillForm=(Find $g "Host or SSH command").Count
    $okHost=@($etv|Where-Object{$_.text -eq $HostText}).Count -gt 0
    $okName=@($etv|Where-Object{$_.text -eq $nm}).Count -gt 0
    if($stillForm -eq 0){ Write-Output "  FORM_GONE t$t"; $done=$true; break }
    if(-not ($okHost -and $okName)){ Write-Output "  FIELD_MISMATCH t$t"; continue }
    TapTxt $g "Add server" 0 2600
    Start-Sleep -Seconds 2
    $h=Cap "MC_${nm}_t${t}_done"
    $formGone=(Find $h "Host or SSH command").Count -eq 0
    $rowIn=@($h.SelectNodes("//node")|Where-Object{$_.text -eq $nm}).Count
    Write-Output "  SUBMIT(t$t) formGone=$formGone exactRowNodes=$rowIn"
    if($formGone -and $rowIn -gt 0){ Write-Output "CREATED $nm"; $done=$true }
  }
  if(-not $done){ Write-Output "FAILED $nm" }
}
