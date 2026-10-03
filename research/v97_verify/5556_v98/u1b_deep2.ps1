. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

function ChipNode([string]$p) { foreach ($n in (GetNodes $p)) { if ($n.Txt -eq 'Queue' -and $n.Y1 -ge 246 -and $n.Y1 -le 300) { return $n } }; return $null }
function SendNode([string]$p) { foreach ($n in (GetNodes $p)) { if (($n.Txt -eq 'Send' -or $n.Txt -eq 'Stop') -and $n.Y1 -ge 246 -and $n.Y1 -le 320) { return $n } }; return $null }
function EditNode([string]$p) { foreach ($n in (GetNodes $p)) { if ($n.Cls -match 'EditText' -and $n.W -gt 200) { return $n } }; return $null }
function Dump2([string]$t) { for ($i=1; $i -le 3; $i++) { $p = DumpIt $t; if ($p -and (Get-Item $p).Length -gt 500) { return $p }; Start-Sleep -Milliseconds 400 }; return $p }

# --- new session ---
$f = Dump2 "a01_home"
$h = $null; foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'Home') { $h = $n; break } }
if ($h) { Ado "input tap $([int](($h.X1+$h.X2)/2)) $([int](($h.Y1+$h.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$f = Dump2 "a02_home"
$ns = $null; foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'New session' -and $n.Y1 -gt 200) { $ns = $n; break } }
if ($ns) { Ado "input tap $([int](($ns.X1+$ns.X2)/2)) $([int](($ns.Y1+$ns.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$f = Dump2 "a03_sheet"
$cr = $null; foreach ($n in (GetNodes $f)) { if ($n.Txt -eq 'Create session') { $cr = $n; break } }
if ($cr) { Ado "input tap $([int](($cr.X1+$cr.X2)/2)) $([int](($cr.Y1+$cr.Y2)/2))" | Out-Null; Start-Sleep -Seconds 4 }
$f = Dump2 "a04_created"
Write-Output "CREATED FOCUS=$(FocusNow) IME=$(ImeState)"

# --- LONG turn (turn must be RUNNING while we queue) ---
$ed = EditNode $f
if ($ed) { Ado "input tap $([int](($ed.X1+$ed.X2)/2)) $([int](($ed.Y1+$ed.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 400 }
TypeTxt "Write a long detailed explanation of how Android window insets work, covering at least 30 numbered points, with a worked example for each point."
Start-Sleep -Milliseconds 250
$f = Dump2 "a05_typed"
$sd = SendNode $f
Ado "input tap $([int](($sd.X1+$sd.X2)/2)) $([int](($sd.Y1+$sd.Y2)/2))" | Out-Null
Start-Sleep -Seconds 2
$f = Dump2 "a06_running"
$sd = SendNode $f
Write-Output "TURN_STATE='$($sd.Txt)' IME=$(ImeState)"

# --- chip (verified-correct: EXACT match) ---
$ed2 = EditNode $f
if ($ed2) { Ado "input tap $([int](($ed2.X1+$ed2.X2)/2)) $([int](($ed2.Y1+$ed2.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 300 }
TypeTxt "q1"
Start-Sleep -Milliseconds 300
$f = Dump2 "a07_chip"
$ch = ChipNode $f
if (-not $ch) { Write-Output "NO_CHIP"; exit 1 }
Ado "input tap $([int](($ch.X1+$ch.X2)/2)) $([int](($ch.Y1+$ch.Y2)/2))" | Out-Null
Start-Sleep -Milliseconds 800
Write-Output "CHIP_TOGGLED_OK"

# --- FIRE 12 while turn runs; re-resolve Send every 2 ---
for ($i = 2; $i -le 13; $i++) {
    TypeTxt "Q$i"
    Start-Sleep -Milliseconds 60
    if ($i % 2 -eq 0) {
        $p = Dump2 "a08_s$i"
        $s = SendNode $p
        if (-not $s) { Write-Output "NO_SEND at $i"; break }
        $sx = [int](($s.X1+$s.X2)/2); $sy = [int](($s.Y1+$s.Y2)/2)
        $e = EditNode $p
        if ($e) { Ado "input tap $([int](($e.X1+$e.X2)/2)) $([int](($e.Y1+$e.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 150 }
    }
    Ado "input tap $sx $sy" | Out-Null
    Start-Sleep -Milliseconds 150
}
Write-Output "FIRED IME=$(ImeState)"

# --- MEASURE x3 for stability ---
for ($k = 1; $k -le 3; $k++) {
    $v = Dump2 "a09_meas$k"
    $ns2 = GetNodes $v
    $tl = $ns2 | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
    $st = @($ns2 | Where-Object { $_.Txt -eq 'Steer' }); $rm = @($ns2 | Where-Object { $_.Txt -eq 'Remove' })
    $qb = @($ns2 | Where-Object { $_.Txt -eq 'Queued' })
    Write-Output ("F{0} IME={1} TLh={2} QUEUED={3} STEER={4} REMOVE={5}" -f $k,(ImeState),$tl.H,$qb.Count,$st.Count,$rm.Count)
    if ($k -eq 1) {
        foreach ($n in $st) { Write-Output ("  STEER [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
        foreach ($n in $rm) { Write-Output ("  REMOVE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
        Write-Output "--- scrollables ---"
        foreach ($n in ($ns2 | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  SCROLL [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) cls=$($n.Cls)") }
        Write-Output "--- queue row nodes (full-width rows y150..245) ---"
        foreach ($n in $ns2) { if ($n.Y1 -ge 150 -and $n.Y1 -le 245 -and $n.W -gt 300 -and ($n.Txt -or $n.Click -eq 'true')) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) scr=$($n.Scroll) '$($n.Txt)'") } }
        ShotIt "a09_meas1" | Out-Null
    }
    Start-Sleep -Milliseconds 600
}
