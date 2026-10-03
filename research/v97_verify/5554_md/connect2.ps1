$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"
$pkg = 'com.xiufeigo.opencodeshell'

function Dump {
    & $adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/w.xml 2>&1 | Out-Null
    & $adb -s emulator-5554 pull /sdcard/w.xml "$dir\w.xml" 2>&1 | Out-Null
    $raw = Get-Content "$dir\w.xml" -Raw -ErrorAction SilentlyContinue
    if ($raw) { return $raw } else { return "" }
}
function ETs([string]$raw) {
    $out = @()
    [regex]::Matches($raw, '<node[^>]*class="android.widget.EditText"[^>]*>') | ForEach-Object {
        $s = $_.Value
        $t = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value
        $pw = [regex]::Match($s, 'password="([^"]*)"').Groups[1].Value
        $b = [regex]::Match($s, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $out += [pscustomobject]@{ text = $t; pw = $pw; x1 = [int]$b.Groups[1].Value; y1 = [int]$b.Groups[2].Value; x2 = [int]$b.Groups[3].Value; y2 = [int]$b.Groups[4].Value }
    }
    return $out
}

# dismiss ANR dialogs until clear (max 5)
for ($i = 0; $i -lt 5; $i++) {
    $raw = Dump
    if ($raw -and $raw -notmatch "isn't responding") { Write-Output "ANR clear after $i"; break }
    $m = [regex]::Match($raw, '<node[^>]*text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        & $adb -s emulator-5554 shell input tap $x $y
        Write-Output "tapped Wait $x,$y"
    } else { Write-Output "no Wait btn" }
    Start-Sleep -Seconds 3
}

$raw = Dump
$ets = ETs $raw
if (-not $ets) { Write-Output "NO EDITTEXT; screen:"; [regex]::Matches($raw, '<node[^>]*>') | ForEach-Object { $s = $_.Value; $t = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value; if ($t) { Write-Output "  '$t'" } }; exit }

$addr = $ets | Where-Object { $_.pw -eq 'false' } | Select-Object -First 1
$pass = $ets | Where-Object { $_.pw -eq 'true' } | Select-Object -First 1
Write-Output "addr field: $($addr.x1),$($addr.y1)-$($addr.x2),$($addr.y2) text='$($addr.text)'"
Write-Output "pass field: $($pass.x1),$($pass.y1)-$($pass.x2),$($pass.y2) text='$($pass.text)' len=$($pass.text.Length)"

# --- password: clear then type in small segments, verify length each time ---
$cx = [int](($pass.x1 + $pass.x2) / 2); $cy = [int](($pass.y1 + $pass.y2) / 2)
& $adb -s emulator-5554 shell input tap $cx $cy
Start-Sleep -Milliseconds 900
& $adb -s emulator-5554 shell input keyevent 123
$del = (1..40 | ForEach-Object { '67' }) -join ' '
& $adb -s emulator-5554 shell input keyevent $del
Start-Sleep -Milliseconds 500
foreach ($seg in @('open', 'code')) {
    & $adb -s emulator-5554 shell input text $seg
    Start-Sleep -Milliseconds 500
}
$raw = Dump
$ets = ETs $raw
$pass = $ets | Where-Object { $_.pw -eq 'true' } | Select-Object -First 1
Write-Output "PASS AFTER TYPE: len=$($pass.text.Length) raw='$($pass.text)'"

# --- address ---
$addr = $ets | Where-Object { $_.pw -eq 'false' } | Select-Object -First 1
if ($addr.text -ne 'http://127.0.0.1:4095') {
    $ax = [int](($addr.x1 + $addr.x2) / 2); $ay = [int](($addr.y1 + $addr.y2) / 2)
    & $adb -s emulator-5554 shell input tap $ax $ay
    Start-Sleep -Milliseconds 800
    & $adb -s emulator-5554 shell input keyevent 123
    & $adb -s emulator-5554 shell input keyevent $del
    Start-Sleep -Milliseconds 400
    foreach ($seg in @('http:', '/', '/', '127.0.0.1', ':', '4095')) {
        & $adb -s emulator-5554 shell input text $seg
        Start-Sleep -Milliseconds 350
    }
}
$raw = Dump
$ets = ETs $raw
Write-Output "ADDR: '$((($ets | Where-Object { $_.pw -eq 'false' } | Select-Object -First 1)).text)'"
Write-Output "PASS: '$((($ets | Where-Object { $_.pw -eq 'true' } | Select-Object -First 1)).text)'"
Write-Output "=== all visible text ==="
[regex]::Matches($raw, '<node[^>]*>') | ForEach-Object { $s = $_.Value; $t = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value; if ($t) { Write-Output "  '$t'" } }
