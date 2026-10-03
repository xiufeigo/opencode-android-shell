# tA ui helper — serial emulator-5554, shots -> research/v31_verify/tA/
param(
    [ValidateSet('summary', 'texts', 'shot', 'tap', 'desc', 'key', 'swipe', 'size', 'dump')]
    [string]$Action = 'summary',
    [string]$Text = '',
    [string]$Name = 'shot',
    [string]$From = '',
    [string]$To = '',
    [int]$Code = 0,
    [int]$Duration = 400,
    [switch]$Contains,
    [int]$MinY = 0,
    [int]$MaxY = 99999,
    [int]$Index = 0,
    [switch]$Tap
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$serial = 'emulator-5554'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp = Join-Path $env:TEMP 'tA_ui.xml'
$dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v31_verify\tA'

function Get-Ui {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & $adb -s $serial shell rm -f /sdcard/tA_ui.xml 2>&1 | Out-Null
    $dump = (& $adb -s $serial shell uiautomator dump /sdcard/tA_ui.xml 2>&1) -join ' '
    if ($LASTEXITCODE -ne 0 -or $dump -notmatch 'dumped to') { throw "dump failed: $dump" }
    & $adb -s $serial pull /sdcard/tA_ui.xml $tmp 2>&1 | Out-Null
    if (-not (Test-Path $tmp)) { throw 'pull failed' }
    return [xml](Get-Content $tmp -Raw -Encoding UTF8)
}

function Tap-Node($n, [string]$label) {
    $n = @($n)[0]
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "BAD BOUNDS $b" }
    $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $adb -s $serial shell input tap $cx $cy | Out-Null
    "tapped '$label' @ $cx,$cy"
}

function Find-ByText($x, [string]$t) {
    $x.SelectNodes('//node') | Where-Object {
        $b = $_.GetAttribute('bounds')
        if ($b -notmatch '\[(\d+),(\d+)\]') { return $false }
        $y = [int]$Matches[2]
        if ($y -lt $MinY -or $y -gt $MaxY) { return $false }
        if ($Contains) { $_.text -and $_.text.Contains($t) } else { $_.text -eq $t }
    }
}

switch ($Action) {
    'summary' { (Get-Ui).SelectNodes('//node[@text!=""]') | ForEach-Object { "  $_" } }
    'texts' {
        $x = Get-Ui
        $x.SelectNodes('//node[@text!=""]') | ForEach-Object { "  $($_.text)  @$($_.bounds)" }
        '--- content-desc ---'
        $x.SelectNodes('//node[@content-desc!=""]') | ForEach-Object { "  $($_.GetAttribute('content-desc'))  @$($_.bounds)" }
    }
    'dump' { (Get-Ui).OuterXml }
    'shot' {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & $adb -s $serial shell screencap -p /sdcard/$Name.png
        & $adb -s $serial pull /sdcard/$Name.png (Join-Path $dir "$Name.png") | Out-Null
        "saved research/v31_verify/tA/$Name.png"
    }
    'tap' {
        $hit = Find-ByText (Get-Ui) $Text
        if (-not $hit) { "NOT FOUND (text='$Text')"; exit 2 }
        "found '$(@($hit)[$Index].text)' count=$(@($hit).Count)"
        if ($Tap) { Tap-Node @($hit)[$Index] $Text }
    }
    'desc' {
        $hit = (Get-Ui).SelectNodes('//node') | Where-Object { $_.GetAttribute('content-desc') -eq $Text }
        if (-not $hit) { "NOT FOUND (desc='$Text')"; exit 2 }
        "found desc '$Text' count=$(@($hit).Count)"
        if ($Tap) { Tap-Node @($hit)[$Index] $Text }
    }
    'key' { & $adb -s $serial shell input keyevent $Code; "keyevent $Code" }
    'swipe' { & $adb -s $serial shell input swipe $From $To $Duration; "swiped" }
    'size' {
        "wm size: $((& $adb -s $serial shell wm size) -join ' ')"
        "density : $((& $adb -s $serial shell wm density) -join ' ')"
    }
}
