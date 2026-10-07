$ErrorActionPreference = 'Stop'
$root = "D:\XiaoZhuTouTou\opencode-android-shell"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location $root
$log = "research/tmp/ux16p2/reverse.txt"
$bk  = "research/tmp/ux16p2/backup"
"" | Out-File -Encoding utf8 $log

$A = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt"
$I = "webapp/src/main/assets/www/index.html"
$U = "webapp/src/main/assets/www/ui/index.html"

function Run-Only([string]$tag) {
  & .\gradlew.bat :webapp:testDebugUnitTest --tests "*RenderProcessRecoveryTest*" --console=plain 2>&1 | Out-Null
  $code = $LASTEXITCODE
  $xml = "webapp/build/test-results/testDebugUnitTest/TEST-com.xiufeigo.opencodeshell.web.RenderProcessRecoveryTest.xml"
  $red = @()
  if (Test-Path $xml) {
    [xml]$x = Get-Content $xml
    foreach ($tc in $x.testsuite.testcase) { if ($tc.failure -or $tc.error) { $red += $tc.name } }
  }
  $s = "### $tag  gradleExit=$code  failingTests=[$($red -join ', ')]"
  Write-Host $s
  Add-Content -Encoding utf8 $log $s
  if ($red.Count -gt 0) {
    [xml]$y = Get-Content $xml
    foreach ($tc in $y.testsuite.testcase) {
      if ($tc.failure -or $tc.error) {
        $msg = if ($tc.failure) { $tc.failure.message } else { $tc.error.message }
        if ($msg.Length -gt 320) { $msg = $msg.Substring(0,320) + " ...[truncated]" }
        Add-Content -Encoding utf8 $log ("    RED >> " + $tc.name + " :: " + $msg)
      }
    }
  }
}

function Restore-File($live, $saved) { Copy-Item $saved $live -Force }

# ---- (1) 撤除重建路径：删掉首崩分支的 destroy() --------------------------------
$orig  = Get-Content $A -Raw
$patch = $orig -replace "(?m)^\s*webView\.destroy\(\)\r?\n(?=\s*// 新实例走)", ""
if ($patch -eq $orig) { throw "(1) patch did not apply" }
Set-Content -Path $A -Value $patch -NoNewline -Encoding utf8
Run-Only "(1) remove webView.destroy() from first-crash branch"
Restore-File $A "$bk/WebHostActivity.kt"

# ---- (2) 撤除诊断页 charset 声明 ----------------------------------------------
$origI  = Get-Content $I -Raw
$patchI = $origI -replace '(?m)^\s*<meta charset="utf-8" />\r?\n', ""
if ($patchI -eq $origI) { throw "(2) patch did not apply" }
Set-Content -Path $I -Value $patchI -NoNewline -Encoding utf8
Run-Only "(2) remove <meta charset> from diagnostic page"
Restore-File $I "$bk/index.html"

# ---- (3) 撤除自带 HTML 的 charset ---------------------------------------------
$origU  = Get-Content $U -Raw
$patchU = $origU -replace '(?m)^\s*<meta charset="utf-8"\s*/?>\r?\n', ""
if ($patchU -eq $origU) { throw "(3) patch did not apply" }
Set-Content -Path $U -Value $patchU -NoNewline -Encoding utf8
Run-Only "(3) remove <meta charset> from assets/www/ui/index.html"
git checkout -- $U

# ---- 恢复后必须全绿 -------------------------------------------------------------
Run-Only "(4) all restored - expect ALL GREEN"
Write-Host "--- working tree must still carry all three edits ---"
git status --short $A $I $U
