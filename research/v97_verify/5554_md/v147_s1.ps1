# v147 main: launch, reach one-row form at 1366x600, run the six criteria
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null
$script:PKG = 'com.xiufeigo.opencodeshell'

"### M.0 launch"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 2
adbx shell am force-stop $script:PKG | Out-Null
Start-Sleep -Seconds 2
adbx shell am start -n com.xiufeigo.opencodeshell/.OcActivity 2>&1 | Out-String
Start-Sleep -Seconds 8
"  focus:  $((Focus) -join '')"
"  appPid: $(AppPid)"

"### M.1 poll for populated session"
$ns = $null
for ($i=1; $i -le 8; $i++) {
  $ns = Nodes (Dump ("v147_L{0}" -f $i))
  "  poll $i : nodes=$($ns.Count) editText=$([bool]($ns | Where-Object {$_.cls -eq 'EditText'})) appPid=$(AppPid)"
  if ($ns.Count -gt 12) { "    -> populated"; break }
  Start-Sleep -Seconds 4
}
Shot 'v147_landed' | Out-Null
"  -- clickable --"
$ns | Where-Object { $_.clickable -eq 'true' } | ForEach-Object {
  $lbl = if ($_.text) { $_.text } elseif ($_.desc) { $_.desc } else { '' }
  "   [{0},{1}][{2},{3}] {4}x{5} cls={6} lbl='{7}'" -f $_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h,$_.cls,$lbl
}
"  -- EditText --"
$ns | Where-Object { $_.cls -eq 'EditText' } | ForEach-Object {
  "   ET [{0},{1}][{2},{3}] {4}x{5} text='{6}'" -f $_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h,$_.text
}
"  -- long text --"
$ns | Where-Object { $_.text -and $_.text.Length -gt 12 } | Select-Object -First 10 | ForEach-Object {
  "   [{0},{1}][{2},{3}] '{4}'" -f $_.x1,$_.y1,$_.x2,$_.y2,($_.text.Substring(0,[Math]::Min(64,$_.text.Length)))
}
"M done"
