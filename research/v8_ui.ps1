# local helper for v8 verification: dump + parse UI hierarchy from 5560
param(
    [ValidateSet('dump','tap','tapText','shot','shotWin')]
    [string]$Action = 'dump',
    [string]$Serial = 'emulator-5560',
    [string]$Text = '',
    [string]$Out = ''
)
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp = 'C:\Users\xiufe\AppData\Local\Temp\v8_ui.xml'
$repo = 'C:\sensorsdata\main\program\opencode-android-shell'

function Dump-Xml([string]$serial) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & $adb -s $serial shell rm -f /sdcard/v8_ui.xml 2>&1 | Out-Null
    & $adb -s $serial shell uiautomator dump /sdcard/v8_ui.xml 2>&1 | Out-Null
    # Pull via Windows path to avoid MSYS path conversion
    & $adb -s $serial pull /sdcard/v8_ui.xml $tmp 2>&1 | Out-Null
    if (-not (Test-Path $tmp)) {
        Write-Error "pull failed"
        exit 2
    }
    return $tmp
}

switch ($Action) {
    'dump' {
        $f = Dump-Xml $Serial
        # Print text nodes
        [xml]$x = Get-Content $f -Raw
        $nodes = $x.SelectNodes('//node')
        $lines = @()
        foreach ($n in $nodes) {
            $t = $n.GetAttribute('text')
            $d = $n.GetAttribute('content-desc')
            $b = $n.GetAttribute('bounds')
            if (($t -ne '') -or ($d -ne '')) {
                $line = ''
                if ($t -ne '') { $line += "[text=$t] " }
                if ($d -ne '') { $line += "[desc=$d] " }
                if ($b -ne '') { $line += "bounds=$b" }
                $lines += $line
            }
        }
        $lines -join "`n"
    }
    'tapText' {
        $f = Dump-Xml $Serial
        [xml]$x = Get-Content $f -Raw
        $hit = $null
        foreach ($n in $x.SelectNodes('//node')) {
            if ($n.GetAttribute('text') -eq $Text) { $hit = $n; break }
        }
        if (-not $hit) { Write-Output "NOT FOUND (text=$Text)"; exit 2 }
        $b = $hit.GetAttribute('bounds')
        if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { Write-Output "BAD BOUNDS $b"; exit 3 }
        $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb -s $Serial shell input tap $cx $cy
        Write-Output "tapped $cx,$cy (text=$Text)"
    }
    'tap' {
        $f = Dump-Xml $Serial
        [xml]$x = Get-Content $f -Raw
        $hit = $null
        foreach ($n in $x.SelectNodes('//node')) {
            $d = $n.GetAttribute('content-desc')
            if ($d -eq $Text) { $hit = $n; break }
        }
        if (-not $hit) { Write-Output "NOT FOUND (desc=$Text)"; exit 2 }
        $b = $hit.GetAttribute('bounds')
        if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { Write-Output "BAD BOUNDS $b"; exit 3 }
        $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb -s $Serial shell input tap $cx $cy
        Write-Output "tapped $cx,$cy (desc=$Text)"
    }
    'shot' {
        if (-not $Out) { $Out = 'v8_shot' }
        $dir = Join-Path $repo 'research\v8_verify\picker_browser'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & $adb -s $Serial shell screencap -p /sdcard/$Out.png 2>&1 | Out-Null
        & $adb -s $Serial pull /sdcard/$Out.png (Join-Path $dir "$Out.png") 2>&1 | Out-Null
        Write-Output "saved research/v8_verify/picker_browser/$Out.png"
    }
    'shotWin' {
        if (-not $Out) { $Out = 'v8_shot' }
        $dir = Join-Path $repo 'research\v8_verify\picker_browser'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & $adb -s $Serial exec-out screencap -p > (Join-Path $dir "$Out.png") 2>$null
        Write-Output "saved research/v8_verify/picker_browser/$Out.png (exec-out stream)"
    }
}
