# v155 shared: retrying dump (null-root race right after launch) + geometry
$ErrorActionPreference = 'Continue'
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
$script:W  = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
$script:ART = "$W\artifacts_v155"
$script:D   = 'emulator-5554'
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

function adbx { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$a) & adb -s $script:D @a }
function AppPid { (adbx shell pidof com.xiufeigo.opencodeshell 2>&1 | Out-String).Trim() }

function Nodes {
  param([string]$xml)
  [xml]$d = Get-Content $xml -Raw -Encoding UTF8
  $res = @()
  foreach ($n in $d.SelectNodes('//node')) {
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
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

# RETRYING dump: the null-root race is real right after launch / after wm size
function Dump {
  param([string]$tag = 'dump', [int]$tries = 6)
  for ($i = 1; $i -le $tries; $i++) {
    $r = (adbx shell uiautomator dump /sdcard/__e2e.xml 2>&1 | Out-String).Trim()
    if ($r -match 'dumped to' -or $r -match 'UI hierchary') {
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
  Write-Output "[AssertIme] mInputShown=$s  ($why)"
  if (-not $s) { throw "ASSERT FAIL: mInputShown=false, refusing to read heights ($why)" }
  return $s
}

# SetDisp: every level gets BOTH wm density 160 and wm size, then settles
function SetDisp {
  param([string]$sz, [int]$settle = 3)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz      | Out-Null
  Start-Sleep -Seconds $settle
}

# GEOM: v145/v147 caliber. (i) = unionBottom - paneBottom, union EXCLUDES 'Open terminal'
# FORM:  one-row (unionPx ~ 48, Send shares the chip's row) vs stacked/full (unionPx ~ 96)
function Geo {
  param([string]$Tag)
  $ime = AssertIme "geom ($Tag)" | Select-Object -Last 1
  $x = Dump $Tag; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return [pscustomobject]@{ Tag=$Tag; Err='NO EditText' } }
  $kids = $ns | Where-Object {
    $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
    -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal')
  }
  if (-not $kids) { return [pscustomobject]@{ Tag=$Tag; Err='NO composer kids' } }
  $uy1=[int](($kids|Measure-Object y1 -Minimum).Minimum)
  $uy2=[int](($kids|Measure-Object y2 -Maximum).Maximum)
  $unionPx=$uy2-$uy1
  $hosts = $ns | Where-Object {
    $_.x1 -le 2 -and $_.x2 -ge 1364 -and $_.y1 -le $et.y1 -and $_.y2 -ge ($uy2 - 2) -and $_.h -lt 900 -and $_.h -ge 40
  }
  $pane = $hosts | Sort-Object { $_.h } | Select-Object -First 1
  if (-not $pane) { return [pscustomobject]@{ Tag=$Tag; Err='NO pane host' } }
  $paneH=$pane.h
  $readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
  $c1 = $uy2 - $pane.y2
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' -or $_.text -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' -or $_.text -eq 'Send' } | Select-Object -First 1
  # FORM discriminator: does Send share the chip's visual row?
  $form = 'UNKNOWN'
  $rowNote = ''
  if ($ot -and $sd) {
    if ($sd.y1 -le $ot.y2 + 2) { $form = 'ONEROW' } else { $form = 'STACKED' }
    $rowNote = "chipY=[$($ot.y1),$($ot.y2)] sendY=[$($sd.y1),$($sd.y2)]"
  }
  return [pscustomobject]@{
    Tag=$Tag; Win=$sz_now; PaneH=$paneH; TimelineBox=($paneH-48); Readable=$readable
    UnionPx=$unionPx; UnionBottom=$uy2; PaneBottom=$pane.y2; C1=$c1
    Form=$form; RowNote=$rowNote; Ime=$ime; Pid=(AppPid)
    Chip=$(if($ot){"[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) c=$($ot.clickable)"}else{'MISSING'})
    Send=$(if($sd){"[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) c=$($sd.clickable) e=$($sd.enabled)"}else{'MISSING'})
    Xml=$x
  }
}
