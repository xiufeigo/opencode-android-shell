$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$out  = Join-Path $root 'research\web\_g2_size.txt'
Remove-Item $out -EA SilentlyContinue
function W($t){ $t | Tee-Object -FilePath $out -Append }
Add-Type -AssemblyName System.IO.Compression.FileSystem

$OLD_MD5 = '8dcffc572ad469f472f61594f12ea990'; $OLD_SIZE = 8636901
$NEW_MD5 = 'b716cbeb4c66e55fcb0f497640ae5e0c'; $NEW_SIZE = 5145604

W "################ GAP#3  why did debug shrink 8.64 MB -> 5.15 MB ################"
W ""
W "=== [STEP 0] Can the OLD binary (md5 $OLD_MD5, $OLD_SIZE B) be obtained? ==="
W "-- 0a. git history: was research/deliver/opencode-shell-web.apk EVER committed? --"
$g = (git log --all --oneline -- research/deliver/opencode-shell-web.apk 2>&1 | Out-String).Trim()
W ("   git log -- research/deliver/opencode-shell-web.apk => [{0}]" -f $(if($g){$g}else{'<EMPTY>'}))
W "   => the file was NEVER tracked, so `git show <commit>:research/deliver/opencode-shell-web.apk` CANNOT work."
W ""
W "-- 0b. every .apk blob that exists anywhere in git history --"
$blobs = (git rev-list --all --objects 2>$null) | Where-Object { $_ -match '\.apk$' }
foreach ($b in $blobs) {
  $p = $b -split ' ',2
  $sz = (git cat-file -s $p[0] 2>$null)
  W ("   blob={0} path={1} size={2} B" -f $p[0].Substring(0,8), $p[1], $sz)
}
W ("   => 1 blob, and it is $(if($blobs){($blobs[0] -split ' ')[1]}else{'none'}) -- NOT research/deliver/opencode-shell-web.apk")
W ""
W "-- 0c. narrow disk search for the old binary by md5 --"
$foundOld = $false
foreach ($d in @('research','dist','webapp\build')) {
  Get-ChildItem $d -Recurse -Filter *.apk -EA SilentlyContinue | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower()
    $mark = if ($h -eq $OLD_MD5) { '   <<<< OLD 8.64MB MATCH' } else { '' }
    W ("   {0,10} B  {1}  {2}{3}" -f $_.Length, $h, $_.FullName.Replace($root+'\',''), $mark)
    if ($h -eq $OLD_MD5) { $script:foundOld = $true }
  }
}
W ("   OLD BINARY PRESENT ON DISK = {0}" -f $foundOld)

W ""
W "=== [STEP 1] Entry-level breakdown of the NEW debug APK (what is actually in the 5.15 MB) ==="
$apk = Join-Path $root 'research\deliver\opencode-shell-web.apk'
$z = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
  $ents = $z.Entries | ForEach-Object { [pscustomobject]@{ Name=$_.FullName; Raw=$_.Length; Comp=$_.CompressedLength } }
} finally { $z.Dispose() }
W ("total entries = {0}   file size = {1} B" -f $ents.Count, (Get-Item $apk).Length)
W ""
W "-- grouped by top-level bucket (raw uncompressed bytes) --"
$ents | Group-Object { if ($_.Name -match '/') { ($_.Name -split '/')[0] + '/' } else { $_.Name } } |
  ForEach-Object {
    [pscustomobject]@{ Bucket=$_.Name; Entries=$_.Count; RawBytes=(($_.Group | Measure-Object Raw -Sum).Sum) }
  } | Sort-Object RawBytes -Descending | Format-Table -AutoSize | Out-String -Width 120 | Write-Output

W "-- top 15 single largest entries by raw size --"
$ents | Sort-Object Raw -Descending | Select-Object -First 15 | Format-Table -AutoSize | Out-String -Width 140 | Write-Output

W ""
W "=== [STEP 2] Which webapp/** commits could change what gets packaged? ==="
W "-- build config (webapp/build.gradle.kts) history --"
$bc = git log --oneline -- webapp/build.gradle.kts
if ($bc) { $bc | ForEach-Object { W ("   $_") } } else { W "   <no commits touching build.gradle.kts>" }
W ""
W "-- current dependency / packaging config --"
Select-String -Path (Join-Path $root 'webapp\build.gradle.kts') -Pattern 'implementation|api |minify|shrink|r8|applicationIdSuffix|signingConfig|jniLibs|packaging|abiFilters' -EA SilentlyContinue |
  Select-Object -First 40 | ForEach-Object { W ("   L{0}: {1}" -f $_.LineNumber, $_.Line.Trim()) }
