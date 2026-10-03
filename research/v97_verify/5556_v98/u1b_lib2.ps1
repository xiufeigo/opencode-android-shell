# u1b_lib2.ps1 — fully self-contained U1-b helpers (emulator-5556)
$ErrorActionPreference = 'Stop'
$global:DEV = 'emulator-5556'
$global:ADB = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$global:OUT = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98'

function Ado([string]$cmd) { & $global:ADB -s $global:DEV shell $cmd }

function DumpIt([string]$tag) {
    $dev = "/sdcard/u1b_$tag.xml"
    $lo  = Join-Path $global:OUT "u1b_$tag.xml"
    Ado "rm -f $dev" | Out-Null
    Ado "uiautomator dump --compressed $dev" | Out-Null
    Start-Sleep -Milliseconds 350
    & $global:ADB -s $global:DEV pull $dev $lo 2>&1 | Out-Null
    Ado "rm -f $dev" | Out-Null
    if (-not (Test-Path $lo)) { Write-Output "DUMP_NULL tag=$tag"; return $null }
    $len = (Get-Item $lo).Length
    if ($len -lt 500) { Write-Output "DUMP_SHORT tag=$tag len=$len" }
    return $lo
}

function ShotIt([string]$tag) {
    $dev = "/sdcard/u1b_$tag.png"
    $lo  = Join-Path $global:OUT "u1b_$tag.png"
    Ado "rm -f $dev" | Out-Null
    Ado "screencap -p $dev" | Out-Null
    Start-Sleep -Milliseconds 350
    & $global:ADB -s $global:DEV pull $dev $lo 2>&1 | Out-Null
    Ado "rm -f $dev" | Out-Null
    if (Test-Path $lo) { return $lo } else { return $null }
}

# text input: split by space, each token chunked <=8 chars; space always keyevent 62
function TypeTxt([string]$txt) {
    $words = $txt -split '\s+' | Where-Object { $_ -ne '' }
    $first = $true
    foreach ($w in $words) {
        if (-not $first) { Ado "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 50 }
        $first = $false
        foreach ($m in [regex]::Matches($w, '.{1,8}')) {
            $tok = $m.Value
            $bad = $tok -replace '[^\x20-\x7E]', ''
            if ($bad -eq '') { continue }
            Ado "input text $bad" 2>&1 | Out-Null
            Start-Sleep -Milliseconds 80
        }
    }
}

function ImeState {
    $txt = (& $global:ADB -s $global:DEV shell dumpsys input_method 2>$null) -join "`n"
    if ($txt -match 'mInputShown=(\w+)') { return $Matches[1] }
    return 'UNKNOWN'
}
function ImeTopPx {
    $txt = (& $global:ADB -s $global:DEV shell dumpsys window 2>$null) -join "`n"
    $top = $null
    foreach ($m in [regex]::Matches($txt, 'type=ime[^\r\n]*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')) { $top = [int]$m.Groups[2].Value }
    if ($null -eq $top) {
        foreach ($m in [regex]::Matches($txt, 'ImeInsetsSourceProvider[\s\S]{0,400}?mSourceFrame=Rect\(\d+, (\d+)')) { $top = [int]$m.Groups[1].Value }
    }
    return $top
}
function FocusNow {
    $txt = (& $global:ADB -s $global:DEV shell dumpsys window 2>$null) -join "`n"
    if ($txt -match 'mCurrentFocus=Window\{\S+ \S+ (\S+)\}') { return $Matches[1] }
    return 'UNKNOWN'
}

# parse: matches BOTH text="..." and text='...' (XML parser normalizes)
function GetNodes([string]$path) {
    if (-not $path) { return @() }
    try { $x = [xml](Get-Content $path -Raw -Encoding UTF8) } catch { return @() }
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
            W  = [int]$m.Groups[3].Value - [int]$m.Groups[1].Value
            H  = [int]$m.Groups[4].Value - [int]$m.Groups[2].Value
            Click = $n.GetAttribute('clickable'); Scroll = $n.GetAttribute('scrollable')
        })
    }
    return $out
}

function FindTxt([string]$path, [string]$needle) {
    foreach ($n in (GetNodes $path)) { if ($n.Txt -and $n.Txt -like "*$needle*") { return $n } }
    return $null
}
function Ctr([string]$p, [string]$k) {
    $n = FindTxt $p $k
    if ($n) { return @([int](($n.X1 + $n.X2) / 2), [int](($n.Y1 + $n.Y2) / 2)) }
    return $null
}
function TapOn([string]$p, [string]$k) {
    $c = Ctr $p $k
    if ($c) { Ado "input tap $($c[0]) $($c[1])" | Out-Null; Start-Sleep -Milliseconds 800; return $true }
    Write-Output "TAPFAIL:$k"; return $false
}

# ======== THE CORE MEASUREMENT ========
# Prints: mInputShown assertion + IME top + timeline h + queue geometry + Steer/x bounds + row heights
function Measure([string]$path, [string]$label) {
    $ns = GetNodes $path
    $shown = ImeState; $top = ImeTopPx
    Write-Output "===== MEASURE $label ====="
    Write-Output "NODES=$($ns.Count) mInputShown=$shown IME_TOP=$top FOCUS=$(FocusNow)"
    # timeline = scrollable, wide
    $tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
    if ($tl) { Write-Output ("TIMELINE [{0},{1}][{2},{3}] h={4} w={5} cls={6}" -f $tl.X1,$tl.Y1,$tl.X2,$tl.Y2,$tl.H,$tl.W,$tl.Cls) }
    else { Write-Output "TIMELINE NONE" }
    # composer EditText
    $ed = $ns | Where-Object { $_.Cls -match 'EditText' } | Select-Object -First 1
    if ($ed) { Write-Output ("EDITTEXT  [{0},{1}][{2},{3}] h={4} bottom={5}" -f $ed.X1,$ed.Y1,$ed.X2,$ed.Y2,$ed.H,$ed.Y2) }
    # ALL scrollables (find queue container)
    foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) {
        Write-Output ("  SCROLLABLE [{0},{1}][{2},{3}] h={4} w={5} cls={6}" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.H,$n.W,$n.Cls)
    }
    # Steer
    $st = @($ns | Where-Object { $_.Txt -and $_.Txt.ToLower().Contains('steer') })
    Write-Output "STEER_NODES=$($st.Count)"
    foreach ($n in $st) { Write-Output ("  STEER [{0},{1}][{2},{3}] w={4} h={5} click={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) }
    # x mark (U+00D7 / U+2715 / U+2716) AND 'Remove' content-desc
    $mk = @($ns | Where-Object { $_.Txt -and ($_.Txt.Contains([char]0x00D7) -or $_.Txt.Contains([char]0x2715) -or $_.Txt.Contains([char]0x2716)) })
    $rm = @($ns | Where-Object { $_.Txt -and $_.Txt.ToLower().Contains('remove') })
    Write-Output "XMARK_NODES=$($mk.Count) REMOVE_NODES=$($rm.Count)"
    foreach ($n in $mk) { Write-Output ("  XMARK [{0},{1}][{2},{3}] w={4} h={5} click={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) }
    foreach ($n in $rm) { Write-Output ("  REMOVE [{0},{1}][{2},{3}] w={4} h={5} click={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) }
    # queue rows: text nodes containing 'Queued' (semantic marker used by prior rounds)
    $qr = @($ns | Where-Object { $_.Txt -and $_.Txt.ToLower().Contains('queued') })
    Write-Output "QUEUED_ROWS=$($qr.Count)"
    foreach ($n in $qr) { Write-Output ("  QROW [{0},{1}][{2},{3}] w={4} h={5} click={6} '{7}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Click,$n.Txt) }
    # right-edge rail candidates (narrow tall nodes) - for MessageNavRail attribution
    $rail = @($ns | Where-Object { $_.W -gt 10 -and $_.W -le 60 -and $_.H -ge 2 -and $_.H -le 16 -and $_.X1 -gt 1250 })
    Write-Output "RAIL_TICKLIKE=$($rail.Count)"
    foreach ($n in ($rail | Select-Object -First 8)) { Write-Output ("  RAIL [{0},{1}][{2},{3}] w={4} h={5} '{6}'" -f $n.X1,$n.Y1,$n.X2,$n.Y2,$n.W,$n.H,$n.Txt) }
    Write-Output ""
    return @{Shown=$shown; Top=$top; TL=$tl; Ed=$ed; Steer=$st; X=$mk; Remove=$rm; Rows=$qr}
}
