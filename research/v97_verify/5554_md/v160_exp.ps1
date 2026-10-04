$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser = '-s'; $dev = 'emulator-5554'
function A { & $adb $ser $dev @args }

function ImeProbe {
    # returns: imeH | raw frame line | mImeHeight | contentTopInsets | touchableRegion | ime cfg
    $w = A shell dumpsys window 2>&1
    $im = A shell dumpsys input_method 2>&1
    $raw = ($w | Select-String 'type=ime frame=' | Select-Object -First 1).Line
    $mih = ($w  | Select-String '^\s+mImeHeight=' | Select-Object -First 1).Line
    $cti = ($im | Select-String 'contentTopInsets=' | Select-Object -First 1).Line
    $cfg = ($im | Select-String 'Configuration=\{' | Select-Object -First 1).Line
    $hint= ($w | Select-String 'mInsetsHint=Insets\{' | Select-Object -First 1).Line
    $cfgDp = if ($cfg -match 'w(\d+)dp h(\d+)dp (\d+)dpi') { "w$($Matches[1])dp h$($Matches[2])dp $($Matches[3])dpi" } else { 'N/A' }
    $ctiV = if ($cti -match 'contentTopInsets=(\d+)') { $Matches[1] } else { '?' }
    return [pscustomobject]@{ Raw = $raw.Trim(); MImeHeight = $mih.Trim(); Cti = $cti.Trim(); Cfg = $cfgDp; Hint = $hint.Trim() }
}

function H8([string]$s) {
    if ($s -match 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return ([int]$Matches[4] - [int]$Matches[2]) }
    return -1
}

Write-Host "################ EXP-1: multi-poll convergence (feedback-loop signature) ################"
foreach ($H in @(620, 600, 599, 598)) {
    A shell wm size "1366x$H" | Out-Null
    $seq = @()
    for ($i = 0; $i -lt 6; $i++) {
        $p = ImeProbe
        $seq += (H8 $p.Raw)
        Start-Sleep -Milliseconds 400
    }
    Write-Host ("H={0}  poll-seq = {1}   distinct={2}  settled={3}" -f $H, ($seq -join '->'), (($seq | Sort-Object -Unique) -join ','), $seq[-1])
    $p = ImeProbe
    Write-Host ("      raw: " + $p.Raw)
    Write-Host ("      " + $p.MImeHeight + "  |  " + $p.Cti + "  |  CFG " + $p.Cfg)
    Write-Host ("      " + $p.Hint)
}

Write-Host ""
Write-Host "################ EXP-2: path dependence (direct jump vs stepped) ################"
Write-Host "-- 768 -> 599 in ONE jump (path A) --"
A shell wm size "1366x768" | Out-Null; Start-Sleep -Milliseconds 1500
A shell wm size "1366x599" | Out-Null; Start-Sleep -Milliseconds 2500
$pA = ImeProbe; Write-Host ("pathA(768->599 direct) imeH=" + (H8 $pA.Raw) + "  " + $pA.Raw); Write-Host ("   " + $pA.Cti + " | " + $pA.MImeHeight + " | CFG " + $pA.Cfg)
Write-Host "-- 768 -> 600 -> 599 (path B) --"
A shell wm size "1366x768" | Out-Null; Start-Sleep -Milliseconds 1500
A shell wm size "1366x600" | Out-Null; Start-Sleep -Milliseconds 2000
A shell wm size "1366x599" | Out-Null; Start-Sleep -Milliseconds 2500
$pB = ImeProbe; Write-Host ("pathB(768->600->599)      imeH=" + (H8 $pB.Raw) + "  " + $pB.Raw); Write-Host ("   " + $pB.Cti + " | " + $pB.MImeHeight + " | CFG " + $pB.Cfg)
Write-Host "-- hysteresis: 599 -> 600 -> 599 (path C) --"
A shell wm size "1366x600" | Out-Null; Start-Sleep -Milliseconds 2000
A shell wm size "1366x599" | Out-Null; Start-Sleep -Milliseconds 2500
$pC = ImeProbe; Write-Host ("pathC(599->600->599)      imeH=" + (H8 $pC.Raw) + "  " + $pC.Raw); Write-Host ("   " + $pC.Cti + " | " + $pC.MImeHeight + " | CFG " + $pC.Cfg)
Write-Host "-- rising back: 599 -> 600 (does it recover to 339?) --"
A shell wm size "1366x600" | Out-Null; Start-Sleep -Milliseconds 2500
$pD = ImeProbe; Write-Host ("pathD(599->600)            imeH=" + (H8 $pD.Raw) + "  " + $pD.Raw); Write-Host ("   " + $pD.Cti + " | " + $pD.MImeHeight + " | CFG " + $pD.Cfg)

Write-Host ""
Write-Host "################ EXP-3: density control (same H, different density) ################"
foreach ($d in @(160, 120, 160)) {
    A shell wm density $d | Out-Null
    foreach ($H in @(620, 600, 599)) {
        A shell wm size "1366x$H" | Out-Null
        Start-Sleep -Milliseconds 1800
        $p = ImeProbe
        Write-Host ("density=$d H=$H imeH=" + (H8 $p.Raw) + "  " + $p.Raw)
        Write-Host ("     " + $p.Cti + " | " + $p.MImeHeight + " | CFG " + $p.Cfg)
    }
}
Write-Host "=== v160 exp1-3 DONE ==="
