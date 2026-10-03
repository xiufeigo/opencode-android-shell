# u1h helper (v104 re-verify): dump + pull + parse, focus gate, reading helpers
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5556"
$pkg = "com.xiufeigo.opencodeshell"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h"
$PFX = "u1h_"
New-Item -ItemType Directory -Force -Path $ev | Out-Null

function A { & $adb -s $dev @args }

function Get-Focus {
  (A shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1).Line
}
function Get-InputShown {
  $l = (A shell dumpsys input_method | Select-String 'mInputShown' | Select-Object -First 1)
  if ($l) { $l.Line.Trim() } else { "mInputShown=<not found>" }
}
function Get-ImeFrame {
  $l = (A shell dumpsys input_method | Select-String 'mFrame' | Select-Object -First 1)
  if ($l) { $l.Line.Trim() } else { "mFrame=<not found>" }
}
function Get-Pidof { (A shell pidof $pkg) }

function Assert-Focus([string]$where) {
  $f = Get-Focus
  if ($f -match 'opencodeshell') { Write-Output "FOCUS-OK[$where]: $f" ; return $true }
  Write-Output "FOCUS-FAIL[$where]: $f"
  return $false
}

function Dump([string]$tag) {
  $remote = "/sdcard/$PFX$tag.xml"
  A shell "rm -f $remote" | Out-Null
  A shell "uiautomator dump --compressed $remote" 2>&1 | Out-Null
  A pull $remote "$ev\$PFX$tag.xml" 2>&1 | Out-Null
  A shell "rm -f $remote" | Out-Null
  $p = "$ev\$PFX$tag.xml"
  if (Test-Path $p) { return $p } else { return $null }
}

function Shot([string]$tag) {
  $remote = "/sdcard/$PFX$tag.png"
  A shell "rm -f $remote" | Out-Null
  A shell "screencap -p $remote" 2>&1 | Out-Null
  A pull $remote "$ev\$PFX$tag.png" 2>&1 | Out-Null
  A shell "rm -f $remote" | Out-Null
}

# parse: match text="..." AND text='...'
function Get-Nodes([string]$path) {
  if (-not (Test-Path $path)) { return @() }
  $raw = [IO.File]::ReadAllText($path)
  $out = New-Object System.Collections.Generic.List[object]
  $rx = [regex]'<node[^>]*>'
  foreach ($m in $rx.Matches($raw)) {
    $s = $m.Value
    $o = [ordered]@{}
    foreach ($k in 'text','content-desc','resource-id','class','bounds','clickable','scrollable','enabled','focused','package','index') {
      $mm = [regex]::Match($s, ($k + '="([^"]*)"'))
      if (-not $mm.Success) { $mm = [regex]::Match($s, ($k + "='([^']*)'")) }
      $o[$k] = if ($mm.Success) { $mm.Groups[1].Value } else { '' }
    }
    $out.Add([pscustomobject]$o)
  }
  return $out
}

# bounds parse -> object with L,T,R,B,W,H,Cx,Cy
function Bnd([string]$b) {
  if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
  $l=[int]$Matches[1]; $t=[int]$Matches[2]; $r=[int]$Matches[3]; $bo=[int]$Matches[4]
  [pscustomobject]@{ L=$l; T=$t; R=$r; B=$bo; W=($r-$l); H=($bo-$t); Cx=[int](($l+$r)/2); Cy=[int](($t+$bo)/2) }
}

# pane = first View with T>100 and W>1000  (per U1G 3.1 caliber)
function Get-Pane($nodes) {
  foreach ($n in $nodes) {
    if ($n.class -match 'View|RecyclerView|LinearLayout|FrameLayout' ) {
      $b = Bnd $n.bounds
      if ($b -and $b.T -gt 100 -and $b.W -gt 1000) { return [pscustomobject]@{ node=$n; b=$b } }
    }
  }
  return $null
}
