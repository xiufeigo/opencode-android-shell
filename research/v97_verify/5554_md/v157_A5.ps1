# v157 A5: confirm the 600->595 reverse jump + pin where the ime actually shrinks
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\v157_lib.ps1"
$log = "$W\_v157_A5.log"
if (Test-Path $log) { Remove-Item $log }
function W_ { param($m) $s = ($m | Out-String).TrimEnd(); Write-Output $s; Add-Content -Path $log -Value $s }

W_ "### A5-1 the claimed breakpoint 588: what does the v156 assumption predict vs what is measured?"
W_ "  v156 assumed ime=426 at every level. Reality below."
W_ "  H     imeH  room(H-ime)  v156-predicted-room(H-426)  FORM      delta(ime-426)"
foreach ($H in @(620,600,595,590,588,585,580,560)) {
  SetDisp "1366x$H" 4
  $null = EnsureIme "A5@$H"
  $im = ImeRaw
  $x = Dump "A5_$H"; $ns = Nodes $x
  $f = FormOf $ns
  W_ ("  {0,-5} {1,-5} {2,-11} {3,-25} {4,-9} {5}" -f $H,$im.H,($H-$im.H),($H-426),$f.Form,($im.H-426))
}

W_ ""
W_ "### A5-2 pin the ime transition zone 604..592 (1dp steps)"
foreach ($H in @(604,603,602,601,600,599,598,597,596,595,594,593,592)) {
  SetDisp "1366x$H" 3
  $null = EnsureIme "A5z@$H"
  $im = ImeRaw
  $x = Dump "A5z_$H"; $ns = Nodes $x
  $f = FormOf $ns
  W_ ("  {0,-5} imeH={1,-5} imeTop={2,-5} room={3,-5} FORM={4,-9} ETw={5}" -f $H,$im.H,$im.Top,($H-$im.H),$f.Form,($(if($f.Et -match '(\d+)x(\d+)'){$Matches[1]}else{'NA'})))
}

W_ ""
W_ "### A5-3 repeatability: 3 passes over 620/600/595/588/580"
foreach ($p in 1..3) {
  foreach ($H in @(620,600,595,588,580)) {
    SetDisp "1366x$H" 3
    $null = EnsureIme "A5r${p}@$H"
    $im = ImeRaw
    $x = Dump "A5r${p}_$H"; $ns = Nodes $x
    $f = FormOf $ns
    W_ ("  pass{0} {1,-5} imeH={2,-5} room={3,-5} FORM={4,-9} chip={5}" -f $p,$H,$im.H,($H-$im.H),$f.Form,$f.Chip)
  }
}

W_ ""
W_ "### A5-4 is form a clean monotone function of `room` (fixed-ime proxy)? sort by room"
$acc = @()
foreach ($H in @(768,700,660,620,600,595,588,580,560)) {
  SetDisp "1366x$H" 3
  $null = EnsureIme "A5s@$H"
  $im = ImeRaw
  $x = Dump "A5s_$H"; $ns = Nodes $x
  $f = FormOf $ns
  $acc += [pscustomobject]@{ H=$H; Ime=$im.H; Room=($H-$im.H); Form=$f.Form }
}
$acc | Sort-Object Room | ForEach-Object { W_ ("  room={0,-5} (H={1,-4} ime={2,-4}) FORM={3}" -f $_.Room,$_.H,$_.Ime,$_.Form) }
$mono = $true
$prev = $null
foreach ($r in ($acc | Sort-Object Room)) {
  $rank = if ($r.Form -eq 'ONEROW') { 0 } else { 1 }
  if ($null -ne $prev -and $rank -lt $prev) { $mono = $false }
  $prev = $rank
}
W_ "  => form vs room monotone non-increasing: $mono"
W_ "### A5 done"
