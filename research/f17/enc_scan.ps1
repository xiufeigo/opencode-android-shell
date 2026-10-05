$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$f = "C:\sensorsdata\main\program\opencode-android-shell\research\HOUSEKEEPING.md"
$b = [System.IO.File]::ReadAllBytes($f)
$strict = New-Object System.Text.UTF8Encoding($false, $true)

function HexRange([byte[]]$arr, [int]$off, [int]$len) {
    $s = [Math]::Max(0, $off)
    $e = [Math]::Min($arr.Length - 1, $off + $len - 1)
    $parts = @()
    for ($i = $s; $i -le $e; $i++) { $parts += $arr[$i].ToString('X2') }
    return ($parts -join ' ')
}

# --- line index ---
$lineStart = New-Object System.Collections.Generic.List[int]
$lineStart.Add(0)
for ($i = 0; $i -lt $b.Length; $i++) {
    if ($b[$i] -eq 0x0A) { $lineStart.Add($i + 1) }
}
$lineCount = $lineStart.Count
"TOTAL_BYTES: $($b.Length)"
"TOTAL_LINES: $lineCount"

# --- per-line strict UTF-8 ---
$bad = New-Object System.Collections.Generic.List[object]
for ($li = 0; $li -lt $lineCount; $li++) {
    $s = $lineStart[$li]
    $e = if ($li + 1 -lt $lineCount) { $lineStart[$li + 1] - 1 } else { $b.Length - 1 }
    $len = $e - $s + 1
    if ($len -le 0) { continue }
    $slice = New-Object byte[] $len
    [Array]::Copy($b, $s, $slice, 0, $len)
    try {
        [void]$strict.GetString($slice)
    } catch {
        $bad.Add([pscustomobject]@{
            Line     = ($li + 1)
            OffStart = $s
            OffEnd   = $e
            Len      = $len
            Hex      = (HexRange $slice 0 ([Math]::Min($len, 120)))
        })
    }
}
"BAD_LINE_COUNT: $($bad.Count)"
""
"=== BAD LINES (line no | byte offset | len | hex) ==="
foreach ($x in $bad) {
    "L$($x.Line) off=$($x.OffStart)..$($x.OffEnd) len=$($x.Len)"
    "   HEX: $($x.Hex)"
    $sliceLen = $x.Len
    $slice = New-Object byte[] $sliceLen
    [Array]::Copy($b, $x.OffStart, $slice, 0, $sliceLen)
    $gbk = [System.Text.Encoding]::GetEncoding(936)
    "   AS_GBK: " + $gbk.GetString($slice)
    try { "   AS_UTF8_LAX: " + [System.Text.Encoding]::UTF8.GetString($slice) } catch { "   AS_UTF8_LAX: <fail>" }
    "   ---"
}
