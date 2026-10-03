param(
  [Parameter(Mandatory=$true)][string]$Text,
  [int]$Enter = 1
)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
# space => keyevent 62 (NEVER %s); <=8 char chunks
$tokens = $Text -split ' '
$first=$true
foreach($t in $tokens){
  if(-not $first){ & $adb -s $S shell input keyevent 62 | Out-Null; Start-Sleep -Milliseconds 150 }
  $first=$false
  if($t -eq ""){ continue }
  for($i=0;$i -lt $t.Length;$i+=8){
    $len=[Math]::Min(8,$t.Length-$i)
    & $adb -s $S shell input text $t.Substring($i,$len) | Out-Null
    Start-Sleep -Milliseconds 220
  }
}
if($Enter -eq 1){ & $adb -s $S shell input keyevent 66 | Out-Null }
"typed: $Text"
