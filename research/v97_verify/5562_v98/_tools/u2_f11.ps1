. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_lib.ps1"

# U11/U12 — Files tab filter: cleared-but-wrong (U11) + recursive match (U12)
"=== ensure review panel open on Files tab ==="
if(Dump "f_00"){ } else { "DUMP FAIL"; exit 1 }
$rv = Find "f_00" "Toggle review panel"
if(-not $rv){ "no toggle (panel may be open)"; }
# Close panel first to get a clean baseline
if($rv -and $rv -ne "NOTFOUND-DUMP"){ TapAt "f_00" "Toggle review panel" "close-panel" | Out-Null; Start-Sleep -Seconds 2 }
if(Dump "f_01"){ TapAt "f_01" "Toggle review panel" "open-panel" | Out-Null; Start-Sleep -Seconds 3 }
if(Dump "f_02"){ Find "f_02" "Changes|Context|Files|Refresh" }

# baseline row count in Files
if(Dump "f_03_files"){ TapAt "f_03_files" "^Files$" "Files" | Out-Null; Start-Sleep -Seconds 2 }
if(Dump "f_04_base"){
  "--- U11-0 baseline (no filter) ---"
  $rows = (Get-Content "$d\f_04_base.xml" -Raw) -split '<node' | Where-Object { $_ -match 'class="android.widget.TextView"' -and $_ -notmatch 'class="android.view.View"' }
  $names = @()
  foreach($m in [regex]::Matches((Get-Content "$d\f_04_base.xml" -Raw),'<node\b[^>]*>')){
    $s=$m.Value
    $g=[regex]::Match($s,'text="([^"]*)"'); if(-not $g.Success -or -not $g.Groups[1].Value.Trim()){ $g=[regex]::Match($s,"text='([^']*)'") }
    if($g.Success -and $g.Groups[1].Value.Trim()){ $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); $names += ("{0}|[{1},{2}]" -f $g.Groups[1].Value,$b.Groups[1].Value,$b.Groups[2].Value) }
  }
  "BASELINE NODES: $($names.Count)"
  $names -join ' ;; '
}

# U12: type a filter that must match a DEEPER level
"--- U12: filter 'gradle' (should recurse) ---"
$fi = Find "f_04_base" "Filter|filter|Search"
"filterbox: $fi"
if(Dump "f_05_focus"){ TapAt "f_05_focus" "Filter|filter|Search" "filterbox" | Out-Null; Start-Sleep -Seconds 1 }
& powershell -File "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u2_input.ps1" -Text "gradle"
Start-Sleep -Seconds 2
if(Dump "f_06_filtered"){
  $names=@(); foreach($m in [regex]::Matches((Get-Content "$d\f_06_filtered.xml" -Raw),'<node\b[^>]*>')){ $s=$m.Value
    $g=[regex]::Match($s,'text="([^"]*)"'); if(-not $g.Success -or -not $g.Groups[1].Value.Trim()){ $g=[regex]::Match($s,"text='([^']*)'") }
    if($g.Success -and $g.Groups[1].Value.Trim()){ $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); $names += ("{0}|[{1},{2}]" -f $g.Groups[1].Value,$b.Groups[1].Value,$b.Groups[2].Value) } }
  "FILTERED NODES: $($names.Count)"; $names -join ' ;; '
}
Probe "filter-ime"

# U11: Clear filter button, then observe
"--- U11: tap Clear filter ---"
if(Dump "f_07_clearbtn"){ TapAt "f_07_clearbtn" "Clear filter|Clear" "clear" | Out-Null; Start-Sleep -Seconds 2 }
if(Dump "f_08_cleared"){
  $names=@(); foreach($m in [regex]::Matches((Get-Content "$d\f_08_cleared.xml" -Raw),'<node\b[^>]*>')){ $s=$m.Value
    $g=[regex]::Match($s,'text="([^"]*)"'); if(-not $g.Success -or -not $g.Groups[1].Value.Trim()){ $g=[regex]::Match($s,"text='([^']*)'") }
    if($g.Success -and $g.Groups[1].Value.Trim()){ $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); $names += ("{0}|[{1},{2}]" -f $g.Groups[1].Value,$b.Groups[1].Value,$b.Groups[2].Value) } }
  "CLEARED NODES: $($names.Count)"; $names -join ' ;; '
}
"--- U11: tap Refresh ---"
if(Dump "f_09_rf"){ TapAt "f_09_rf" "Refresh" "refresh" | Out-Null; Start-Sleep -Seconds 3 }
if(Dump "f_10_refreshed"){
  $names=@(); foreach($m in [regex]::Matches((Get-Content "$d\f_10_refreshed.xml" -Raw),'<node\b[^>]*>')){ $s=$m.Value
    $g=[regex]::Match($s,'text="([^"]*)"'); if(-not $g.Success -or -not $g.Groups[1].Value.Trim()){ $g=[regex]::Match($s,"text='([^']*)'") }
    if($g.Success -and $g.Groups[1].Value.Trim()){ $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); $names += ("{0}|[{1},{2}]" -f $g.Groups[1].Value,$b.Groups[1].Value,$b.Groups[2].Value) } }
  "REFRESHED NODES: $($names.Count)"; $names -join ' ;; '
}
Shot "f_11_end"
"=== U11/U12 DONE ==="
