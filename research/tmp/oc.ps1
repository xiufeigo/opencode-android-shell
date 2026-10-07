param(
  [Parameter(Mandatory=$true,Position=0)][string]$Cmd,
  [Parameter(ValueFromRemainingArguments=$true)][string[]]$Rest,
  [switch]$Shot,
  [string]$Tag = "shot"
)
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s="emulator-6790"
$T="D:\XiaoZhuTouTou\opencode-android-shell\research\tmp"
if($Rest.Count -gt 0){ Write-Host "ARGS: $($Rest -join ' | ')" }
switch ($Cmd) {
  "tap"   { & $adb -s $s shell input tap $Rest[0] $Rest[1] }
  "txt"   { & $adb -s $s shell input text $Rest[0] }
  "key"   { & $adb -s $s shell input keyevent $Rest[0] }
  "swipe" { & $adb -s $s shell input swipe $Rest[0] $Rest[1] $Rest[2] $Rest[3] $Rest[4] }
  "sh"    { & $adb -s $s shell ($Rest -join ' ') }
  "pull"  { & $adb -s $s exec-out screencap -p > "$T\$($Rest[0]).png"; "saved:$($Rest[0])" }
  "ls"    { Get-ChildItem "$T\*.png" | Sort-Object LastWriteTime -Descending | Select-Object -First 12 Name,LastWriteTime }
  "size"  { & $adb -s $s shell wm size }
  "focus" { & $adb -s $s shell dumpsys window | Select-String "mCurrentFocus" | Select-Object -First 1 }
  "logcat"{ & $adb -s $s logcat -d -s "$($Rest[0]):V" | Select-Object -Last 60 }
}
if ($Shot) { & $adb -s $s exec-out screencap -p > "$T\$Tag.png"; "shot:$Tag" }