$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = "C:\sensorsdata\main\program\opencode-android-shell"
$path = "research/HOUSEKEEPING.md"
$strict = New-Object System.Text.UTF8Encoding($false, $true)

$commits = git -C $repo log --format='%H|%ci|%s' -- $path
foreach ($line in $commits) {
    $parts = $line -split '\|', 3
    $sha = $parts[0]; $date = $parts[1]; $subj = $parts[2]
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        & git -C $repo show "${sha}:${path}" 2>$null | Set-Content -LiteralPath $tmp -Encoding utf8NoBOM
    } catch { }
    # read raw bytes from git directly
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "git"
    $psi.Arguments = "-C `"$repo`" cat-file -p `"${sha}:${path}`""
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $psi.StandardOutputEncoding = $null
    $proc = [System.Diagnostics.Process]::Start($psi)
    $ms = New-Object System.IO.MemoryStream
    $proc.StandardOutput.BaseStream.CopyTo($ms)
    $proc.WaitForExit()
    $bytes = $ms.ToArray()
    $status = "VALID_UTF8"
    $badCount = 0
    $qCount = 0
    try { [void]$strict.GetString($bytes) }
    catch { $status = "INVALID_UTF8"; $badCount = -1 }
    # count 0x3F and raw GBK-ish pairs
    $qCount = ($bytes | Where-Object { $_ -eq 0x3F }).Count
    "{0}  {1}  bytes={2,-7} {3,-13} qmark={4,-5} {5}" -f $sha.Substring(0,8), $date.Substring(0,19), $bytes.Length, $status, $qCount, $subj.Substring(0,[Math]::Min(50,$subj.Length))
}
