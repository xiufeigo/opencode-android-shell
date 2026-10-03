# r9_form.ps1 - fill the Add-server form ONCE, IME disabled from the start.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"

function SheetFields($x){
  $a=@()
  foreach($n in (Nodes $x)){
    if($n.cls -ne 'android.widget.EditText'){ continue }
    $b=Bounds $n
    if(-not $b){ continue }
    if($b.x1 -ge 130){ $a+=$n }
  }
  return @($a | Sort-Object { (Bounds $_).y1 })
}

Log "=== R9 D1: open form with IME OFF ==="
ImeOff | Out-Null
# scroll list to top so the Add button is on screen
for($k=0;$k -lt 5;$k++){ & $ADB -s $DEV shell input swipe 540 1200 540 1900 350 2>&1 | Out-Null; Start-Sleep -Milliseconds 500 }
ImeOff | Out-Null
$x = Dump "c9_D1a"
$add = @(FindTxt $x 'Add SSH server')[0]
if($add){ $b=Bounds $add; TapXY $b.cx $b.cy 1600 "Add SSH server" }
else { Log "  add btn STILL miss" }
ImeOff | Out-Null
$x = Dump "c9_D1b"
$sf = SheetFields $x
Log "  sheet fields = $($sf.Count) : $(($sf | ForEach-Object { $_.b }) -join ' , ')"

# field 0 = Host or SSH command
function SetSheetField([int]$idx,[string]$val,[string]$tag){
  for($try=1;$try -le 2;$try++){
    $xx = Dump "c9_${tag}_pre"
    $ff = SheetFields $xx
    if($ff.Count -le $idx){ Log "  SET-MISS idx=$idx (have $($ff.Count))"; return $false }
    $n = $ff[$idx]
    $b = Bounds $n
    & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
    Start-Sleep -Milliseconds 450
    & $ADB -s $DEV shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
    for($i=0;$i -lt 100;$i++){ & $ADB -s $DEV shell input keyevent 67 2>&1 | Out-Null }
    if($val -ne ''){ TypeTxt $val }
    ImeOff | Out-Null
    $xv = Dump "c9_${tag}_v"
    $fv = SheetFields $xv
    $got = $(if($fv.Count -gt $idx){ $fv[$idx].text } else { '' })
    Log ("  SET idx={0} try={1} want='{2}' got='{3}' {4}" -f $idx,$try,$val,$got,$(if($got -eq $val){'OK'}else{'MISMATCH'}))
    if($got -eq $val){ return $true }
  }
  return $false
}

SetSheetField 0 'sshtest@127.0.0.1:2222' 'host' | Out-Null
SetSheetField 1 'E2ECHK5554d3f-key' 'name' | Out-Null

# Password field lives below; scroll sheet down
Log "--- scroll for Password ---"
for($k=0;$k -lt 3;$k++){ & $ADB -s $DEV shell input swipe 540 1600 540 950 400 2>&1 | Out-Null; Start-Sleep -Milliseconds 600; ImeOff | Out-Null }
$x = Dump "c9_D1_pw"
$sf = SheetFields $x
Log "  after scroll: sheet fields = $($sf.Count) : $(($sf | ForEach-Object { "$($_.b)='$($_.text)'" }) -join ' , ')"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -match 'Password|Host|Server name|Add server|Cancel|Key|User|Identity'){ Log ("  LBL '{0}' b={1}" -f $l,$n.b) } }
# password is the last sheet field if 3 exist, else find under 'Password' label
$pwIdx = -1
if($sf.Count -ge 3){ $pwIdx = 2 }
else {
  $pl = @(FindTxt $x 'Password')[0]
  if($pl){ $pb=Bounds $pl; $bd=99999
    for($i=0;$i -lt $sf.Count;$i++){ $b=Bounds $sf[$i]; $dy=$b.y1-$pb.y1; if($dy -ge 0 -and $dy -lt 150 -and $dy -lt $bd){ $bd=$dy; $pwIdx=$i } } }
}
Log "  password idx = $pwIdx"
if($pwIdx -ge 0){ SetSheetField $pwIdx 'opencode' 'pw' | Out-Null }

$x = Dump "c9_D1_pre_submit"
Log "--- FINAL form values ---"
foreach($n in (SheetFields $x)){ Log ("  EDIT {0} val='{1}'" -f $n.b,$n.text) }
$ad = @(FindTxt $x 'Add server')[0]
if($ad){ $b=Bounds $ad; TapXY $b.cx $b.cy 2500 "Add server" } else { Log "  NO Add server button" }
$x = Dump "c9_D1_done"
Log "--- after submit (all labels) ---"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
