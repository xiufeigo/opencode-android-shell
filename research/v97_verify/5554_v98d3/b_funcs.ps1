function GetFormET { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { if ($z.class -like '*EditText') { $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if ([int]$b.Groups[1].Value -eq 138) { $cc=Center $z; $r += [pscustomobject]@{b=$z.bounds; cx=$cc.x; cy=$cc.y; pw=$z.password; val=$z.text; foc=$z.focused; h=([int]$b.Groups[4].Value-[int]$b.Groups[2].Value)} } } }
  return $r }
function FindTxt { param($xml,[string]$t) return ((Nodes $xml) | Where-Object { $_.text -eq $t -or $_.'content-desc' -eq $t } | Select-Object -First 1) }
function CardUp { param($xml) return [bool]((Nodes $xml) | Where-Object { $_.text -eq 'Verify host identity' }) }
function PanelRows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'E2ECHK5554d3d-\d+$') { $c=Center $z; $r += [pscustomobject]@{v=$v; b=$z.bounds; cy=$c.cy} } }; return $r }
function States { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'Connecting|Connected$|Disconnected|Authentication required') { $r += $v } }; return $r }
function TunnelLen { param([string]$tag)
  $o = A @("shell","run-as","com.xiufeigo.opencodeshell","cat","shared_prefs/oc_server_config.xml")
  $t=($o|Out-String); Set-Content "$W\_prefs_$tag.txt" -Value $t -Encoding UTF8
  $m=[regex]::Match($t,'<string name="AXhs57KqmMxUXQA/h1gxbGu9ZbW7vQQstN5gTAw="[^>]*>([^<]*)</string>')
  $len = if($m.Success){$m.Groups[1].Value.Length}else{-1}
  Log "  TUNNELKEY[$tag] len=$len md5=$((Get-FileHash "$W\_prefs_$tag.txt" -Algorithm MD5).Hash.Substring(0,10))"
  return $len }
function EnsurePanel {
  for ($k=0; $k -lt 4; $k++) {
    $x = Dump "EP$k"
    if (CardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
      if ($cc) { Log "  [Ensure] card up -> Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue } }
    $fc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $fc=$c } } }
    if ($fc) { Log "  [Ensure] form open -> footer Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 2000; continue }
    $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
    if ($add) { Log "  [Ensure] panel clean"; return $true }
    Log "  [Ensure] round $k no panel (focus=$(Focus))"
    $st = FindTxt $x "Settings"
    if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2200; $sv=$null
      for ($i=1;$i -le 8;$i++){ $x=Dump "EPs"; $sv=FindTxt $x "Servers"; if($sv){break}; Swipe 950 745 150 745 400 }
      if($sv){ $c=Center $sv; Tap $c; Start-Sleep -Milliseconds 2000 } } }
  return $false }

function CreateEntry { param([string]$name)
  if (-not (EnsurePanel)) { Log "  [Create] panel unreachable"; return $false }
  $x = Dump "CC0"
  $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
  $c=Center $add; Log "  [Create] Add SSH server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2200
  $x = Dump "CC1"
  if (-not (FindTxt $x "Add server")) { Log "  [Create] FAIL form"; return $false }
  $t = GetFormET $x | Sort-Object h -Descending | Select-Object -First 1
  TapAt $t.cx $t.cy; Start-Sleep -Milliseconds 900
  if (-not (GetFormET (Dump "CC2") | Where-Object { $_.foc -eq 'true' })) { Log "  [Create] FAIL target focus"; return $false }
  A @("shell","input","text","ssh%s-p%s2222%ssshtest@127.0.0.1") | Out-Null; Start-Sleep -Milliseconds 1200
  if (-not (GetFormET (Dump "CC3") | Where-Object { $_.val -eq 'ssh -p 2222 sshtest@127.0.0.1' })) { Log "  [Create] FAIL target rb"; return $false }
  A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 1000
  $fo = GetFormET (Dump "CC4") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $fo -or $fo.pw -eq 'true') { Log "  [Create] FAIL TAB1 name"; return $false }
  A @("shell","input","text",$name) | Out-Null; Start-Sleep -Milliseconds 1200
  if (-not (GetFormET (Dump "CC5") | Where-Object { $_.val -eq $name })) { Log "  [Create] FAIL name rb"; return $false }
  for ($k=1; $k -le 4; $k++) {
    A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 950
    if (GetFormET (Dump "CC6_$k") | Where-Object { $_.pw -eq 'true' -and $_.foc -eq 'true' }) { break }
    if ($k -eq 4) { Log "  [Create] FAIL pw focus"; return $false } }
  A @("shell","input","text","keypass123") | Out-Null; Start-Sleep -Milliseconds 1200
  $x = Dump "CC7"
  $as = FindTxt $x "Add server"
  if (-not $as) { Log "  [Create] FAIL no Add server"; return $false }
  $c=Center $as; Log "  [Create] Add server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 5000
  $x = Dump "CC8"
  Log "  [Create] cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'}) rows=[$((PanelRows $x | ForEach-Object { $_.v }) -join '|')]"
  return $true }


