param([int]$Cycle = 1)
$ErrorActionPreference = 'Stop'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\d11b_helper.ps1"

$pre = Dump "d11b_c${Cycle}_pre"
$rc = Center $pre "Refresh"
if (-not $rc) { "CYCLE ${Cycle}: REFRESH BUTTON NOT FOUND"; exit 1 }
"Tap Refresh @ $($rc[0]),$($rc[1])"
& $adb -s $dev shell input tap $rc[0] $rc[1]

$sw = [Diagnostics.Stopwatch]::StartNew()
$results = @()
foreach ($t in 1,3,10,20,40) {
    $wait = $t - [int][math]::Floor($sw.Elapsed.TotalSeconds)
    if ($wait -gt 0) { Start-Sleep -Seconds $wait }
    $actual = [int][math]::Round($sw.Elapsed.TotalSeconds)
    $tag = "d11b_c${Cycle}_t${t}"
    $xml = Dump $tag
    Shot $tag
    $cl = Classify $xml
    $res = [ordered]@{ cycle=$Cycle; planned=$t; actual=$actual; kind=$cl[0]; texts=$cl[1]; dump="$tag.xml"; shot="$tag.png" }
    $results += [pscustomobject]$res
    "[C${Cycle} T+${t} (actual ${actual}s)] $($cl[0])"
}
$results | ConvertTo-Json -Depth 4 | Out-File -FilePath "$outDir\d11b_c${Cycle}.json" -Encoding utf8
$results | Export-Csv -Path "$outDir\d11b_c${Cycle}.csv" -NoTypeInformation -Encoding utf8
"---- CYCLE ${Cycle} SUMMARY ----"
$results | ForEach-Object { "T+$($_.planned)s (actual $($_.actual)s): $($_.kind)" }
$fe = $results | Where-Object { $_.kind -eq "FALSE_EMPTY" }
"CYCLE ${Cycle} FALSE_EMPTY COUNT = " + $fe.Count
