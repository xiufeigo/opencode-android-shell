param([string]$Name)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$sh="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"

function Get-Nodes($tag){
  & "$d\cap.ps1" -Tag $tag | Out-Null
  $x=[xml](Get-Content "$d\$tag.xml"); return $x
}
function TapAt($tag,[string]$text,[int]$idx=0){
  $x=Get-Nodes $tag
  $h=@($x.SelectNodes("//node") | Where-Object { $_.text -eq $text -or $_."content-desc" -eq $text })[$idx]
  if(-not $h){ Write-Output "MISS '$text'"; return $null }
  $n=[regex]::Matches($h.bounds,'\d+')|%{[int]$_.Value}
  $cx=[int](($n[0]+$n[2])/2); $cy=[int](($n[1]+$n[3])/2)
  & $adb -s emulator-5554 shell input tap $cx $cy
  Write-Output "TAP '$text' $cx,$cy"
  Start-Sleep -Milliseconds 900
  return @($cx,$cy)
}

# 1. host field: bottom-most EditTexts in the form area; host is the upper of the last two
$x=Get-Nodes "a_$Name"
$ets=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" } |
        ForEach-Object { $n=[regex]::Matches($_.bounds,'\d+')|%{[int]$_.Value}; [pscustomobject]@{b=$_.bounds; y=$n[1]; y2=$n[3]; x1=$n[0]; x2=$n[2]} } |
        Where-Object { $_.y1 -eq $null -or $_.y -gt 1300 } | Sort-Object y)
$hostEt = $ets[0]
$hn=[regex]::Matches($hostEt.b,'\d+')|%{[int]$_.Value}
$hx=[int](($hn[0]+$hn[2])/2); $hy=[int](($hn[1]+$hn[3])/2)
& $adb -s emulator-5554 shell input tap $hx $hy
Start-Sleep -Milliseconds 800
& $adb -s emulator-5554 shell input text $Name
Start-Sleep -Milliseconds 1200
Write-Output "typed host '$Name' at $hx,$hy"

# 2. name field = next EditText below host
$x=Get-Nodes "b_$Name"
$ets2=@($x.SelectNodes("//node") | Where-Object { $_.class -eq "android.widget.EditText" } |
        ForEach-Object { $n=[regex]::Matches($_.bounds,'\d+')|%{[int]$_.Value}; [pscustomobject]@{b=$_.bounds; y=$n[1]; y2=$n[3]; x1=$n[0]; x2=$n[2]} } |
        Where-Object { $_.y -gt 1300 } | Sort-Object y)
$nameEt=$null
$hostY=$hn[1]
foreach($e in $ets2){ if($e.y -gt $hostY){ $nameEt=$e; break } }
if($nameEt){
  $nn=[regex]::Matches($nameEt.b,'\d+')|%{[int]$_.Value}
  $nx=[int](($nn[0]+$nn[2])/2); $ny=[int](($nn[1]+$nn[3])/2)
  & $adb -s emulator-5554 shell input tap $nx $ny
  Start-Sleep -Milliseconds 800
  & $adb -s emulator-5554 shell input text $Name
  Start-Sleep -Milliseconds 1000
  Write-Output "typed name '$Name' at $nx,$ny"
}
# 3. Add server (no ESC/keyevent — discipline forbids ESC in overlays)
TapAt "c_$Name" "Add server" | Out-Null
Start-Sleep -Seconds 2
Write-Output "ADDED $Name"
