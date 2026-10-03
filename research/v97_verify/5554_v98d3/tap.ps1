param([string]$Text,[int]$Idx=0,[switch]$NoTap,[string]$Tag="")
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
if($Tag -eq ""){ $Tag="_probe" }
& "$d\cap.ps1" -Tag $Tag | Out-Null
$x=[xml](Get-Content "$d\$Tag.xml")
$hits=@($x.SelectNodes("//node") | Where-Object { $_.text -eq $Text -or $_."content-desc" -eq $Text })
if($hits.Count -eq 0){ Write-Output "MISS '$Text'"; exit 2 }
$h=$hits[$Idx]
$nums = [regex]::Matches($h.bounds,'\d+') | ForEach-Object { [int]$_.Value }
$cx=[int](($nums[0]+$nums[2])/2); $cy=[int](($nums[1]+$nums[3])/2)
Write-Output "HIT[$Idx] '$Text' bounds=$($h.bounds) center=$cx,$cy total=$($hits.Count)"
if(-not $NoTap){ & $adb -s emulator-5554 shell input tap $cx $cy; Start-Sleep -Seconds 2 }
