. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Stop'

Write-Host '=== 1. APK PROBE POSITIVE CONTROL (validate the substring method) ==='
$tmp = Join-Path $env:TEMP 'oc_probe'
$apkLocal = Join-Path $tmp 'oc.apk'
if(-not (Test-Path $apkLocal)){ Q pull ((Q shell "pm path com.xiufeigo.opencodeshell" | Out-String).Trim() -replace '^package:','') $apkLocal | Out-Null }
$bytes = [IO.File]::ReadAllBytes($apkLocal)
$ascii = [Text.Encoding]::ASCII.GetString($bytes)
Write-Host "apk bytes=$($bytes.Length)"
Write-Host '  -- POSITIVE CONTROLS (must be FOUND for the method to be valid) --'
foreach($pat in @('SshDialog','SshModel','SshFormError','PasswordRequired','opencodeshell','BringIntoViewRequester','validateSshForm','SshAuthMethod')){
  Write-Host ("     {0,-30} {1}" -f $pat, $(if($ascii.Contains($pat)){'FOUND'}else{'ABSENT  <-- control FAILED, method unusable'}))
}
Write-Host '  -- SUBJECT (the v98 fix) --'
foreach($pat in @('SshFormReach','SshFormField','sshFormErrorField','sshFormDrawnFields','sshFormRequiredFields','sshFormFieldsAllReachable','revealRefusedField')){
  Write-Host ("     {0,-30} {1}" -f $pat, $(if($ascii.Contains($pat)){'FOUND'}else{'absent'}))
}
Write-Host '  -- classes.dex timestamps for the record --'
(Q shell "ls -l /data/app/~~XrHSiACGwVk-hrAsYSjsHw==/com.xiufeigo.opencodeshell-5e2kFTvvZ-kU3mVOSnwB-Q==/" | Out-String).Trim()
Write-Host "  workspace SshFormReach.kt mtime: $((Get-Item 'C:\sensorsdata\main\program\opencode-android-shell\app\src\main\java\com\xiufeigo\opencodeshell\ui\servers\SshFormReach.kt' -ErrorAction SilentlyContinue).LastWriteTime)"

Write-Host ''
Write-Host '=== 2. DEEP SCROLL SWEEP (correct gesture: 100px / 300ms) ==='
$rows = New-Object System.Collections.ArrayList
$seenPw = $false; $seenFields = @{}
for($i=0;$i -le 14;$i++){
    $n = NodesNow "C01_sw$i"
    $ime = ImeShown
    $et = Fields $n
    $pw  = $n | Select-String -Pattern 'pw=true' | Select-Object -First 1
    $lbl = ($n | Select-String -Pattern 'Host or SSH command|Server name|^Authentication\t|^Password\t|^Passphrase\t|^Private key\t' | ForEach-Object { ($_.Line -split "`t")[0] }) -join ' | '
    $geom = ($n | Select-String -Pattern 'EditText\t' | Where-Object {$_ -match '\t\[138,'} | ForEach-Object { (($_ -split "`t")[1]) }) -join ' '
    [void]$rows.Add("STEP $i IME=$ime EditTexts=$($et.Count) pw=$(if($pw){'YES'}else{'NO'})")
    [void]$rows.Add("   GEOM $geom")
    [void]$rows.Add("   LBL  $lbl")
    if($pw){ $seenPw = $true; $seenFields["pw"] = $pw.Line }
    foreach($f in $et){ $r = RectOf $f; $seenFields[("y=$($r[1])")] = $f }
    if($i -lt 14){ Q shell 'input swipe 1018 1650 1018 1550 300' | Out-Null; Start-Sleep -Milliseconds 700 }
}
$rows | ForEach-Object { Write-Host $_ }
$rows | Out-File (Join-Path $global:DIR 'C01_scroll_sweep.txt') -Encoding utf8
Write-Host "PASSWORD_FIELD_EVER_SEEN=$seenPw"
Write-Host '--- distinct EditText geometries seen across all 15 scroll positions ---'
$seenFields.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host "  $($_.Value)" }
ShotN 'C01_deep_scroll'

Write-Host ''
Write-Host '=== 3. UI SSH ROWS vs GROUND TRUTH ==='
$GT = GtCount
Write-Host "GT_ENTRIES=$GT"
$null = Uidump 'C02_rows'
$n = NodesNow 'C02r'
Write-Host '--- panel content (candidate rows) ---'
($n | Select-String -Pattern 'SSH server|Connect|Disconnect|:409|:22|localhost|127.0.0.1|More options|E2ECHK|Disconnected|Connected|Connecting' | ForEach-Object { $_.Line }) | ForEach-Object { Write-Host "  $_" }
Write-Host '--- Server actions buttons (one per row) ---'
$sa = @($n | Select-String -Pattern 'Server actions')
Write-Host "SERVER_ACTIONS_COUNT=$($sa.Count)"
$sa | ForEach-Object { Write-Host "  $($_.Line)" }
Write-Host "DONE-C"
