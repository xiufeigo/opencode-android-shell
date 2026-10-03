# walk model picker with small, slow swipes; aborts/reopens robustly; prints all counted rows
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5558'
$exclude = @('minimal','low','medium','high','xhigh','max','Default','Use server default')
$script:meta = New-Object System.Collections.Generic.List[string]
function Get-Rows {
    & $adb -s $serial shell uiautomator dump /sdcard/adbui.xml 2>&1 | Out-Null
    & $adb -s $serial pull /sdcard/adbui.xml "$env:TEMP\walk.xml" 2>&1 | Out-Null
    if (-not (Test-Path "$env:TEMP\walk.xml")) { return , (New-Object System.Collections.Generic.List[string]) }
    $xml = [xml](Get-Content "$env:TEMP\walk.xml" -Raw)
    $rows = New-Object System.Collections.Generic.List[string]
    $hasSearch = $false
    $lastHdr = ''
    foreach ($n in $xml.SelectNodes('//*[@text!=""]')) {
        if ($n.text -eq 'Search models') { $hasSearch = $true }
        $b = $n.GetAttribute('bounds')
        if ($b -match '^\[146,(\d+)\]\[1048,(\d+)\]$' -and $exclude -notcontains $n.text) {
            $rows.Add($n.text + '@' + $Matches[1])
        } elseif ($b -match '^\[146,\d+\]\[(\d+),\d+\]$' -and [int]$Matches[1] -lt 500) {
            $lastHdr = $n.text
            $m = 'HDR|' + $n.text
            if (-not $script:meta.Contains($m)) { $script:meta.Add($m) }
        } elseif ($b -match '^\[(\d+),\d+\]\[(\d+),\d+\]$' -and [int]$Matches[1] -ge 340 -and [int]$Matches[2] -le 700 -and $n.text -ne 'Search models') {
            $m = 'BADGE|' + $n.text
            if (-not $script:meta.Contains($m)) { $script:meta.Add($m) }
            if ($lastHdr -ne '') {
                $p = 'PAIR|' + $lastHdr + ' => ' + $n.text
                if (-not $script:meta.Contains($p)) { $script:meta.Add($p) }
            }
        }
    }
    $rows.Add("__SHEET=$hasSearch")
    return , $rows
}
function Strip-Flag($rows) {
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($r in $rows) { if (-not $r.StartsWith('__SHEET')) { $out.Add($r) } }
    return , $out
}
$prevRaw = Get-Rows
$sheetOpen = ($prevRaw | Where-Object { $_ -eq '__SHEET=True' }).Count -gt 0
if (-not $sheetOpen) { Write-Output "SHEET_CLOSED_AT_START"; exit 1 }
$prev = Strip-Flag $prevRaw
$total = $prev.Count
$names = New-Object System.Collections.Generic.List[string]
foreach ($r in $prev) { $names.Add($r) }
$stable = 0
for ($i = 0; $i -lt 80; $i++) {
    & $adb -s $serial shell input swipe 540 1850 540 1250 800 | Out-Null
    Start-Sleep -Milliseconds 900
    $curRaw = Get-Rows
    $open = ($curRaw | Where-Object { $_ -eq '__SHEET=True' }).Count -gt 0
    if (-not $open) { Write-Output "SHEET_CLOSED_MID_WALK at i=$i"; break }
    $cur = Strip-Flag $curRaw
    $maxk = 0
    $lim = [Math]::Min($prev.Count, $cur.Count)
    for ($k = $lim; $k -ge 1; $k--) {
        $ok = $true
        for ($j = 0; $j -lt $k; $j++) {
            $pt = ($prev[$prev.Count - $k + $j] -split '@')[0]
            $ct = ($cur[$j] -split '@')[0]
            if ($pt -ne $ct) { $ok = $false; break }
        }
        if ($ok) { $maxk = $k; break }
    }
    if ($maxk -eq 0 -and $cur.Count -gt 0) {
        # no overlap -> unsafe jump; small corrective swipe and rescan same step
        & $adb -s $serial shell input swipe 540 1700 540 1350 700 | Out-Null
        Start-Sleep -Milliseconds 900
        $cur2Raw = Get-Rows
        $cur2 = Strip-Flag $cur2Raw
        $lim2 = [Math]::Min($prev.Count, $cur2.Count)
        for ($k = $lim2; $k -ge 1; $k--) {
            $ok = $true
            for ($j = 0; $j -lt $k; $j++) {
                $pt = ($prev[$prev.Count - $k + $j] -split '@')[0]
                $ct = ($cur2[$j] -split '@')[0]
                if ($pt -ne $ct) { $ok = $false; break }
            }
            if ($ok) { $maxk = $k; break }
        }
        $cur = $cur2
    }
    $newCount = $cur.Count - $maxk
    if ($newCount -le 0) { $stable++; if ($stable -ge 3) { break } }
    else { $stable = 0; $total += $newCount; for ($j = $maxk; $j -lt $cur.Count; $j++) { $names.Add($cur[$j]) } }
    $prev = $cur
}
Write-Output "MODEL_ROWS_COUNTED=$total"
Write-Output "FIRST=$($names[0])"
Write-Output "LAST=$($names[$names.Count-1])"
$names -join "`n" | Out-File -Encoding utf8 "$PSScriptRoot\picker_rows.txt"
Write-Output "== META =="
$script:meta | ForEach-Object { Write-Output $_ }
