. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3lib.ps1"
$ErrorActionPreference = 'Stop'

function Fields {
    $o = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -like "*EditText*") {
            $b = Bnd $n
            $null = $o.Add([pscustomobject]@{ N = $n; B = $b; T = $n.GetAttribute("text"); F = $n.GetAttribute("focused"); C = $n.GetAttribute("class") })
        }
    }
    return $o
}
function ShowFields([string]$tag) {
    Write-Host "  FIELDS[$tag]:"
    foreach ($f in (Fields)) {
        if (-not $f.B) { continue }
        Write-Host ("    [{0},{1}][{2},{3}] foc={4} class={5} text='{6}'" -f $f.B['l'],$f.B['t'],$f.B['r'],$f.B['b'],$f.F,$f.C,$f.T)
    }
}
# field box centre ~ label_top + 123 (measured off A06_formscroll2.png)
function TapBelow([string]$label, [int]$off = 123) {
    $n = FindN $label
    if (-not $n) { Write-Host "  no label '$label'"; return $false }
    $b = Bnd $n
    $x = [int](($b['l'] + $b['r']) / 2); $y = $b['t'] + $off
    Write-Host ("  TAP below '{0}' -> {1},{2}" -f $label, $x, $y)
    TapXY $x $y
    return $true
}
function ScrollForm([int]$times = 1, [string]$dir = "down") {
    for ($i = 0; $i -lt $times; $i++) {
        if ($dir -eq "down") { Sh "input swipe 500 1650 500 1150 300" }
        else { Sh "input swipe 500 1150 500 1700 300" }
        Start-Sleep -Milliseconds 650
    }
}
function FormState([string]$tag) {
    Say "STATE[$tag] formOpen=$(FormOpen)"
    ShowFields $tag
    foreach ($t in @("Add server", "Cancel", "Authentication", "Host or SSH command", "Server name (optional)")) {
        $n = FindN $t
        if ($n) { $b = Bnd $n; Write-Host ("    '{0}' [{1},{2}][{3},{4}]" -f $t,$b['l'],$b['t'],$b['r'],$b['b']) }
    }
}
function FillEntry([string]$target, [string]$name, [string]$pass, [string]$tag) {
    Say "=== FillEntry $tag ==="
    ScrollForm 7 "up"            # to form top
    $null = Cap "${tag}_f0_top"
    FormState "${tag}_top"
    # host
    if (TapBelow "Host or SSH command") { Start-Sleep -Milliseconds 500 }
    ClearET 200
    TypeS $target
    Start-Sleep -Milliseconds 500
    $null = Cap "${tag}_f1_host"
    FormState "${tag}_host"
    # name (one step down)
    ScrollForm 1 "down"
    $null = Cap "${tag}_f2_namepre"
    if (TapBelow "Server name (optional)") { Start-Sleep -Milliseconds 500 }
    ClearET 120
    TypeS $name
    Start-Sleep -Milliseconds 500
    $null = Cap "${tag}_f3_name"
    FormState "${tag}_name"
    # password (to bottom)
    ScrollForm 5 "down"
    $null = Cap "${tag}_f4_pwpre"
    if (TapBelow "Password" 123) { Start-Sleep -Milliseconds 500 }
    ClearET 80
    TypeS $pass
    Start-Sleep -Milliseconds 500
    $null = Cap "${tag}_f5_pw"
    FormState "${tag}_pw"
}
