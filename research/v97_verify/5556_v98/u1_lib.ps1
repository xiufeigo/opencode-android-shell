# u1_lib.ps1 — U1 复验工具（emulator-5556）
# 方法论坑规避：函数形参禁用 s / S（与 $DEV 序列号变量大小写不敏感同名）
$ErrorActionPreference = 'Stop'
$global:DEV = 'emulator-5556'
$global:ADB = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$global:OUT = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98'

function Ado([string]$cmd) { & $global:ADB -s $global:DEV shell $cmd }

# dump --compressed + pull；返回本地路径
function DumpIt([string]$tag) {
    $dev = "/sdcard/u1_$tag.xml"
    $lo  = Join-Path $global:OUT "u1_$tag.xml"
    Ado "rm -f $dev" | Out-Null
    Ado "uiautomator dump --compressed $dev" | Out-Null
    Start-Sleep -Milliseconds 400
    & $global:ADB -s $global:DEV pull $dev $lo 2>&1 | Out-Null
    Ado "rm -f $dev" | Out-Null
    if (-not (Test-Path $lo)) { return $null }
    $len = (Get-Item $lo).Length
    if ($len -lt 500) { Write-Output "DUMP_SHORT tag=$tag len=$len"; return $lo }
    return $lo
}

# 截图：screencap -p + pull（禁 exec-out）
function ShotIt([string]$tag) {
    $dev = "/sdcard/u1_$tag.png"
    $lo  = Join-Path $global:OUT "u1_$tag.png"
    Ado "rm -f $dev" | Out-Null
    Ado "screencap -p $dev" | Out-Null
    Start-Sleep -Milliseconds 400
    & $global:ADB -s $global:DEV pull $dev $lo 2>&1 | Out-Null
    Ado "rm -f $dev" | Out-Null
    if (Test-Path $lo) { return $lo } else { return $null }
}

function TapXY([int]$x, [int]$y) { Ado "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds 700 }

# 文本输入：按空格切词（词内 <=8 字符分段）；空格一律 keyevent 62；禁 %s / 禁非 ASCII
function TypeTxt([string]$txt) {
    $words = $txt -split '\s+' | Where-Object { $_ -ne '' }
    $first = $true
    foreach ($w in $words) {
        if (-not $first) { Ado "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 60 }
        $first = $false
        foreach ($m in [regex]::Matches($w, '.{1,8}')) {
            $tok = $m.Value
            $bad = $tok -replace '[^\x20-\x7E]', ''
            if ($bad -eq '') { continue }
            Ado "input text $bad" 2>&1 | Out-Null
            Start-Sleep -Milliseconds 90
        }
    }
}

# IME 是否显示（读 dumpsys input_method 的 mInputShown）
function ImeState {
    $txt = (& $global:ADB -s $global:DEV shell dumpsys input_method 2>$null) -join "`n"
    $shown = if ($txt -match 'mInputShown=(\w+)') { $Matches[1] } else { 'UNKNOWN' }
    return $shown
}
function ImeTopPx {
    $txt = (& $global:ADB -s $global:DEV shell dumpsys window 2>$null) -join "`n"
    $top = $null
    foreach ($m in [regex]::Matches($txt, 'type=ime[^\r\n]*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')) {
        $top = [int]$m.Groups[2].Value
    }
    if ($null -eq $top) { foreach ($m in [regex]::Matches($txt, 'ImeInsetsSourceProvider[\s\S]{0,400}?mSourceFrame=Rect\(\d+, (\d+)')) { $top = [int]$m.Groups[1].Value } }
    return $top
}
function FocusNow {
    $txt = (& $global:ADB -s $global:DEV shell dumpsys window 2>$null) -join "`n"
    if ($txt -match 'mCurrentFocus=Window\{\S+ \S+ (\S+)\}') { return $Matches[1] }
    return 'UNKNOWN'
}

# 解析：同时匹配 text="…" 与 text='…'
function GetNodes([string]$path) {
    $x = [xml](Get-Content $path -Raw -Encoding UTF8)
    $out = New-Object System.Collections.ArrayList
    foreach ($n in $x.SelectNodes('//*')) {
        $b = $n.GetAttribute('bounds')
        $m = [regex]::Match($b, '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
        if (-not $m.Success) { continue }
        $t = $n.GetAttribute('text'); if (-not $t) { $t = $n.GetAttribute('content-desc') }
        $cn = $n.GetAttribute('class'); if (-not $cn) { $cn = $n.GetAttribute('class-name') }
        [void]$out.Add([pscustomobject]@{
            Cls = $cn; Txt = $t
            X1 = [int]$m.Groups[1].Value; Y1 = [int]$m.Groups[2].Value
            X2 = [int]$m.Groups[3].Value; Y2 = [int]$m.Groups[4].Value
            W = [int]$m.Groups[3].Value - [int]$m.Groups[1].Value
            H = [int]$m.Groups[4].Value - [int]$m.Groups[2].Value
            Click = $n.GetAttribute('clickable')
            Scroll = $n.GetAttribute('scrollable')
        })
    }
    return $out
}

# 打印几何摘要：scrollable 时间线 / 队列行 / composer EDIT / Steer / ×
function Geo([string]$path, [string]$label) {
    $ns = GetNodes $path
    Write-Output "===== GEO $label ====="
    Write-Output ("IME_SHOWN=" + (ImeState) + "  IME_TOP=" + (ImeTopPx) + "  FOCUS=" + (FocusNow))
    foreach ($n in $ns) {
        if ($n.Scroll -eq 'true' -and $n.H -gt 0) {
            Write-Output ("SCROLL  [{0},{1}][{2},{3}] h={4} w={5} cls={6}" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.H,$n.W,$n.Cls)
        }
    }
    foreach ($n in $ns) {
        if ($n.Cls -match 'EditText' -and $n.H -gt 0) {
            Write-Output ("EDIT    [{0},{1}][{2},{3}] h={4} t='{5}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.H,$n.Txt)
        }
    }
    foreach ($n in $ns) {
        if ($n.Txt -match 'Steer' ) {
            Write-Output ("STEER   [{0},{1}][{2},{3}] click={4} cls={5}" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Click,$n.Cls)
        }
        if ($n.Txt -eq '×' -or $n.Txt -eq '✕' -or $n.Txt -eq 'x') {
            Write-Output ("XMARK   [{0},{1}][{2},{3}] click={4} cls={5}" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Click,$n.Cls)
        }
        if ($n.Txt -match '^Queued') {
            Write-Output ("QROW    [{0},{1}][{2},{3}] click={4} t='{5}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.Click,$n.Txt)
        }
    }
    Write-Output ""
}

# 找某文本节点（双引号/单引号都已由 GetNodes 统一处理）
function FindTxt([string]$path, [string]$needle) {
    foreach ($n in (GetNodes $path)) {
        if ($n.Txt -and $n.Txt -like "*$needle*") { return $n }
    }
    return $null
}
