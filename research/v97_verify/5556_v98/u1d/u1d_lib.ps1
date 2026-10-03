# u1d_lib.ps1 — U1-d verification helpers (emulator-5556 ONLY)
$ErrorActionPreference = 'Stop'
$ADB  = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV  = "emulator-5556"
$DUMP = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1d\dumps"
$PKG  = "com.xiufeigo.opencodeshell"
$PFX  = "E2ECHK5556u1d"

function A { param([string]$c) & $ADB -s $DEV shell $c }

function Get-Focus {
    (A "dumpsys window | grep mCurrentFocus") -join "`n"
}

# HARD gate: focus must be this app. Returns $true / throws.
function Assert-Focus {
    param([string]$Where)
    $f = Get-Focus
    if ($f -match 'com\.xiufeigo\.opencodeshell') { return $f }
    throw "FOCUS LOST at $Where -> BLOCKED. focus=$f"
}

function Get-ImeState {
    $r = (A "dumpsys input_method | grep -E 'mInputShown'") -join "`n"
    $shown = if ($r -match 'mInputShown=true') { $true } else { $false }
    [pscustomobject]@{ Raw = $r.Trim(); Shown = $shown }
}

function Get-ImeTop {
    $r = (A "dumpsys window windows | grep -A3 'InputMethod'") -join "`n"
    if ($r -match 'frame=\[0,(\d+),') { [int]$Matches[1] } else { $null }
}

function Dump-UI {
    param([string]$Tag)
    $devPath = "/sdcard/u1d_$Tag.xml"
    $local   = Join-Path $DUMP "u1d_$Tag.xml"
    for ($try=0; $try -lt 4; $try++) {
        A "uiautomator dump --compressed $devPath" | Out-Null
        & $ADB -s $DEV pull $devPath $local 2>&1 | Out-Null
        A "rm -f $devPath" | Out-Null
        if ((Test-Path $local) -and (Get-Item $local).Length -gt 500) { return $local }
        Start-Sleep -Milliseconds 800
    }
    throw "dump failed after retries: $Tag"
}

function Get-Nodes {
    param([string]$Path)
    [xml]$x = Get-Content -Raw -Encoding UTF8 $Path
    $out = New-Object System.Collections.Generic.List[object]
    $stack = New-Object System.Collections.Generic.Stack[object]
    $stack.Push($x.hierarchy.node)
    while ($stack.Count -gt 0) {
        $n = $stack.Pop()
        $b = $null
        if ($n.bounds -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
            $b = [pscustomobject]@{
                X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]
                X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
            }
        }
        $rec = [pscustomobject]@{
            Class = [string]$n.class
            Text  = [string]$n.text
            Desc  = [string]$n.'content-desc'
            Id    = [string]$n.'resource-id'
            Click = ([string]$n.clickable -eq 'true')
            Scroll= ([string]$n.scrollable -eq 'true')
            B     = $b
            W = if ($b) { $b.X2 - $b.X1 } else { 0 }
            H = if ($b) { $b.Y2 - $b.Y1 } else { 0 }
            Node = $n
        }
        $out.Add($rec)
        foreach ($c in $n.node) { $stack.Push($c) }
    }
    $out
}

function Subtree-Has {
    param($Node, [string]$Text)
    $stack = New-Object System.Collections.Generic.Stack[object]
    $stack.Push($Node)
    while ($stack.Count -gt 0) {
        $n = $stack.Pop()
        if ([string]$n.text -eq $Text) { return $true }
        if ([string]$n.'content-desc' -eq $Text) { return $true }
        foreach ($c in $n.node) { $stack.Push($c) }
    }
    $false
}

# Count how many queue rows are in the tree: nodes carrying a 'Queued' label descendant
function Get-QueueRowNodes {
    param($Nodes)
    $Nodes | Where-Object { $_.B -and $_.W -gt 1000 -and $_.W -lt 1360 -and (Subtree-Has $_.Node 'Queued') } |
        Sort-Object { $_.W * $_.H }
}

function Measure-Frame {
    param([string]$Path)
    $nodes = Get-Nodes $Path

    $steer = $nodes | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' } | Select-Object -First 1
    $xmark = $nodes | Where-Object { $_.Text -eq 'Remove' -or $_.Desc -eq 'Remove' } | Select-Object -First 1

    $wides = $nodes | Where-Object { $_.Scroll -and $_.W -gt 900 -and $_.B }
    $holders = $nodes | Where-Object { $_.B -and $_.W -gt 500 -and ((Subtree-Has $_.Node 'Steer') -or (Subtree-Has $_.Node 'Remove')) }
    $qrow = $holders | Sort-Object { $_.W * $_.H } | Select-Object -First 1
    $qvp  = $holders | Where-Object { $_.Scroll } | Sort-Object { $_.W * $_.H } | Select-Object -First 1
    if (-not $qvp) { $qvp = $qrow }
    $qline = Get-QueueRowNodes $nodes | Select-Object -First 1

    $tl = $wides | Where-Object { -not ((Subtree-Has $_.Node 'Steer') -or (Subtree-Has $_.Node 'Remove')) } |
          Sort-Object { $_.B.Y1 } | Select-Object -First 1
    $et = $nodes | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1

    # row geometry: every 'Queued'-labelled row node, dedup by y
    $rows = Get-QueueRowNodes $nodes
    $rowYs = @()
    foreach ($r in $rows) { $rowYs += $r.B.Y1 }
    $rowYs = $rowYs | Sort-Object -Unique
    $rowHs = @()
    foreach ($r in $rows) { $rowHs += $r.H }
    $rowHs = $rowHs | Sort-Object -Unique

    $res = [ordered]@{
        file      = Split-Path $Path -Leaf
        timeline  = $null; queueBar = $null; queueVP = $null
        overlap   = $null; readable = $null
        steer = $null; xmark = $null
        etH = $null; tlH = $null; qH = $null
        rowCount = $rows.Count; rowYs = ($rowYs -join ','); rowHs = ($rowHs -join ',')
        steerCount = @($nodes | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' }).Count
        xmarkCount = @($nodes | Where-Object { $_.Text -eq 'Remove' -or $_.Desc -eq 'Remove' }).Count
    }
    if ($tl)   { $res.timeline = "$($tl.B.X1),$($tl.B.Y1) - $($tl.B.X2),$($tl.B.Y2)"; $res.tlH = $tl.H }
    if ($qvp)  { $res.queueVP = "$($qvp.B.X1),$($qvp.B.Y1) - $($qvp.B.X2),$($qvp.B.Y2)" }
    if ($et)   { $res.etH = $et.H }
    if ($steer){ $res.steer = "b=$($steer.B.X1),$($steer.B.Y1)-$($steer.B.X2),$($steer.B.Y2) w=$($steer.W) h=$($steer.H) clk=$($steer.Click)" }
    if ($xmark){ $res.xmark = "b=$($xmark.B.X1),$($xmark.B.Y1)-$($xmark.B.X2),$($xmark.B.Y2) w=$($xmark.W) h=$($xmark.H) clk=$($xmark.Click)" }

    if ($tl) {
        $parts = @()
        foreach ($q in @($qrow,$qvp,$qline,$steer,$xmark)) { if ($q -and $q.B) { $parts += $q.B } }
        foreach ($r in $rows) { $parts += $r.B }
        if ($parts.Count -gt 0) {
            $qY1 = ($parts | Measure-Object -Property Y1 -Minimum).Minimum
            $qY2 = ($parts | Measure-Object -Property Y2 -Maximum).Maximum
            $res.queueBar = "y=$qY1..$qY2 (h=$($qY2-$qY1))"
            $ovTop = [Math]::Max($tl.B.Y1, $qY1)
            $ovBot = [Math]::Min($tl.B.Y2, $qY2)
            $res.overlap = [Math]::Max(0, $ovBot - $ovTop)
            $res.readable = $tl.H - $res.overlap
        }
    }
    [pscustomobject]$res
}

function Find-Exact {
    param($Nodes, [string]$Text, [int]$YMin = -9999, [int]$YMax = 99999)
    $Nodes | Where-Object { ($_.Text -eq $Text) -and $_.B -and ($_.B.Y1 -ge $YMin) -and ($_.B.Y1 -le $YMax) } | Select-Object -First 1
}

function Tap-Node {
    param($N)
    $cx = [int](($N.B.X1 + $N.B.X2) / 2)
    $cy = [int](($N.B.Y1 + $N.B.Y2) / 2)
    A "input tap $cx $cy" | Out-Null
    "tapped at $cx,$cy"
}

# Type in <=8 char chunks, space = keyevent 62, never %s
function Type-Chunked {
    param([string]$Text)
    $i = 0
    while ($i -lt $Text.Length) {
        $len = [Math]::Min(8, $Text.Length - $i)
        $seg = $Text.Substring($i, $len)
        A "input text $seg" | Out-Null
        $i += $len
        if ($i -lt $Text.Length) { A "input keyevent 62" | Out-Null }
    }
}

function Shot {
    param([string]$Tag)
    $devPath = "/sdcard/u1d_$Tag.png"
    $local   = Join-Path $DUMP "u1d_$Tag.png"
    A "screencap -p $devPath" | Out-Null
    & $ADB -s $DEV pull $devPath $local 2>&1 | Out-Null
    A "rm -f $devPath" | Out-Null
    if (Test-Path $local) { return $local } else { throw "shot failed $Tag" }
}

function Log {
    param([string]$Text)
    $Text | Tee-Object -FilePath (Join-Path $DUMP "u1d_run.log") -Append
}
