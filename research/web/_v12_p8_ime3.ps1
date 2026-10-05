$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p8_ime3.log" -Value $s -Encoding utf8; Write-Output $s }

L "########## IME final: single-shell capture (no re-tap race) ##########"
Sh "input tap 540 1450" | Out-Null
# one shell round-trip: assert mInputShown AND grab the window dump together
$cap = Sh "dumpsys input_method > /data/local/tmp/im.txt; dumpsys window windows > /data/local/tmp/ww.txt; grep -o 'mInputShown=[a-z]*' /data/local/tmp/im.txt | head -1"
L "CAPTURED=$cap"
$imTxt = Sh "cat /data/local/tmp/im.txt"
$wTxt  = Sh "cat /data/local/tmp/ww.txt"
($imTxt | Out-String) | Out-File "$OUT\ime_input_method.txt" -Encoding utf8
($wTxt | Out-String)  | Out-File "$OUT\ime_window_dump.txt" -Encoding utf8
$shown = [regex]::Match($imTxt,'mInputShown=(\w+)').Groups[1].Value
L "IME_ASSERT_mInputShown='$shown'  PASS=$($shown -eq 'true')"
foreach($m in [regex]::Matches($imTxt,'(?m)^.*(mInputShown|mIsInputViewShown|mShowRequested).*$')){ L "IME_RAW: $($m.Value.Trim())" }
A @('shell','screencap','-p','/sdcard/imef.png') | Out-Null
A @('pull','/sdcard/imef.png',"$OUT\ime_shown.png") | Out-Null
L "IME_screenshot bytes=$((Get-Item "$OUT\ime_shown.png" -ErrorAction SilentlyContinue).Length)"

$blocks = [regex]::Split($wTxt,'(?=\|\s+Window\s+#\d+\s+Window\{)')
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
L "IME_APP_blk_found=$($null -ne $appBlk)  IME_blk_found=$($null -ne $imeBlk)"
if($fA){ L "IME_APP_FRAME=($($fA[0]),$($fA[1]))-($($fA[2]),$($fA[3])) appH=$($fA[3]-$fA[1])" }
if($fI){ L "IME_type_ime_frame=($($fI[0]),$($fI[1]))-($($fI[2]),$($fI[3])) imeH=$($fI[3]-$fI[1])" }
if($imeBlk){
  $vv=[regex]::Match($imeBlk,'isOnScreen=(\w+).*?isVisible=(\w+)'); if($vv.Success){ L "IME_isOnScreen=$($vv.Groups[1].Value) isVisible=$($vv.Groups[2].Value)" }
  $tr=[regex]::Match($imeBlk,'touchable region=([^|]*)'); if($tr.Success){ L "IME_touchable_region=$($tr.Groups[1].Value.Trim())" }
  $ra=[regex]::Match($imeBlk,'mAttrs=\{\(0,0\)\((\d+)x(\d+)\)'); if($ra.Success){ L "IME_type_ime_RAW_requested=$($ra.Groups[1].Value)x$($ra.Groups[2].Value)" }
}
if($fA -and $fI -and $shown -eq 'true'){
  $ov=[Math]::Max(0,$fA[3]-$fI[1])
  L "IME_OVERLAP_PX=$ov (appBottom=$($fA[3]) imeTop=$($fI[1]))"
  L "IME_VERDICT=$(if($ov -eq 0){'PASS overlap=0'}else{"OVERLAP=${ov}px"})"
} else { L "IME_VERDICT=INCONCLUSIVE (mInputShown='$shown' appFrame=$($null -ne $fA) imeFrame=$($null -ne $fI))" }
L "=== P8 DONE ==="
