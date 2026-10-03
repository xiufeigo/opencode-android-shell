# E2ECHK5558_s3_scan.ps1 - v71 worktree row-end x hit-geometry scan on emulator-5558.
#
# WHAT IS SCANNED
#   The v71 criterion ("x 793..839 opens the delete confirm, x >= 841 only closes
#   the menu") was measured on emulator-5556 at density 160 with the ring's right
#   edge at x=840.  emulator-5558 is 480 dpi (3.0x) on a 1344 px wide screen, so
#   those absolute x are device-specific: on this device they land ~450 px to the
#   LEFT of the popup and cannot decide the fix.  Two groups are scanned:
#     A. the literal v71 x values, recorded for the record;
#     B. the SAME offsets re-based on this device's dump-derived ring right edge
#        (offset * 3.0), which is the criterion that actually decides the fix.
#   Group C pins the ring edges and the popup card border.
#
# v71 LESSON BAKED IN (an earlier draft of this script got it wrong and deleted a
# worktree): the delete CONFIRM DIALOG's action button carries the SAME
# content-desc as the menu row's x ("Delete worktree").  Every state test here
# therefore keys on the UNAMBIGUOUS anchor - the dialog's title "Delete worktree?"
# (with the question mark) - and no scan coordinate is ever tapped until a fresh
# dump has proved the confirm dialog is NOT up.
#
# DISCIPLINE: no BACK, no ESC, every coordinate comes from a FRESH dump.
param(
    [string]$Dev = 'emulator-5558'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev

$script:log = @()

# Dismiss the confirm dialog if it is up.  ALWAYS via the dump-resolved Cancel,
# never a bare coordinate.  Returns $true when a dialog was dismissed.
function DismissConfirm {
    $n = Nodes 'E2ECHK5558_S3_nav_tmp'
    if (-not ($n | Select-String -Pattern '^Delete worktree\?\t' -Quiet)) { return $false }
    $c = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if (-not $c) { throw 'confirm dialog is up but Cancel is not in the dump - STOP' }
    $p = CenterOf $c.Line
    Write-Host "   [safety] confirm dialog up -> Cancel @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1] 1000
    return $true
}

# The location dropdown TRIGGER on the draft screen.  Its label is whatever the
# draft currently points at ("Local", a worktree name, ...) and CHANGES when a
# row body is tapped, so the trigger is found INSIDE its container
# 「Select where to run session」, never by a literal string.  The container node's
# own bounds include the chip, and the branch chip below sits outside them.
$script:lastTap = ''
$script:sameTap = 0
function LocationChip($n) {
    $lab = $n | Select-String -Pattern '^Select where to run session\t' | Select-Object -First 1
    if (-not $lab) { return $null }
    $b = BoundsOf $lab.Line
    $best = $null
    foreach ($line in $n) {
        $x = BoundsOf $line
        if (-not $x) { continue }
        # skip the container node itself (its own centre is trivially inside it)
        if ($x[0] -eq $b[0] -and $x[1] -eq $b[1] -and $x[2] -eq $b[2] -and $x[3] -eq $b[3]) { continue }
        $cx = [int](($x[0] + $x[2]) / 2); $cy = [int](($x[1] + $x[3]) / 2)
        if ($cx -gt $b[0] -and $cx -lt $b[2] -and $cy -gt $b[1] -and $cy -lt $b[3]) {
            if (-not $best) { $best = $x }
        }
    }
    if (-not $best) { return $null }
    $t = $n | Where-Object { $_ -like "*`t[$($best[0]),$($best[1])][$($best[2]),$($best[3])]`t*" } | Select-Object -First 1
    return @{ bounds = $best; text = if ($t) { ($t -split "`t")[0] } else { '?' } }
}

# Get to: workspace menu OPEN, worktree row on screen, no confirm dialog, and the
# draft pointed at 「Local repository」.  The chip label also decides WHERE the
# popup is anchored, so a worktree chip shifts the whole popup left and moves the
# ring; resetting to the project root keeps one geometry for the whole scan and
# makes the re-based x values comparable.
$script:needReset = $true
function OpenWsMenu {
    $script:sameTap = 0   # guard is PER CALL, not per script
    for ($i = 0; $i -lt 9; $i++) {
        if (DismissConfirm) { $script:needReset = $true; continue }
        $n = Nodes 'E2ECHK5558_S3_nav_tmp'
        # menu state: a worktree row carries its own x.  Require the row NAME too,
        # so the dialog's action button can never satisfy this test.
        if (($n | Select-String -Pattern '^Delete worktree\t' -Quiet) -and
            ($n | Select-String -Pattern '^[a-z0-9]+-[a-z0-9]+\t' -Quiet)) {
            if ($script:needReset) {
                $lr = $n | Select-String -Pattern '^Local repository\t' | Select-Object -First 1
                if (-not $lr) { Write-Host '  nav: menu open but no "Local repository" row'; return $null }
                $p = CenterOf $lr.Line
                Write-Host "   [nav] reset chip -> Local repository @ $($p[0]),$($p[1])"
                Tap $p[0] $p[1] 1200
                $script:needReset = $false
                continue
            }
            return $n
        }
        if ($n | Select-String -Pattern '^Create session\t' -Quiet) {
            $chip = LocationChip $n
            if (-not $chip) { Write-Host '  nav: no location chip inside the container'; return $null }
            $c = $chip.bounds
            $tx = [int](($c[0] + $c[2]) / 2); $ty = [int](($c[1] + $c[3]) / 2)
            $key = "$tx,$ty"
            if ($key -eq $script:lastTap) { $script:sameTap++ } else { $script:sameTap = 1 }
            $script:lastTap = $key
            if ($script:sameTap -ge 3) {
                Write-Host "  nav: tapping $key three times did not open the menu (chip='$($chip.text)') - ABORT"
                return $null
            }
            Tap $tx $ty 1200
            continue
        }
        if ($n | Select-String -Pattern '^Projects\t' -Quiet) {
            TapMatch '^New session\t' 'E2ECHK5558_S3_nav_tmp' 0 | Out-Null
            continue
        }
        $b = $n | Select-String -Pattern '^Back\t' | Select-Object -First 1
        if ($b) { $p = CenterOf $b.Line; Tap $p[0] $p[1] 1300; continue }
        Write-Host '  nav: unrecognised screen, dumping for diagnosis:'
        ($n | Select-String -Pattern 'TextView|Button') | Select-Object -First 12 | ForEach-Object { Write-Host "     $($_.Line)" }
        return $null
    }
    return $null
}

# The worktree row's own ring, read from a dump that is PROVABLY the menu.
function RowRing($n) {
    $del = $n | Select-String -Pattern '^Delete worktree\t' | Select-Object -First 1
    $name = $n | Select-String -Pattern '^[a-z0-9]+-[a-z0-9]+\t' | Select-Object -First 1
    if (-not $del -or -not $name) { return $null }
    $b = BoundsOf $del.Line
    $nb = BoundsOf $name.Line
    # the ring must be to the RIGHT of, and overlapping, the row label
    if ($b[0] -lt $nb[2] -or $b[0] -gt $nb[2] + 40) { return $null }
    return @($b, $nb, (($name.Line -split "`t")[0]))
}

function Classify($n) {
    if ($n | Select-String -Pattern '^Delete worktree\?\t' -Quiet) { return 'DELETE_CONFIRM' }
    if ($n | Select-String -Pattern '^Delete worktree\t' -Quiet) { return 'MENU_STILL_OPEN' }
    if ($n | Select-String -Pattern '^Create session\t' -Quiet) { return 'MENU_CLOSED_ONLY' }
    return 'NAVIGATED_ELSEWHERE'
}

function DoScan([int]$x, [string]$label) {
    $n = OpenWsMenu
    if (-not $n) { Write-Host "SKIP x=$x ($label): menu unreachable"; return }
    $r = RowRing $n
    if (-not $r) { Write-Host "SKIP x=$x ($label): worktree row not identified in the menu"; return }
    $b = $r[0]
    $ry = [int](($b[1] + $b[3]) / 2)
    $tag = "E2ECHK5558_S3_x${x}"
    Write-Host "---- x=$x  ($label)  ring=[$($b[0])..$($b[2])] rowY=$ry"
    Tap $x $ry 1100
    $after = Nodes $tag
    $res = Classify $after
    Shot $tag | Out-Null
    $script:log += [pscustomobject]@{
        x = $x; label = $label; result = $res
        ringL = $b[0]; ringR = $b[2]; rowY = $ry
        fromRing = $x - $b[2]
    }
    Write-Host "     => $res"
    # a row-body tap selects the worktree -> the next open must reset the chip
    if ($res -ne 'DELETE_CONFIRM') { $script:needReset = $true }
    # always leave the screen clean for the next point
    if ($res -eq 'DELETE_CONFIRM') { [void](DismissConfirm) }
}

# ---- baseline: ring from a PROVABLY menu-state dump ----
$base = OpenWsMenu
if (-not $base) { throw 'workspace menu never opened' }
$rr = RowRing $base
if (-not $rr) { throw 'worktree row not identified' }
$ring = $rr[0]
$ringL = $ring[0]; $ringR = $ring[2]
$dp = ($ringR - $ringL) / 3.0
Write-Host "RING (menu state, dump-derived): [$ringL,$($ring[1])][$ringR,$($ring[3])]  width=$($ringR - $ringL)px = $([math]::Round($dp,1))dp"
Write-Host "RING row label: $($rr[2])  at [$($rr[1][0]),$($rr[1][1])][$($rr[1][2]),$($rr[1][3])]"

$map = New-Object System.Collections.ArrayList
foreach ($v in @(793, 807, 823, 839, 841, 860, 900)) {
    $nx = [int](($v - 840) * 3.0 + $ringR)
    [void]$map.Add(@($nx, "v71 x=$v (offset $($v - 840)px@160dpi -> x$nx here)"))
}
Write-Host "GROUP B mapped points: $((@($map | ForEach-Object { $_[0] })) -join ', ')"

Write-Host '=== GROUP A: literal v71 x values (record only) ==='
foreach ($v in @(793, 807, 823, 839, 841, 860, 900)) { DoScan $v "A-literal v71 x=$v" }

Write-Host '=== GROUP B: v71 offsets re-based on this device ring ==='
foreach ($m in $map) { DoScan $m[0] $m[1] }

Write-Host '=== GROUP C: ring edges + card border ==='
foreach ($k in @($ringL, $ringR, 1145, 1146)) { DoScan $k "C-edge" }

$script:log | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
$script:log | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $global:DIR 'E2ECHK5558_S3_scan.csv')
Write-Host 'SCAN CSV -> E2ECHK5558_S3_scan.csv'
