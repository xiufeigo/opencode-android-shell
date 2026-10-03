. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3lib.ps1"
$ErrorActionPreference = 'Stop'

# Form text fields are the EditTexts with l≈138; panel/session search boxes are l=96 / l=180.
function FormFields {
    $o = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -notlike "*EditText*") { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['l'] -lt 130 -or $b['l'] -gt 150) { continue }
        $null = $o.Add([pscustomobject]@{ B = $b; T = $n.GetAttribute("text"); F = $n.GetAttribute("focused") })
    }
    return @($o | Sort-Object { $_.B['t'] })
}
function AuthY {
    foreach ($t in @("Authentication")) {
        $n = FindN $t
        if ($n) { $b = Bnd $n; return $b['t'] }
    }
    return 9999
}
# role map: above 'Authentication' -> [host, name]; below -> [password, keypath/passphrase...]
function Roles {
    $ay = AuthY
    $ff = FormFields
    $above = @(); $below = @()
    foreach ($f in $ff) { if ($f.B['t'] -lt $ay) { $above += $f } else { $below += $f } }
    $r = [ordered]@{}
    if ($above.Count -ge 1) { $r['host']   = $above[0] }
    if ($above.Count -ge 2) { $r['name']   = $above[1] }
    if ($below.Count -ge 1) { $r['pass']   = $below[0] }
    return $r
}
function ShowM([string]$tag) {
    $ff = FormFields
    Write-Host "  MAP[$tag] authY=$(AuthY) fields=$($ff.Count)"
    foreach ($f in $ff) {
        Write-Host ("    [{0},{1}][{2},{3}] foc={4} '{5}'" -f $f.B['l'],$f.B['t'],$f.B['r'],$f.B['b'],$f.F,$f.T)
    }
    $r = Roles
    foreach ($k in @('host','name','pass')) {
        if ($r.Contains($k)) { Write-Host ("    role {0} -> y={1} '{2}'" -f $k, $r[$k].B['t'], $r[$k].T) }
        else { Write-Host "    role $k -> ABSENT" }
    }
}
# scroll only inside the form card (below y=1400) so the panel list is untouched
function ScrollForm([int]$n = 1, [string]$dir = "up") {
    for ($i = 0; $i -lt $n; $i++) {
        if ($dir -eq "up")   { Sh "input swipe 540 1420 540 1700 280" }
        else                 { Sh "input swipe 540 1700 540 1420 280" }
        Start-Sleep -Milliseconds 600
    }
}
function ClearIfNeeded($f) {
    if ($f.T) {
        $len = $f.T.Length
        Sh ('i=0; while [ $i -lt ' + ($len * 2 + 15) + ' ]; do input keyevent 67; i=$((i+1)); done')
        Start-Sleep -Milliseconds 250
    }
}
function TypeInto([string]$role, [string]$val, [string]$tag) {
    $null = Cap "${tag}_pre_$role"
    $r = Roles
    if (-not $r.Contains($role)) { Write-Host "  !! role '$role' absent"; return $false }
    $b = $r[$role].B
    $x = $b['cx']; $y = $b['cy']
    Write-Host ("  TYPE role={0} @{1},{2} was='{3}' -> '{4}'" -f $role, $x, $y, $r[$role].T, $val)
    TapXY $x $y
    Start-Sleep -Milliseconds 400
    $null = Cap "${tag}_foc_$role"
    $r2 = Roles
    if ($r2.Contains($role)) { ClearIfNeeded $r2[$role] } else { Write-Host "  !! lost role after focus" }
    TypeS $val
    Start-Sleep -Milliseconds 400
    $null = Cap "${tag}_done_$role"
    $r3 = Roles
    if ($r3.Contains($role)) { Write-Host "  AFTER role=$role text='$($r3[$role].T)'" } else { Write-Host "  AFTER role=$role ABSENT" }
    return $true
}
