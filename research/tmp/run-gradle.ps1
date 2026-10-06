param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME = 'C:\Users\xiufeigo\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location 'D:\XiaoZhuTouTou\opencode-android-shell'
& .\gradlew.bat @GradleArgs
"EXITCODE=$LASTEXITCODE"