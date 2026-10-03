# nav.ps1 - launch app, get to Settings > Servers. No BACK(4), no ESC(111).
param([switch]$Force)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$pkg="com.xiufeigo.opencodeshell"

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
  Start-Sleep -Milliseconds 1200
  return $true
}

if($Force){ & $adb -s emulator-5554 shell "am force-stop $pkg"; Start-Sleep -Seconds 1 }
& $adb -s emulator-5554 shell "monkey -p $pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
Start-Sleep -Seconds 4
$x=Cap "C00_home"
if((Find $x "Settings").Count -eq 0){ $x=Cap "C00b_home" }
if((Find $x "Settings").Count -gt 0){ TapTxt $x "Settings" | Out-Null; Start-Sleep -Seconds 1; $x=Cap "C01_settings" }
if((Find $x "Servers").Count -gt 0){ TapTxt $x "Servers" | Out-Null; $x=Cap "C02_servers" }
$x=Cap "C02b_servers"

$sv=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.ScrollView" -and $_.scrollable -eq "true" })
foreach($s in $sv){ Write-Output "SCROLLVIEW $($s.bounds)" }
$rows=@($x.SelectNodes("//node") | Where-Object { $_.text -like "E2ECHK*" -or $_.text -eq "chk123" })
Write-Output "ROWS_VISIBLE=$($rows.Count)"
$rows | ForEach-Object { Write-Output "  ROW '$($_.text)' $($_.bounds)" }
Write-Output "HAS_ADDSERVER_BTN=$((Find $x 'Add SSH server').Count)"
