# Local-only: extract classes*.dex from the pulled APK and scan the DECOMPRESSED dex.
# (Scanning the raw zip failed: dex payloads are DEFLATE-compressed inside the zip.)
$ErrorActionPreference = 'Stop'
$tmp = Join-Path $env:TEMP 'oc_probe'
$apkLocal = Join-Path $tmp 'oc.apk'
$ext = Join-Path $tmp 'ext'
if(Test-Path $ext){ Remove-Item $ext -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ext | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apkLocal)
$dexFiles = $zip.Entries | Where-Object { $_.FullName -like 'classes*.dex' }
Write-Host "apk=$apkLocal  entries=$($zip.Entries.Count)  dex entries=$($dexFiles.Count)"
$all = New-Object System.Text.StringBuilder
foreach($e in $dexFiles){
    Write-Host "  dex: $($e.FullName) raw=$($e.Length) compressed=$($e.CompressedLength)"
    $ms = New-Object IO.MemoryStream
    $s = $e.Open(); $s.CopyTo($ms); $s.Close()
    $bytes = $ms.ToArray(); $ms.Close()
    $null = $all.Append([Text.Encoding]::ASCII.GetString($bytes))
}
$zip.Dispose()
$ascii = $all.ToString()
Write-Host "total decompressed dex chars=$($ascii.Length)"
Write-Host ''
Write-Host '=== POSITIVE CONTROLS (must be FOUND) ==='
$ctrl = @('SshDialog','SshModel','SshFormError','PasswordRequired','opencodeshell','validateSshForm','SshAuthMethod','TargetRequired','AddSSHserver')
foreach($p in $ctrl){
    $h = $ascii.Contains($p)
    Write-Host ("   {0,-24} {1}" -f $p, $(if($h){'FOUND'}else{'ABSENT  <-- CONTROL FAILED'}))
}
Write-Host ''
Write-Host '=== SUBJECT: v98 D3 form-reachability fix ==='
$subj = @('SshFormReach','SshFormField','sshFormErrorField','sshFormDrawnFields','sshFormRequiredFields','sshFormFieldsAllReachable','revealRefusedField','PrivateKeyPath','Passphrase')
foreach($p in $subj){
    $h = $ascii.Contains($p)
    Write-Host ("   {0,-30} {1}" -f $p, $(if($h){'FOUND-IN-DEX'}else{'absent'}))
}
