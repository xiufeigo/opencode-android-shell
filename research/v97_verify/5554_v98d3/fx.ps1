$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
Log "=== FORM DUMP (current screen) ==="
$x = Dump 'FX'
foreach ($z in (Nodes $x)) {
  $v = if($z.text){$z.text}else{$z.'content-desc'}
  if ($v -ne '' -or $z.class -like '*EditText*') { Log ("   '{0}' cls={1} b={2} clk={3} pw={4} foc={5}" -f $v,$z.class,$z.bounds,$z.clickable,$z.password,$z.focused) }
}
Shot 'FX_form'
Log "focus=$(Focus) ime=$(ImeOff)"
