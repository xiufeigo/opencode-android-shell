$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
Assert-Focus
Dump-UI "qq2" | Out-Null
Write-Output (TapMatch "Create session")
Start-Sleep -Seconds 3
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)"
Dump-UI "qq3" | Out-Null
Write-Output "nodes: $($script:N.Count)"
$i=0; foreach($n in $script:N){ $i++; $d=$n.GetAttribute('content-desc'); if(-not $d){$d=$n.GetAttribute('text')}; if($d -and $d.Length -gt 55){$d=$d.Substring(0,55)+'…'}; "{0,3} | {1} | {2} | clk={3}" -f $i, ($n.GetAttribute('class') -replace 'android\.widget\.',''), $d, $n.GetAttribute('clickable') }
