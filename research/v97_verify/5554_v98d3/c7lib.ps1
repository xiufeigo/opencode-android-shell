# c7lib.ps1 — shared helpers for coverage-7 run (emulator-5554 only)
$ErrorActionPreference = 'Continue'
$env:ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:DEV = "emulator-5554"
$W    = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$env:LOG = "$W\D00_run.log"
$env:SDK = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

function A { param([string[]]$a) & $env:SDK @("-s",$env:DEV) @a 2>&1 }
function Log { param([string]$m) $s = "[$(Get-Date -Format 'HH:mm:ss')] $m"; $s | Tee-Object -FilePath $env:LOG -Append }

# dump UI, return text of xml (compressed preferred). $tag -> /sdcard/_$tag.xml
function Dump {
  param([string]$tag)
  A @("shell","uiautomator","dump","--compressed","/sdcard/_$tag.xml") | Out-Null
  A @("pull","/sdcard/_$tag.xml","$W\_$tag.xml") | Out-Null
  A @("shell","rm","-f","/sdcard/_$tag.xml") | Out-Null
  return (Get-Content "$W\_$tag.xml" -Raw -Encoding UTF8)
}

# parse all nodes -> PSCustomObject list
function Nodes {
  param([string]$xml)
  $out = @()
  foreach ($m in [regex]::Matches($xml, '<node\b[^>]*>')) {
    $n = $m.Value
    $o = [ordered]@{}
    foreach ($k in @('text','content-desc','resource-id','class','package','bounds','clickable','focused','enabled','scrollable','password','visible-to-user','selected','checkable','checked','long-clickable')) {
      $re = if ($k -eq 'class') { [regex]::new('\bclass\s*=\s*(?:"([^"]*)"|''([^'']*)'')') }
             else { [regex]::new("\b$k\s*=\s*(?:`"([^`"]*)`"|'([^'']*)')") }
      $mm = $re.Match($n)
      $o[$k] = if ($mm.Success) { if ($mm.Groups[1].Success) { $mm.Groups[1].Value } else { $mm.Groups[2].Value } } else { '' }
    }
    $o['raw'] = $n
    $out += [pscustomobject]$o
  }
  return $out
}

# get center x,y of first node whose (text|content-desc) equals/contains $t
function FindNode {
  param([string]$xml, [string]$t, [switch]$Exact)
  $n = Nodes $xml
  foreach ($x in $n) {
    $a = $x.text; $b = $x.'content-desc'
    $hit = if ($Exact) { ($a -eq $t) -or ($b -eq $t) } else { ($a -like "*$t*") -or ($b -like "*$t*") }
    if ($hit) { return $x }
  }
  return $null
}
function Center { param($node) $b = [regex]::Match($node.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
  @{ x=[int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value)/2); y=[int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value)/2);
     l=[int]$b.Groups[1].Value; t=[int]$b.Groups[2].Value; r=[int]$b.Groups[3].Value; bt=[int]$b.Groups[4].Value } }
function Tap { param($c) A @("shell","input","tap","$($c.x)","$($c.y)") | Out-Null }
function TapAt { param([int]$x,[int]$y) A @("shell","input","tap","$x","$y") | Out-Null }

function ImeOff { A @("shell","dumpsys","input_method") | Out-String | Select-String -Pattern "mInputShown\s*=\s*(\w+)" | ForEach-Object { $_.Matches[0].Groups[1].Value } }
function Focus { A @("shell","dumpsys","window") | Out-String | Select-String -Pattern "mCurrentFocus=(.+)" | ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() } }

function Shot { param([string]$name) A @("shell","screencap","-p","/sdcard/$name.png") | Out-Null; A @("pull","/sdcard/$name.png","$W\$name") | Out-Null; A @("shell","rm","-f","/sdcard/$name.png") | Out-Null }

# self-built tunnel rows: content-desc/text of the row name label, plus state text under it
function Rows { param([string]$xml)
  $n = Nodes $xml
  $labels = @()
  foreach ($x in $n) {
    $v = if ($x.text -and $x.text -ne '') { $x.text } else { $x.'content-desc' }
    if ($v -match '^E2ECHK5554d3d-\d+$' -or $v -match '^E2ECHK5554d3d-') { $labels += $x }
  }
  return $labels
}
# read prefs file (tunnel table value length + file size + md5)
function Prefs {
  $o = A @("shell","run-as","com.xiufeigo.opencodeshell","cat","shared_prefs/oc_server_config.xml")
  $txt = ($o | Out-String)
  $p = "$W\_prefs_snapshot.txt"
  Set-Content -Path $p -Value $txt -Encoding UTF8
  $size = (Get-Item $p).Length
  $md5 = (Get-FileHash $p -Algorithm MD5).Hash
  $keys = [regex]::Matches($txt,'<string name="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
  return @{ text=$txt; size=$size; md5=$md5; keys=$keys }
}
function Swipe { param([int]$x1,[int]$y1,[int]$x2,[int]$y2,[int]$ms=400) A @("shell","input","swipe","$x1","$y1","$x2","$y2","$ms") | Out-Null; Start-Sleep -Milliseconds 900 }
