. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY='C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
$FR = Join-Path $global:DIR 'formrect.py'
# CORRECT ground truth: the SSH tunnel list is ONE encrypted key; its VALUE LENGTH is the delta signal.
function GtTunnel { 
    $c = (Q shell "run-as com.xiufeigo.opencodeshell cat shared_prefs/oc_server_config.xml" 2>&1 | Out-String)
    $m = [regex]::Match($c,'<string name="AXhs57KqmMxUXQA[^"]*">([^<]*)</string>')
    $f = (Q shell "stat -c %s shared_prefs/oc_server_config.xml" 2>&1 | Out-String).Trim()
    if($m.Success){ L "  GT tunnelValLen=$($m.Groups[1].Value.Length)  serverConfigBytes=$f" ; return $m.Groups[1].Value.Length }
    L "  GT tunnel key NOT FOUND"; return -1
}
function NT($tag){
    $ErrorActionPreference='Continue'
    & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_f1.xml" 2>&1 | Out-Null
    & $global:ADB -s $global:DEV pull /sdcard/_f1.xml (Join-Path $global:DIR "$tag.xml") 2>&1 | Out-Null
    & $global:ADB -s $global:DEV shell "rm -f /sdcard/_f1.xml" 2>&1 | Out-Null
    return @(python $NODESPY (Join-Path $global:DIR "$tag.xml"))
}
function FR($tag){
    $out = @(python $FR (Join-Path $global:DIR "$tag.xml"))
    foreach($l in $out){ if($l -like 'FORM_RECT*'){ return ,@($l -replace '^FORM_RECT ','' -split ',') } }
    return $null
}
function CenterOf($line){ if($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
function ScrollF([int]$dir,$r){
    if(-not $r){ return $false }
    $x=$r[2]-20; $mid=$r[1]+[int](($r[3]-$r[1])*0.5)
    $lo=$r[1]+20; $hi=$r[3]-20
    $y1=$mid; $y2=$mid+($dir*120)
    if($y1 -lt $lo){$y1=$lo}; if($y1 -gt $hi){$y1=$hi}
    if($y2 -lt $lo){$y2=$lo}; if($y2 -gt $hi){$y2=$hi}
    if($y1 -eq $y2){ return $false }
    Q shell "input swipe $x $y1 $x $y2 300" | Out-Null
    Start-Sleep -Milliseconds 600
    return $true
}
function TypeText($t,$chunk=8,$sleepMs=800){
    $words=@($t -split ' ' | Where-Object { $_ -ne '' })
    for($w=0;$w -lt $words.Count;$w++){
        $word=$words[$w]
        for($i=0;$i -lt $word.Length;$i+=$chunk){
            $seg=$word.Substring($i,[Math]::Min($chunk,$word.Length-$i))
            Q shell "input text '$seg'" | Out-Null; Start-Sleep -Milliseconds $sleepMs }
        if($w -lt $words.Count-1){ Q shell "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 300 }
    }
}
function FieldUnder($lines,[string]$label){
    $lab = $lines | Select-String -Pattern ([regex]::Escape($label)) | Select-Object -First 1
    if(-not $lab){ return $null }
    $p = CenterOf $lab.Line; if(-not $p){ return $null }
    foreach($f in @($lines | Select-String -Pattern 'EditText\t')){
        $c = CenterOf $f.Line; if(-not $c){ continue }
        if($c[1] -gt $p[1] -and $c[1] -lt ($p[1]+300) -and $f.Line -notmatch '\[96,'){ return $f.Line } }
    return $null
}
function TapAndType($line,[string]$what,[string]$txt){
    $p = CenterOf $line
    L "  tap $what @ $($p[0]),$($p[1])"
    Q shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Milliseconds 900
    $n = NT 'tmp_focus'
    $foc = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'foc=true' } | Select-Object -First 1
    L "    focused=$($foc.Line)"
    TypeText $txt
    Start-Sleep -Milliseconds 700
    CollapseIme
}
function CancelTrustCard {
    $n = NT 'tmp_card'
    $c = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if($c -and @($n | Select-String -Pattern 'Trust and connect').Count -gt 0){
        $p = CenterOf $c.Line
        L "  trust card up -> Cancel @ $($p[0]),$($p[1])"
        Q shell "input tap $($p[0]) $($p[1])" | Out-Null
        Start-Sleep -Seconds 1
        return $true
    }
    return $false
}

L '=== F1: create entries #2 and #3 with corrected ground truth ==='
CancelTrustCard
$GTB = GtTunnel
L "  baseline captured"
# make sure the panel is showing
$n = NT 'F10_panel'
if(@($n | Select-String -Pattern 'Add SSH server\t').Count -eq 0 -and @($n | Select-String -Pattern 'SSH tunnels').Count -eq 0){
    GotoServers; $n = NT 'F10b_panel'
}
L "  rows(before)=$(@($n | Select-String -Pattern 'Server actions').Count) tunnelLabels=$((@($n | Select-String -Pattern 'E2ECHK5554d3c-\d') | ForEach-Object { ($_.Line -split "`t")[0] }) -join ',')"

foreach($k in @(2,3)){
    L "--- create entry #$k ---"
    $n = NT "F2${k}_pre"
    if(@($n | Select-String -Pattern 'Host or SSH command').Count -eq 0){
        $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
        if(-not $add){ L "  FAIL: no Add SSH server button"; break }
        $p = CenterOf $add.Line
        L "  tap Add SSH server @ $($p[0]),$($p[1])"
        Q shell "input tap $($p[0]) $($p[1])" | Out-Null
        Start-Sleep -Seconds 1
    }
    $n = NT "F2${k}_form"
    if(@($n | Select-String -Pattern 'Host or SSH command').Count -eq 0){ L "  FAIL: form did not open"; break }
    # HOST (scroll up)
    $hf=$null
    for($i=0;$i -lt 12;$i++){
        $n = NT "F2${k}_h"
        $hf = FieldUnder $n 'Host or SSH command'
        if($hf){ L "  host after $i scroll-up(s)"; break }
        $r = FR "F2${k}_h"
        if(-not (ScrollF 1 $r)){ L '  scroll limit (up)'; break }
    }
    if(-not $hf){ L '  FAIL host'; break }
    TapAndType $hf 'host' 'ssh -p 2222 sshtest@127.0.0.1'
    # NAME (scroll down)
    $nf=$null
    for($i=0;$i -lt 12;$i++){
        $n = NT "F2${k}_n"
        $nf = FieldUnder $n 'Server name (optional)'
        if($nf){ L "  name after $i scroll-down(s)"; break }
        $r = FR "F2${k}_n"
        if(-not (ScrollF -1 $r)){ L '  scroll limit (down)'; break }
    }
    if(-not $nf){ L '  FAIL name'; break }
    TapAndType $nf 'name' "E2ECHK5554d3c-$k"
    # PASSWORD: scroll down until centre is inside the clip
    $n = NT "F2${k}_p"
    $r = FR "F2${k}_p"
    L "  FORM_RECT=[$($r -join ',')]"
    $pwLine=$null
    for($i=0;$i -lt 16;$i++){
        $n = NT "F2${k}_p"
        $r = FR "F2${k}_p"
        $cand = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'pw=true' } | Select-Object -First 1
        if($cand){
            $c = CenterOf $cand.Line
            if($r -and $c[1] -gt ($r[1]+20) -and $c[1] -lt ($r[3]-20)){ $pwLine = $cand.Line; L "  PASSWORD tappable after $i scroll-down(s): $($cand.Line)"; break }
            L "  pw cy=$($c[1]) not in clip ($($r[1])..$($r[3]))"
        }
        if(-not (ScrollF -1 $r)){ L '  scroll limit (pw)'; break }
    }
    if(-not $pwLine){ L '  FAIL password unreachable'; break }
    $c = CenterOf $pwLine
    Q shell "input tap $($c[0]) $($c[1])" | Out-Null
    Start-Sleep -Milliseconds 1000
    $n = NT "F2${k}_pf"
    $foc = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'foc=true' -and $_ -match 'pw=true' } | Select-Object -First 1
    L "  PASSWORD FOCUSED=$($foc.Line)"
    if($foc){ TypeText 'keypass123' } else { L '  WARN password not focused' }
    Start-Sleep -Milliseconds 700
    CollapseIme
    $n = NT "F2${k}_ready"
    foreach($x in @($n | Select-String -Pattern 'EditText')){ L "    READY $($x.Line)" }
    Q shell "screencap -p /sdcard/_f1.png" | Out-Null
    Q pull /sdcard/_f1.png (Join-Path $global:DIR "F2${k}_ready.png") | Out-Null
    Q shell "rm -f /sdcard/_f1.png" | Out-Null
    $G0 = GtTunnel
    $sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
    if(-not $sub){ L '  FAIL: Add server not visible'; break }
    $p = CenterOf $sub.Line
    L "  TAP Add server @ $($p[0]),$($p[1])"
    Q shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Seconds 2
    $n = NT "F2${k}_after"
    L "  rows(after)=$(@($n | Select-String -Pattern 'Server actions').Count) tunnelRows=$((@($n | Select-String -Pattern 'E2ECHK5554d3c-\d') | ForEach-Object { ($_.Line -split "`t")[0] }) -join ',')"
    $G1 = GtTunnel
    L "  GT tunnelValLen $G0 -> $G1  (delta $($G1-$G0))"
    Q shell "screencap -p /sdcard/_f1.png" | Out-Null
    Q pull /sdcard/_f1.png (Join-Path $global:DIR "F2${k}_after.png") | Out-Null
    Q shell "rm -f /sdcard/_f1.png" | Out-Null
    CancelTrustCard
}
L "  final GT:"; $GTA = GtTunnel
$n = NT 'F30_final'
L "  ServerActions=$(@($n | Select-String -Pattern 'Server actions').Count)"
foreach($x in @($n | Select-String -Pattern 'Server actions|E2ECHK5554d3c-\d|10.0.0.2:4095|Trust and connect|Connecting|Connected|Disconnected')){ L "    $($x.Line)" }
L 'DONE-F1'
