. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1
$ErrorActionPreference='Continue'

function GTTable {
  $raw = (Sh @('run-as',$script:PKG,'cat','shared_prefs/oc_server_config.xml')) -join "`n"
  $ms = [regex]::Matches($raw, '<string name="([^"]+)">([^<]*)</string>')
  $rows = @()
  foreach ($m in $ms) { $rows += [pscustomobject]@{ Name=$m.Groups[1].Value; ValLen=$m.Groups[2].Value.Length } }
  return $rows
}
function GTPrint($tag) {
  $g = GTTable
  $d = @($g | Where-Object { $_.Name -notlike '__androidx*' })
  "[$tag] GT_DATA_KEYS={0}  sig={1}" -f $d.Count, (($d | ForEach-Object { "$($_.Name.Substring(0,8)):$($_.ValLen)" }) -join ' ')
  return $d.Count
}
# UI row count over one scroll traversal of the panel body
function UITraverse([string]$tag, [int]$steps) {
  $seen = [ordered]@{}
  $snap = @()
  for ($i=0; $i -lt $steps; $i++) {
    $doc = Dump ("{0}_s{1}" -f $tag,$i)
    foreach ($k in (KebabList $doc)) {
      $t = RowTitleFor $doc $k
      if ($t) { $seen[$t] = $k.CY }
    }
    $sv = ScrollableBounds $doc | Where-Object { $_.H -gt 500 } | Select-Object -First 1
    $snap += "  step${i}: {0}" -f ((@($seen.Keys) | ForEach-Object { "'$_'" }) -join ', ')
    if ($null -ne $sv) {
      $x = [int](($sv.L+$sv.R)/2); $yFrom = $sv.B - 120; $yTo = $yFrom - 100
      A @('shell','input','swipe',"$x","$yFrom","$x","$yTo",'80') | Out-Null
      Start-Sleep -Milliseconds 300
    } else { "  step${i}: NO TALL SCROLLABLE" }
  }
  $snap
  return @($seen.Keys)
}

$label = $args[0]

"### ===== GROUND TRUTH $label ====="
$before = GTPrint ("{0}-BEFORE" -f $label)

"### ===== UI TRAVERSE $label ====="
$rows = UITraverse ("{0}_pre" -f $label) 4
$rows
"UI_ROWS_PRE = {0} -> {1}" -f @($rows).Count, ((@($rows) | ForEach-Object {"'$_'"}) -join ', ')

"### ===== SUBMIT $label ====="
# open the form
$doc = Dump ("{0}_f0" -f $label)
$btn = FindByLabel $doc 'Add SSH server'
if (@($btn).Count -eq 0) { "NO_ADDSSH_BTN"; exit 1 }
$b = NBnd @($btn)[0]
"tap Add SSH server @($([int](($b.L+$b.R)/2)),$([int](($b.T+$b.B)/2)))"
TapXY ([int](($b.L+$b.R)/2)) ([int](($b.T+$b.B)/2))
Start-Sleep -Seconds 1

# form EditTexts by y order, excluding the panel search box (y<800) and the tall outer scroller
function FormETs($doc) {
  $ets = @()
  foreach ($n in (AllNodes $doc)) {
    if ((NCls $n) -ne 'android.widget.EditText') { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.T -lt 800) { continue }
    $ets += ,@($n,$b)
  }
  return @($ets | Sort-Object { $_[1].T })
}
$doc = Dump ("{0}_f1" -f $label)
"form EditTexts:"
$ets = FormETs $doc
for ($i=0;$i -lt @($ets).Count;$i++){ "  role$i [{0},{1}][{2},{3}] h={4} text='{5}'" -f $ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H,(NText $ets[$i][0]) }

if (@($ets).Count -lt 2) { "NOT_ENOUGH_FORM_FIELDS"; Cap ("{0}_ffail" -f $label); exit 1 }

# fill host
$h = $ets[0][1]
TapXY ([int](($h.L+$h.R)/2)) ([int](($h.T+$h.B)/2))
Start-Sleep -Milliseconds 500
TypeS 'ssh -p 2222 sshtest@127.0.0.1'
Start-Sleep -Milliseconds 600
$doc = Dump ("{0}_f2" -f $label)
$ets = FormETs $doc
for ($i=0;$i -lt @($ets).Count;$i++){ "  AFTER_HOST role$i [{0},{1}][{2},{3}] h={4} text='{5}'" -f $ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,$ets[$i][1].H,(NText $ets[$i][0]) }
"GT after host fill:"
GTPrint ("{0}-AFTERHOST" -f $label) | Out-Null

# move focus to the name field with DPAD_DOWN, then type without tapping
KeyEv 20
Start-Sleep -Milliseconds 700
$doc = Dump ("{0}_f3" -f $label)
$ets = FormETs $doc
"after DPAD_DOWN:"
for ($i=0;$i -lt @($ets).Count;$i++){ "  role$i [{0},{1}][{2},{3}] h={5} text='{4}'" -f $ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,(NText $ets[$i][0]),$ets[$i][1].H }
TypeS $label
Start-Sleep -Milliseconds 600
$doc = Dump ("{0}_f4" -f $label)
$ets = FormETs $doc
"after typing name:"
for ($i=0;$i -lt @($ets).Count;$i++){ "  role$i [{0},{1}][{2},{3}] h={5} text='{4}'" -f $ets[$i][1].L,$ets[$i][1].T,$ets[$i][1].R,$ets[$i][1].B,(NText $ets[$i][0]),$ets[$i][1].H }

# clickable ancestor of 'Add server' (the TextView itself always reports enabled=true)
$as = FindByLabel $doc 'Add server'
if (@($as).Count -eq 0) { "NO_ADDSERVER"; Cap ("{0}_asfail" -f $label); exit 1 }
$an = @($as)[0]
$p = $an
while ($p -ne $null -and (NClk $p) -ne 'true') { $p = $p.ParentNode }
if ($null -eq $p) { $p = $an }
$pb = NBnd $p
"Add server: self_en={0} anc_en={1} anc_bounds=[{2},{3}][{4},{5}] anc_cls={6}" -f (NEn $an),(NEn $p),$pb.L,$pb.T,$pb.R,$pb.B,(NCls $p)
"IME shown = {0}" -f ((Sh @('dumpsys','input_method') | Select-String 'mInputShown') -ne $null)
TapXY ([int](($pb.L+$pb.R)/2)) ([int](($pb.T+$pb.B)/2))
Start-Sleep -Seconds 2
Cap ("{0}_afterSubmit" -f $label)
$doc = Dump ("{0}_f5" -f $label)
"after submit click, notable text:"
foreach ($n in (AllNodes $doc)) {
  $t = NText $n; if (-not $t) { continue }
  $b = NBnd $n; if ($null -eq $b) { continue }
  if ($b.T -lt 700) { continue }
  "  [{0},{1}][{2},{3}] {4}" -f $b.L,$b.T,$b.R,$b.B,$t
}
"### ===== GROUND TRUTH $label AFTER ====="
$after = GTPrint ("{0}-AFTER" -f $label)
"GT_DELTA = {0} - {1} = {2}" -f $after,$before,($after-$before)
