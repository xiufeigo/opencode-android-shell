# lib.ps1 - shared helpers for v98d3c coverage run
$ErrorActionPreference='Stop'
$global:ADB="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$global:DD="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$global:DEV="emulator-5554"
$global:SD="/sdcard/d3"

function Cap($tag){
  & $global:ADB -s $global:DEV shell "mkdir -p $global:SD" | Out-Null
  & $global:ADB -s $global:DEV shell "uiautomator dump $global:SD/$tag.xml" | Out-Null
  & $global:ADB -s $global:DEV pull "$global:SD/$tag.xml" "$global:DD\$tag.xml" 2>&1 | Out-Null
  & $global:ADB -s $global:DEV shell "screencap -p $global:SD/$tag.png" | Out-Null
  & $global:ADB -s $global:DEV pull "$global:SD/$tag.png" "$global:DD\$tag.png" 2>&1 | Out-Null
  return [xml](Get-Content "$global:DD\$tag.xml")
}
function Find($x,[string]$t){ return @($x.SelectNodes("//node") | Where-Object { $_.text -eq $t -or $_."content-desc" -eq $t }) }
function BNum($b){ return [regex]::Matches($b,'\d+')|%{[int]$_.Value} }
function Ctr($b){ $n=BNum $b; return @([int](($n[0]+$n[2])/2), [int](($n[1]+$n[3])/2)) }
function TapXY($cx,$cy,$ms=1300){ & $global:ADB -s $global:DEV shell input tap $cx $cy; Start-Sleep -Milliseconds $ms }
function TapTxt($x,[string]$t,[int]$i=0,$ms=1300){
  $h=(Find $x $t)[$i]
  if(-not $h){ return $false }
  $c=Ctr $h.bounds; TapXY $c[0] $c[1] $ms; return $true
}
function InServers($x){
  return ((Find $x "Add SSH server").Count -gt 0) -or ((Find $x "SSH tunnels").Count -gt 0)
}
# The panel body: the ScrollView that is NOT the tab strip (tab strip is short/one line)
function PanelViewport($x){
  $svs=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.ScrollView" -and $_.scrollable -eq "true" })
  foreach($s in $svs){
    $n=BNum $s.bounds
    if(($n[3]-$n[1]) -gt 600){ return $s.bounds }   # the tall one = panel body
  }
  return $null
}
# list SSH row names, in visible tree order, with bounds
function SshRows($x){
  return @($x.SelectNodes("//node") | Where-Object { $_.text -like "E2ECHK5554d3c*" } |
           Sort-Object { (BNum $_.bounds)[1] })
}
function GotoServers(){
  $x=Cap "G00"
  if(-not (InServers $x)){
    if((Find $x "Settings").Count -gt 0){ TapTxt $x "Settings" 0 2000 | Out-Null; $x=Cap "G01" }
  }
  for($i=0;$i -lt 6;$i++){
    if(InServers $x){ return $x }
    # scroll the tab strip (short ScrollView near the top) rightwards
    $tabstrip=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.ScrollView" -and $_.scrollable -eq "true" }) |
              Where-Object { ((BNum $_.bounds)[3]-(BNum $_.bounds)[1]) -lt 200 } | Select-Object -First 1
    if($tabstrip){
      $n=BNum $tabstrip.bounds
      $ty=[int](($n[1]+$n[3])/2)
      for($k=0;$k -lt 4;$k++){
        & $global:ADB -s $global:DEV shell input swipe 900 $ty 200 $ty 220
        Start-Sleep -Milliseconds 450
      }
    }
    $x=Cap "G02_$i"
    $sv=@(Find $x "Servers")
    if($sv.Count -gt 0){ TapTxt $x "Servers" 0 1600 | Out-Null; $x=Cap "G03_$i" }
  }
  return $x
}
