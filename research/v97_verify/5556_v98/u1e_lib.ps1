# U1E typing / measuring helpers — emulator-5556 only
# Discipline: no gradle, no install, no kill (except am force-stop own app), never BACK(4),
# never ESC(111) in overlays, spaces via keyevent 62, NEVER %s, <=8 chars per segment.

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$global:ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$global:DEV = "emulator-5556"
$global:PKG = "com.xiufeigo.opencodeshell"
$global:ART = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1e"

function A { param([Parameter(ValueFromRemainingArguments=$true)]$Rest) & $global:ADB -s $global:DEV @Rest }
function Sh { param([string]$Cmd) & $global:ADB -s $global:DEV shell $Cmd 2>$null }

function Get-Focus {
  $f = & $global:ADB -s $global:DEV shell dumpsys window 2>$null | Select-String -Pattern 'mCurrentFocus' | Select-Object -First 1
  if ($f) { return ($f.ToString() -replace '.*mCurrentFocus=','').Trim() } else { return "NO_FOCUS" }
}
function Get-InputShown {
  $f = & $global:ADB -s $global:DEV shell dumpsys input_method 2>$null | Select-String -Pattern 'mInputShown' | Select-Object -First 1
  if ($f) { return ($f.ToString() -replace '.*mInputShown=','').Trim() } else { return "NO_LINE" }
}
function Get-Pid {
  $p = (& $global:ADB -s $global:DEV shell pidof $global:PKG 2>$null | Out-String).Trim()
  if ($p) { return $p } else { return "EMPTY" }
}
function Assert-Focus {
  $f = Get-Focus
  if ($f -match 'com\.xiufeigo\.opencodeshell') { Write-Output "[FOCUS-OK] $f"; return $true }
  Write-Output "[FOCUS-BAD] $f"; return $false
}

function Parse-Nodes {
  param([string]$Xml)
  $res = New-Object System.Collections.ArrayList
  foreach ($nc in [regex]::Matches($Xml, '<node[^>]*>')) {
    $s = $nc.Value
    $t=$null;$d=$null;$c=$null;$clk=$null
    $mb = [regex]::Match($s, 'bounds="\[(?<x1>-?\d+),(?<y1>-?\d+)\]\[(?<x2>-?\d+),(?<y2>-?\d+)\]"')
    if (-not $mb.Success) { continue }
    $mt = [regex]::Match($s, 'text=(?:"(?<v>[^"]*)"|''(?<v>[^'']*)'')')
    if ($mt.Success) { $t = $mt.Groups['v'].Value }
    $md = [regex]::Match($s, 'content-desc=(?:"(?<v>[^"]*)"|''(?<v>[^'']*)'')')
    if ($md.Success) { $d = $md.Groups['v'].Value }
    $mc = [regex]::Match($s, 'class=(?:"(?<v>[^"]*)"|''(?<v>[^'']*)'')')
    if ($mc.Success) { $c = $mc.Groups['v'].Value }
    $mk = [regex]::Match($s, 'clickable="(?<v>true|false)"')
    if ($mk.Success) { $clk = ($mk.Groups['v'].Value -eq 'true') }
    [void]$res.Add([pscustomobject]@{
      Text=$t; Desc=$d; Class=$c
      X1=[int]$mb.Groups['x1'].Value; Y1=[int]$mb.Groups['y1'].Value
      X2=[int]$mb.Groups['x2'].Value; Y2=[int]$mb.Groups['y2'].Value
      Clickable=$clk; Raw=$s
    })
  }
  return $res
}

function Dump-Ui {
  param([string]$Name)
  if (-not (Test-Path $global:ART)) { New-Item -ItemType Directory -Force -Path $global:ART | Out-Null }
  $remote = "/sdcard/$Name.xml"
  & $global:ADB -s $global:DEV shell uiautomator dump --compressed $remote 2>&1 | Out-Null
  $local = Join-Path $global:ART "$Name.xml"
  & $global:ADB -s $global:DEV pull $remote $local 2>&1 | Out-Null
  & $global:ADB -s $global:DEV shell rm -f $remote 2>&1 | Out-Null
  return $local
}
function Shot {
  param([string]$Name)
  if (-not (Test-Path $global:ART)) { New-Item -ItemType Directory -Force -Path $global:ART | Out-Null }
  $remote = "/sdcard/$Name.png"
  & $global:ADB -s $global:DEV shell screencap -p $remote 2>&1 | Out-Null
  $local = Join-Path $global:ART "$Name.png"
  & $global:ADB -s $global:DEV pull $remote $local 2>&1 | Out-Null
  & $global:ADB -s $global:DEV shell rm -f $remote 2>&1 | Out-Null
  return $local
}
# label = Text if present else Desc
function Label($n) { if ($n.Text -and $n.Text.Trim()) { return $n.Text } elseif ($n.Desc) { return $n.Desc } else { return "" } }

function Tap-Desc {
  # tap by content-desc (exact), coords resolved at runtime from dump
  param([string]$Desc, [int]$Index = 0)
  $f = Dump-Ui ("u1e_tap_tmp")
  $nodes = Parse-Nodes (Get-Content $f -Raw)
  $hits = @($nodes | Where-Object { $_.Desc -eq $Desc })
  if ($hits.Count -le $Index) { Write-Output "NOTFOUND desc='$Desc' (hits=$($hits.Count))"; return $null }
  $n = $hits[$Index]
  $x = [int](($n.X1+$n.X2)/2); $y = [int](($n.Y1+$n.Y2)/2)
  & $global:ADB -s $global:DEV shell input tap $x $y 2>&1 | Out-Null
  Write-Output "TAP $x,$y desc='$Desc'"
  Start-Sleep -Milliseconds 600
  return $x,$y
}
function Tap-Text {
  param([string]$Text, [int]$Index = 0)
  $f = Dump-Ui ("u1e_tap_tmp")
  $nodes = Parse-Nodes (Get-Content $f -Raw)
  $hits = @($nodes | Where-Object { $_.Text -eq $Text })
  if ($hits.Count -le $Index) { Write-Output "NOTFOUND text='$Text' (hits=$($hits.Count))"; return $null }
  $n = $hits[$Index]
  $x = [int](($n.X1+$n.X2)/2); $y = [int](($n.Y1+$n.Y2)/2)
  & $global:ADB -s $global:DEV shell input tap $x $y 2>&1 | Out-Null
  Write-Output "TAP $x,$y text='$Text'"
  Start-Sleep -Milliseconds 600
  return $x,$y
}
# Type text with discipline: <=8 char segments, space = keyevent 62, never %s
function Type-Text {
  param([string]$Text)
  $words = $Text -split ' '
  for ($i=0; $i -lt $words.Count; $i++) {
    $w = $words[$i]
    if ($i -gt 0) { & $global:ADB -s $global:DEV shell input keyevent 62 2>&1 | Out-Null }
    if ($w.Length -eq 0) { continue }
    for ($j=0; $j -lt $w.Length; $j += 8) {
      $seg = $w.Substring($j, [Math]::Min(8, $w.Length-$j))
      $esc = $seg -replace '([&;<>|()$`\\"''])','\$1'
      & $global:ADB -s $global:DEV shell input text $esc 2>&1 | Out-Null
      Start-Sleep -Milliseconds 120
    }
  }
  Write-Output "TYPED: $Text"
}
# Find EditText and tap it (to raise IME)
function Tap-EditText {
  $f = Dump-Ui ("u1e_tap_tmp")
  $nodes = Parse-Nodes (Get-Content $f -Raw)
  $et = @($nodes | Where-Object { $_.Class -eq 'android.widget.EditText' })
  if ($et.Count -eq 0) { Write-Output "NO_EDITTEXT"; return $null }
  $n = $et[0]
  $x = [int](($n.X1+$n.X2)/2); $y = [int](($n.Y1+$n.Y2)/2)
  & $global:ADB -s $global:DEV shell input tap $x $y 2>&1 | Out-Null
  Write-Output "TAP $x,$y EditText (h=$($n.Y2-$n.Y1))"
  Start-Sleep -Milliseconds 800
  return $x,$y
}
# Wait until mInputShown=true, up to N tries
function Wait-Ime {
  param([int]$Tries = 8, [int]$SleepMs = 900)
  for ($i=1; $i -le $Tries; $i++) {
    $s = Get-InputShown
    Write-Output "  ime[$i]=$s"
    if ($s -eq 'true') { return $true }
    Start-Sleep -Milliseconds $SleepMs
  }
  return $false
}
