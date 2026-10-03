param(
    [string]$Serial = 'emulator-5562',
    [int]$X,
    [int]$Y
)
$ErrorActionPreference = 'Stop'
& C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe -s $Serial shell input tap $X $Y
Write-Output "tapped $X,$Y"
