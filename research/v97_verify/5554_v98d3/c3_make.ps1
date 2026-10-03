. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
$HOST_X = 540; $HOST_Y = 1645
$PASS_X = 540; $PASS_Y = 1680

function FieldTexts {
    $o = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -notlike "*EditText*") { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['l'] -lt 130 -or $b['l'] -gt 150) { continue }
        $null = $o.Add([pscustomobject]@{ Txt = $n.GetAttribute("text"); Y = $b['t']; b = $b })
    }
    return @($o | Sort-Object { $_.Y })
}
function ShowF([string]$tag) {
    Write-Host "  FIELDS[$tag]:"
    foreach ($f in (FieldTexts)) { Write-Host ("    Y={0} [{1},{2}][{3},{4}] '{5}'" -f $f.Y, $f.b['l'], $f.b['t'], $f.b['r'], $f.b['b'], $f.Txt) }
}
function SrvRows {
    $o = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $null = $o.Add((Bnd $n)) } }
    return @($o)
}
function ListRows([string]$tag) {
    $r = SrvRows
    Write-Host "  ROWS[$tag] count=$($r.Count) moreY=$(($r | ForEach-Object { $_.t }) -join ',')"
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if (-not $t) { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['t'] -lt 900 -or $b['t'] -gt 1990) { continue }
        Write-Host ("     [{0},{1}][{2},{3}] {4}" -f $b['l'], $b['t'], $b['r'], $b['b'], $t)
    }
}
function EnsureForm {
    if ($null -ne (FindN "Host or SSH command")) { Write-Host "  form already open"; return }
    $a = FindN "Add SSH server"
    if ($a) { $b = Bnd $a; Write-Host "  tap Add SSH server @$($b['cx']),$($b['cy'])"; TapXY $b['cx'] $b['cy'] }
    Start-Sleep -Milliseconds 1400
}
function MakeEntry([string]$tag, [string]$target, [string]$pass) {
    Say "=== MakeEntry $tag ==="
    EnsureForm
    $null = Cap "${tag}_0_open"
    ShowF "${tag}_open"
    ScrollForm 8 "up"
    $null = Cap "${tag}_1_top"
    ShowF "${tag}_top"
    TapXY $HOST_X $HOST_Y
    Start-Sleep -Milliseconds 600
    ClearET 140
    TypeS $target
    Start-Sleep -Milliseconds 400
    $null = Cap "${tag}_2_host"
    ShowF "${tag}_host"
    ScrollForm 8 "down"
    $null = Cap "${tag}_3_bot"
    ShowF "${tag}_bot"
    TapXY $PASS_X $PASS_Y
    Start-Sleep -Milliseconds 600
    ClearET 60
    TypeS $pass
    Start-Sleep -Milliseconds 400
    $null = Cap "${tag}_4_pass"
    ShowF "${tag}_pass"
    $add = FindN "Add server"
    if (-not $add) { Say "  NO Add server button"; return $false }
    $b = Bnd $add
    Say ("  TAP Add server @{0},{1}" -f $b['cx'], $b['cy'])
    TapXY $b['cx'] $b['cy']
    Start-Sleep -Milliseconds 3500
    $null = Cap "${tag}_5_post"
    ListRows "${tag}_post"
    return $true
}
