# E2ECHK5558 nav helpers  (emulator-5558 only)
$ErrorActionPreference = 'Stop'
$global:ADB = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5558'
$global:DEV = if ($env:E2E_DEV) { $env:E2E_DEV } else { 'emulator-5558' }
# NOTE: after the 2026-10-01 fleet relaunch the Pixel_9a (1080x2424@420, app + /sdcard/v95)
# sits on emulator-5554; port 5558 is now an unrelated 3840x2160 AVD with no app.
# Override with $env:E2E_DEV='emulator-5554'.

function Shot($name) {
    $out = (Join-Path $DIR "$name.png") -replace '\\', '/'
    cmd /c "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe -s emulator-5558 exec-out screencap -p > `"$out`""
    $len = (Get-Item (Join-Path $DIR "$name.png")).Length
    if ($len -lt 20000) {
        # fallback: shell screencap + pull
        & $global:ADB -s $global:DEV shell "screencap -p /sdcard/_e2e5558.png"
        & $global:ADB -s $global:DEV pull /sdcard/_e2e5558.png (Join-Path $DIR "$name.png") | Out-Null
        & $global:ADB -s $global:DEV shell "rm -f /sdcard/_e2e5558.png"
        $len = (Get-Item (Join-Path $DIR "$name.png")).Length
    }
    Write-Host "SHOT $name.png bytes=$len"
}

function Ui($name) {
    $ErrorActionPreference = 'Continue'
    $remote = "/sdcard/$name.xml"
    & $global:ADB -s $global:DEV shell "uiautomator dump $remote" 2>&1 | Out-Null
    & $global:ADB -s $global:DEV pull $remote (Join-Path $DIR "ui_$name.xml") 2>&1 | Out-Null
    & $global:ADB -s $global:DEV shell "rm -f $remote" 2>&1 | Out-Null
    Write-Host "UI ui_$name.xml"
}

function Tap($x, $y) {
    $ErrorActionPreference = 'Continue'
    & $global:ADB -s $global:DEV shell "input tap $x $y" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
}

function TypeText($t) {
    # `input text` with %s is unreliable here: a literal "%s" can reach the app,
    # which then parses the target as a remote command. Emit real SPACE keyevents between
    # words instead (and never after the last word, which would leave a trailing space).
    $ErrorActionPreference = 'Continue'
    $words = @($t -split ' ' | Where-Object { $_ -ne '' })
    for ($w = 0; $w -lt $words.Count; $w++) {
        $word = $words[$w]
        for ($i = 0; $i -lt $word.Length; $i += 8) {
            $seg = $word.Substring($i, [Math]::Min(8, $word.Length - $i))
            & $global:ADB -s $global:DEV shell "input text '$seg'" 2>&1 | Out-Null
            Start-Sleep -Milliseconds 1500
        }
        if ($w -lt $words.Count - 1) {
            & $global:ADB -s $global:DEV shell "input keyevent 62" 2>&1 | Out-Null   # KEYCODE_SPACE
            Start-Sleep -Milliseconds 400
        }
    }
}

function Esc() { $ErrorActionPreference='Continue'; & $global:ADB -s $global:DEV shell "input keyevent 111" 2>&1 | Out-Null; Start-Sleep -Milliseconds 600 }
function HideKb() {
    # NOTE: mInputShown lives in `dumpsys input_method` on this build, not `dumpsys window`.
    # Only send ESC when an IME is actually shown, else it dismisses the dialog.
    $ErrorActionPreference = 'Continue'
    $s = (& $global:ADB -s $global:DEV shell "dumpsys input_method | grep mInputShown" 2>&1 | Out-String)
    if ($s -match 'mInputShown=true') { Esc; Write-Host "KB hidden (was up)" }
    else { Write-Host "KB not shown - ESC skipped" }
}

function Dump() {
    & $global:ADB -s $global:DEV shell "dumpsys window | grep -E 'mCurrentFocus|mInputShown'"
}
