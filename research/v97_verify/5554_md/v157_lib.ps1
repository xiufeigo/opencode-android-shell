# v157 shared lib. Key addition over v155: RAW ime frame readout (confound control).
$ErrorActionPreference = 'Continue'
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
$script:W   = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
$script:ART = "$W\artifacts_v157"
$script:D   = 'emulator-5554'
$script:PKG = 'com.xiufeigo.opencodeshell'
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

function adbx { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$a) & adb -s $script:D @a }
function AppPid { (adbx shell pidof $script:PKG 2>&1 | Out-String).Trim() }

function Nodes {
  param([string]$xml)
  [xml]$d = Get-Content $xml -Raw -Encoding UTF8
  $res = @()
  foreach ($n in $d.SelectNodes('//node')) {
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { continue }
    $res += [pscustomobject]@{
      text = $n.GetAttribute('text'); desc = $n.GetAttribute('content-desc')
      cls  = $n.GetAttribute('class').Split('.')[-1]
      clickable = $n.GetAttribute('clickable'); enabled = $n.GetAttribute('enabled')
      x1=[int]$Matches[1]; y1=[int]$Matches[2]; x2=[int]$Matches[3]; y2=[int]$Matches[4]
    }
  }
  foreach ($r in $res) {
    $r | Add-Member -NotePropertyName w -NotePropertyValue ($r.x2-$r.x1) -Force
    $r | Add-Member -NotePropertyName h -NotePropertyValue ($r.y2-$r.y1) -Force
    $r | Add-Member -NotePropertyName cx -NotePropertyValue ([int](($r.x1+$r.x2)/2)) -Force
    $r | Add-Member -NotePropertyName cy -NotePropertyValue ([int](($r.y1+$r.y2)/2)) -Force
  }
  return $res
}

function Dump {
  param([string]$tag = 'dump', [int]$tries = 6)
  for ($i = 1; $i -le $tries; $i++) {
    $r = (adbx shell uiautomator dump /sdcard/__e2e.xml 2>&1 | Out-String).Trim()
    if ($r -match 'dumped to' -or $r -match 'hierchary') {
      $out = "$script:ART\$tag.xml"
      adbx pull /sdcard/__e2e.xml $out 2>&1 | Out-Null
      if (Test-Path $out) { return $out }
    }
    Start-Sleep -Milliseconds 900
  }
  throw "Dump failed after $tries tries ($tag): $r"
}

function ImeState {
  $d = adbx shell dumpsys input_method 2>&1 | Out-String
  if ($d -match 'mInputShown=(true|false)') { return ($Matches[1] -eq 'true') }
  return $false
}
function AssertIme {
  param([string]$why)
  $s = ImeState
  if (-not $s) { throw "ASSERT FAIL: mInputShown=false ($why)" }
  return $s
}

function SetDisp {
  param([string]$sz, [int]$settle = 4)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz      | Out-Null
  Start-Sleep -Seconds $settle
}

# ---- v157 CONFOUND CONTROL: the RAW ime frame line, verbatim ----
# source: `dumpsys window` -> "InsetsSource id=3 type=ime frame=[0,342][1366,768] ..."
function ImeRaw {
  $d = adbx shell dumpsys window 2>&1 | Out-String
  $m = [regex]::Match($d, 'type=ime\s+frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
  if (-not $m.Success) {
    return [pscustomobject]@{ Raw='NOT_FOUND'; Top=$null; Bot=$null; H=$null; Left=$null; Right=$null }
  }
  $top=[int]$m.Groups[2].Value; $bot=[int]$m.Groups[4].Value
  $raw=$m.Value
  return [pscustomobject]@{ Raw=$raw; Top=$top; Bot=$bot; H=($bot-$top); Left=[int]$m.Groups[1].Value; Right=[int]$m.Groups[3].Value }
}

# composer form, dual discriminator (record BOTH; CONFLICT if they disagree)
function FormOf {
  param($ns)
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' -or $_.text -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' -or $_.text -eq 'Send' } | Select-Object -First 1
  if (-not $et) { return [pscustomobject]@{ Form='NO_ET'; ByWidth='NA'; ByRow='NA'; Agree='NA'; Et='NA'; Chip='NA'; Send='NA' } }
  $byW = if ($et.w -lt 800) { 'ONEROW' } else { 'STACKED' }
  $byRow = 'UNKNOWN'
  if ($ot -and $sd) { if ($sd.y1 -le $ot.y2 + 2) { $byRow = 'ONEROW' } else { $byRow = 'STACKED' } }
  $form = if ($byW -eq $byRow) { $byW } else { 'CONFLICT' }
  return [pscustomobject]@{
    Form=$form; ByWidth=$byW; ByRow=$byRow; Agree=$(if($byW -eq $byRow){'Y'}else{'N'})
    Et=$(if($et){"[$($et.x1),$($et.y1)][$($et.x2),$($et.y2)] $($et.w)x$($et.h) txt='$($et.text)'"}else{'NA'})
    Chip=$(if($ot){"[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h)"}else{'MISSING'})
    Send=$(if($sd){"[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h)"}else{'MISSING'})
  }
}

# re-parse coordinates every level (uiautomator bounds scale can jump 820<->1366)
function EnsureIme {
  param([string]$tag)
  if (ImeState) { return $true }
  $x = Dump "${tag}_rec"; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if ($et) { adbx shell input tap $et.cx $et.cy | Out-Null }
  Start-Sleep -Seconds 2
  return (ImeState)
}

function FillComposer {
  param([string]$text = 'v157monotonecheck')
  $x = Dump 'fill_pre'; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return 'NO_ET' }
  adbx shell input tap $et.cx $et.cy | Out-Null
  Start-Sleep -Seconds 2
  adbx shell input text $text | Out-Null
  Start-Sleep -Seconds 2
  $x2 = Dump 'fill_post'; $ns2 = Nodes $x2
  $et2 = $ns2 | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  return $(if($et2){"'$($et2.text)' $($et2.w)x$($et2.h)"}else{'NO_ET_AFTER'})
}

function Shot { param([string]$name)
  adbx shell "screencap -p /sdcard/__v157.png" | Out-Null
  adbx pull /sdcard/__v157.png "$script:ART\$name.png" 2>&1 | Out-Null
}
