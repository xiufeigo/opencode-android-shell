. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Continue'
$LOG = Join-Path $global:DIR 'D00_run.log'
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Host $s; Add-Content $LOG $s }
$NODESPY = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554\E2ECHK5554_nodes.py'
$ANC     = Join-Path $global:DIR 'anc.py'
function NT($tag){
    $ErrorActionPreference='Continue'
    & $global:ADB -s $global:DEV shell "uiautomator dump --compressed /sdcard/_e2.xml" 2>&1 | Out-Null
    & $global:ADB -s $global:DEV pull /sdcard/_e2.xml (Join-Path $global:DIR "$tag.xml") 2>&1 | Out-Null
    & $global:ADB -s $global:DEV shell "rm -f /sdcard/_e2.xml" 2>&1 | Out-Null
    return @(python $NODESPY (Join-Path $global:DIR "$tag.xml"))
}
function Shot($tag){
    Q shell "screencap -p /sdcard/_e2.png" | Out-Null
    Q pull /sdcard/_e2.png (Join-Path $global:DIR "$tag.png") | Out-Null
    Q shell "rm -f /sdcard/_e2.png" | Out-Null
}
function CenterOf($line){ if($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ return ,@([int](([int]$Matches[1]+[int]$Matches[3])/2),[int](([int]$Matches[2]+[int]$Matches[4])/2)) }; return $null }
function FormBodyRect($tag){
    $x = [xml](Get-Content (Join-Path $global:DIR "$tag.xml") -Raw)
    $sc=New-Object System.Collections.ArrayList; $an=New-Object System.Collections.ArrayList
    $st = New-Object System.Collections.Stack; $st.Push($x.hierarchy)
    while($st.Count -gt 0){
        $nd=$st.Pop(); if($null -eq $nd){continue}
        if("$($nd.bounds)" -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
            $r=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
            if("$($nd.class)" -eq 'android.widget.ScrollView'){[void]$sc.Add($r)}
            $tx="$($nd.text)"
            if($tx -eq 'Host or SSH command' -or $tx -eq 'Server name (optional)' -or $tx -eq 'Authentication' -or $tx -like 'Password*' -or $tx -eq 'Passphrase'){[void]$an.Add($r)}
            if("$($nd.class)" -eq 'android.widget.EditText' -and $r[0] -eq 138){[void]$an.Add($r)}
        }
        foreach($c in $nd.node){$st.Push($c)}
    }
    if($an.Count -eq 0){return $null}
    $best=$null;$ba=[int]::MaxValue
    foreach($s in $sc){ foreach($a in $an){
        if($a[0] -ge $s[0] -and $a[1] -ge $s[1] -and $a[2] -le $s[2] -and $a[3] -le $s[3]){
            $ar=($s[2]-$s[0])*($s[3]-$s[1]); if($ar -gt 0 -and $ar -lt $ba){$best=$s;$ba=$ar}; break } } }
    return $best
}
function ScrollBy($tag,[int]$dir){
    $r = FormBodyRect $tag
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

L '=== E2: diagnose Add server disabled, fix host, resubmit ==='
$n = NT 'E10_now'
L "  EDITTEXT_COUNT=$(@($n | Select-String -Pattern 'EditText\t').Count)"
foreach($x in @($n | Select-String -Pattern 'EditText')){ L "    $($x.Line)" }
L '  --- Add server enabled-state (ancestor chain) ---'
$ancOut = python $ANC (Join-Path $global:DIR 'E10_now.xml') 'Add server'
foreach($x in $ancOut){ L "    $x" }

L '  --- scroll form to TOP to inspect the host field ---'
for($i=0;$i -lt 12;$i++){
    $n = NT 'E11_top'
    if(@($n | Select-String -Pattern 'Host or SSH command').Count -gt 0){ L "  host label visible after $i scroll-up(s)"; break }
    if(-not (ScrollBy 'E11_top' 1)){ L '  scroll limit'; break }
}
$n = NT 'E12_top'
L "  IME=$(ImeShown)"
foreach($x in @($n | Select-String -Pattern 'EditText|Host or SSH command|Server name|^Authentication|^Add server|^Cancel')){ L "    $($x.Line)" }
Shot 'E12_top'
$hostNode = $null
foreach($x in @($n | Select-String -Pattern 'EditText\t')){ if($x.Line -notmatch '\[96,'){ $hostNode = $x.Line; break } }
L "  HOST_NODE='$hostNode'"
$hostVal = ''
if($hostNode){ $k = ($hostNode -split "`t")[0]; $hostVal = $k }
L "  HOST_VALUE='$hostVal'  (empty => Add server disabled => explains GT_DELTA=0)"
if($hostVal.Trim() -eq ''){
    L '  >>> HOST EMPTY - retyping target'
    $p = CenterOf $hostNode
    Q shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Milliseconds 900
    TypeText 'ssh -p 2222 sshtest@127.0.0.1'
    Start-Sleep -Milliseconds 900
    $n = NT 'E13_retyped'
    foreach($x in @($n | Select-String -Pattern 'EditText')){ L "    READBACK $($x.Line)" }
    CollapseIme
    $n = NT 'E14_after'
    L '  --- Add server enabled-state after retyping host ---'
    foreach($x in (python $ANC (Join-Path $global:DIR 'E14_after.xml') 'Add server')){ L "    $x" }
    Shot 'E14_after'
}
L 'DONE-E2'
