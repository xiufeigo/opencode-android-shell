param([Parameter(Mandatory=$true)][string]$Text)
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$tokens = $Text -split ' '
$first = $true
foreach($t in $tokens){
  if(-not $first){ & $adb -s $S shell input keyevent 62 | Out-Null; Start-Sleep -Milliseconds 120 }
  $first = $false
  if($t -eq ""){ continue }
  for($i=0; $i -lt $t.Length; $i+=8){
    $len = [Math]::Min(8, $t.Length - $i)
    & $adb -s $S shell input text $t.Substring($i,$len) | Out-Null
    Start-Sleep -Milliseconds 200
  }
}
"typed: $Text"
