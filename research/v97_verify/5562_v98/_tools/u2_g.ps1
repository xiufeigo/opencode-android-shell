. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_lib.ps1"

function Rows([string]$n){
  $f="$d\$n.xml"; if(-not (Test-Path $f)){ return @() }
  $out=@()
  foreach($m in [regex]::Matches((Get-Content $f -Raw),'<node\b[^>]*>')){
    $s=$m.Value
    $g=[regex]::Match($s,'text="([^"]*)"'); if(-not $g.Success -or -not $g.Groups[1].Value.Trim()){ $g=[regex]::Match($s,"text='([^']*)'") }
    if($g.Success -and $g.Groups[1].Value.Trim()){ $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
      $out += ("{0}@{1}" -f $g.Groups[1].Value, [int]$b.Groups[2].Value) }
  }
  return $out
}
# panel = nodes with x>1100 in the Files tab (right-hand review panel)
function Panel([string]$n){
  $f="$d\$n.xml"; if(-not (Test-Path $f)){ return @() }
  $out=@()
  foreach($m in [regex]::Matches((Get-Content $f -Raw),'<node\b[^>]*>')){
    $s=$m.Value
    $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); if(-not $b.Success){ continue }
    if([int]$b.Groups[1].Value -lt 1100){ continue }
    $g=[regex]::Match($s,'text="([^"]*)"'); if(-not $g.Success -or -not $g.Groups[1].Value.Trim()){ $g=[regex]::Match($s,"text='([^']*)'") }
    $cls=[regex]::Match($s,'class="([^"]*)"').Groups[1].Value
    if($g.Success -and $g.Groups[1].Value.Trim()){ $out += ("{0}@{1}" -f $g.Groups[1].Value, [int]$b.Groups[2].Value) }
    elseif($cls -match "EditText"){ $out += ("<EDITTEXT>@{0},cx={1}" -f [int]$b.Groups[2].Value, [int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2)) }
  }
  return $out
}

if(-not (Dump "g_00")){ "DUMP FAIL"; exit 1 }
"=== panel state ==="
Find "g_00" "Files|Changes|All files"

# ensure Files tab active
if((Find "g_00" "All files") -eq "NOTFOUND-DUMP"){
  TapAt "g_00" "^Files$" "open-files" | Out-Null; Start-Sleep -Seconds 2
  if(-not (Dump "g_01")){ "DUMP FAIL 2"; exit 1 }
}
if(-not (Dump "g_02_base")){ "DUMP FAIL 3"; exit 1 }
"--- U11-0 BASELINE (Files, no filter) ---"
$base = Panel "g_02_base"
"BASE COUNT=$($base.Count)"
($base | Where-Object { $_ -notmatch 'Search|Review|main@|Refresh|Git changes|Branch changes' }) -join ' ;; '

# find the filter EditText inside the panel
$ed = (Get-Content "$d\g_02_base.xml" -Raw)
$ednode = $null
foreach($m in [regex]::Matches($ed,'<node\b[^>]*>')){
  $s=$m.Value
  if($s -notmatch 'EditText'){ continue }
  $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); if(-not $b.Success){ continue }
  if([int]$b.Groups[1].Value -lt 1100){ continue }
  $ednode = ("{0},{1}" -f [int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2), [int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2))
}
"FILTER EDITTEXT AT: $ednode"
if($ednode){
  $xy = $ednode.Split(',')
  & $adb -s $S shell input tap $xy[0] $xy[1]
  Start-Sleep -Seconds 1
  & powershell -File "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_input.ps1" -Text "gradle"
  Start-Sleep -Seconds 2
  if(Dump "g_03_filt"){
    "--- U12 FILTERED 'gradle' (should recurse) ---"
    $f1 = Panel "g_03_filt"
    "FILTERED COUNT=$($f1.Count)"
    ($f1 | Where-Object { $_ -notmatch 'Search session|Review@|main@|Refresh|Git changes|Branch changes|Changes@|Context@|Files@' }) -join ' ;; '
  }
  "--- U11 tap Clear filter ---"
  if(Dump "g_04_cb"){
    $cl = (Find "g_04_cb" "Clear filter|^Clear$") | Select-Object -First 1
    "clear node: $cl"
    if($cl -and $cl -notmatch "NOTFOUND"){ TapAt "g_04_cb" "Clear filter|^Clear$" "clear" | Out-Null; Start-Sleep -Seconds 2 }
    if(Dump "g_05_cleared"){
      $c1 = Panel "g_05_cleared"
      "CLEARED COUNT=$($c1.Count)"
      ($c1 | Where-Object { $_ -notmatch 'Search session|Review@|main@|Refresh|Git changes|Branch changes|Changes@|Context@|Files@' }) -join ' ;; '
    }
  }
  "--- U11 tap Refresh ---"
  if(Dump "g_06_rf"){ TapAt "g_06_rf" "Refresh" "refresh" | Out-Null; Start-Sleep -Seconds 3 }
  if(Dump "g_07_ref"){
    $r1 = Panel "g_07_ref"
    "REFRESHED COUNT=$($r1.Count)"
    ($r1 | Where-Object { $_ -notmatch 'Search session|Review@|main@|Refresh|Git changes|Branch changes|Changes@|Context@|Files@' }) -join ' ;; '
  }
}
Shot "g_08_end"
"=== DONE ==="
