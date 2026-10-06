$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME = "C:\Users\xiufeigo\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location "D:\XiaoZhuTouTou\opencode-android-shell"

$log = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux14p1_green.log"
"=== UX14-P1 GREEN (restored) start $(Get-Date -Format o) ===" | Out-File $log -Encoding utf8

& .\gradlew.bat --no-daemon --rerun-tasks :webapp:testDebugUnitTest 2>&1 | Tee-Object -FilePath $log -Append
$e = $LASTEXITCODE
"=== GREEN_TEST_EXIT=$e $(Get-Date -Format o) ===" | Tee-Object -FilePath $log -Append
exit 0
