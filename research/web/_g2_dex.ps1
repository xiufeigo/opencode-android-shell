$ErrorActionPreference = 'Stop'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$work = Join-Path $root 'research\web\_g2_work'
New-Item -ItemType Directory -Force -Path $work | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

$rel = Join-Path $root 'research\deliver\opencode-shell-web-release-nor8.apk'
$dbg = Join-Path $root 'research\deliver\opencode-shell-web.apk'

function Get-DexEntries($apk) {
  $z = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $z.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' } |
      Sort-Object { [int]($_.Name -replace '\D','') } |
      ForEach-Object {
        [pscustomobject]@{
          Name = $_.Name; Size = $_.Length;
          Crc  = ('{0:x8}' -f $_.Crc32);
          Comp = $_.CompressedLength
        }
      }
  } finally { $z.Dispose() }
}

function Expand-Dex($apk, $dest) {
  if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  $z = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    foreach ($e in $z.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) {
      $out = Join-Path $dest $e.Name
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
    }
  } finally { $z.Dispose() }
}

function Md5($p) { (Get-FileHash $p -Algorithm MD5).Hash.ToLower() }
function Sha256($p) { (Get-FileHash $p -Algorithm SHA256).Hash.ToLower() }

Write-Output "############ GAP#1  METHOD: unzip -l equivalent (System.IO.Compression) + per-dex md5 + dexdump  ############"
Write-Output ""
Write-Output "=== [1] APK file identity ==="
foreach ($a in @($rel, $dbg)) {
  $fi = Get-Item $a
  Write-Output ("{0}  bytes={1}  md5={2}" -f $fi.Name, $fi.Length, (Md5 $fi.FullName))
}
Write-Output ""
Write-Output "=== [2] ALL classes*.dex entries (unzip -l equivalent) ==="
Write-Output "--- RELEASE (opencode-shell-web-release-nor8.apk) ---"
$relE = Get-DexEntries $rel
$relE | Format-Table -AutoSize | Out-String -Width 200 | Write-Output
Write-Output ("RELEASE dex entry count = {0}" -f $relE.Count)
Write-Output ""
Write-Output "--- DEBUG (opencode-shell-web.apk) ---"
$dbgE = Get-DexEntries $dbg
$dbgE | Format-Table -AutoSize | Out-String -Width 200 | Write-Output
Write-Output ("DEBUG dex entry count = {0}" -f $dbgE.Count)
Write-Output ""
Write-Output "=== [3] per-dex byte comparison (md5 / sha256 / size) ==="
Expand-Dex $rel (Join-Path $work 'dex_release')
Expand-Dex $dbg (Join-Path $work 'dex_debug')
$rows = @()
$names = ($relE.Name + $dbgE.Name) | Sort-Object -Unique
foreach ($n in $names) {
  $rp = Join-Path $work "dex_release\$n"
  $dp = Join-Path $work "dex_debug\$n"
  $r = if (Test-Path $rp) { [pscustomobject]@{ exists=$true;  size=(Get-Item $rp).Length; md5=(Md5 $rp); sha=(Sha256 $rp) } } else { [pscustomobject]@{ exists=$false; size=0; md5='-'; sha='-' } }
  $d = if (Test-Path $dp) { [pscustomobject]@{ exists=$true;  size=(Get-Item $dp).Length; md5=(Md5 $dp); sha=(Sha256 $dp) } } else { [pscustomobject]@{ exists=$false; size=0; md5='-'; sha='-' } }
  $rows += [pscustomobject]@{
    dex = $n
    rel_size = $r.size; dbg_size = $d.size
    rel_md5 = $r.md5;   dbg_md5 = $d.md5
    IDENTICAL = ($r.exists -and $d.exists -and $r.sha -eq $d.sha)
  }
}
$rows | Format-Table -AutoSize | Out-String -Width 250 | Write-Output
$diffCount = ($rows | Where-Object { -not $_.IDENTICAL }).Count
Write-Output ("ENTRY-WISE: total={0}  identical={1}  differing={2}" -f $rows.Count, ($rows.Count - $diffCount), $diffCount)
$rows | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $root 'research\web\_g2_dex_entries.json') -Encoding UTF8
