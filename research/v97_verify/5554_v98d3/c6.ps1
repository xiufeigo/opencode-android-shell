# c6.ps1 - v98 D3 round 6 (form reachability re-verify + D3-3/D3-4)
# ASCII only. No keyevent 4. No keyevent 111 inside dialogs.
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$global:ADB = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$global:DEV = $Dev
$global:PKG = 'com.xiufeigo.opencodeshell'
$global:PY   = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
. "$global:PY\E2ECHK5554_run.ps1" -Dev $Dev
# redirect evidence dir to this round
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'

function A { & $global:ADB -s $global:DEV @args }

# ---- ground truth: encrypted prefs entry count (NOT UI dependent) ----
function GtCount {
    $c = (Q shell "run-as $global:PKG cat shared_prefs/oc_server_config.xml" 2>&1 | Out-String)
    $all  = ([regex]::Matches($c, '<string name=')).Count
    $keys = ([regex]::Matches($c, '__androidx_security_crypto_encrypted_prefs')).Count
    $n = $all - $keys
    Write-Host "GT raw_string_lines=$all keysets=$keys ENTRIES=$n"
    return $n
}
function GtRaw { (Q shell "run-as $global:PKG cat shared_prefs/oc_server_config.xml" 2>&1 | Out-String) }

# ---- dump parsing: match BOTH text="..." and text='...' ----
# adb pull writes progress to stderr; with ErrorActionPreference=Stop that aborts.
# So every native adb call goes through Q (quiet) which tolerates stderr.
function Q { $ErrorActionPreference='Continue'; & $global:ADB -s $global:DEV @args 2>$null; $ErrorActionPreference='Stop' }
function Uidump($name) {
    Q shell "uiautomator dump --compressed /sdcard/_c6.xml" | Out-Null
    Q pull /sdcard/_c6.xml (Join-Path $global:DIR "$name.xml") | Out-Null
    Q shell "rm -f /sdcard/_c6.xml" | Out-Null
    return (Join-Path $global:DIR "$name.xml")
}
function ShotN($name) {
    Q shell "screencap -p /sdcard/_c6.png" | Out-Null
    Q pull /sdcard/_c6.png (Join-Path $global:DIR "$name.png") | Out-Null
    Q shell "rm -f /sdcard/_c6.png" | Out-Null
    Write-Host "SHOT $name.png"
}
# node table honouring both quote styles
function NT($name) {
    $f = Uidump $name
    $t = [xml](Get-Content $f -Raw)
    $out = New-Object System.Collections.ArrayList
    function W($n) {
        $txt = $n.text; if (-not $txt) { $txt = $n.'content-desc' }
        $cls = $n.class
        if ($txt -or ($cls -like '*EditText')) {
            [void]$out.Add("$txt`t$($n.bounds)`tpw=$($n.password)`tfoc=$($n.focused)`t$cls`tclk=$($n.clickable)")
        }
        foreach ($c in $n.node) { W $c }
    }
    W $t.hierarchy
    return $out
}
function ImeShown {
    $s = (Q shell "dumpsys input_method | grep mInputShown" 2>&1 | Out-String).Trim()
    if ($s -match 'mInputShown=(\w+)') { return $Matches[1] } else { return 'unknown' }
}
function Scrolls {
    $f = Join-Path $global:DIR 'nav_tmp.xml'
    $t = [xml](Get-Content $f -Raw)
    $o = New-Object System.Collections.ArrayList
    function W2($n) {
        if ("$($n.class)" -eq 'android.widget.ScrollView' -and "$($n.bounds)" -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            [void]$o.Add(@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]))
        }
        foreach ($c in $n.node) { W2 $c }
    }
    W2 $t.hierarchy
    return $o
}
function Grab { $null = Uidump 'nav_tmp'; return $null }
function NodesNow($n) { $null = Uidump $n; $f = Join-Path $global:DIR "$n.xml"; $t=[xml](Get-Content $f -Raw); $out=New-Object System.Collections.ArrayList
    function W3($nd){ $tx=$nd.text; if(-not $tx){$tx=$nd.'content-desc'}; $cl=$nd.class
        if($tx -or ($cl -like '*EditText')){ [void]$out.Add("$tx`t$($nd.bounds)`tpw=$($nd.password)`tfoc=$($nd.focused)`t$cl`tclk=$($nd.clickable)") }
        foreach($c in $nd.node){W3 $c} }
    W3 $t.hierarchy; return $out }

function CenterOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2), [int](([int]$Matches[2]+[int]$Matches[4])/2))
    }
    return $null
}
function Tap($x,$y,$ms=700){ Q shell "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds $ms }
function TapP($n,[string]$pat,[int]$idx=0,[string]$what=''){
    $m = @($n | Select-String -Pattern $pat)
    if($m.Count -le $idx){ Write-Host "NOMATCH '$pat' ($($m.Count)) [$what]"; return $null }
    $p = CenterOf $m[$idx].Line
    if(-not $p){ return $null }
    Write-Host "TAP '$pat' -> $($p[0]),$($p[1]) [$what]  <<$(($m[$idx].Line -split "`t")[0])>>"
    Tap $p[0] $p[1]
    return $m[$idx].Line
}
function TypeText($t,$chunk=8,$sleepMs=900){
    $words = @($t -split ' ' | Where-Object { $_ -ne '' })
    for($w=0;$w -lt $words.Count;$w++){
        $word=$words[$w]
        for($i=0;$i -lt $word.Length;$i+=$chunk){
            $seg=$word.Substring($i,[Math]::Min($chunk,$word.Length-$i))
            Q shell "input text '$seg'" | Out-Null; Start-Sleep -Milliseconds $sleepMs
        }
        if($w -lt $words.Count-1){ Q shell "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 300 }
    }
}
function CollapseIme { Write-Host '  collapse IME (keyevent 61)'; Q shell "input keyevent 61" | Out-Null; Start-Sleep -Milliseconds 900 }
# form body scroll rect: smallest ScrollView fully CONTAINING a form anchor
function FormRect {
    $f = Join-Path $global:DIR 'nav_tmp.xml'
    $t = [xml](Get-Content $f -Raw)
    $sc = New-Object System.Collections.ArrayList; $an = New-Object System.Collections.ArrayList
    function W4($n){
        if("$($n.bounds)" -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
            $r=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
            if("$($n.class)" -eq 'android.widget.ScrollView'){[void]$sc.Add($r)}
            $tx="$($n.text)"
            if($tx -eq 'Host or SSH command' -or $tx -eq 'Server name (optional)' -or $tx -eq 'Authentication' -or $tx -like 'Password*' -or $tx -eq 'Passphrase'){[void]$an.Add($r)}
            if("$($n.class)" -eq 'android.widget.EditText' -and $r[0] -eq 138){[void]$an.Add($r)}
        }
        foreach($c in $n.node){W4 $c} }
    W4 $t.hierarchy
    if($an.Count -eq 0){ return $null }
    $best=$null;$ba=[int]::MaxValue
    foreach($s in $sc){ foreach($a in $an){
        if($a[0] -ge $s[0] -and $a[1] -ge $s[1] -and $a[2] -le $s[2] -and $a[3] -le $s[3]){
            $area=($s[2]-$s[0])*($s[3]-$s[1]); if($area -gt 0 -and $area -lt $ba){$best=$s;$ba=$area}; break } } }
    return $best
}
# small scroll (~100px per the task brief), inside the form body, never on an EditText, never in keyboard zone
function FormSwipe($dir=-1){
    $r = FormRect
    if(-not $r){ Write-Host '  swipe SKIP (form ScrollView not found)'; return $false }
    $x = $r[2] - 20; $top=$r[1]+30; $bot=$r[3]-30
    if(($bot-$top) -lt 100){ Write-Host "  swipe SKIP (band $top..$bot too small)"; return $false }
    if($dir -ge 0){$y1=$top;$y2=$bot}else{$y1=$bot;$y2=$top}
    Q shell "input swipe $x $y1 $x $y2 80" | Out-Null
    Start-Sleep -Milliseconds 500
    return $true
}
function Fields($n){ @($n | Select-String -Pattern 'EditText\t' | ForEach-Object{$_.Line} | Where-Object { $_ -match '\t\[138,' }) }
function RectOf($line){ if($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) }; return $null }
function FieldH($line){ $r=RectOf $line; if(-not $r){return 0}; return ($r[3]-$r[1]) }
function FieldNear($n,$label,$mode='below'){
    $lab = $n | Select-String -Pattern ([regex]::Escape($label)) | Select-Object -First 1
    if(-not $lab){ return $null }
    $lr = RectOf $lab.Line; if(-not $lr){ return $null }
    $ly=$lr[1]; $cands=New-Object System.Collections.ArrayList
    foreach($f in (Fields $n)){ $fr=RectOf $f; if(-not $fr){continue}
        if((FieldH $f) -lt 50){continue}
        if($mode -eq 'below'){ if($fr[1] -gt $ly -and $fr[1] -lt ($ly+300)){[void]$cands.Add(@(($fr[1]-$ly),$f))} }
        else { if($fr[3] -le $ly -and $fr[3] -gt ($ly-150)){[void]$cands.Add(@(($ly-$fr[3]),$f))} } }
    if($cands.Count -eq 0){ return $null }
    return (($cands | Sort-Object{$_[0]})[0])[1]
}
function ScrollToNear($label,$what,$mode='below'){
    for($i=0;$i -lt 14;$i++){
        $null = Uidump 'nav_tmp'
        $t=[xml](Get-Content (Join-Path $global:DIR 'nav_tmp.xml') -Raw)
        $n = NodesNow "nav_tmp"
        $f = FieldNear $n $label $mode
        if($f){ Write-Host "  FOUND $what after $i scroll(s)"; return ,@($f,$n) }
        $d=-1; if($i -ge 7){$d=1}
        Write-Host "  '$what' not visible (try $($i+1), dir $d)"
        if($i -eq 3 -or $i -eq 9){ CollapseIme; continue }
        if(-not (FormSwipe $d)){ return $null }
    }
    return $null
}
function TapField($line,$what){
    for($i=0;$i -lt 3;$i++){
        $p = CenterOf $line; if(-not $p){ return $false }
        Write-Host "  tap $what @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1] 900
        $n = NodesNow 'nav_tmp'
        $foc = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'foc=true' } | Select-Object -First 1
        if($foc){ Write-Host "    focused OK bounds=$((($foc.Line -split "`t")[1]))"; return $true }
        Write-Host "  $what not focused; retry"
        if(-not (FormSwipe -1)){ return $false }
        $n = NodesNow 'nav_tmp'
        $line = (Fields $n | Where-Object { (FieldH $_) -ge 50 } | Select-Object -First 1)
        if(-not $line){ return $false }
    }
    return $false
}
function ScrollToPw($what){
    for($i=0;$i -lt 14;$i++){
        $n = NodesNow 'nav_tmp'
        $hit = Fields $n | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1
        if($hit){ Write-Host "  FOUND $what after $i scroll(s)"; return ,@($hit,$n) }
        Write-Host "  '$what' not visible (try $($i+1))"
        if($i -eq 2 -or $i -eq 8){ CollapseIme; continue }
        if(-not (FormSwipe -1)){ return $null }
    }
    return $null
}
function GotoSettings {
    $null = Uidump 'nav_tmp'; $n = NodesNow 'nav_tmp'
    if([bool]($n | Select-String -Pattern '^(General|Appearance|Notifications|Servers|Shell|Experimental|About)\t' -Quiet)){ Write-Host 'Settings already open'; return }
    $l = TapP $n '^Settings\t' 0 'settings' 2>&1
    Start-Sleep -Seconds 1
    $g = $null -ne ($null | Select-String -Pattern 'x')
    $n = NodesNow 'nav_tmp'
    if(-not [bool]($n | Select-String -Pattern '^(General|Appearance|Notifications|Servers)\t' -Quiet)){ ShotN 'NAV_fail_settings'; throw 'settings did not open' }
    $ty=744
    $gg = $n | Select-String -Pattern '^(General|Appearance|Notifications)\t' | Select-Object -First 1
    if($gg -and $gg.Line -match '\[(\d+),(\d+)\]'){ $ty=[int]$Matches[2] }
    for($i=0;$i -lt 6;$i++){ Q shell "input swipe 200 $ty 960 $ty 250" | Out-Null; Start-Sleep -Milliseconds 350 }
}
function GotoServers {
    GotoSettings
    $srv=$null
    for($i=0;$i -lt 6;$i++){
        $n = NodesNow 'nav_tmp'
        $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
        if($srv){ break }
        Q shell "input swipe 950 744 150 744 350" | Out-Null; Start-Sleep -Milliseconds 700
    }
    if(-not $srv){ ShotN 'NAV_fail_servers'; throw 'Servers tab not found' }
    $null = TapP $n '^Servers\t' 0 'servers tab'
    Start-Sleep -Seconds 1
}
function OpenAddForm {
    $null = Uidump 'nav_tmp'; $n = NodesNow 'nav_tmp'
    if([bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)){ Write-Host 'form already open'; return }
    for($i=0;$i -lt 3;$i++){
        $n = NodesNow 'nav_tmp'
        $null = TapP $n '^Add SSH server\t' 0 'Add SSH server'
        Start-Sleep -Seconds 1
        $n = NodesNow 'nav_tmp'
        if([bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)){ Write-Host "form open (try $($i+1))"; return }
        Write-Host "  form did not open (try $($i+1))"
    }
    ShotN 'NAV_fail_form'; throw 'Add form did not open'
}

