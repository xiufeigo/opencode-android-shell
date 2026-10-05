$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repo = "C:\sensorsdata\main\program\opencode-android-shell"
$path = "research/HOUSEKEEPING.md"
$strict = New-Object System.Text.UTF8Encoding($false, $true)
$good = "ca1b2085"

function Get-GitBytes([string]$sha) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "git"
    $psi.Arguments = "-C `"$repo`" cat-file -p `"${sha}:${path}`""
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    $ms = New-Object System.IO.MemoryStream
    $p.StandardOutput.BaseStream.CopyTo($ms)
    $p.WaitForExit()
    return $ms.ToArray()
}

$goodBytes = Get-GitBytes $good
$goodText = $strict.GetString($goodBytes)
$goodLines = $goodText -split "`n"

$curBytes = [System.IO.File]::ReadAllBytes("$repo\$path")
$curLines = [System.Text.Encoding]::UTF8.GetString($curBytes) -split "`n"

"GOOD_COMMIT: $good   good_lines=$($goodLines.Count)"
"CUR: cur_lines=$($curLines.Count)"

# normalize: a line is BAD if it does not round-trip through strict utf8
function IsBad([int]$idx, [byte[]]$bytes) { return $false }

# Build byte-level line map for current
$lineStart = New-Object System.Collections.Generic.List[int]
$lineStart.Add(0)
for ($i=0; $i -lt $curBytes.Length; $i++) { if ($curBytes[$i] -eq 0x0A) { $lineStart.Add($i+1) } }

$report = New-Object System.Collections.Generic.List[object]
for ($li=0; $li -lt $lineStart.Count; $li++) {
    $s = $lineStart[$li]
    $e = if ($li+1 -lt $lineStart.Count) { $lineStart[$li+1]-1 } else { $curBytes.Length-1 }
    $len = $e-$s+1
    if ($len -le 0) { continue }
    $slice = New-Object byte[] $len
    [Array]::Copy($curBytes, $s, $slice, 0, $len)
    $ok = $true
    try { [void]$strict.GetString($slice) } catch { $ok = $false }
    $report.Add([pscustomobject]@{ Line=($li+1); Off=$s; Len=$len; Valid=$ok })
}

"BAD LINES: $((($report | Where-Object { -not $_.Valid })).Count) / $($report.Count)"

# Now: for each BAD line, find best match among goodLines using '?' wildcard
# Build a function: does goodLine match curLine if we treat 0x3F in cur as wildcard
function MatchWithWildcard([string]$cur, [string]$g) {
    if ($cur.Length -ne $g.Length) { return $false }
    $cb = [System.Text.Encoding]::UTF8.GetBytes($cur)
    $gb = [System.Text.Encoding]::UTF8.GetBytes($g)
    for ($i=0; $i -lt $cb.Length; $i++) {
        if ($cb[$i] -ne $gb[$i]) {
            # allow cur byte 0x3F to wildcard-match, but ONLY if the good byte is a lead/continuation
            if ($cb[$i] -eq 0x3F -and $gb[$i] -gt 0x7F) { continue }
            return $false
        }
    }
    return $true
}

$results = New-Object System.Collections.Generic.List[object]
foreach ($r in ($report | Where-Object { -not $_.Valid })) {
    $s = $r.Off; $len = $r.Len
    $slice = New-Object byte[] $len
    [Array]::Copy($curBytes, $s, $slice, 0, $len)
    $curLax = [System.Text.Encoding]::UTF8.GetString($slice)
    $hit = -1
    for ($gi=0; $gi -lt $goodLines.Count; $gi++) {
        if (MatchWithWildcard $curLax $goodLines[$gi]) { $hit = $gi; break }
    }
    $results.Add([pscustomobject]@{
        CurLine = $r.Line
        CurOff  = $r.Off
        CurLen  = $r.Len
        GoodLine = if ($hit -ge 0) { $hit + 1 } else { -1 }
        Recoverable = ($hit -ge 0)
    })
}

""
"=== RECOVERABILITY OF BAD LINES (from commit $good) ==="
foreach ($x in $results) {
    "L{0,-4} off={1,-7} len={2,-6} -> goodL{3,-5} {4}" -f $x.CurLine, $x.CurOff, $x.CurLen, $x.GoodLine, ($(if ($x.Recoverable) {'RECOVERABLE'} else {'NOT-RECOVERABLE'}))
}
$rec = ($results | Where-Object { $_.Recoverable }).Count
$non = ($results | Where-Object { -not $_.Recoverable }).Count
"RECOVERABLE=$rec  NOT_RECOVERABLE=$non"

""
"=== VALID LINE MAP (cur line -> good line) for valid lines ==="
$results | Out-Null
$idx=0
$validCount = ($report | Where-Object { $_.Valid }).Count
"VALID_LINES_IN_CUR=$validCount"
