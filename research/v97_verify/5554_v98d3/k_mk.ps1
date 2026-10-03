$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"
function P(){ return Dump "N00" }
$x=P
Write-Output "IN_SERVERS=$((InServers $x))"
Write-Output "SCROLLNODES: $((@($x.SelectNodes('//node')|Where-Object{$_.scrollable -eq 'true'}|ForEach-Object{$_.class+' '+$_.bounds})) -join ' || ')"
$vp=PanelViewport $x
Write-Output "TASKA_PANEL_SCROLLABLE=$($vp -ne $null) VP=$vp"
Write-Output "ROWS_BEFORE(all names in panel body):"
$x.SelectNodes("//node")|Where-Object{ $t=if($_.text){$_.text}else{$_."content-desc"}; $t -and $t -match "^(E2ECHK|sshtest|10\.0\.2\.2|127\.0\.0\.1)" -and ([regex]::Matches($_.bounds,'-?\d+')|%{[int]$_.Value})[1] -ge 1100 }|ForEach-Object{ $t=if($_.text){$_.text}else{$_."content-desc"}; "   [$($_.bounds)] $t" }
Cap "A00_panel"
foreach($nm in $args){
  Write-Output "===== $nm ====="
  $x=P
  if(ConfirmBar $x){ TapTxt $x "Cancel" 0 1200|Out-Null; $x=P }
  if(AnyModal $x){ TapTxt $x "Cancel" 0 1200|Out-Null; $x=P }
  if(PwdDlg $x){
    $pe=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 700
      $h=Dump "B_${nm}_stale"; if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500|Out-Null } }
    $x=P }
  if(-not (InForm $x)){
    $a=@(Find $x "Add SSH server")[0]
    if(-not $a){ Write-Output "$nm NO_ADD_BTN"; break }
    $c=Ctr $a.bounds
    Write-Output "   tap Add SSH server at $c bounds=$($a.bounds)"
    TapXY $c[0] $c[1] 2200
    $x=Dump "B_${nm}_form"; Cap "B_${nm}_form"
    Write-Output "   after tap: form=$((InForm $x))"
  }
  if(-not (InForm $x)){ Write-Output "$nm FORM_NOT_OPEN"; break }
  $lbl=@(Find $x "Host or SSH command")[0]
  if(-not $lbl){ Write-Output "$nm NO_LABEL"; break }
  $ly=(BNum $lbl.bounds)[3]
  $he=@($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (BNum $_.bounds)[1] -ge $ly}|Sort-Object{(BNum $_.bounds)[1]})[0]
  if(-not $he){ Write-Output "$nm NO_HOST_ET"; break }
  $hc=Ctr $he.bounds; TapXY $hc[0] $hc[1] 800
  & $global:ADB -s $global:DEV shell input text "ssh%s-p%s2222%ssshtest@127.0.0.1"; Start-Sleep -Milliseconds 800
  & $global:ADB -s $global:DEV shell input keyevent 61; Start-Sleep -Milliseconds 700
  & $global:ADB -s $global:DEV shell input text $nm; Start-Sleep -Milliseconds 800
  $g=Dump "B_${nm}_filled"; Cap "B_${nm}_filled"
  $e=@($g.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -ne ""}|Sort-Object{(BNum $_.bounds)[1]})
  Write-Output "   FILL: $(($e|ForEach-Object{TxtOf $_}) -join ' ~ ')"
  foreach($er in @($g.SelectNodes("//node")|Where-Object{(TxtOf $_) -match "required|Enter the path|Invalid|must be|Please enter"})){
    Write-Output "   ERRTEXT [{0}] '{1}'" -f $er.bounds,(TxtOf $er) }
  if((Find $g "Add server").Count -eq 0){ Write-Output "$nm NO_SUBMIT_BTN"; break }
  TapTxt $g "Add server" 0 2500|Out-Null
  $z=Dump "B_${nm}_post1"; Cap "B_${nm}_post1"
  if(PwdDlg $z){
    $pe=@($z.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.EditText" -and (TxtOf $_) -eq "" -and (BNum $_.bounds)[1] -ge 900})[0]
    if($pe){ $c=Ctr $pe.bounds; TapXY $c[0] $c[1] 700
      & $global:ADB -s $global:DEV shell input text keypass123; Start-Sleep -Milliseconds 700
      $h=Dump "B_${nm}_pwf"; Cap "B_${nm}_pwf"
      if((Find $h "Add server").Count -gt 0){ TapTxt $h "Add server" 0 2500|Out-Null } else { Write-Output "   PWD_NO_BTN" } }
    else { Write-Output "   PWD_NO_ET" } }
  Start-Sleep -Seconds 2
  $x=P; Cap "B_${nm}_post2"
  Write-Output "   POST2 form=$((InForm $x)) pwd=$((PwdDlg $x)) modal=$((AnyModal $x))"
  $rows=@($x.SelectNodes("//node")|Where-Object{ $t=if($_.text){$_.text}else{$_."content-desc"}; $t -and ([regex]::Matches($_.bounds,'-?\d+')|%{[int]$_.Value})[1] -ge 1100 -and ($t -match "d3c|sshtest|E2ECHK|10\.0\.2\.2|127\.0\.0\.1") })
  Write-Output "   ROWS_AFTER = $($rows.Count)"
  foreach($r in $rows){ $t=if($r.text){$r.text}else{$r."content-desc"}; Write-Output "      [$($r.bounds)] $t" } }
$x=P; Cap "A01_final"
Write-Output "===== FINAL ====="
$x.SelectNodes("//node")|Where-Object{ $t=if($_.text){$_.text}else{$_."content-desc"}; $t -and ([regex]::Matches($_.bounds,'-?\d+')|%{[int]$_.Value})[1] -ge 1100 -and ($t -match "d3c|sshtest|E2ECHK|10\.0\.2\.2|127\.0\.0\.1") }|ForEach-Object{ $t=if($_.text){$_.text}else{$_."content-desc"}; "   [$($_.bounds)] $t" }
Write-Output "VIEWPORT=$(PanelViewport $x)"
