$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"

function Get-Nodes {
    & $adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/z.xml 2>&1 | Out-Null
    & $adb -s emulator-5554 pull /sdcard/z.xml "$dir\z.xml" 2>&1 | Out-Null
    $raw = Get-Content "$dir\z.xml" -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { return $null }
    return $raw
}

for ($i = 0; $i -lt 8; $i++) {
    $raw = Get-Nodes
    if (-not $raw) { Write-Output "iter $i : no dump"; Start-Sleep -Seconds 2; continue }
    if ($raw -notmatch "isn't responding") { Write-Output "iter $i : ANR CLEARED"; break }
    $m = [regex]::Match($raw, '<node[^>]*text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { $m = [regex]::Match($raw, '<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="Wait"') }
    if ($m.Success) {
        $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        Write-Output "iter ${i}: tapping Wait at $x,$y"
        & $adb -s emulator-5554 shell input tap $x $y
    } else { Write-Output "iter ${i}: Wait button not found" }
    Start-Sleep -Seconds 4
}

$raw = Get-Nodes
if ($raw) {
    [regex]::Matches($raw, '<node[^>]*>') | ForEach-Object {
        $s = $_.Value; $t = [regex]::Match($s, 'text="([^"]*)"').Groups[1].Value
        $c = [regex]::Match($s, 'class="([^"]*)"').Groups[1].Value
        if ($c -eq 'android.widget.EditText' -or $t.Length -gt 2) {
            $b = [regex]::Match($s, 'bounds="([^"]*)"').Groups[1].Value
            $pw = [regex]::Match($s, 'password="([^"]*)"').Groups[1].Value
            Write-Output "$c pw=$pw t='$t' $b"
        }
    }
}
