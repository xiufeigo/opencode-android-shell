$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
$PKG='com.xiufeigo.opencodeshell'

Log "=== F0: navigate to Servers panel + probe form auth options ==="
$x = Dump 'F0a'
$st = FindTxt $x "Settings"
if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2500 }
$sv=$null
for ($i=1; $i -le 8; $i++) { $x = Dump 'F0b'; $sv = FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
if ($sv) { $c=Center $sv; Log "tap Servers @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500 }
$x = Dump 'F0c'
$add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
if (-not $add) { Log "FAIL no Add SSH server (focus=$(Focus))"; exit 1 }
$c=Center $add; Log "tap Add SSH server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500
$x = Dump 'F0d'
if (-not (FindTxt $x "Add server")) { Log "FAIL form did not open"; exit 1 }
Log "form OPEN"

function DumpForm { param([string]$tag)
  $xx = Dump $tag
  Log "--- FORM $tag ---"
  foreach ($z in (Nodes $xx)) {
    $v = if($z.text){$z.text}else{$z.'content-desc'}
    if ($v -ne '' -or $z.class -like '*EditText*') { Log ("   '{0}' cls={1} b={2} clk={3} pw={4}" -f $v,$z.class,$z.bounds,$z.clickable,$z.password) }
  }
  return $xx }
DumpForm 'F0form' | Out-Null
Shot 'F0_form'
Log "focus=$(Focus) ime=$(ImeOff)"
