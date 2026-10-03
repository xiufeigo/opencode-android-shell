param(
  [string]$LongText = "E2ECHK5556q-06_LONGTURN_Count_slowly_from_1_to_30_and_after_each_number_write_a_long_detailed_sentence_explaining_your_reasoning",
  [string]$QueueText = "E2ECHK5556q-07_CANCELME_QUEUEPROBE",
  [string]$Tag = "qa_011_queuepanel"
)
$ErrorActionPreference='Continue'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev="emulator-5556"
$out="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function Dump([string]$name) {
  & $adb -s $dev shell uiautomator dump --compressed /sdcard/$name.xml | Out-Null
  & $adb -s $dev pull /sdcard/$name.xml "$out\$name.xml" 2>&1 | Out-Null
  & $adb -s $dev shell rm /sdcard/$name.xml
  return "$out\$name.xml"
}
function FindCtr([string]$file, [string]$token) {
  [xml]$x = Get-Content -Raw -Encoding UTF8 $file
  foreach ($n in $x.SelectNodes("//node")) {
    $t = $n.GetAttribute("text"); $d = $n.GetAttribute("content-desc")
    if ($t -eq $token -or $d -eq $token) {
      $b = $n.GetAttribute("bounds")
      if ($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
        $bx1=[int]$Matches[1]; $by1=[int]$Matches[2]
        $bx2=[int]$Matches[3]; $by2=[int]$Matches[4]
        return @([int](($bx1+$bx2)/2), [int](($by1+$by2)/2), $b)
      }
    }
  }
  return $null
}

# 1) send the long turn
$send = FindCtr (Dump "tmp_pre") "Send"
"### Send ctr=$($send[0]),$($send[1])"
& $adb -s $dev shell input tap $send[0] $send[1]
Start-Sleep -Seconds 2

# 2) type long text
$ed = FindCtr (Dump "tmp_ed") "Ask anything, / for commands, @ for context..."
& $adb -s $dev shell input tap $ed[0] $ed[1]
Start-Sleep -Milliseconds 700
& $adb -s $dev shell input text $LongText
Start-Sleep -Milliseconds 900

# 3) runtime-find and tap the Queue chip
$d = Dump "tmp_chip"
$chip = FindCtr $d "Queue"
"### Queue chip bounds=$($chip[2]) ctr=$($chip[0]),$($chip[1])"
if ($chip -eq $null) { "!!! NO CHIP"; return }
& $adb -s $dev shell input tap $chip[0] $chip[1]
Start-Sleep -Milliseconds 900

# 4) type the item to actually enqueue
$ed2 = FindCtr (Dump "tmp_ed2") "Ask anything, / for commands, @ for context..."
& $adb -s $dev shell input tap $ed2[0] $ed2[1]
Start-Sleep -Milliseconds 600
& $adb -s $dev shell input text $QueueText
Start-Sleep -Milliseconds 700

# 5) send it -> should land in queue
$s2 = FindCtr (Dump "tmp_send2") "Send"
"### Send#2 ctr=$($s2[0]),$($s2[1])"
& $adb -s $dev shell input tap $s2[0] $s2[1]
Start-Sleep -Seconds 2

# 6) final dump + shot
$f = Dump $Tag
& $adb -s $dev shell screencap -p /sdcard/$Tag.png
& $adb -s $dev pull /sdcard/$Tag.png "$out\$Tag.png" 2>&1 | Out-Null
& $adb -s $dev shell rm /sdcard/$Tag.png
"### RESULT: $f"
foreach ($t in @("Queued","Steer","Remove","Send","CANCELME_QUEUEPROBE","Working")) {
  $c = FindCtr $f $t
  if ($c) { "  HIT  $t -> bounds=$($c[2]) ctr=$($c[0]),$($c[1])" } else { "  MISS $t" }
}
