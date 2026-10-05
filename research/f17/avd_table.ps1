$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = "C:\sensorsdata\main\program\opencode-android-shell"
$out  = "$repo\research\f17\avd_table.txt"
$avdRoot = "$env:USERPROFILE\.android\avd"

$rows = @()
foreach ($ini in (Get-ChildItem -Path $avdRoot -Filter '*.ini' -File -Force | Sort-Object Name)) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($ini.Name)
    $dirPath = Join-Path $avdRoot "$name.avd"
    $bytes = 0L; $fc = 0
    if (Test-Path -LiteralPath $dirPath) {
        foreach ($f in (Get-ChildItem -LiteralPath $dirPath -Recurse -File -Force -ErrorAction SilentlyContinue)) { $bytes += $f.Length; $fc++ }
    }
    $bytes += $ini.Length; $fc++
    # ownership per the in-repo rule from HOUSEKEEPING.md L5 / gL13:
    #   ours = OC_* and Medium_Desktop_B ; everything else is the product owner's
    $own = if ($name -like 'OC_*' -or $name -eq 'Medium_Desktop_B') { 'OURS' } else { 'PRODUCT' }
    # evidence: userdata-qemu.img / snapshot dirs
    $snapshots = 0
    $sp = Join-Path $dirPath 'snapshots'
    if (Test-Path -LiteralPath $sp) {
        $snapshots = (Get-ChildItem -LiteralPath $sp -Directory -Force -ErrorAction SilentlyContinue).Count
    }
    $rows += [pscustomobject]@{
        Name=$name; Owner=$own; Bytes=$bytes; Files=$fc; Snapshots=$snapshots
        DirMTime=(Get-Item -LiteralPath $dirPath -ErrorAction SilentlyContinue).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
        IniMTime=$ini.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
    }
}
$rows = $rows | Sort-Object Bytes -Descending

$sb = New-Object System.Text.StringBuilder
function W([string]$s) { [void]$sb.AppendLine($s) }

W "# AVD INVENTORY - F17 (report only, nothing deleted)"
W "root: $avdRoot"
W "generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
W ""
W "rank | AVD | owner | bytes | GiB | files | snapshots | .ini mtime | .avd dir mtime"
W "-----"
$rank=0
foreach ($r in $rows) {
    $rank++
    W ("{0,2} | {1,-26} | {2,-7} | {3,14} | {4,7:N2} | {5,6} | {6,3} | {7} | {8}" -f $rank,$r.Name,$r.Owner,$r.Bytes,($r.Bytes/1GB),$r.Files,$r.Snapshots,$r.IniMTime,$r.DirMTime)
}
W ""
$ours = $rows | Where-Object { $_.Owner -eq 'OURS' }
$prod = $rows | Where-Object { $_.Owner -eq 'PRODUCT' }
$ob = ($ours | Measure-Object -Property Bytes -Sum).Sum
$pb = ($prod | Measure-Object -Property Bytes -Sum).Sum
$tb = $ob + $pb
W ("OURS count={0}  bytes={1}  GiB={2:N2}" -f $ours.Count, $ob, ($ob/1GB))
W ("PRODUCT count={0} bytes={1}  GiB={2:N2}" -f $prod.Count, $pb, ($pb/1GB))
W ("TOTAL count={0}  bytes={1}  GiB={2:N2}" -f ($rows.Count), $tb, ($tb/1GB))
W ("OURS share = {0:N1}%" -f (100*$ob/$tb))
W ""
W "=== OURS detail (deletion candidates) ==="
$rank=0
foreach ($r in $ours) {
    $rank++
    W ("{0,2}. {1,-26} {2,14} bytes  {3,7:N2} GiB  snapshots={4}" -f $rank,$r.Name,$r.Bytes,($r.Bytes/1GB),$r.Snapshots)
}
W ""
W "=== disk ==="
$d = Get-PSDrive C
W ("C: Used={0} ({1:N2} GiB)  Free={2} ({3:N2} GiB)  Total={4} ({5:N2} GiB)" -f $d.Used,($d.Used/1GB),$d.Free,($d.Free/1GB),($d.Used+$d.Free),(($d.Used+$d.Free)/1GB))

[System.IO.File]::WriteAllText($out, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
"WROTE: $out"
