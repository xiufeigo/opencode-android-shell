$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = "C:\sensorsdata\main\program\opencode-android-shell"
$path = "research/HOUSEKEEPING.md"
$good = "ca1b2085"
$recovered = "$repo\research\HOUSEKEEPING_RECOVERED_F17.md"
$proof = "$repo\research\f17\proof.txt"

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
$curBefore = [System.IO.File]::ReadAllBytes("$repo\$path")

# write byte-exact recovered copy
[System.IO.File]::WriteAllBytes($recovered, $gb)

$recBytes = [System.IO.File]::ReadAllBytes($recovered)
$curAfter  = [System.IO.File]::ReadAllBytes("$repo\$path")

$sha = [System.Security.Cryptography.SHA256]::Create()
function Get-Hex([byte[]]$b) { return ([BitConverter]::ToString($sha.ComputeHash($b))).Replace('-','') }

$strict = New-Object System.Text.UTF8Encoding($false, $true)
$sb = New-Object System.Text.StringBuilder
function W([string]$s) { [void]$sb.AppendLine($s) }

W "=== PROOF 1: recovered file is byte-identical to git blob ca1b2085:research/HOUSEKEEPING.md ==="
W ("git blob bytes      = " + $gb.Length)
W ("recovered file bytes= " + $recBytes.Length)
W ("git blob  SHA256    = " + (Get-Hex $gb))
W ("recovered  SHA256   = " + (Get-Hex $recBytes))
W ("IDENTICAL           = " + ($gb.Length -eq $recBytes.Length -and (Get-Hex $gb) -eq (Get-Hex $recBytes)))
W ""
W "=== PROOF 2: original research/HOUSEKEEPING.md was NOT modified ==="
W ("SHA256 before = " + (Get-Hex $curBefore))
W ("SHA256 after  = " + (Get-Hex $curAfter))
W ("UNCHANGED     = " + ((Get-Hex $curBefore) -eq (Get-Hex $curAfter)))
W ""
W "=== PROOF 3: strict UTF-8 validity ==="
$okG = $true; $okR = $true; $okC = $true
try { [void]$strict.GetString($gb) } catch { $okG = $false }
try { [void]$strict.GetString($recBytes) } catch { $okR = $false }
try { [void]$strict.GetString($curAfter) } catch { $okC = $false }
W ("ca1b2085 blob   strict-utf8 = $okG")
W ("RECOVERED file  strict-utf8 = $okR")
W ("HEAD  (current) strict-utf8 = $okC   <-- still broken, deliberately untouched")
W ""
W "=== PROOF 4: line-by-line Compare-Object, HEAD(current) vs RECOVERED ==="
$curLax    = [System.Text.Encoding]::UTF8.GetString($curAfter) -split "`n"
$recLines  = $strict.GetString($recBytes) -split "`n"
W ("current lines = " + $curLax.Count + "   recovered lines = " + $recLines.Count)
W ""
$diff = Compare-Object -ReferenceObject $recLines -DifferenceObject $curLax -IncludeEqual:$false
W ("Compare-Object differing records = " + @($diff).Count)
W "(this is EXPECTED to be large: HEAD gained F11-F13 rows AND lost 17 line breaks)"
W ""
W "--- subset check: are the 38 still-VALID current lines present verbatim in RECOVERED? ---"
$recSet = @{}
foreach ($l in $recLines) { $recSet[$l] = $true }
# build current line validity
$lineStart = New-Object System.Collections.Generic.List[int]
$lineStart.Add(0)
for ($i=0; $i -lt $curAfter.Length; $i++) { if ($curAfter[$i] -eq 0x0A) { $lineStart.Add($i+1) } }
$validIdx = @(); $badIdx = @()
for ($li=0; $li -lt $lineStart.Count; $li++) {
    $s = $lineStart[$li]
    $e = if ($li+1 -lt $lineStart.Count) { $lineStart[$li+1]-1 } else { $curAfter.Length-1 }
    $len = $e-$s+1
    if ($len -le 0) { continue }
    $sl = New-Object byte[] $len
    [Array]::Copy($curAfter, $s, $sl, 0, $len)
    $ok=$true; try { [void]$strict.GetString($sl) } catch { $ok=$false }
    if ($ok) { $validIdx += ($li+1) } else { $badIdx += ($li+1) }
}
$missing = @()
foreach ($li in $validIdx) {
    $s = $lineStart[$li-1]
    $e = if ($li -lt $lineStart.Count) { $lineStart[$li]-1 } else { $curAfter.Length-1 }
    $len = $e-$s+1
    $sl = New-Object byte[] $len
    [Array]::Copy($curAfter, $s, $sl, 0, $len)
    $t = $strict.GetString($sl)
    if (-not $recSet.ContainsKey($t)) { $missing += $li }
}
W ("current VALID lines           = " + $validIdx.Count)
W ("  of which NOT found in recovered (i.e. new F11-F13 content) = " + $missing.Count)
W ("  new content line numbers    = " + ($missing -join ', '))
W ("current DAMAGED lines         = " + $badIdx.Count)
W ("  damaged line numbers        = " + ($badIdx -join ', '))
W ""
W "=== PROOF 5: concrete byte-level damage evidence (line 1) ==="
W "GOOD  ca1b2085 L1 tail bytes: " + (( $gb[25..37] | ForEach-Object { $_.ToString('X2') }) -join ' ')
W "CUR   HEAD    L1 tail bytes: " + (( $curAfter[25..37] | ForEach-Object { $_.ToString('X2') }) -join ' ')
W "GOOD  L1 as text: " + $strict.GetString($gb[0..37])
W "CUR   L1 as text: " + [System.Text.Encoding]::UTF8.GetString($curAfter[0..37])
W "-> byte 0x89 (3rd byte of EF BC 89 = U+FF09 fullwidth ')' ) was overwritten with 0x3F '?'"
W ""
W "=== PROOF 6: line-break loss ==="
W ("GOOD  0x0A count = " + (($gb | Where-Object {$_ -eq 0x0A}).Count) + "  0x0D count = " + (($gb | Where-Object {$_ -eq 0x0D}).Count))
W ("CUR   0x0A count = " + (($curAfter | Where-Object {$_ -eq 0x0A}).Count) + "  0x0D count = " + (($curAfter | Where-Object {$_ -eq 0x0D}).Count))
W "-> 17 LF bytes were dropped and 1 stray CR appeared: the mangling is LOSSY, not a pure re-encoding"

[System.IO.File]::WriteAllText($proof, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "PROOF WRITTEN"
Get-Content $proof -Encoding UTF8 | Select-Object -First 40

