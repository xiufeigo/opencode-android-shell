# install_all.ps1 - idempotent install of the freshly built debug APK across the v97 fleet.
# Lead-owned. Prints the serial -> AVD map and the installed versionName per device.
# Usage:  pwsh -File research\v97_verify\install_all.ps1
param(
    [string[]]$Serials = @('emulator-5554','emulator-5556','emulator-5558','emulator-5560','emulator-5562'),
    [switch]$SkipReverse
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk = 'C:\sensorsdata\main\program\opencode-android-shell\app\build\outputs\apk\debug\app-debug.apk'
$pkg = 'com.xiufeigo.opencodeshell'
$out = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\install_all.txt'

if (-not (Test-Path $apk)) { throw "APK missing: $apk" }
$fi = Get-Item $apk
$md5 = (Get-FileHash $apk -Algorithm MD5).Hash.ToLower()
$lines = @()
$lines += "APK $apk"
$lines += "  bytes=$($fi.Length) mtime=$($fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) md5=$md5"
Write-Host $lines[-1]

foreach ($s in $Serials) {
    $avd = (& $adb -s $s emu avd name 2>&1 | Select-Object -First 1)
    if (-not $avd) { $avd = '<no-avd-name>' }
    Write-Host "=== $s (AVD=$avd) ==="
    $inst = (& $adb -s $s install -r $apk 2>&1) -join ' '
    Write-Host "  install: $inst"
    if (-not $SkipReverse) {
        (& $adb -s $s reverse tcp:4095 tcp:4095 2>&1) | Out-Null
        (& $adb -s $s reverse tcp:2222 tcp:22 2>&1) | Out-Null
    }
    $rev = ((& $adb -s $s reverse --list 2>&1) -join ' | ')
    $ver = ((& $adb -s $s shell "dumpsys package $pkg" 2>&1) | Select-String -Pattern 'versionName|lastUpdateTime' |
            ForEach-Object { $_.ToString().Trim() }) -join ' ; '
    # desktop-class emulators need a soft keyboard with a hard keyboard attached
    if ($avd -match 'Desktop') { (& $adb -s $s shell settings put secure show_ime_with_hard_keyboard 1 2>&1) | Out-Null }
    Write-Host "  reverse: $rev"
    Write-Host "  $ver"
    $lines += "$s AVD=$avd install=$inst"
    $lines += "   reverse=$rev"
    $lines += "   $ver"
}
$lines -join "`n" | Set-Content -Path $out -Encoding UTF8
Write-Host "WROTE $out"
