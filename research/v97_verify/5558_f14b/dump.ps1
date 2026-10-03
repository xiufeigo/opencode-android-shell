param([string]$Tag="d", [switch]$Scrollable, [int]$SettleMs=900)
$ErrorActionPreference='Continue'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_f14b"
Start-Sleep -Milliseconds $SettleMs
& $adb -s emulator-5558 shell uiautomator dump /sdcard/w.xml 2>&1 | Out-Null
& $adb -s emulator-5558 pull /sdcard/w.xml "$dir\$Tag.xml" 2>&1 | Out-Null
[xml]$x = Get-Content "$dir\$Tag.xml" -Raw -Encoding UTF8
Write-Output "===== DUMP $Tag ====="
Write-Output "--- EditText nodes ---"
$i=0
foreach($n in $x.SelectNodes('//node')){
  if($n.class -eq 'android.widget.EditText'){
    $i++
    $mask=""
    if($n.password -eq 'true'){$mask="MASKED"}else{$mask="plain"}
    Write-Output ("[{0}] text='{1}' desc='{2}' bounds={3} focused={4} {5}" -f $i,$n.text,$n.'content-desc',$n.bounds,$n.focused,$mask)
  }
}
Write-Output "--- labels/text of interest ---"
foreach($n in $x.SelectNodes('//node')){
  $t="$($n.text)$($n.'content-desc')"
  if($t -match 'Authentication|Server name|Host or SSH|Private key|Password|Passphrase|SSH|Save|Add|Key'){
    Write-Output ("  {0} | '{1}' | '{2}' | {3} | clickable={4}" -f $n.class,$n.text,$n.'content-desc',$n.bounds,$n.clickable)
  }
}
if($Scrollable){
  Write-Output "--- scrollable nodes ---"
  foreach($n in $x.SelectNodes('//node')){
    if($n.scrollable -eq 'true'){ Write-Output ("  {0} bounds={1}" -f $n.class,$n.bounds) }
  }
}
