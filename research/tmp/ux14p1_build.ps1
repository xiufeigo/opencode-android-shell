$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME = "C:\Users\xiufeigo\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location "D:\XiaoZhuTouTou\opencode-android-shell"

$log = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux14p1_build.log"
"=== UX14-P1 build start $(Get-Date -Format o) ===" | Out-File $log -Encoding utf8

& .\gradlew.bat --no-daemon --rerun-tasks :webapp:testDebugUnitTest 2>&1 | Tee-Object -FilePath $log -Append
$testExit = $LASTEXITCODE
"=== TEST_EXIT=$testExit $(Get-Date -Format o) ===" | Tee-Object -FilePath $log -Append

if ($testExit -ne 0) {
    "=== ABORT: tests failed, skipping assemble ===" | Tee-Object -FilePath $log -Append
    exit $testExit
}

& .\gradlew.bat --no-daemon --rerun-tasks :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8 2>&1 | Tee-Object -FilePath $log -Append
$asmExit = $LASTEXITCODE
"=== ASSEMBLE_EXIT=$asmExit $(Get-Date -Format o) ===" | Tee-Object -FilePath $log -Append

"=== ALL DONE ===" | Tee-Object -FilePath $log -Append
exit 0