# c2lib.ps1 - fast helpers for the D3 coverage-2 run (dump-only by default)
$ErrorActionPreference='Stop'
$global:ADB="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$global:DD="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$global:DEV="emulator-5554"
$global:SD="/sdcard/d3c2"

function Dump($tag){
  & $global:ADB -s $global:DEV shell "mkdir -p $global:SD" | Out-Null
  & $global:ADB -s $global:DEV shell "uiautomator dump $global:SD/$tag.xml" | Out-Null
  & $global:ADB -s $global:DEV pull "$global:SD/$tag.xml" "$global:DD\$tag.xml" 2>&1 | Out-Null
  return [xml](Get-Content "$global:DD\$tag.xml" -Raw)
}
function Cap($tag){
  & $global:ADB -s $global:DEV shell "screencap -p $global:SD/$tag.png" | Out-Null
  & $global:ADB -s $global:DEV pull "$global:SD/$tag.png" "$global:DD\$tag.png" 2>&1 | Out-Null
}
# match both text="..." and text='...'  (uiautomator switches quoting between versions)
function TxtOf($n){
  $v = $n.GetAttribute("text")
  if($null -eq $v -or $v -eq ""){ $v = $n.GetAttribute("content-desc") }
  return $v
}
function Find($x,[string]$t){ return @($x.SelectNodes("//node") | Where-Object { (TxtOf $_) -eq $t }) }
function BNum($b){ return @([regex]::Matches($b,'-?\d+')|ForEach-Object{[int]$_.Value}) }
function Ctr($b){ $n=BNum $b; return @([int](($n[0]+$n[2])/2), [int](($n[1]+$n[3])/2)) }
function TapXY($cx,$cy,$ms=1300){ & $global:ADB -s $global:DEV shell input tap $cx $cy; Start-Sleep -Milliseconds $ms }
function TapTxt($x,[string]$t,[int]$i=0,$ms=1300){
  $h=(Find $x $t)[$i]; if(-not $h){ return $false }
  $c=Ctr $h.bounds; TapXY $c[0] $c[1] $ms; return $true
}
function InServers($x){ return ((Find $x "Add SSH server").Count -gt 0) -or ((Find $x "SSH tunnels").Count -gt 0) }
function InForm($x){ return ((Find $x "Host or SSH command").Count -gt 0) }
function PwdDlg($x){ return ((Find $x "Enter the password for this connection.").Count -gt 0) }
function AnyModal($x){
  return ((Find $x "Connection failed").Count -gt 0) -or ((Find $x "Could not establish the SSH connection. Check your network and SSH configuration.").Count -gt 0)
}
function ConfirmBar($x){ return ((Find $x "Delete SSH server?").Count -gt 0) }
function PanelViewport($x){
  foreach($s in @($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.ScrollView" -and $_.scrollable -eq "true" })){
    $n=BNum $s.bounds
    if(($n[3]-$n[1]) -gt 600){ return $s.bounds }
  }
  return $null
}
# every visible clickable row-menu (the 3-dot "Server actions") with its y
function Dots($x){ return @($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.Button" -and $_.clickable -eq "true" } | Sort-Object { (BNum $_.bounds)[1] }) }
# all SSH entry names visible in the panel, in y order (works for any prefix)
function SshRows($x,[string]$pfx="E2ECHK5554d3c"){
  return @($x.SelectNodes("//node") | Where-Object { (TxtOf $_) -like "$pfx*" } | Sort-Object { (BNum $_.bounds)[1] })
}
function RowNames($x,[string]$pfx="E2ECHK5554d3c"){
  $r=@(SshRows $x $pfx)
  $out=@()
  foreach($n in $r){ $out += ("{0}  [{1}]" -f (TxtOf $n), $n.bounds) }
  return $out
}
function GotoServers(){
  $x=Dump "G00"
  if(-not (InServers $x)){
    if((Find $x "Settings").Count -gt 0){ TapTxt $x "Settings" 0 2000 | Out-Null; $x=Dump "G01" }
  }
  for($i=0;$i -lt 6;$i++){
    if(InServers $x){ return $x }
    $ts=@($x.SelectNodes("//node") | Where-Object { $_.scrollable -eq "true" }) |
         Where-Object { ((BNum $_.bounds)[3]-(BNum $_.bounds)[1]) -lt 200 } | Select-Object -First 1
    if($ts){ $n=BNum $ts.bounds; $ty=[int](($n[1]+$n[3])/2)
      for($k=0;$k -lt 4;$k++){ & $global:ADB -s $global:DEV shell input swipe 900 $ty 200 $ty 220; Start-Sleep -Milliseconds 400 } }
    $x=Dump "G02_$i"
    if((Find $x "Servers").Count -gt 0){ TapTxt $x "Servers" 0 1600 | Out-Null; $x=Dump "G03_$i" }
  }
  return $x
}
# fast flick inside the panel body; only a fast flick (~100px/80ms) actually scrolls
function Flick($x,[int]$fromY,[int]$toY){
  $tx=540
  & $global:ADB -s $global:DEV shell input swipe $tx $fromY $tx $toY 80
  Start-Sleep -Milliseconds 1400
}
