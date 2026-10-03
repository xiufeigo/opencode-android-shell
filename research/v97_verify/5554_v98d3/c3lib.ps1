# c3lib.ps1 — v98d3 round-3 tool library (defensive, type-guarded)
$ErrorActionPreference = 'Stop'
$global:ADB  = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$global:RDIR = "/sdcard/c3"
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

# XmlElement list only — guards against stray strings in the pipeline
function Nodes {
    $d = Doc
    $out = New-Object System.Collections.ArrayList
    foreach ($x in $d.SelectNodes("//node")) {
        if ($x -is [System.Xml.XmlElement]) { $null = $out.Add($x) }
    }
    return $out
}

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

function ClearET([int]$n = 260) {
    Sh ('i=0; while [ $i -lt ' + $n + ' ]; do input keyevent 67; i=$((i+1)); done')
    Start-Sleep -Milliseconds 250
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

function Say($m) { Write-Host $m }

function FocusInfo {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys window | grep mCurrentFocus"
    Write-Host "FOCUS: $o"
    return ($o -join ' ')
}
