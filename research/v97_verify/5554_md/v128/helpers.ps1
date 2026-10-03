# v128 helper: adb + dump + geometry parse.
$ErrorActionPreference = 'Stop'
$global:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$global:SERIAL = 'emulator-5554'
$global:PKG = 'com.xiufeigo.opencodeshell'
$global:OUT = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128'

function A { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args) & $global:ADB -s $global:SERIAL @Args }

function Dump([string]$Name) {
  $f = Join-Path $global:OUT $Name
  A shell uiautomator dump /sdcard/w.xml | Out-Null
  A pull /sdcard/w.xml $f | Out-Null
  A shell rm -f /sdcard/w.xml | Out-Null
  return $f
}

function Shot([string]$Name) {
  $f = Join-Path $global:OUT $Name
  A shell "screencap -p /sdcard/s.png" | Out-Null
  A pull /sdcard/s.png $f | Out-Null
  A shell rm -f /sdcard/s.png | Out-Null
  return $f
}

function GetAttr([string]$name, [string]$chunk) {
  $m = [regex]::Match($chunk, ($name + '="([^"]*)"'))
  if ($m.Success) { return $m.Groups[1].Value }
  $m2 = [regex]::Match($chunk, ($name + "='([^']*)'"))
  if ($m2.Success) { return $m2.Groups[1].Value }
  return ''
}

function Nodes([string]$File) {
  $xml = Get-Content $File -Raw
  $res = New-Object System.Collections.Generic.List[object]
  foreach ($chunk in ($xml -split '<node ')) {
    if ($chunk -notmatch 'bounds=') { continue }
    $b = [regex]::Match($chunk, 'bounds="?\[(\d+),(\d+)\]\[(\d+),(\d+)\]"?')
    if (-not $b.Success) { continue }
    $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
    $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
    $res.Add([pscustomobject]@{
      class     = (GetAttr 'class' $chunk)
      desc      = (GetAttr 'content-desc' $chunk)
      text      = (GetAttr 'text' $chunk)
      clickable = (GetAttr 'clickable' $chunk)
      scrollable= (GetAttr 'scrollable' $chunk)
      x1 = $x1; y1 = $y1; x2 = $x2; y2 = $y2
      wd = ($x2 - $x1); ht = ($y2 - $y1)
      cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2)
    })
  }
  return $res
}

function Focus { (A shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1).ToString().Trim() }
function AppPid { (A shell pidof $global:PKG).Trim() }
function ImeShown { (A shell dumpsys input_method | Select-String 'mInputShown' | Select-Object -First 1).ToString().Trim() }
function TapXY([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 900 }
function TapNode($N) { TapXY $N.cx $N.cy }
function TypeText([string]$t) {
  $esc = $t -replace ' ', '%s'
  A shell input text $esc | Out-Null
  Start-Sleep -Milliseconds 600
}

# clickable node whose desc or text equals the given label (runtime coordinate resolution)
function FindByLabel($Nodes_, [string]$label) {
  $Nodes_ | Where-Object {
    ($_.desc -eq $label -or $_.text -eq $label) -and $_.clickable -eq 'true'
  } | Select-Object -First 1
}
function FindAnyByLabel($Nodes_, [string]$label) {
  $Nodes_ | Where-Object { $_.desc -eq $label -or $_.text -eq $label } | Select-Object -First 1
}
