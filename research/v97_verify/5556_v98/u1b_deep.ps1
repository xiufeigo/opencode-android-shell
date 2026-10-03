. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

function ChipNode([string]$p) { foreach ($n in (GetNodes $p)) { if ($n.Txt -eq 'Queue' -and $n.Y1 -ge 246 -and $n.Y1 -le 300) { return $n } }; return $null }
function SendNode([string]$p) { foreach ($n in (GetNodes $p)) { if (($n.Txt -eq 'Send' -or $n.Txt -eq 'Stop') -and $n.Y1 -ge 246 -and $n.Y1 -le 320) { return $n } }; return $null }
function EditNode([string]$p) { foreach ($n in (GetNodes $p)) { if ($n.Cls -match 'EditText' -and $n.W -gt 200) { return $n } }; return $null }

# fresh LONG turn so the turn is RUNNING while we queue
$f = DumpIt "50_pre"
$ed = EditNode $f
if ($ed) { Ado "input tap $([int](($ed.X1+$ed.X2)/2)) $([int](($ed.Y1+$ed.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 300 }
TypeTxt "Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points, with a worked example for each."
Start-Sleep -Milliseconds 250
$f = DumpIt "51_typed"
$sd = SendNode $f
Ado "input tap $([int](($sd.X1+$sd.X2)/2)) $([int](($sd.Y1+$sd.Y2)/2))" | Out-Null
Start-Sleep -Seconds 2

# chip visible (composer has draft)
$ed2 = EditNode (DumpIt "52_pre_chip")
if ($ed2) { Ado "input tap $([int](($ed2.X1+$ed2.X2)/2)) $([int](($ed2.Y1+$ed2.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 300 }
TypeTxt "q1"
Start-Sleep -Milliseconds 300
$f = DumpIt "53_chip"
$ch = ChipNode $f
if ($ch) {
    Ado "input tap $([int](($ch.X1+$ch.X2)/2)) $([int](($ch.Y1+$ch.Y2)/2))" | Out-Null
    Start-Sleep -Seconds 1
    Write-Output "CHIP_TOGGLED"
} else { Write-Output "NO_CHIP" }

# FIRE: re-resolve Send coords every 2 sends (queue bar growth moves the composer)
for ($i = 1; $i -le 13; $i++) {
    TypeTxt "Q$i u1b"
    Start-Sleep -Milliseconds 80
    if ($i % 2 -eq 1) {
        $p = DumpIt "54_s$i"
        $s = SendNode $p
        if (-not $s) { Write-Output "NO_SEND at $i"; break }
        $sx = [int](($s.X1 + $s.X2) / 2); $sy = [int](($s.Y1 + $s.Y2) / 2)
        $e = EditNode $p
        if ($e) { Ado "input tap $([int](($e.X1+$e.X2)/2)) $([int](($e.Y1+$e.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 200 }
    }
    Ado "input tap $sx $sy" | Out-Null
    Start-Sleep -Milliseconds 200
}
Write-Output "FIRED_13 IME=$(ImeState) IMETOP=$(ImeTopPx)"

$v = DumpIt "55_qdepth_measure"
$ns = GetNodes $v
$tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
$st = @($ns | Where-Object { $_.Txt -eq 'Steer' }); $rm = @($ns | Where-Object { $_.Txt -eq 'Remove' })
$qb = @($ns | Where-Object { $_.Txt -eq 'Queued' })
Write-Output "===== MEASURE q11+ ====="
Write-Output "mInputShown=$(ImeState) IMETOP=$(ImeTopPx) NODES=$($ns.Count)"
Write-Output ("TIMELINE [{0},{1}][{2},{3}] h={4}" -f $tl.X1,$tl.Y1,$tl.X2,$tl.Y2,$tl.H)
Write-Output "QUEUED_BADGES=$($qb.Count) STEER=$($st.Count) REMOVE=$($rm.Count)"
foreach ($n in $st) { Write-Output ("  STEER [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
foreach ($n in $rm) { Write-Output ("  REMOVE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
Write-Output "--- all scrollables ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  SCROLL [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) cls=$($n.Cls)") }
Write-Output "--- queue region nodes y150..240 ---"
foreach ($n in $ns) { if ($n.Y1 -ge 150 -and $n.Y1 -le 245 -and $n.W -gt 4 -and ($n.Txt -or $n.Click -eq 'true')) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) scr=$($n.Scroll) '$($n.Txt)'") } }
ShotIt "55_qdepth_measure" | Out-Null
