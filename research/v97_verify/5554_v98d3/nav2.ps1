# nav2.ps1 - open Settings panel and verify we are really in Servers (SSH tunnels visible)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$pkg="com.xiufeigo.opencodeshell"
$global:ADB=$adb; $global:DD=$d

function Cap($tag){
  $sd="/sdcard/d3"
  & $adb -s emulator-5554 shell "mkdir -p $sd" | Out-Null
  & $adb -s emulator-5554 shell "uiautomator dump $sd/$tag.xml" | Out-Null
  & $adb -s emulator-5554 pull "$sd/$tag.xml" "$d\$tag.xml" 2>&1 | Out-Null
  & $adb -s emulator-5554 shell "screencap -p $sd/$tag.png" | Out-Null
  & $adb -s emulator-5554 pull "$sd/$tag.png" "$d\$tag.png" 2>&1 | Out-Null
  return [xml](Get-Content "$d\$tag.xml")
}
function Find($x,[string]$t){ return @($x.SelectNodes("//node") | Where-Object { $_.text -eq $t -or $_."content-desc" -eq $t }) }
function TapTxt($x,[string]$t,[int]$i=0){
  $h=(Find $x $t)[$i]
  if(-not $h){ Write-Output "MISS '$t'"; return $false }
  $n=[regex]::Matches($h.bounds,'\d+')|%{[int]$_.Value}
  $cx=[int](($n[0]+$n[2])/2); $cy=[int](($n[1]+$n[3])/2)
  & $adb -s emulator-5554 shell input tap $cx $cy
  Write-Output "TAP '$t' [$($h.bounds)] -> $cx,$cy"
  Start-Sleep -Milliseconds 1400
  return $true
}
# is the Servers panel really open? (has the SSH section header / Add SSH server entry)
function InServers($x){
  return ((Find $x "Add SSH server").Count -gt 0) -or ((Find $x "SSH tunnels").Count -gt 0)
}

& $adb -s emulator-5554 shell "monkey -p $pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
Start-Sleep -Seconds 4
$x=Cap "C00_home"

if(-not (InServers $x)){
  if((Find $x "Settings").Count -eq 0){ Write-Output "FATAL: no Settings node"; exit 2 }
  TapTxt $x "Settings" | Out-Null
  Start-Sleep -Seconds 2
  $x=Cap "C01_settings"
}
# tap Servers tab until the panel really shows the SSH section
for($i=0;$i -lt 3;$i++){
  if(InServers $x){ break }
  $sv=@($x.SelectNodes("//node") | Where-Object { $_.text -eq "Servers" -or $_."content-desc" -eq "Servers" })
  if($sv.Count -eq 0){ Write-Output "FATAL: no Servers tab"; exit 3 }
  TapTxt $x "Servers" 0 | Out-Null
  $x=Cap "C02_srv$($i+1)"
}
if(-not (InServers $x)){ Write-Output "FATAL: Servers panel did not open"; exit 4 }
$x=Cap "C03_servers"
Write-Output "=== IN SERVERS PANEL ==="
$sv=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.ScrollView" -and $_.scrollable -eq "true" })
foreach($s in $sv){ Write-Output "SCROLLVIEW $($s.bounds)" }
(Find $x "SSH tunnels") | ForEach-Object { Write-Output "SSH_TUNNELS_HDR $($_.bounds)" }
(Find $x "Add SSH server") | ForEach-Object { Write-Output "ADD_SSH $($_.bounds)" }
