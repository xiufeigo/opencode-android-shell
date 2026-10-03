. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# EXACT chip finder: text == 'Queue' AND inside composer tool row (y 246..298)
function ChipNode([string]$p) {
    foreach ($n in (GetNodes $p)) {
        if ($n.Txt -eq 'Queue' -and $n.Y1 -ge 246 -and $n.Y1 -le 298) { return $n }
    }
    return $null
}
function SendNode([string]$p) {
    foreach ($n in (GetNodes $p)) {
        if (($n.Txt -eq 'Send' -or $n.Txt -eq 'Stop') -and $n.Y1 -ge 246 -and $n.Y1 -le 300) { return $n }
    }
    return $null
}
function EditNode([string]$p) {
    foreach ($n in (GetNodes $p)) { if ($n.Cls -match 'EditText' -and $n.W -gt 200) { return $n } }
    return $null
}

# make sure a turn is RUNNING and composer has content -> chip visible
$f = DumpIt "40_pre"
$ed = EditNode $f
if ($ed) { Ado "input tap $([int](($ed.X1+$ed.X2)/2)) $([int](($ed.Y1+$ed.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 400 }
TypeTxt "q1"
Start-Sleep -Milliseconds 400
$f = DumpIt "41_chip_exact"
$ch = ChipNode $f
$sd = SendNode $f
Write-Output "CHIP_EXACT=$($ch -ne $null) SENDNODE='$($sd.Txt)' IME=$(ImeState)"
foreach ($n in (GetNodes $f)) { if ($n.Y1 -ge 246 -and $n.Y1 -le 298 -and $n.X1 -gt 900 -and $n.W -gt 0 -and $n.Txt) { Write-Output ("  PRE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
if (-not $ch) { Write-Output "NO_CHIP_ABORT"; exit 1 }

Ado "input tap $([int](($ch.X1+$ch.X2)/2)) $([int](($ch.Y1+$ch.Y2)/2))" | Out-Null
Start-Sleep -Seconds 2
$f2 = DumpIt "42_toggled"
Write-Output "--- POST-TOGGLE tool row ---"
foreach ($n in (GetNodes $f2)) { if ($n.Y1 -ge 246 -and $n.Y1 -le 298 -and $n.X1 -gt 900 -and $n.W -gt 0 -and $n.Txt) { Write-Output ("  POST [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
$sd2 = SendNode $f2
Write-Output "SEND_AFTER='$($sd2.Txt)' at $([int](($sd2.X1+$sd2.X2)/2)),$([int](($sd2.Y1+$sd2.Y2)/2)) IME=$(ImeState)"
ShotIt "42_toggled" | Out-Null
