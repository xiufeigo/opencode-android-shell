# measure_1_gfxinfo.ps1 — jank / smoothness baseline.
# Usage: pwsh -File research\measure\measure_1_gfxinfo.ps1
# Emits: out/gfxinfo_full.txt  and prints the parsed numbers.

. (Join-Path $PSScriptRoot 'common.ps1')

Write-Output "### reset gfxinfo"
ResetGfx
Write-Output "### running SCRIPT-A (fixed interaction) ..."
$sw = [Diagnostics.Stopwatch]::StartNew()
ScriptA
$sw.Stop()
Write-Output ("### interaction wall time = {0:N1} s" -f $sw.Elapsed.TotalSeconds)

Start-Sleep -Seconds 2
$pid_ = EnsureForeground
Write-Output "### app pid = $pid_ (must be non-empty, else gfxinfo will say 'frozen')"
$full = GetGfx
$full | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_full.txt')

Write-Output "### ----- raw head -----"
($full | Select-Object -First 30) -join "`n"

Write-Output "### ----- parsed -----"
$txt = $full -join "`n"
$grab = { param($k) if ($txt -match ("(?m)^\s*" + [regex]::Escape($k) + ":\s*(.+)$")) { $Matches[1].Trim() } else { 'N/A' } }
foreach ($k in 'Total frames rendered','Janky frames','50th percentile','90th percentile','95th percentile','99th percentile','Number Missed Vsync','Number High input latency','Number Slow UI thread','Number Slow bitmap uploads','Number Slow issue draw commands','HISTOGRAM') {
  Write-Output ("{0,-32} = {1}" -f $k, (& $grab $k))
}
Write-Output "### percent janky:"
# gfxinfo prints "59 (43.07%)" -> take only the leading integer of the count.
$tfv = [int](($tf -split '[^0-9]')[0]); $jfv = [int](($jf -split '[^0-9]')[0])
if ($tfv -gt 0) {
  $pct = [math]::Round(100.0 * $jfv / $tfv, 2)
  Write-Output ("janky_pct = {0}  ({1}/{2})" -f $pct, $jfv, $tfv)
}
