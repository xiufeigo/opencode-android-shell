$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"
$pkg = 'com.xiufeigo.opencodeshell'

function Dump {
    & $adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/v.xml 2>&1 | Out-Null
    & $adb -s emulator-5554 pull /sdcard/v.xml "$dir\v.xml" 2>&1 | Out-Null
    $raw = Get-Content "$dir\v.xml" -Raw -ErrorAction SilentlyContinue
    if ($raw) { return $raw } else { return "" }
}
function ETs([string]$raw) {
    $out = @()
    [regex]::Matches($raw, '<node[^>]*class="android.widget.EditText"[^>]*>') | ForEach-Object {
        $s = $_.Value
        $b = [regex]::Match($s, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $out += [pscustomobject]@{
            text = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value
            pw   = [regex]::Match($s, 'password="([^"]*)"').Groups[1].Value
            x1 = [int]$b.Groups[1].Value; y1 = [int]$b.Groups[2].Value
            x2 = [int]$b.Groups[3].Value; y2 = [int]$b.Groups[4].Value
        }
    }
    return $out
}

& $adb -s emulator-5554 shell am force-stop $pkg
Start-Sleep -Seconds 3
& $adb -s emulator-5554 shell am start -n "$pkg/.OcActivity" | Out-Null

# wait for connect screen, dismissing any ANR
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Seconds 4
    $raw = Dump
    $ets = ETs $raw
    if ($ets.Count -ge 2) { Write-Output "connect screen ready at iter $i"; break }
    if ($raw -match "isn't responding") {
        $m = [regex]::Match($raw, '<node[^>]*text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success) {
            $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
            $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
            & $adb -s emulator-5554 shell input tap $x $y
            Write-Output "iter $i dismissed ANR"
        } else { Write-Output "iter $i ANR, no btn" }
    } else { Write-Output "iter $i screen: $([regex]::Match($raw,'text="([^"]*)"').Groups[1].Value)" }
}

$raw = Dump
$ets = ETs $raw
if ($ets.Count -lt 2) { Write-Output "GAVE UP: no connect screen"; exit }

$addr = $ets | Where-Object { $_.pw -eq 'false' } | Select-Object -First 1
$pass = $ets | Where-Object { $_.pw -eq 'true' } | Select-Object -First 1
$del = (1..40 | ForEach-Object { '67' }) -join ' '

# password
$cx = [int](($pass.x1 + $pass.x2) / 2); $cy = [int](($pass.y1 + $pass.y2) / 2)
& $adb -s emulator-5554 shell input tap $cx $cy
Start-Sleep -Milliseconds 1000
& $adb -s emulator-5554 shell input keyevent 123
& $adb -s emulator-5554 shell input keyevent $del
Start-Sleep -Milliseconds 400
& $adb -s emulator-5554 shell input text "open"
Start-Sleep -Milliseconds 600
& $adb -s emulator-5554 shell input text "code"
Start-Sleep -Milliseconds 700
$ets = ETs (Dump)
$pass = $ets | Where-Object { $_.pw -eq 'true' } | Select-Object -First 1
Write-Output "PASS len=$($pass.text.Length)"

# address
$addr = $ets | Where-Object { $_.pw -eq 'false' } | Select-Object -First 1
$ax = [int](($addr.x1 + $addr.x2) / 2); $ay = [int](($addr.y1 + $addr.y2) / 2)
& $adb -s emulator-5554 shell input tap $ax $ay
Start-Sleep -Milliseconds 1000
& $adb -s emulator-5554 shell input keyevent 123
& $adb -s emulator-5554 shell input keyevent $del
Start-Sleep -Milliseconds 400
foreach ($seg in @('http:', '/', '/', '127.0.0.1', ':', '4095')) {
    & $adb -s emulator-5554 shell input text $seg
    Start-Sleep -Milliseconds 400
}
$ets = ETs (Dump)
$addr = $ets | Where-Object { $_.pw -eq 'false' } | Select-Object -First 1
$pass = $ets | Where-Object { $_.pw -eq 'true' } | Select-Object -First 1
Write-Output "ADDR='$($addr.text)' PASSLEN=$($pass.text.Length)"

# tap Connect
$raw = Dump
$m = [regex]::Match($raw, '<node[^>]*text="Connect"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($m.Success) {
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb -s emulator-5554 shell input tap $x $y
    Write-Output "tapped Connect $x,$y"
    Start-Sleep -Seconds 8
}
& $adb -s emulator-5554 shell screencap -p /sdcard/s_final.png | Out-Null
& $adb -s emulator-5554 pull /sdcard/s_final.png "$dir\s_final.png" | Out-Null
$raw = Dump
Write-Output "=== FINAL SCREEN TEXT ==="
[regex]::Matches($raw, '<node[^>]*>') | ForEach-Object {
    $s = $_.Value; $t = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value
    $c = [regex]::Match($s, 'class="([^"]*)"').Groups[1].Value
    $b = [regex]::Match($s, 'bounds="([^"]*)"').Groups[1].Value
    if ($t) { Write-Output "  '$t' $b" }
}
