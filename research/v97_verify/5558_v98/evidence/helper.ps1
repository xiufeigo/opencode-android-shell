# helper.ps1 — 5558 复验辅助函数
$ErrorActionPreference = 'Stop'
$ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S   = "emulator-5558"
$PKG = "com.xiufeigo.opencodeshell"
$EV  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_v98\evidence"

function Dump([string]$tag) {
  $rp = "/sdcard/window_dump_$tag.xml"
  & $ADB -s $S shell uiautomator dump $rp | Out-Null
  Start-Sleep -Milliseconds 300
  & $ADB -s $S pull $rp "$EV\$tag.xml" 2>&1 | Out-Null
  & $ADB -s $S shell rm -f $rp | Out-Null
  return "$EV\$tag.xml"
}

function Shot([string]$tag) {
  $rp = "/sdcard/scr_$tag.png"
  & $ADB -s $S shell screencap -p $rp | Out-Null
  Start-Sleep -Milliseconds 200
  & $ADB -s $S pull $rp "$EV\$tag.png" 2>&1 | Out-Null
  & $ADB -s $S shell rm -f $rp | Out-Null
  return "$EV\$tag.png"
}

# Signature: all node texts + content-desc + bounds + class (covers both quote styles)
function Sig([string]$path) {
  $x = Get-Content $path -Raw
  $m = [regex]::Matches($x, '<node[^>]*>')
  $lines = foreach ($n in $m) {
    $v = $n.Value
    $t = ''
    if ($v -match 'text="([^"]*)"') { $t = $Matches[1] } elseif ($v -match "text='([^']*)'") { $t = $Matches[1] }
    $d = ''
    if ($v -match 'content-desc="([^"]*)"') { $d = $Matches[1] } elseif ($v -match "content-desc='([^']*)'") { $d = $Matches[1] }
    $b = ''
    if ($v -match 'bounds="([^"]*)"') { $b = $Matches[1] } elseif ($v -match "bounds='([^']*)'") { $b = $Matches[1] }
    $c = ''
    if ($v -match 'class="([^"]*)"') { $c = $Matches[1] } elseif ($v -match "class='([^']*)'") { $c = $Matches[1] }
    "$c|$t|$d|$b"
  }
  return $lines
}

function Count([string]$path) { return (Sig $path).Count }

function Center([string]$bounds) {
  if ($bounds -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
  $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
  return @([int](($x1+$x2)/2), [int](($y1+$y2)/2))
}

# Print every node with a non-empty text or content-desc, index-tagged
function ListNodes([string]$path) {
  $x = Get-Content $path -Raw
  $i = 0
  foreach ($n in [regex]::Matches($x, '<node[^>]*>')) {
    $v = $n.Value
    $t = ''
    if ($v -match 'text="([^"]*)"') { $t = $Matches[1] } elseif ($v -match "text='([^']*)'") { $t = $Matches[1] }
    $d = ''
    if ($v -match 'content-desc="([^"]*)"') { $d = $Matches[1] } elseif ($v -match "content-desc='([^']*)'") { $d = $Matches[1] }
    $b = ''
    if ($v -match 'bounds="([^"]*)"') { $b = $Matches[1] } elseif ($v -match "bounds='([^']*)'") { $b = $Matches[1] }
    $c = ''
    if ($v -match 'class="([^"]*)"') { $c = $Matches[1] } elseif ($v -match "class='([^']*)'") { $c = $Matches[1] }
    $ck = ''
    if ($v -match 'clickable="([^"]*)"') { $ck = $Matches[1] } elseif ($v -match "clickable='([^']*)'") { $ck = $Matches[1] }
    $pk = ''
    if ($v -match 'package="([^"]*)"') { $pk = $Matches[1] } elseif ($v -match "package='([^']*)'") { $pk = $Matches[1] }
    if ($t -ne '' -or $d -ne '') {
      "[$i] $c | t='$t' | d='$d' | b=$b | click=$ck | pkg=$pk"
    }
    $i++
  }
}

function Tap([int]$x, [int]$y) { & $ADB -s $S shell input tap $x $y | Out-Null }
