# u1c_lib.ps1 — U1-c verification helpers (emulator-5556 only)
$ErrorActionPreference = 'Stop'
$ADB   = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV   = "emulator-5556"
$DUMP  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
$PKG   = "com.xiufeigo.opencodeshell"

function A { param([string]$c) & $ADB -s $DEV shell $c }

function Get-ImeState {
    $r = (A "dumpsys input_method | grep -E 'mInputShown'") -join "`n"
    $shown = if ($r -match 'mInputShown=true') { $true } else { $false }
    [pscustomobject]@{ Raw = $r.Trim(); Shown = $shown }
}

function Get-ImeTop {
    # IME top edge = top of the IME window frame
    $r = (A "dumpsys window windows | grep -A3 'InputMethod'") -join "`n"
    if ($r -match 'frame=\[0,(\d+),') { [int]$Matches[1] } else { $null }
}

function Get-Focus {
    (A "dumpsys window | grep mCurrentFocus") -join "`n"
}

# Dump UI (compressed) and pull to disk, with retry on transient small dumps
function Dump-UI {
    param([string]$Tag)
    $devPath = "/sdcard/u1c_$Tag.xml"
    $local = Join-Path $DUMP "u1c_$Tag.xml"
    for ($try=0; $try -lt 4; $try++) {
        A "uiautomator dump --compressed $devPath" | Out-Null
        & $ADB -s $DEV pull $devPath $local 2>&1 | Out-Null
        A "rm -f $devPath" | Out-Null
        if ((Test-Path $local) -and (Get-Item $local).Length -gt 500) { return $local }
        Start-Sleep -Milliseconds 800
    }
    throw "dump failed after retries: $Tag"
}

# Parse a dump into node records
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

# Does this node's subtree contain a node with the given text?
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

# Measure a frame: timeline box, queue bar, overlap, readable, Steer/x, row height
function Measure-Frame {
    param([string]$Path)
    $nodes = Get-Nodes $Path

    # Queue action controls: text 'Steer' and 'Remove' (the x mark)
    $steer = $nodes | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' } | Select-Object -First 1
    $xmark = $nodes | Where-Object { $_.Text -eq 'Remove' -or $_.Desc -eq 'Remove' } | Select-Object -First 1

    # Wide scrollable containers = candidates for timeline pane / queue bar viewport
    $wides = $nodes | Where-Object { $_.Scroll -and $_.W -gt 900 -and $_.B }

    # Every node whose subtree holds Steer or Remove
    $holders = $nodes | Where-Object { $_.B -and $_.W -gt 500 -and ((Subtree-Has $_.Node 'Steer') -or (Subtree-Has $_.Node 'Remove')) }

    # Queue bar = INNERMOST such container (smallest area) => the visible queue band,
    # never the root/decor node that merely contains it.
    $qrow = $holders | Sort-Object { $_.W * $_.H } | Select-Object -First 1
    $qvp  = $holders | Where-Object { $_.Scroll } | Sort-Object { $_.W * $_.H } | Select-Object -First 1
    if (-not $qvp) { $qvp = $qrow }

    # Queue row = the child of the bar that actually carries the 'Queued' label
    $qline = $nodes | Where-Object { $_.B -and $_.W -gt 1000 -and $_.W -lt 1340 -and (Subtree-Has $_.Node 'Queued') } |
             Sort-Object { $_.W * $_.H } | Select-Object -First 1

    # Timeline pane: wide scrollable that does NOT hold queue actions
    $tl  = $wides | Where-Object { -not ((Subtree-Has $_.Node 'Steer') -or (Subtree-Has $_.Node 'Remove')) } |
           Sort-Object { $_.B.Y1 } | Select-Object -First 1

    # composer EditText
    $et = $nodes | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1

    $res = [ordered]@{
        file       = Split-Path $Path -Leaf
        timeline   = $null; queueBar = $null; queueVP = $null; queueLine = $null
        overlap    = $null; readable  = $null
        steer      = $null; xmark = $null
        etH = $null; etB = $null
        tlH = $null; qH = $null
    }

    if ($tl)   { $res.timeline = "$($tl.B.X1),$($tl.B.Y1) - $($tl.B.X2),$($tl.B.Y2)"; $res.tlH = $tl.H }
    if ($qrow) { $res.queueBar = "$($qrow.B.X1),$($qrow.B.Y1) - $($qrow.B.X2),$($qrow.B.Y2)"; $res.qH = $qrow.H }
    if ($qvp)  { $res.queueVP  = "$($qvp.B.X1),$($qvp.B.Y1) - $($qvp.B.X2),$($qvp.B.Y2)" }
    if ($qline){ $res.queueLine = "$($qline.B.X1),$($qline.B.Y1) - $($qline.B.X2),$($qline.B.Y2)" }
    if ($et)   { $res.etH = $et.H; $res.etB = "$($et.B.X1),$($et.B.Y1) - $($et.B.X2),$($et.B.Y2)" }

    if ($steer){ $res.steer = [ordered]@{ b="$($steer.B.X1),$($steer.B.Y1)-$($steer.B.X2),$($steer.B.Y2)"; w=$steer.W; h=$steer.H; clk=$steer.Click } }
    if ($xmark){ $res.xmark = [ordered]@{ b="$($xmark.B.X1),$($xmark.B.Y1)-$($xmark.B.X2),$($xmark.B.Y2)"; w=$xmark.W; h=$xmark.H; clk=$xmark.Click } }

    # readable = timeline height - |timeline INTERSECT queue bar|
    # The queue bar's vertical footprint = y-union of EVERY queue-bar node
    # (band container, row, Steer, Remove) => the most conservative reading.
    if ($tl) {
        $parts = @()
        foreach ($q in @($qrow,$qvp,$qline,$steer,$xmark)) { if ($q -and $q.B) { $parts += $q.B } }
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
    "tapped '$($N.Text)' at $cx,$cy"
}

# Type text in <=8 char chunks, space = keyevent 62
function Type-Chunked {
    param([string]$Text)
    $i = 0
    while ($i -lt $Text.Length) {
        $len = [Math]::Min(8, $Text.Length - $i)
        $seg = $Text.Substring($i, $len)
        $esc = $seg -replace ' ', '%s'   # never send literal spaces via input text
        A "input text $esc" | Out-Null
        $i += $len
        if ($i -lt $Text.Length) { A "input keyevent 62" | Out-Null }
    }
}
