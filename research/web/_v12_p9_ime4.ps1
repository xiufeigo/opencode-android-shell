$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p9_ime4.log" -Value $s -Encoding utf8; Write-Output $s }

L "########## IME: on-device atomic tap+read ##########"
# All on-device in ONE shell call so the IME cannot toggle away before the read.
$cmd = @'
st=$(dumpsys input_method | grep -o "mInputShown=[a-z]*" | head -1)
echo "PRE=$st"
if [ "$st" != "mInputShown=true" ]; then input tap 540 1450; sleep 2; fi
st2=$(dumpsys input_method | grep -o "mInputShown=[a-z]*" | head -1)
echo "POST=$st2"
if [ "$st2" = "mInputShown=true" ]; then
  dumpsys input_method > /data/local/tmp/im2.txt
  dumpsys window windows > /data/local/tmp/ww2.txt
  screencap -p /sdcard/ime_ok.png
  echo "CAPTURED_OK"
else
  echo "CAPTURED_FAIL"
fi
'@
$o = Sh $cmd
L "DEVICE_SCRIPT_OUTPUT: $o"
if($o -match 'CAPTURED_OK'){
  $imTxt = Sh "cat /data/local/tmp/im2.txt"
  $wTxt  = Sh "cat /data/local/tmp/ww2.txt"
  ($imTxt|Out-String)|Out-File "$OUT\ime_input_method.txt" -Encoding utf8
  ($wTxt|Out-String)|Out-File "$OUT\ime_window_dump.txt" -Encoding utf8
  A @('pull','/sdcard/ime_ok.png',"$OUT\ime_shown.png") | Out-Null
  L "IME_screenshot bytes=$((Get-Item "$OUT\ime_shown.png" -ErrorAction SilentlyContinue).Length)"
  $shown=[regex]::Match($imTxt,'mInputShown=(\w+)').Groups[1].Value
  L "IME_ASSERT_mInputShown='$shown' PASS=$($shown -eq 'true')"
  foreach($m in [regex]::Matches($imTxt,'(?m)^.*(mInputShown|mIsInputViewShown).*$')){ L "IME_RAW: $($m.Value.Trim())" }
  $blocks=[regex]::Split($wTxt,'(?=\|\s+Window\s+#\d+\s+Window\{)')
  $imeBlk=$null;$appBlk=$null
  foreach($b in $blocks){ if($b -match 'Window\{[^}]*InputMethod\}'){$imeBlk=$b}; if($b -match 'com\.xiufeigo\.opencodeshell\.web\.WebHostActivity'){$appBlk=$b} }
  function FrameOf($blk){ if($null -eq $blk){return $null}; $m=[regex]::Match($blk,'\|\s+Frames:.*?\|\s+frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]'); if($m.Success){return @([int]$m.Groups[1].Value,[int]$m.Groups[2].Value,[int]$m.Groups[3].Value,[int]$m.Groups[4].Value)}; return $null }
  $fA=FrameOf $appBlk; $fI=FrameOf $imeBlk
  if($fA){ L "IME_APP_FRAME=($($fA[0]),$($fA[1]))-($($fA[2]),$($fA[3])) appH=$($fA[3]-$fA[1])" } else { L "IME_APP_FRAME=PARSE_FAIL" }
  if($fI){ L "IME_type_ime_frame=($($fI[0]),$($fI[1]))-($($fI[2]),$($fI[3])) imeH=$($fI[3]-$fI[1])" } else { L "IME_type_ime_frame=PARSE_FAIL" }
  if($imeBlk){ $vv=[regex]::Match($imeBlk,'isOnScreen=(\w+).*?isVisible=(\w+)'); if($vv.Success){L "IME_isOnScreen=$($vv.Groups[1].Value) isVisible=$($vv.Groups[2].Value)"}; $tr=[regex]::Match($imeBlk,'touchable region=([^|]*)'); if($tr.Success){L "IME_touchable_region=$($tr.Groups[1].Value.Trim())"} }
  if($fA -and $fI){
    $ov=[Math]::Max(0,$fA[3]-$fI[1])
    L "IME_OVERLAP_PX=$ov (appBottom=$($fA[3]) imeTop=$($fI[1]))"
    L "IME_VERDICT=$(if($ov -eq 0){'PASS overlap=0'}else{"OVERLAP=${ov}px"})"
  } else { L "IME_VERDICT=INCONCLUSIVE" }
} else {
  $imTxt = Sh "dumpsys input_method"
  L "IME_ASSERT_mInputShown_now=$([regex]::Match($imTxt,'mInputShown=(\w+)').Groups[1].Value)"
  L "IME_VERDICT=NOT_MEASURABLE (mInputShown did not become true; precondition mInputShown=true not met)"
}
L "=== P9 DONE ==="
