$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ser = '-s'; $dev = 'emulator-5554'
function A { & $adb $ser $dev @args }

# ---- raw extractors (return the ORIGINAL line, never a recomputed number) ----
function Get-ImeRaw {
    $w = A shell dumpsys window 2>&1
    $f = $w | Select-String 'type=ime frame=' | Select-Object -First 1
    if ($f) { $f.Line.Trim() } else { 'NO-IME-SOURCE-LINE' }
}
function Get-Field([string[]]$w, [string]$pat) {
    $m = $w | Select-String $pat | Select-Object -First 1
    if ($m) { $m.Line.Trim() } else { 'N/A' }
}
function Get-Height {
    $raw = Get-ImeRaw
    if ($raw -match 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return [int]$Matches[4] - [int]$Matches[2]
    }
    return -1
}

$levels = @(768, 700, 660, 640, 620, 610, 606, 605, 604, 603, 602, 601, 600, 599, 598, 597, 596, 595, 590, 585, 580, 570, 560)
$out = @()
foreach ($H in $levels) {
    A shell wm size "1366x$H" | Out-Null
    Start-Sleep -Milliseconds 1800

    $w = A shell dumpsys window 2>&1
    $imeSrc  = Get-Field $w 'type=ime frame='
    $imeHin  = Get-Field $w 'mInsetsHint=Insets\{'
    $imeHt   = Get-Field $w '^\s+mImeHeight='
    $imeShow = Get-Field $w '^\s+mIsImeShowing='
    $navSrc  = Get-Field $w 'type=navigationBars frame='
    $appVis  = Get-Field $w 'mVisibleFrame=Rect'   # not present in displays dump; kept as probe
    $appKeep = Get-Field $w 'keepClearAreas=Rect'

    $im = A shell dumpsys input_method 2>&1
    $cti    = Get-Field $im 'contentTopInsets='
    $touch  = Get-Field $im 'touchableRegion='
    $shown  = Get-Field $im 'mIsInputViewShown='
    $inShwn = Get-Field $im 'mInputShown='
    $req    = Get-Field $im 'mShowInputRequested='
    $cfg    = Get-Field $im 'Configuration=\{'     # the IME's OWN config
    $cfgH   = if ($cfg -match 'sw(\d+)dp w(\d+)dp h(\d+)dp (\d+)dpi') { "sw$($Matches[1])dp w$($Matches[2])dp h$($Matches[3])dp $($Matches[4])dpi" } else { 'N/A' }
    $cfgApp = if ($cfg -match 'mAppBounds=Rect\(([^)]*)\)') { $Matches[1] } else { 'N/A' }

    # app window frame (needs the 'windows' section)
    $ww = A shell dumpsys window windows 2>&1
    $appBlk = $ww | Select-String 'Window\{[0-9a-f]+ u0 com\.xiufeigo\.opencodeshell/com\.xiufeigo\.opencodeshell\.OcActivity\}' | Select-Object -Last 1
    $appFrame = 'N/A'; $appSoft = 'N/A'; $appCompat = 'N/A'
    if ($appBlk) {
        $i = $appBlk.LineNumber - 1
        $blk = $ww[$i..([Math]::Min($i + 40, $ww.Count - 1))]
        $appFrame = (Get-Field $blk '^\s+mFrame=Rect').Trim()
        $appSoft  = (Get-Field $blk 'softInputMode').Trim()
        $appCompat= (Get-Field $blk 'mCompatFrame=Rect').Trim()
    }

    $imeH = Get-Height
    $line = "H=$H imeH=$imeH | $imeSrc | $imeHt | $imeHin | $imeShow | $cti | $touch | $shown/$inShwn/$req | IME_CFG=$cfgH appBounds=$cfgApp | APP $appFrame | $appCompat | SOFT $appSoft | keepClear=$appKeep"
    Write-Host $line
    $out += $line
}
$out | Set-Content -Encoding UTF8 "$env:TEMP\_v160_curve.txt"
Write-Host "=== DONE curve: $($out.Count) levels ==="
