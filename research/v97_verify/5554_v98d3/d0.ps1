$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"

Log "=== D0: current screen state ==="
[void](TunnelLen 'D0')
$x = Dump 'D0'
Log "cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'}) authCard=$(if(AuthCardUp $x){'TRUE'}else{'FALSE'}) formOpen=$(if(FindTxt $x 'Add server'){'TRUE'}else{'FALSE'})"
Log "--- all visible texts ---"
foreach ($z in (Nodes $x)) { $v = if($z.text){$z.text}else{$z.'content-desc'}; if ($v -ne '') { Log ("   '{0}' b={1} clk={2} en={3}" -f $v,$z.bounds,$z.clickable,$z.enabled) } }
Shot 'D0_state'
Log "focus=$(Focus) ime=$(ImeOff)"
