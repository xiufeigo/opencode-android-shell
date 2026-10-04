# v147 recon: launch app, settle, dump the one-row form at 1366x600
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

"### recon 0: current focus / pid"
"  focus:  $((Focus) -join '')"
"  appPid: $(AppPid)"
"  ime:    $(ImeState)"

"### recon 1: set 1366x600 (one-row form), density 160"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x600 | Out-Null
Start-Sleep -Seconds 3
"  wm size:    $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"
"  wm density: $((adbx shell wm density 2>&1 | Select-String 'Override') -join '')"
"  focus:  $((Focus) -join '')"
"  appPid: $(AppPid)"
"  ime:    $(ImeState)"

"### recon 2: dump tree, look for Open terminal / Send / composer"
$ns = Nodes (Dump 'v147_recon_600')
"  total nodes: $($ns.Count)"
""
"  -- clickable nodes --"
$ns | Where-Object { $_.clickable -eq 'true' } | ForEach-Object {
  $lbl = if ($_.text) { $_.text } elseif ($_.desc) { $_.desc } else { '' }
  "   [{0},{1}][{2},{3}] {4}x{5}  cls={6}  lbl='{7}'" -f $_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h,$_.cls,$lbl
}

"### recon 3: all EditText"
$ns | Where-Object { $_.cls -eq 'EditText' } | ForEach-Object {
  "   EditText [{0},{1}][{2},{3}] {4}x{5} text='{6}'" -f $_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h,$_.text
}

"### recon 4: bar nodes (Steer/Remove/Queue)"
$bar = $ns | Where-Object { $_.text -in @('Steer','Remove','Queue') -or $_.desc -in @('Steer','Remove','Queue') }
"  bar count: $($bar.Count)"
"recon done"
