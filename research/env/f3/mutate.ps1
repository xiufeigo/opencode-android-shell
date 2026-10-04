$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location "C:\sensorsdata\main\program\opencode-android-shell"
$bk = "research\env\f3\revert_backup"
# GBK file: use a lenient decoder so byte 0x80-style sequences don't throw
$enc = [System.Text.Encoding]::GetEncoding(936, [System.Text.EncoderFallback]::ReplacementFallback, [System.Text.DecoderFallback]::ReplacementFallback)

function RunTests($tag) {
  $r = .\gradlew.bat :webapp:testDebugUnitTest --rerun-tasks --console=plain --offline 2>&1
  $dir = "webapp\build\test-results\testDebugUnitTest"
  $total = 0; $fails = 0
  $names = @()
  Get-ChildItem $dir -Filter "*.xml" | ForEach-Object {
    [xml]$d = Get-Content $_.FullName
    $total += [int]$d.testsuite.tests
    $d.testsuite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object {
      $fails++
      $names += "$($_.name)"
    }
  }
  $build = if ($r -match 'BUILD SUCCESSFUL') { 'SUCCESS' } else { 'FAILED' }
  Write-Output "  [$tag] build=$build tests=$total RED=$fails"
  foreach ($n in $names) { Write-Output "     RED -> $n" }
}

# ---------- M1: resurrect the self-made bypass (direct oc.postMessage in index.html)
Write-Output "=== M1: index.html direct oc.postMessage (self-made bypass resurrected) ==="
$p = "webapp\src\main\assets\www\index.html"
$t = [System.IO.File]::ReadAllText($p, $enc)
$anchor = 'new window.Notification('
$inject = 'oc.postMessage("MUTBYPASS","MUT","sess_mut"); ' + $anchor
$t = $t.Replace($anchor, $inject)
[System.IO.File]::WriteAllText($p, $t, $enc)
RunTests "M1"
Copy-Item "$bk\index.html" $p -Force

# ---------- M2: drop the trusted-origin gate
Write-Output "=== M2: onPermissionRequest trusted-origin gate removed ==="
$k = "webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt"
$kt = [System.IO.File]::ReadAllText($k)
$kt = $kt.Replace('val trusted = origin != null && isTrustedWebOrigin(origin)', 'val trusted = true')
[System.IO.File]::WriteAllText($k, $kt, (New-Object System.Text.UTF8Encoding($false)))
RunTests "M2"
Copy-Item "$bk\WebHostActivity.kt" $k -Force

# ---------- M3: drop the CAMERA declaration
Write-Output "=== M3: manifest CAMERA declaration removed ==="
$m = "webapp\src\main\AndroidManifest.xml"
$mt = [System.IO.File]::ReadAllText($m)
$mt = $mt.Replace("`r`n    <uses-permission android:name=`"android.permission.CAMERA`" />", "")
$mt = $mt.Replace("`r`n    <uses-feature android:name=`"android.hardware.camera`" android:required=`"false`" />", "")
[System.IO.File]::WriteAllText($m, $mt, (New-Object System.Text.UTF8Encoding($false)))
RunTests "M3"
Copy-Item "$bk\AndroidManifest.xml" $m -Force

# ---------- restore verification
Write-Output "=== RESTORE CHECK ==="
$kh2 = [System.IO.File]::ReadAllText($k)
$mh2 = [System.IO.File]::ReadAllText($m)
$ih2 = [System.IO.File]::ReadAllText($p, $enc)
$q = [char]34
$mutNeedle = 'oc.postMessage(' + $q + 'MUTBYPASS' + $q
Write-Output "MUT2_TRUSTED_GATE_RESTORED=$($kh2.Contains('isTrustedWebOrigin(origin)'))"
Write-Output "MUT3_CAMERA_DECL_RESTORED=$($mh2.Contains('android.permission.CAMERA'))"
Write-Output "MUT1_BYPASS_RESTORED=$(-not $ih2.Contains($mutNeedle))"
RunTests "RESTORED"
