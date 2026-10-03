. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Stop'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; Add-Content -Path $LOG -Value $s }

function ScrollBy([int]$dir) {
    $null = Uidump 'nav_tmp'
    $r = FormRect
    if(-not $r){ return $false }
    $x = $r[2] - 20
    $mid = $r[1] + [int](($r[3]-$r[1]) * 0.5)
    $lo = $r[1] + 20; $hi = $r[3] - 20
    $y1=$mid; $y2=$mid + ($dir*120)
    if($y1 -lt $lo){$y1=$lo}; if($y1 -gt $hi){$y1=$hi}
    if($y2 -lt $lo){$y2=$lo}; if($y2 -gt $hi){$y2=$hi}
    if($y1 -eq $y2){ return $false }
    Q shell "input swipe $x $y1 $x $y2 300" | Out-Null
    Start-Sleep -Milliseconds 600
    return $true
}
function FindLabel($n,[string]$p){ $m = $n | Select-String -Pattern ([regex]::Escape($p)) | Select-Object -First 1; if($m){ return $m.Line }; return $null }
function FieldUnder($n,[string]$label){
    $l = FindLabel $n $label; if(-not $l){ return $null }
    $lr = RectOf $l
    foreach($f in (Fields $n)){ $r = RectOf $f
        if($r[1] -ge $lr[1] -and $r[1] -le ($lr[1]+300) -and ($r[3]-$r[1]) -ge 50){ return $f } }
    return $null
}
function PwField($n){ Fields $n | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1 }
function FormOpen { $n = NodesNow 'nav_tmp'; return [bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet) }

# ---- UI row inventory: count 'Server actions' buttons across a scroll sweep ----
function UiRows {
    $seen = New-Object System.Collections.ArrayList
    for($i=0;$i -lt 8;$i++){
        $n = NodesNow "rows_$i"
        $n | Select-String -Pattern 'Server actions' | ForEach-Object { [void]$seen.Add($_.Line) }
        if(-not (ScrollBy 1)){ break }
    }
    return $seen
}

L "=== round 6 start ==="
AssertDevice
$GT0 = GtCount
L "GT_BASELINE=$GT0"

# ---------- navigate ----------
$n = NodesNow 'D00_home'
if(-not [bool]($n | Select-String -Pattern '^(General|Appearance|Notifications|Servers)\t' -Quiet)){
    $null = TapP $n '^Settings\t' 0 'settings'
    Start-Sleep -Seconds 1
}
GotoServers
$n = NodesNow 'D01_servers'
$null = Uidump 'nav_tmp'
$srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
if($srv){ $p = CenterOf $srv.Line; Tap $p[0] $p[1]; Start-Sleep -Milliseconds 800 }
ShotN 'D01_servers'
L "panel ready. ServerActions=$(@($n | Select-String -Pattern 'Server actions').Count)"

# ---------- create N entries ----------
function CreateEntry([int]$idx, [string]$name, [string]$target, [string]$pass) {
    L "--- CREATE #$idx name=$name ---"
    $GTB = GtCount
    L "  GT before = $GTB"
    if(-not (FormOpen)){
        $n = NodesNow 'nav_tmp'
        $null = TapP $n '^Add SSH server\t' 0 'Add SSH server'
        Start-Sleep -Seconds 1
    }
    if(-not (FormOpen)){ L "  FAIL: form did not open"; return $false }
    L "  form open"

    # HOST: scroll toward top
    $hf = $null
    for($i=0;$i -lt 12;$i++){
        $n = NodesNow 'nav_tmp'; $hf = FieldUnder $n 'Host or SSH command'
        if($hf){ L "  host found after $i scroll-up(s): $hf"; break }
        if(-not (ScrollBy 1)){ for($j=0;$j -lt 12;$j++){ $n=NodesNow 'nav_tmp'; $hf=FieldUnder $n 'Host or SSH command'; if($hf){ L "  host found after $i down + $j scroll-down(s)"; break }; if(-not (ScrollBy -1)){ break } }; break }
    }
    if(-not $hf){ L "  FAIL: host field unreachable"; return $false }
    $null = TapField $hf 'host'
    TypeText $target
    Start-Sleep -Milliseconds 900
    $n = NodesNow 'nav_tmp'
    L "  host readback: $(($n | Select-String -Pattern 'EditText\t' | Where-Object {$_ -notmatch '\[96,'} | ForEach-Object { (($_.Line -split "`t")[0]) }) -join ' // ')"
    CollapseIme

    # NAME: scroll toward bottom
    $nf = $null
    for($i=0;$i -lt 12;$i++){
        $n = NodesNow 'nav_tmp'; $nf = FieldUnder $n 'Server name (optional)'
        if($nf){ L "  name found after $i scroll-down(s): $nf"; break }
        if(-not (ScrollBy -1)){ break }
    }
    if(-not $nf){ L "  FAIL: name field unreachable"; return $false }
    $null = TapField $nf 'name'
    TypeText $name
    Start-Sleep -Milliseconds 900
    CollapseIme

    # PASSWORD: scroll down until FULLY inside the form body clip
    $null = Uidump 'nav_tmp'
    $fr = FormRect
    if(-not $fr){ L "  FAIL: no form rect"; return $false }
    L "  FORM_BODY_RECT=[$($fr[0]),$($fr[1])][$($fr[2]),$($fr[3])]"
    $pw=$null
    for($i=0;$i -lt 16;$i++){
        $n = NodesNow 'nav_tmp'
        $pw = PwField $n
        if($pw){
            $r = RectOf $pw
            $cy = [int](($r[1]+$r[3])/2)
            if($cy -gt $fr[1]+20 -and $cy -lt $fr[3]-20){ L "  PASSWORD tappable after $i scroll-down(s): [$($r[0]),$($r[1])][$($r[2]),$($r[3])] cy=$cy"; break }
            L "  pw at [$($r[1])..$($r[3])] cy=$cy NOT in clip ($($fr[1])..$($fr[3])), scrolling"
            $pw=$null
        }
        if(-not (ScrollBy -1)){ L "  scroll limit reached"; break }
    }
    if(-not $pw){ L "  FAIL: no TAPPABLE password field"; return $false }
    $r = RectOf $pw
    $cy = [int](($r[1]+$r[3])/2)
    $null = Q shell "input tap 540 $cy"
    Start-Sleep -Milliseconds 1000
    $n = NodesNow 'nav_tmp'
    $foc = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'foc=true' -and $_ -match 'pw=true' } | Select-Object -First 1
    if(-not $foc){
        L "  FAIL: password did not focus (tap y=$cy)"
        L "  fields: $((Fields $n) -join ' | ')"
        return $false
    }
    L "  PASSWORD FOCUSED: $($foc.Line)"
    TypeText $pass
    Start-Sleep -Milliseconds 900
    $n = NodesNow 'nav_tmp'
    L "  final fields: $((Fields $n) -join ' | ')"
    if(-not [bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)){ L "  FAIL: form closed unexpectedly"; return $false }
    ShotN "D10_entry$idx`_filled"

    # SUBMIT
    $sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
    if(-not $sub){ L "  FAIL: Add server not visible"; return $false }
    $sp = CenterOf $sub.Line
    L "  ADD_SERVER_NODE=$($sub.Line)"
    L "  TAP Add server @ $($sp[0]),$($sp[1])"
    Tap $sp[0] $sp[1] 1700
    Start-Sleep -Seconds 2
    $n = NodesNow "D11_entry$`_after"
    $open = [bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)
    $err = ($n | Select-String -Pattern 'required|Please|Enter connection|invalid' | ForEach-Object { (($_.Line -split "`t")[0]) }) -join ' | '
    L "  FORM_STILL_OPEN=$open  ERRORS='$err'"
    $GTA = GtCount
    L "  GT after = $GTA  DELTA=$($GTA-$GTB)"
    ShotN "D11_entry${idx}_after"
    return @{ ok = (-not $open); gtDelta = ($GTA-$GTB) }
}

$results = @()
for($k=1;$k -le 4;$k++){
    $nm = "E2ECHK5554d3c-$k"
    $res = CreateEntry $k $nm 'ssh -p 2222 sshtest@127.0.0.1' 'keypass123'
    $results += ,$res
    if(-not $res){ L "STOP: entry #$k failed"; break }
    # leave the form/panel in a clean state for the next round
    $n = NodesNow 'nav_tmp'
    if([bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)){
        $c = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
        if($c){ $p = CenterOf $c.Line; Tap $p[0] $p[1]; Start-Sleep -Milliseconds 800 }
    }
}

L "--- summary ---"
foreach($r in $results){ if($r){ L "  entry: formClosed=$($r.ok) gtDelta=$($r.gtDelta)" } }
$GTF = GtCount
L "GT_FINAL=$GTF (baseline $GT0, delta $($GTF-$GT0))"
L '--- UI rows now ---'
$n = NodesNow 'D20_rows'
L "SERVER_ACTIONS_VISIBLE=$(@($n | Select-String -Pattern 'Server actions').Count)"
($n | Select-String -Pattern 'E2ECHK|sshtest|127.0.0.1|10.0.2.2' | ForEach-Object { $_.Line }) | ForEach-Object { L "  ROW $_" }
ShotN 'D20_final'
L "DONE-D"
