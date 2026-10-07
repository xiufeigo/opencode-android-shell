$ErrorActionPreference = 'Continue'
$script:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$script:DEV = 'emulator-6790'
$script:PKG = 'com.xiufeigo.opencodeshell.web.debug'
$script:ACT = "$($script:PKG)/com.xiufeigo.opencodeshell.web.WebHostActivity"
$script:SHOTDIR = 'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux16p3\shots'
$script:TMP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux16p3'
$script:SCALE = 1080.0 / 411.0
New-Item -ItemType Directory -Force -Path $script:SHOTDIR | Out-Null

function Sh { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$c) & $script:ADB -s $script:DEV shell @c 2>&1 }
function Shot {
  param([string]$name)
  $p = Join-Path $script:SHOTDIR "$name.png"
  & $script:ADB -s $script:DEV exec-out screencap -p > $p
  Write-Output "SHOT $name bytes=$((Get-Item $p).Length)"
}
function Cdp {
  param([string[]]$ArgList)
  $a = @("$script:TMP\cli.py") + $ArgList
  & python @a
}
function Js { param([Parameter(ValueFromRemainingArguments=$true)][string]$c,[int]$w=1800)
  $b64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($c))
  & $script:ADB -s $script:DEV shell am start --activity-single-top -n $script:ACT --es oc_debug_eval "b64:$b64" | Out-Null
  Start-Sleep -Milliseconds $w }
function LogHit { param([string]$pat)
  (& $script:ADB -s $script:DEV logcat -d -v time 2>&1 | Out-String) -split "`n" | Where-Object { $_ -match $pat } }
function ClearLog { & $script:ADB -s $script:DEV logcat -c }
function TapDev { param([double]$cx,[double]$cy)
  $dx=[int][Math]::Round($cx*$script:SCALE); $dy=[int][Math]::Round($cy*$script:SCALE)
  & $script:ADB -s $script:DEV shell input tap $dx $dy | Out-Null }
# click by visible text via CDP -> device tap
function ClickText {
  param([string]$t)
  $r = (Cdp @('click', $t)) | ConvertFrom-Json
  if ($r.ok) { TapDev $r.cx $r.cy; Write-Output "CLICK '$t' css=($($r.cx),$($r.cy)) txt=$($r.txt)" }
  else { Write-Output "CLICK-MISS '$t' $($r.why)" }
  return $r
}
function ClickCss {
  param([string]$sel,[int]$n=0)
  $r = (Cdp @('clickcss', $sel, "$n")) | ConvertFrom-Json
  if ($r.ok) { TapDev $r.cx $r.cy; Write-Output "CLICKCSS $sel#$n css=($($r.cx),$($r.cy)) txt=$($r.txt)" }
  else { Write-Output "CLICKCSS-MISS $sel#$n $($r.why)" }
  return $r
}
function SetInput {
  param([string]$sel,[string]$val,[int]$n=0)
  (Cdp @('setinput', $sel, $val, "$n"))
}
function CdList { Cdp @('list') }
function CdEval { param([string]$file,[int]$to=30) Cdp @('eval', $file, "$to") }
function TypeText { param([string]$t) & $script:ADB -s $script:DEV shell input text $t | Out-Null }
function Key { param([int]$k) & $script:ADB -s $script:DEV shell input keyevent $k | Out-Null }
function Home { & $script:ADB -s $script:DEV shell input keyevent 3 | Out-Null }