$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = "C:\sensorsdata\main\program\opencode-android-shell"
$path = "research/HOUSEKEEPING.md"
$good = "ca1b2085"
$out  = "$repo\research\f17\recover_report.txt"

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

$gb = Get-GitBytes $good
$cb = [System.IO.File]::ReadAllBytes("$repo\$path")
$strict = New-Object System.Text.UTF8Encoding($false, $true)

function SplitLines([byte[]]$b) {
    $st = New-Object System.Collections.Generic.List[int]
    $st.Add(0)
    for ($i=0; $i -lt $b.Length; $i++) { if ($b[$i] -eq 0x0A) { $st.Add($i+1) } }
    $arr = @()
    for ($li=0; $li -lt $st.Count; $li++) {
        $s = $st[$li]
        $e = if ($li+1 -lt $st.Count) { $st[$li+1]-1 } else { $b.Length-1 }
        $len = $e-$s+1
        $arr += ,@($s,$len)
    }
    return ,$arr
}

$gl = SplitLines $gb
$cl = SplitLines $cb

$sb = New-Object System.Text.StringBuilder
function W([string]$s) { [void]$sb.AppendLine($s) }

W "GOOD $good bytes=$($gb.Length) lines=$($gl.Count)"
W "CUR  HEAD   bytes=$($cb.Length) lines=$($cl.Count)"
W ("GOOD CRLF_count=" + (($gb | Where-Object {$_ -eq 0x0D}).Count) + " LF_count=" + (($gb | Where-Object {$_ -eq 0x0A}).Count))
W ("CUR  CRLF_count=" + (($cb | Where-Object {$_ -eq 0x0D}).Count) + " LF_count=" + (($cb | Where-Object {$_ -eq 0x0A}).Count))
W ""

# byte-level: cur byte 0x3F may wildcard-match any byte >0x7F
function WildMatch([byte[]]$a, [byte[]]$b) {
    if ($a.Length -ne $b.Length) { return $false }
    for ($i=0; $i -lt $a.Length; $i++) {
        if ($a[$i] -ne $b[$i]) {
            if ($a[$i] -eq 0x3F -and $b[$i] -gt 0x7F) { continue }
            return $false
        }
    }
    return $true
}

W "=== PER-CURRENT-LINE: valid? and best wildcard match in GOOD ==="
$recover = @()
$badList = @()
for ($li=0; $li -lt $cl.Count; $li++) {
    $s = $cl[$li][0]; $len = $cl[$li][1]
    if ($len -le 0) { continue }
    $slice = New-Object byte[] $len
    [Array]::Copy($cb, $s, $slice, 0, $len)
    $ok = $true
    try { [void]$strict.GetString($slice) } catch { $ok = $false }
    if ($ok) { W ("L{0,-4} VALID" -f ($li+1)); continue }

    $badList += ($li+1)
    $hit = -1
    for ($gi=0; $gi -lt $gl.Count; $gi++) {
        $gs = $gl[$gi][0]; $glen = $gl[$gi][1]
        if ($glen -ne $len) { continue }
        $gsl = New-Object byte[] $glen
        [Array]::Copy($gb, $gs, $gsl, 0, $glen)
        if (WildMatch $slice $gsl) { $hit = $gi; break }
    }
    $st = if ($hit -ge 0) { "RECOVERABLE(goodL$($hit+1))" } else { "NOT-RECOVERABLE" }
    if ($hit -ge 0) { $recover += [pscustomobject]@{Cur=$li+1; Good=$hit+1; Off=$s; Len=$len} }
    W ("L{0,-4} off={1,-7} len={2,-6} {3}" -f ($li+1), $s, $len, $st)
}
W ""
W ("BAD_TOTAL=" + $badList.Count + "  RECOVERABLE=" + $recover.Count + "  NOT=" + ($badList.Count - $recover.Count))
W ""
W "=== RECOVERABLE PAIRS (curLine <- goodLine) ==="
foreach ($r in $recover) { W ("cur L$($r.Cur)  <-  good L$($r.Good)   off=$($r.Off) len=$($r.Len)") }
W ""
W "=== GOOD FILE line map (index:off:len:preview) ==="
for ($gi=0; $gi -lt $gl.Count; $gi++) {
    $s = $gl[$gi][0]; $len = $gl[$gi][1]
    $gsl = New-Object byte[] $len
    [Array]::Copy($gb, $s, $gsl, 0, $len)
    $txt = $strict.GetString($gsl)
    $prev = $txt
    if ($prev.Length -gt 60) { $prev = $prev.Substring(0,60) }
    W ("gL{0,-4} off={1,-7} len={2,-6} | {3}" -f ($gi+1), $s, $len, $prev)
}

[System.IO.File]::WriteAllText($out, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
"WROTE: $out"
