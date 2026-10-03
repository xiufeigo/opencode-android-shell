param(
  [Parameter(Mandatory=$true)][string]$Text
)
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
# Split on spaces; type <=8 char chunks; use keyevent 62 for space (never %s, never input-text spaces)
$tokens = $Text -split ' '
$first = $true
foreach($t in $tokens){
  if(-not $first){ & $adb -s $S shell input keyevent 62 | Out-Null; Start-Sleep -Milliseconds 120 }
  $first = $false
  if($t -eq ""){ continue }
  for($i=0; $i -lt $t.Length; $i+=8){
    $len = [Math]::Min(8, $t.Length - $i)
    $chunk = $t.Substring($i,$len)
    & $adb -s $S shell input text $chunk | Out-Null
    Start-Sleep -Milliseconds 200
  }
}
"typed: $Text"
