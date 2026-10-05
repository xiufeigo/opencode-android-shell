$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p7_ime2.log" -Value $s -Encoding utf8; Write-Output $s }
function Shown { $im = Sh "dumpsys input_method"; $a=[regex]::Match($im,'mInputShown=(\w+)').Groups[1].Value; return $a }

L "########## IME retry: force the soft keyboard up ##########"
# make the soft IME available and force it visible
L "SET show_ime_with_hard_keyboard=0: $(Sh 'settings put secure show_ime_with_hard_keyboard 0')"
$imms = Sh "ime list -s"
L "IME_LIST_S=$imms"
# pick the latin IME explicitly
$lat = ([regex]::Matches($imms,'com\.google\.android\.inputmethod\.latin[\S]*') | Select-Object -First 1)
if($lat){ L "IME_ENABLE: $(Sh "ime enable $($lat.Value)")"; L "IME_SET: $(Sh "ime set $($lat.Value)")" }

$pts = @(@(540,1450),@(540,1380),@(300,1450),@(540,1200),@(540,1500))
foreach($p in $pts){
  Sh "input tap $($p[0]) $($p[1])" | Out-Null
  Start-Sleep -Seconds 2
  $s = Shown
  L "TAP ($($p[0]),$($p[1])) -> mInputShown=$s"
  if($s -eq 'true'){ L "IME_UP_AT=($($p[0]),$($p[1]))"; break }
}
Start-Sleep -Seconds 2
$final = Shown
L "IME_ASSERT_mInputShown=$final  PASS=$($final -eq 'true')"
if($final -eq 'true'){
  A @('shell','screencap','-p','/sdcard/ime2.png') | Out-Null
  A @('pull','/sdcard/ime2.png',"$OUT\ime_shown.png") | Out-Null
  $w = Sh "dumpsys window windows"
  $blocks = [regex]::Split($w,'(?=\|\s+Window\s+#\d+\s+Window\{)')
  $imeBlk=$null; $appBlk=$null
  foreach($b in $blocks){
    if($b -match 'Window\{[^}]*InputMethod\}'){ $imeBlk=$b }
    if($b -match 'com\.xiufeigo\.opencodeshell\.web\.WebHostActivity'){ $appBlk=$b }
  }
  function FrameOf($blk){
    if($null -eq $blk){ return $null }
    $m=[regex]::Match($blk,'\|\s+Frames:.*?\|\s+frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if($m.Success){ return @([int]$m.Groups[1].Value,[int]$m.Groups[2].Value,[int]$m.Groups[3].Value,[int]$m.Groups[4].Value) }
    return $null
  }
  $fA=FrameOf $appBlk; $fI=FrameOf $imeBlk
  L "IME_APP_blk=$($null -ne $appBlk) IME_blk=$($null -ne $imeBlk)"
  if($fA){ L "IME_APP_FRAME=($($fA[0]),$($fA[1]))-($($fA[2]),$($fA[3])) appH=$($fA[3]-$fA[1])" }
  if($fI){ L "IME_TYPE_IME_FRAME=($($fI[0]),$($fI[1]))-($($fI[2]),$($fI[3])) imeH=$($fI[3]-$fI[1])" }
  if($imeBlk){
    $vr=[regex]::Match($imeBlk,'(?m)^.*touchable region=.*$'); if($vr.Success){ L "IME_touchable: $($vr.Value.Trim())" }
    $vv=[regex]::Match($imeBlk,'isOnScreen=(\w+).*?isVisible=(\w+)'); if($vv.Success){ L "IME_isOnScreen=$($vv.Groups[1].Value) isVisible=$($vv.Groups[2].Value)" }
  }
  if($fA -and $fI){
    $ov=[Math]::Max(0,$fA[3]-$fI[1])
    L "IME_OVERLAP_PX=$ov (appBottom=$($fA[3]) imeTop=$($fI[1]))"
    L "IME_VERDICT=$(if($ov -eq 0){'PASS overlap=0'}else{"OVERLAP=${ov}px"})"
  } else { L "IME_VERDICT=INCONCLUSIVE" }
  ($w | Out-String) | Out-File "$OUT\ime_window_dump.txt" -Encoding utf8
} else {
  L "IME_VERDICT=NOT_MEASURABLE (mInputShown never became true on this WebView page)"
  A @('shell','screencap','-p','/sdcard/ime3.png') | Out-Null
  A @('pull','/sdcard/ime3.png',"$OUT\ime_notshown.png") | Out-Null
}
L "=== P7 DONE ==="
