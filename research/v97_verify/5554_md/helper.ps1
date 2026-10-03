# helper for emulator-5554 verification (v132)
# dot-source: . $W\helper.ps1
$ErrorActionPreference = 'Stop'
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
$script:W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
$script:ART = "$W\artifacts"
$script:D = 'emulator-5554'

function adbx { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$a) & adb -s $script:D @a }

function Dump {
  # dumps UI hierarchy to a timestamped xml, returns path
  param([string]$tag = 'dump')
  $out = "$script:ART\$tag.xml"
  adbx shell uiautomator dump /sdcard/__e2e.xml | Out-Null
  adbx pull /sdcard/__e2e.xml $out | Out-Null
  return $out
}

function Shot {
  param([string]$tag = 'shot')
  $out = "$script:ART\$tag.png"
  adbx shell "screencap -p /sdcard/__e2e.png" | Out-Null
  adbx pull /sdcard/__e2e.png $out | Out-Null
  return $out
}

# Parse all nodes with bounds/text/desc/class/clickable
function Nodes {
  param([string]$xml)
  [xml]$d = Get-Content $xml -Raw -Encoding UTF8
  $res = @()
  foreach ($n in $d.SelectNodes('//node')) {
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
    $x1=[int]$Matches[1]; $y1=[int]$Matches[2]; $x2=[int]$Matches[3]; $y2=[int]$Matches[4]
    $res += [pscustomobject]@{
      text = $n.GetAttribute('text'); desc = $n.GetAttribute('content-desc')
      cls = $n.GetAttribute('class').Split('.')[-1]
      clickable = $n.GetAttribute('clickable'); scrollable=$n.GetAttribute('scrollable')
      focused=$n.GetAttribute('focused'); selected=$n.GetAttribute('selected')
      x1=$x1; y1=$y1; x2=$x2; y2=$y2; w=($x2-$x1); h=($y2-$y1)
      cx=[int](($x1+$x2)/2); cy=[int](($y1+$y2)/2)
    }
  }
  return $res
}

function Find1 {
  param($nodes, [string]$text, [string]$desc)
  foreach ($n in $nodes) {
    if ($text -and $n.text -eq $text) { return $n }
    if ($desc  -and $n.desc  -eq $desc)  { return $n }
  }
  return $null
}

function Tap {
  param($n)
  if ($null -eq $n) { throw "Tap: null node" }
  adbx shell input tap $n.cx $n.cy | Out-Null
}

function LongTap {
  param($n, [int]$ms = 1300)
  if ($null -eq $n) { throw "LongTap: null node" }
  adbx shell input swipe $n.cx $n.cy $n.cx $n.cy $ms | Out-Null
}

# IME / mInputShown assertion
function ImeState {
  $d = adbx shell dumpsys input_method 2>&1 | Out-String
  $shown = $false
  if ($d -match 'mInputShown=(true|false)') { $shown = ($Matches[1] -eq 'true') }
  return $shown
}
function AssertIme {
  param([string]$why)
  $s = ImeState
  Write-Output "[AssertIme] mInputShown=$s  ($why)"
  if (-not $s) { throw "ASSERT FAIL: mInputShown=false, refusing to read heights ($why)" }
  return $s
}

function AppPid {
  $p = (adbx shell pidof com.xiufeigo.opencodeshell 2>&1 | Out-String).Trim()
  return $p
}

function Lg {
  # logcat with -t (mandatory) filtered by app pid
  param([string]$tag='log', [int]$n=200, [string]$extra='')
  $pid = AppPid
  if (-not $pid) { Write-Output "[Lg] no app pid"; return }
  $f = "$script:ART\$tag.log"
  adbx logcat -t $n -v brief 2>&1 | Out-String |
    Select-String -Pattern "\s($pid)\s" | Out-String | Out-File -Encoding utf8 $f
  if ($extra) { Get-Content $f | Select-String -Pattern $extra }
  return $f
}

function Focus { adbx shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' }

# ---- a11y node text summary (for report quoting) ----
function ChipRow {
  param($nodes)
  $rows = @()
  foreach ($n in $nodes) {
    $lbl = if ($n.text) { $n.text } else { $n.desc }
    if ($lbl -and ($lbl -match '^(Queue|Steer|Stop|Remove|×|Edit|Done|Cancel|Send)$')) {
      $rows += $n
    }
  }
  return $rows
}
