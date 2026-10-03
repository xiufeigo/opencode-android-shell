# c5lib.ps1  -- round 5 harness (ground-truth vs UI row count)
# Discipline: absolute adb path; 5554 only; screencap -p + pull (no exec-out);
# uiautomator dump --compressed; XML DOM GetAttribute (matches text="..." and text='...');
# coordinates ALWAYS parsed at runtime from the current dump.

$ErrorActionPreference = 'Stop'
$script:ADB  = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$script:SER  = 'emulator-5554'
$script:PKG  = 'com.xiufeigo.opencodeshell'
$script:RDIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$script:DEV  = '/sdcard/c5'

# NOTE: args MUST be passed as a quoted array (@('shell','mkdir','-p',...)).
# Bare tokens like -p would bind to PowerShell's -ProgressAction alias.
function A { param([string[]]$a)
  & $script:ADB -s $script:SER @a }

function Sh { param([string[]]$a)
  (& $script:ADB -s $script:SER shell @a) 2>&1 }

function IsANR {
  $f = (Sh @('dumpsys','window') | Select-String 'mCurrentFocus').ToString()
  if ($f -match 'Application Not Responding') { return $true }
  return $false
}

# ---- XML DOM node accessor: GetAttribute reads the parsed value, so it matches
#      text="..." and text='...' equally. No regex on the raw file.
function Attr($n, [string]$k) { $v = $n.GetAttribute($k); if ($null -eq $v) { return '' } return [string]$v }

function ParseBounds([string]$b) {
  # "[x1,y1][x2,y2]" -> object
  $m = [regex]::Match($b, '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
  if (-not $m.Success) { return $null }
  [pscustomobject]@{
    L = [int]$m.Groups[1].Value; T = [int]$m.Groups[2].Value
    R = [int]$m.Groups[3].Value; B = [int]$m.Groups[4].Value
    W = ([int]$m.Groups[3].Value - [int]$m.Groups[1].Value)
    H = ([int]$m.Groups[4].Value - [int]$m.Groups[2].Value)
  }
}

function AllNodes($doc) {
  $out = New-Object System.Collections.ArrayList
  foreach ($n in $doc.SelectNodes('//node')) { [void]$out.Add($n) }
  return $out
}

function NText($n)  { return (Attr $n 'text') }
function NCls($n)   { return (Attr $n 'class') }
function NDesc($n)  { return (Attr $n 'content-desc') }
function NCtr($n)   { return (Attr $n 'content-desc') }
function NEn($n)    { return (Attr $n 'enabled') }
function NClk($n)   { return (Attr $n 'clickable') }
function NScr($n)   { return (Attr $n 'scrollable') }
function NBnd($n)   { return (ParseBounds (Attr $n 'bounds')) }

# ---- capture: dump --compressed on device, then adb pull
function Dump([string]$tag) {
  A @('shell','mkdir','-p',$script:DEV) | Out-Null
  Sh @('uiautomator','dump','--compressed','/sdcard/c5/d.xml') | Out-Null
  $local = Join-Path $script:RDIR ("c5_{0}.xml" -f $tag)
  A @('pull','/sdcard/c5/d.xml',$local) | Out-Null
  [xml]$doc = Get-Content $local -Raw
  return $doc
}

function Cap([string]$tag) {
  Sh @('screencap','-p','/sdcard/c5/s.png') | Out-Null
  $local = Join-Path $script:RDIR ("c5_{0}.png" -f $tag)
  A @('pull','/sdcard/c5/s.png',$local) | Out-Null
  return $local
}

# ---- find nodes whose text OR content-desc equals $label
function FindByLabel($doc, [string]$label) {
  $r = New-Object System.Collections.ArrayList
  foreach ($n in (AllNodes $doc)) {
    if ((NText $n) -eq $label -or (NDesc $n) -eq $label) { [void]$r.Add($n) }
  }
  return @($r)
}
# find whose text CONTAINS $sub (exact-substring, not regex)
function FindTextContains($doc, [string]$sub) {
  $r = New-Object System.Collections.ArrayList
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n
    if ($t -and $t.Contains($sub)) { [void]$r.Add($n) }
  }
  return @($r)
}

# ---- ROW-LEVEL anchors: kebab = content-desc 'Server actions' (one per row, both WS rows and SSH rows)
function KebabList($doc) {
  $r = New-Object System.Collections.ArrayList
  foreach ($n in (AllNodes $doc)) {
    $d = NDesc $n
    if ($d -and $d.Contains('Server actions')) {
      $b = NBnd $n
      [void]$r.Add([pscustomobject]@{ Node=$n; Bnd=$b; CX=[int](($b.L+$b.R)/2); CY=[int](($b.T+$b.B)/2) })
    }
  }
  return @($r)
}

# ---- row title for a given kebab: the nearest text node with left edge in the row-indent band
#      (x1 in [195..220]) sitting above the kebab and not a group heading (group headings are x1=148)
function RowTitleFor($doc, $keb) {
  $best = $null; $bestD = 1e9
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n
    if (-not $t) { continue }
    $b = NBnd $n
    if ($null -eq $b) { continue }
    if ($b.L -lt 195 -or $b.L -gt 220) { continue }
    if ($b.B -gt $keb.Bnd.T + 5) { continue }          # must be above the kebab
    $d = $keb.Bnd.T - $b.B
    if ($d -lt 0) { continue }
    if ($d -lt $bestD) { $bestD = $d; $best = $n }
  }
  if ($null -eq $best) { return '' }
  return (NText $best)
}

# ---- group headings: left edge ~148, short text
function GroupHeadings($doc) {
  $r = New-Object System.Collections.ArrayList
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n
    if (-not $t) { continue }
    $b = NBnd $n
    if ($null -eq $b) { continue }
    if ($b.L -ge 140 -and $b.L -le 160 -and $b.W -gt 60 -and $b.W -lt 500) { [void]$r.Add($t) }
  }
  return @($r)
}

function ScrollableBounds($doc) {
  $r = New-Object System.Collections.ArrayList
  foreach ($n in (AllNodes $doc)) {
    if ((NScr $n) -eq 'true') { $b = NBnd $n; if ($b) { [void]$r.Add($b) } }
  }
  return @($r)
}

function TapXY([int]$x, [int]$y) { A @('shell','input','tap',"$x","$y") | Out-Null; Start-Sleep -Milliseconds 700 }
function TypeS([string]$s) { $e = $s -replace ' ','%s'; A @('shell','input','text',$e) | Out-Null; Start-Sleep -Milliseconds 400 }
function KeyEv([int]$k) { A @('shell','input','keyevent',"$k") | Out-Null; Start-Sleep -Milliseconds 300 }

# fast scroll: 100px / 80ms, landing point inside the list body
function FastScroll([int]$x, [int]$yFrom, [int]$yTo) {
  A @('shell','input','swipe',"$x","$yFrom","$x","$yTo",'80') | Out-Null
  Start-Sleep -Milliseconds 260
}

# pick a swipe y inside the list body that avoids EditText bands and the keyboard zone
function PickSwipeY($doc) {
  $ets = New-Object System.Collections.ArrayList
  foreach ($n in (AllNodes $doc)) { if ((NCls $n) -eq 'android.widget.EditText') { $b = NBnd $n; if ($b -and $b.H -gt 20) { [void]$ets.Add($b) } } }
  $cands = @(1200, 1300, 1100, 1400, 1000, 1500)
  foreach ($c in $cands) {
    $ok = $true
    foreach ($b in $ets) { if ($c -ge ($b.T - 60) -and $c -le ($b.B + 60)) { $ok = $false; break } }
    if ($ok) { return $c }
  }
  return 1200
}

# ---- GROUND TRUTH: read the encrypted prefs XML, return data-key count + key set
function GroundTruth {
  $raw = (Sh @('run-as',$script:PKG,'cat','shared_prefs/oc_server_config.xml')) -join "`n"
  $names = [regex]::Matches($raw, '<string name="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
  $data = @($names | Where-Object { $_ -notlike '__androidx*' })
  return [pscustomobject]@{ Total = @($names).Count; Data = $data.Count; Keys = $data }
}

function WriteLog([string]$name, [string]$body) {
  $p = Join-Path $script:RDIR $name
  Add-Content -Path $p -Value $body -Encoding UTF8
  Write-Output "### $body"
}
