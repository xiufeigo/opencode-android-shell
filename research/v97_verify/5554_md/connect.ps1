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
function TapText([string]$label, [string]$raw) {
    $m = [regex]::Match($raw, '<node[^>]*text="' + [regex]::Escape($label) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $null }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb -s emulator-5554 shell input tap $x $y
    return "$x,$y"
}

# 1. kill app (clears its ANR), restart
& $adb -s emulator-5554 shell am force-stop $pkg
Start-Sleep -Seconds 2
& $adb -s emulator-5554 shell am start -n "$pkg/.OcActivity" | Out-Null
Start-Sleep -Seconds 12

$raw = Dump
Write-Output "=== after start ==="
if ($raw -match "isn't responding") { Write-Output "ANR STILL UP" } else { Write-Output "screen OK" }
[regex]::Matches($raw, '<node[^>]*>') | ForEach-Object {
    $s = $_.Value; $t = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value
    $c = [regex]::Match($s, 'class="([^"]*)"').Groups[1].Value
    if ($c -eq 'android.widget.EditText' -or $t.Length -gt 2) {
        $b = [regex]::Match($s, 'bounds="([^"]*)"').Groups[1].Value
        $pw = [regex]::Match($s, 'password="([^"]*)"').Groups[1].Value
        Write-Output "$c pw=$pw t='$t' $b"
    }
}
