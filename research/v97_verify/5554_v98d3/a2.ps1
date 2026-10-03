$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"
function FindTxt { param($xml,[string]$t) return ((Nodes $xml) | Where-Object { $_.text -eq $t -or $_.'content-desc' -eq $t } | Select-Object -First 1) }
function CardUp { param($xml) return [bool]((Nodes $xml) | Where-Object { $_.text -eq 'Verify host identity' }) }
function PanelRows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'E2ECHK5554d3d-') { $c=Center $z; $r += [pscustomobject]@{v=$v; b=$z.bounds; cy=$c.cy} } }; return $r }
function States { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'Connecting|Connected$|Disconnected|Authentication required') { $r += $v } }; return $r }
function Snap { param([string]$tag)
  $o = A @("shell","run-as","com.xiufeigo.opencodeshell","cat","shared_prefs/oc_server_config.xml")
  $t = ($o | Out-String); $p = "$W\_prefs_$tag.txt"
  Set-Content -Path $p -Value $t -Encoding UTF8
  $keys = [regex]::Matches($t,'<string name="([^"]+)"[^>]*>([^<]*)</string>')
  $log = @()
  foreach ($k in $keys) { $log += ("{0} len={1}" -f $k.Groups[1].Value, $k.Groups[2].Value.Length) }
  Log "  PREFS[$tag] size=$((Get-Item $p).Length) md5=$((Get-FileHash $p -Algorithm MD5).Hash.Substring(0,12)) keys: $($log -join ' | ')"
  return $t }

Log "########## A2: BEFORE card Cancel ##########"
$x = Dump "C20"
Log "IME=$(ImeOff) FOCUS=$(Focus)"
Log "cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"
Log "--- card buttons ---"
foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.text -eq 'Trust and connect' -or $z.text -like '*host identity*' -or $z.text -like 'MD5:*' -or $z.text -like '*\]:2222*' -or $z.text -eq 'ssh-ed25519') { Log ("  BTN '" + $z.text + "' clk=" + $z.clickable + " [" + $z.bounds + "]") } }
Log "--- panel rows BEFORE ---"; foreach ($p in (PanelRows $x)) { Log "  ROW $($p.v) [$($p.b)]" }
Log "--- states BEFORE ---"; foreach ($s in (States $x)) { Log "  STATE: $s" }
foreach ($d in ((Nodes $x) | Where-Object { $_.'content-desc' -eq 'Server actions' })) { Log "  DOTS clk=$($d.clickable) en=$($d.enabled) [$($d.bounds)]" }
Snap "before_cancel" | Out-Null
Shot "C20_card_before"

$cc = $null
foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; Log "  Cancel cand [$($z.bounds)] c=$($c.x),$($c.y)"; if ($c.y -gt 1700) { $cc=$c } } }
if (-not $cc) { Log "ABORT: card Cancel not found"; exit 1 }
Log ">>> tap CARD Cancel @ $($cc.x),$($cc.y)"
Tap $cc
Start-Sleep -Milliseconds 2500

Log "########## A2: AFTER card Cancel ##########"
$x2 = Dump "C21"
Log "ASSERT-1 cardGone=$(if(CardUp $x2){'FALSE-STILL-UP'}else{'TRUE'})"
Log "  'Trust and connect' still present=$(if(FindTxt $x2 'Trust and connect'){'YES'}else{'NO'})"
Log "  'Verify host identity' text matches=$(if((Nodes $x2 | Where-Object { $_.text -like '*host identity*' })){'YES'}else{'NO'})"
Log "  form branch 'Add server' present=$(if(FindTxt $x2 'Add server'){'YES'}else{'NO'})"
Log "--- panel rows AFTER (dialog still open per F13b) ---"; foreach ($p in (PanelRows $x2)) { Log "  ROW $($p.v) [$($p.b)]" }
Log "--- states AFTER ---"; foreach ($s in (States $x2)) { Log "  STATE: $s" }
foreach ($z in (Nodes $x2)) { $c=Center $z; if ($c.t -gt 1250 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  POST '" + $z.text + "' cd=" + $z.'content-desc' + " clk=" + $z.clickable + " [" + $z.bounds + "]") } }
Shot "C21_after_card_cancel"

Log "########## A3: tap form footer Cancel ##########"
$x3 = Dump "C22"
$fc = $null
foreach ($z in (Nodes $x3)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; Log "  Cancel cand [$($z.bounds)] c=$($c.x),$($c.y)" } }
foreach ($z in (Nodes $x3)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1300 -and $c.y -lt 1700) { $fc=$c } } }
if (-not $fc) { Log "ABORT: footer Cancel not found"; exit 1 }
Log ">>> tap FOOTER Cancel @ $($fc.x),$($fc.y)"
Tap $fc
Start-Sleep -Milliseconds 2200
$x4 = Dump "C23"
Log "########## A3 AFTER: panel state ##########"
$add = (Nodes $x4) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
if ($add) { $ca=Center $add; Log "ASSERT-3 'Add SSH server' PRESENT [$($add.bounds)] center=$($ca.x),$($ca.y)" } else { Log "ASSERT-3 'Add SSH server' ABSENT" }
Log "--- panel rows AFTER ---"; foreach ($p in (PanelRows $x4)) { Log "  ROW $($p.v) [$($p.b)]" }
Log "--- states AFTER (was 'Connecting over SSH...') ---"; foreach ($s in (States $x4)) { Log "  STATE: $s" }
foreach ($d in ((Nodes $x4) | Where-Object { $_.'content-desc' -eq 'Server actions' })) { Log "  DOTS clk=$($d.clickable) en=$($d.enabled) [$($d.bounds)]" }
Snap "after_cancel" | Out-Null
Shot "C23_panel_after"
