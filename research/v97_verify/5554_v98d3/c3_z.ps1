. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "Z0_after"
Say "--- AFTER delete: full row census (scroll to force all rows into the tree) ---"
function Census([string]$tag) {
    foreach ($n in (Nodes)) {
        $t = TxtOf $n; if (-not $t) { continue }
        if ($t -match '^10\.0\.2\.2|^127\.0\.0\.1:|^keypass123|^E2ECHK|^sshtest$') {
            $b = Bnd $n
            Write-Host ("  [{0}] [{1},{2}][{3},{4}] {5}" -f $tag,$b['l'],$b['t'],$b['r'],$b['b'],$t)
        }
    }
    $c = 0
    foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $c++ } }
    Write-Host "  [$tag] SERVER_ACTIONS=$c"
}
Census "scroll0"
for ($i = 0; $i -lt 3; $i++) {
    Sh "input swipe 540 1700 540 1150 300"
    Start-Sleep -Milliseconds 800
    $null = Cap "Z1_s$i"
    Census "scroll$($i+1)"
}
Say "DONE_Z"
