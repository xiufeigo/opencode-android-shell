$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
$PKG='com.xiufeigo.opencodeshell'
Log "=== S0: state probe / form structure ==="
Log "focus=$(Focus)  ime=$(ImeOff)"
A @("shell","am","force-stop",$PKG) | Out-Null
Start-Sleep -Seconds 2
A @("shell","am","start","-n","$PKG/.OcActivity") | Out-Null
Start-Sleep -Seconds 5
Log "focus=$(Focus)  ime=$(ImeOff)"
$x = Dump 's0_home'
Log "--- home text nodes ---"
foreach($n in (Nodes $x)){ $v = if($n.text){$n.text}else{$n.'content-desc'}; if($v -and $v -ne ''){ Log ("  '{0}' cls={1} b={2} clk={3}" -f $v,$n.class,$n.bounds,$n.clickable) } }
# find SSH-related entry
$hit = (Nodes $x) | Where-Object { $_.text -match 'SSH|Terminal|Server' -or $_.'content-desc' -match 'SSH|Terminal|Server' }
foreach($h in $hit){ Log ("  HIT: t='{0}' cd='{1}' b={2} clk={3}" -f $h.text,$h.'content-desc',$h.bounds,$h.clickable) }
Log "focus=$(Focus)"
