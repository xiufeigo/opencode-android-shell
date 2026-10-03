# c4lib.ps1 — v98d3 round-4 tool library
# Reuses c3lib.ps1 logic; adds ancestor-walk (for the real Add server button),
# EditText enumeration, fast 100px/80ms form swipes, and server row census.
$ErrorActionPreference = 'Stop'
$global:ADB  = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$global:RDIR = "/sdcard/c4"
$global:OUTD = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$global:LASTXML = $null

function Sh([string]$cmd) { & $global:ADB -s emulator-5554 shell $cmd | Out-Null }

function InitDir { Sh "mkdir -p $global:RDIR" }

function Cap([string]$nm) {
    $b = ("{0}_{1}" -f $nm, (Get-Date -Format 'HHmmssfff'))
    $r = "$global:RDIR/$b"
    Sh "uiautomator dump $r.xml"
    Start-Sleep -Milliseconds 200
    Sh "screencap -p $r.png"
    Start-Sleep -Milliseconds 200
    & $global:ADB -s emulator-5554 pull "$r.xml" "$global:OUTD\$b.xml" | Out-Null
    & $global:ADB -s emulator-5554 pull "$r.png" "$global:OUTD\$b.png" | Out-Null
    $global:LASTXML = "$global:OUTD\$b.xml"
    return $global:LASTXML
}

function Doc {
    if (-not $global:LASTXML) { $null = Cap "auto" }
    return [xml](Get-Content -LiteralPath $global:LASTXML -Raw)
}

function Nodes {
    $d = Doc
    $out = New-Object System.Collections.ArrayList
    foreach ($x in $d.SelectNodes("//node")) {
        if ($x -is [System.Xml.XmlElement]) { $null = $out.Add($x) }
    }
    return $out
}

# Xml DOM attribute read: matches text="..." and text='...' identically (both are XML attrs)
function TxtOf($n) {
    if (-not ($n -is [System.Xml.XmlElement])) { return "" }
    $t = $n.GetAttribute("text")
    if ($t) { return $t }
    $c = $n.GetAttribute("content-desc")
    if ($c) { return $c }
    return ""
}

function Bnd($n) {
    if (-not ($n -is [System.Xml.XmlElement])) { return $null }
    $b = $n.GetAttribute("bounds")
    if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { return $null }
    $o = [ordered]@{}
    $o['l'] = [int]$Matches[1]; $o['t'] = [int]$Matches[2]
    $o['r'] = [int]$Matches[3]; $o['b'] = [int]$Matches[4]
    $o['cx'] = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $o['cy'] = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    return $o
}

function FindN([string]$t, [int]$ix = 0) {
    $k = 0
    foreach ($n in (Nodes)) {
        if ((TxtOf $n) -eq $t) { if ($k -eq $ix) { return $n }; $k++ }
    }
    return $null
}

function FindPat([string]$p) {
    $out = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if ($t -and ($t -like $p)) { $null = $out.Add($n) }
    }
    return $out
}

# ---- round-4 addition: walk up to the nearest clickable ancestor ----
# The Add server TextView reports en=true cl=false; the real button is an ancestor.
function ClickAncestor($n, [int]$maxUp = 6) {
    $cur = $n; $i = 0
    while ($cur -is [System.Xml.XmlElement] -and $i -lt $maxUp) {
        if ($cur.GetAttribute("clickable") -eq "true") { return $cur }
        $cur = $cur.ParentNode; $i++
    }
    return $null
}

function BtnState([string]$label) {
    # returns "label|en=..|cl=..|anc_en=..|anc_cl=..|anc_bounds=..|anc_cls=..|x,y"
    $n = FindN $label 0
    if (-not ($n -is [System.Xml.XmlElement])) { return "$label|NOT_IN_A11Y_TREE" }
    $b = Bnd $n
    $a = ClickAncestor $n
    if ($a -is [System.Xml.XmlElement]) {
        $ab = Bnd $a
        return ("{0}|self_en={1}|self_cl={2}|anc_en={3}|anc_cl={4}|anc_bounds=[{5},{6}][{7},{8}]|anc_cls={9}|self_bounds=[{10},{11}][{12},{13}]|tap={14},{15}" -f `
            $label, $n.GetAttribute("enabled"), $n.GetAttribute("clickable"), `
            $a.GetAttribute("enabled"), $a.GetAttribute("clickable"), `
            $ab['l'],$ab['t'],$ab['r'],$ab['b'], $a.GetAttribute("class"), `
            $b['l'],$b['t'],$b['r'],$b['b'], $b['cx'], $b['cy'])
    }
    return ("{0}|self_en={1}|self_cl={2}|NO_CLICKABLE_ANCESTOR|self_bounds=[{3},{4}][{5},{6}]|tap={7},{8}" -f `
        $label, $n.GetAttribute("enabled"), $n.GetAttribute("clickable"), `
        $b['l'],$b['t'],$b['r'],$b['b'], $b['cx'], $b['cy'])
}

function TapXY([int]$x, [int]$y) { Sh "input tap $x $y"; Start-Sleep -Milliseconds 700 }

function TapTxt([string]$t, [int]$ix = 0) {
    $n = FindN $t $ix
    if (-not ($n -is [System.Xml.XmlElement])) { Write-Host "  TAPFAIL: no node '$t'"; return $false }
    $b = Bnd $n
    Write-Host ("  TAP '{0}'#{1} @{2},{3} bounds=[{4},{5}][{6},{7}] en={8} cl={9} foc={10}" -f `
        $t, $ix, $b['cx'], $b['cy'], $b['l'], $b['t'], $b['r'], $b['b'], `
        $n.GetAttribute("enabled"), $n.GetAttribute("clickable"), $n.GetAttribute("focused"))
    TapXY $b['cx'] $b['cy']
    return $true
}

function TypeS([string]$s) {
    $e = $s -replace '[^A-Za-z0-9._%@-]', '%s'
    Sh "input text '$e'"
    Start-Sleep -Milliseconds 350
}

function ClearET([int]$n = 60) {
    Sh ('i=0; while [ $i -lt ' + $n + ' ]; do input keyevent 67; i=$((i+1)); done')
    Start-Sleep -Milliseconds 200
}

function DumpET {
    $out = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -eq "android.widget.EditText") { $null = $out.Add($n) }
    }
    return $out
}

# enumerate EditTexts in a y-band (the panel body area), with class/cls/hint
function DumpETs([string]$label) {
    Write-Host "---- EDITTEXT [$label] ----"
    $k = 0
    foreach ($n in (DumpET)) {
        $b = Bnd $n
        $t = TxtOf $n
        Write-Host ("  ET#{0} [{1},{2}][{3},{4}] @{5},{6} foc={7} text='{8}' rid={9} desc='{10}'" -f `
            $k, $b['l'],$b['t'],$b['r'],$b['b'], $b['cx'],$b['cy'], `
            $n.GetAttribute("focused"), $t, $n.GetAttribute("resource-id"), $n.GetAttribute("content-desc"))
        $k++
    }
    if ($k -eq 0) { Write-Host "  (none in a11y tree)" }
    return $k
}

# fast small swipe: 100px over 80ms, inside the form body, not on an EditText, not in keyboard area
function FastScroll([int]$x, [int]$yFrom, [int]$yTo, [int]$dur = 80) {
    Sh "input swipe $x $yFrom $x $yTo $dur"
    Start-Sleep -Milliseconds 260
}

function InServers {
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -eq "Add SSH server" -or $t -eq "SSH tunnels") { return $true } }
    return $false
}

function FormOpen {
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -eq "Host or SSH command") { return $true } }
    return $false
}

function Viewport {
    $best = $null; $ba = 0
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("scrollable") -ne "true") { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['t'] -lt 300) { continue }
        $a = ($b['r'] - $b['l']) * ($b['b'] - $b['t'])
        if ($a -gt $ba) { $ba = $a; $best = $b }
    }
    return $best
}

function DumpT([string]$label) {
    Write-Host "---- TEXTS [$label] ----"
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if (-not $t) { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        Write-Host ("  [{0},{1}][{2},{3}] en={4} cl={5} foc={6} | {7}" -f `
            $b['l'], $b['t'], $b['r'], $b['b'], $n.GetAttribute("enabled"), `
            $n.GetAttribute("clickable"), $n.GetAttribute("focused"), $t)
    }
}

# server row census: a row = a text node inside the servers panel area (y>=800)
# plus its kebab (:) if present
function RowCensus([string]$label) {
    Write-Host "---- ROWS [$label] ----"
    $k = 0
    foreach ($n in (Nodes)) {
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['t'] -lt 800 -or $b['t'] -gt 1990) { continue }
        $t = TxtOf $n
        if (-not $t) { continue }
        $cl = $n.GetAttribute("class")
        if ($cl -ne "android.widget.TextView" -and $cl -ne "android.view.View") { continue }
        Write-Host ("  ROW#{0} [{1},{2}][{3},{4}] @{5},{6} cls={7} | {8}" -f `
            $k, $b['l'],$b['t'],$b['r'],$b['b'], $b['cx'],$b['cy'], $cl, $t)
        $k++
    }
    if ($k -eq 0) { Write-Host "  (no row-ish nodes)" }
    return $k
}

# count kebab (row action) buttons
function CountKebab {
    $k = 0
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if ($t -eq "" ) { continue }
        if ($t -match '[\u22EE\u2807]') { $k++ }
    }
    return $k
}

function Say($m) { Write-Host $m }

function FocusInfo {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys window | grep mCurrentFocus"
    Write-Host "FOCUS: $o"
    return ($o -join ' ')
}

function IsANR {
    $f = FocusInfo
    if ($f -match 'Application Not Responding' -or $f -match 'ANR') { return $true }
    return $false
}
