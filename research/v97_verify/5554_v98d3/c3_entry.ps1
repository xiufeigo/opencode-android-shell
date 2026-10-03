. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_map.ps1"
$ErrorActionPreference = 'Stop'
InitDir

# The form's own field geometry, measured off A06_formscroll2.png + the form-top map:
#   host field  = [138,1582][943,1708] @ (540,1645)   when form scrolled to TOP
#   pass field  = [138,1639][977,1721] @ (540,1680)   when form scrolled to BOTTOM
$HOST_X = 540; $HOST_Y = 1645
$PASS_X = 540; $PASS_Y = 1680

function Rows {
    $o = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) {
        if ((TxtOf $n) -eq "Server actions") { $b = Bnd $n; $null = $o.Add($b) }
    }
    return @($o)
}
function ListRows([string]$tag) {
    $r = Rows
    Write-Host "  ROWS[$tag] count=$($r.Count)"
    $i = 1
    foreach ($b in $r) { Write-Host ("    #{0} more=[{1},{2}][{3},{4}]" -f $i,$b['l'],$b['t'],$b['r'],$b['b']); $i++ }
    $seen = @()
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if (-not $t) { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if ($t -match '^E2ECHK|^127\.0\.0\.1|^10\.0\.2\.2|^sshtest|Server (healthy|not reachable)|^v2') {
            Write-Host ("       name/text [{0},{1}][{2},{3}] '{4}'" -f $b['l'],$b['t'],$b['r'],$b['b'],$t)
        }
    }
}
function ClosePrompt {
    $c = FindN "Cancel"
    if ($c) { $b = Bnd $c; TapXY $b['cx'] $b['cy']; Write-Host "  closed prompt via Cancel @ $($b['cx']),$($b['cy'])" ; Start-Sleep -Milliseconds 800 }
}
function OpenForm {
    if (-not (InServers)) { Say "  NOT IN SERVERS"; return $false }
    $a = FindN "Add SSH server"
    if (-not $a) { Say "  no Add SSH server"; return $false }
    $b = Bnd $a; TapXY $b['cx'] $b['cy']; Start-Sleep -Milliseconds 1200
    return (FormOpen -or ($null -ne (FindN "Add server")))
}
function AddEntry([string]$tag, [string]$target, [string]$pass) {
    Say "=== AddEntry $tag ==="
    # close any prompt card / form left over
    ClosePrompt
    if (-not (OpenForm)) { Say "  open form failed"; return $false }
    $null = Cap "${tag}_A_open"
    ScrollForm 8 "up"                      # form to top
    $null = Cap "${tag}_B_top"
    TapXY $HOST_X $HOST_Y
    Start-Sleep -Milliseconds 500
    ClearET 120
    TypeS $target
    Start-Sleep -Milliseconds 400
    $null = Cap "${tag}_C_host"
    ShowM "${tag}_host"
    ScrollForm 8 "down"                    # form to bottom
    $null = Cap "${tag}_D_bot"
    TapXY $PASS_X $PASS_Y
    Start-Sleep -Milliseconds 500
    ClearET 60
    TypeS $pass
    Start-Sleep -Milliseconds 400
    $null = Cap "${tag}_E_pass"
    ShowM "${tag}_pass"
    $add = FindN "Add server"
    if (-not $add) { Say "  no Add server btn"; return $false }
    $b = Bnd $add
    Write-Host ("  TAP Add server @{0},{1}" -f $b['cx'],$b['cy'])
    TapXY $b['cx'] $b['cy']
    Start-Sleep -Milliseconds 2500
    $null = Cap "${tag}_F_post"
    ListRows "${tag}_post"
    return $true
}
