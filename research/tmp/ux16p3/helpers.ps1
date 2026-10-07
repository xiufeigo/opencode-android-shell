$ErrorActionPreference = 'Continue'
$script:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$script:DEV = 'emulator-6790'
$script:PKG = 'com.xiufeigo.opencodeshell.web.debug'
$script:ACT = "$($script:PKG)/com.xiufeigo.opencodeshell.web.WebHostActivity"
$script:SHOTDIR = 'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux16p3\shots'
New-Item -ItemType Directory -Force -Path $script:SHOTDIR | Out-Null

function Sh {
  param([Parameter(ValueFromRemainingArguments=$true)][string[]]$c)
  $out = & $script:ADB -s $script:DEV shell @c 2>&1
  return $out
}
function Shot {
  param([string]$name)
  $p = Join-Path $script:SHOTDIR "$name.png"
  & $script:ADB -s $script:DEV exec-out screencap -p > $p
  Write-Output "SHOT $name bytes=$((Get-Item $p).Length)"
}
function ClearLog { & $script:ADB -s $script:DEV logcat -c }
function GrepLog {
  param([string]$pat = '.*')
  $out = & $script:ADB -s $script:DEV logcat -d -v time 2>&1 | Out-String
  ($out -split "`n" | Where-Object { $_ -match $pat })
}
function ClearTag { param([string]$tag) & $script:ADB -s $script:DEV logcat -c -s $tag }

# inject JS through the debug eval channel (base64, synchronous am start)
function Js {
  param([Parameter(ValueFromRemainingArguments=$true)][string]$code, [int]$waitMs = 1800)
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($code))
  & $script:ADB -s $script:DEV shell am start --activity-single-top -n $script:ACT --es oc_debug_eval "b64:$b64" | Out-Null
  Start-Sleep -Milliseconds $waitMs
}
function JsEval {
  param([Parameter(ValueFromRemainingArguments=$true)][string]$code, [int]$waitMs = 1800)
  & $script:ADB -s $script:DEV logcat -c -s 'OcWebHost/Eval:*' ':S' | Out-Null
  Js $code $waitMs
  & $script:ADB -s $script:DEV logcat -d -v brief -s 'OcWebHost/Eval:*' ':S' 2>&1 | Out-String
}
function DumpUi {
  & $script:ADB -s $script:DEV shell uiautomator dump /sdcard/ui.xml | Out-Null
  $raw = & $script:ADB -s $script:DEV exec-out cat /sdcard/ui.xml 2>$null
  if (-not $raw) { $raw = & $script:ADB -s $script:DEV shell cat /sdcard/ui.xml 2>$null }
  ($raw -join '')
}
function UiTexts {
  param([string]$xml)
  if (-not $xml) { return '(no xml)' }
  $m = [regex]::Matches($xml, '(?:text|content-desc)="([^"]+)"')
  $res = New-Object System.Collections.Generic.List[string]
  foreach ($x in $m) { $t = $x.Groups[1].Value; if ($t -and $t.Trim()) { $res.Add($t) } }
  ($res -join ' ~ ')
}
function Tap { param([int]$x,[int]$y) & $script:ADB -s $script:DEV shell input tap $x $y | Out-Null }
function Swipe { param([int]$x1,[int]$y1,[int]$x2,[int]$y2,[int]$ms=300) & $script:ADB -s $script:DEV shell input swipe $x1 $y1 $x2 $y2 $ms | Out-Null }
function TypeText { param([string]$t) & $script:ADB -s $script:DEV shell input text $t | Out-Null }
function Key { param([int]$k) & $script:ADB -s $script:DEV shell input keyevent $k | Out-Null }