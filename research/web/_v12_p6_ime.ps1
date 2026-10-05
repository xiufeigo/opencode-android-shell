$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p6_ime.log" -Value $s -Encoding utf8; Write-Output $s }

L "########## IME overlap (correct parse) ##########"
# 1) show the IME: tap the composer textbox in the official UI
A @('shell','uiautomator','dump','/sdcard/i.xml') | Out-Null
$d = Sh "cat /sdcard/i.xml"
$cl=@()
foreach($m in [regex]::Matches($d,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')){
  $cl += [pscustomobject]@{x1=[int]$m.Groups[1].Value;y1=[int]$m.Groups[2].Value;x2=[int]$m.Groups[3].Value;y2=[int]$m.Groups[4].Value}
}
L "clickable_nodes=$($cl.Count)"
if($cl.Count -ge 1){
  $n=($cl | Sort-Object y1 -Descending | Select-Object -First 1)
  $cx=[int](($n.x1+$n.x2)/2); $cy=[int](($n.y1+$n.y2)/2)
  Sh "input tap $cx $cy" | Out-Null; L "tapped=($cx,$cy)"
}
Start-Sleep -Seconds 4

# 2) assert mInputShown BEFORE reading (input_method is the authoritative source)
$im = Sh "dumpsys input_method"
$ms=[regex]::Match($im,'mInputShown=(\w+)')
$ms2=[regex]::Match($im,'mIsInputViewShown=(\w+)')
$ms3=[regex]::Match($im,'mInputShown\s*=\s*(\w+)')
L "IME_ASSERT_mInputShown_raw='$($ms.Groups[1].Value)$($ms3.Groups[1].Value)'  mIsInputViewShown=$($ms2.Groups[1].Value)"
L "IME_ASSERT_PASS=$($im -match 'mInputShown=true')"
# raw lines
foreach($pat in 'mInputShown','mIsInputViewShown','mShowRequested','mWindowVisible'){
  foreach($m in [regex]::Matches($im,"(?m)^.*$pat=.*$")){ L "IME_input_method_RAW: $($m.Value.Trim())" }
}
A @('shell','screencap','-p','/sdcard/ime.png') | Out-Null
A @('pull','/sdcard/ime.png',"$OUT\ime_shown.png") | Out-Null
L "IME_screenshot bytes=$((Get-Item "$OUT\ime_shown.png" -ErrorAction SilentlyContinue).Length)"

# 3) window dump -> split into per-window blocks, pick the IME window and the app window
$w = Sh "dumpsys window windows"
$blocks = [regex]::Split($w,'(?=\|\s+Window\s+#\d+\s+Window\{)')
$imeBlk=$null; $appBlk=$null
foreach($b in $blocks){
  if($b -match 'mIsImWindow=true' -and $b -match 'InputMethod'){ $imeBlk=$b }
  if($b -match 'com\.xiufeigo\.opencodeshell'){ $appBlk=$b }
}
function FrameOf($blk){
  if($null -eq $blk){ return $null }
  $m=[regex]::Match($blk,'\|\s+Frames:.*?\|\s+frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  if($m.Success){ return @([int]$m.Groups[1].Value,[int]$m.Groups[2].Value,[int]$m.Groups[3].Value,[int]$m.Groups[4].Value) }
  return $null
}
$fA = FrameOf $appBlk
$fI = FrameOf $imeBlk
L "IME_APP_frame_found=$($null -ne $fA)  IME_IME_block_found=$($null -ne $imeBlk)"
if($fA){ L "IME_APP_FRAME=($($fA[0]),$($fA[1]))-($($fA[2]),$($fA[3]))  appH=$($fA[3]-$fA[1])" }
if($fI){ L "IME_IME_FRAME=($($fI[0]),$($fI[1]))-($($fI[2]),$($fI[3]))  imeH=$($fI[3]-$fI[1])" }
# raw type=ime evidence line from the IME block
if($imeBlk){
  $tl=[regex]::Match($imeBlk,'(?m)^.*mIsImWindow=true.*$')
  if($tl.Success){ $t=$tl.Value; if($t.Length -gt 240){$t=$t.Substring(0,240)+'...'}; L "IME_type_ime_RAW: $($t.Trim())" }
  $rr=[regex]::Match($imeBlk,'(?m)^.*touchable region=.*$')
  if($rr.Success){ L "IME_touchable_RAW: $($rr.Value.Trim())" }
  $vv=[regex]::Match($imeBlk,'isOnScreen=(\w+).*?isVisible=(\w+)')
  if($vv.Success){ L "IME_window_isOnScreen=$($vv.Groups[1].Value) isVisible=$($vv.Groups[2].Value)" }
}
if($fA -and $fI){
  $appBottom=$fA[3]; $imeTop=$fI[1]
  $ov=[Math]::Max(0,$appBottom-$imeTop)
  L "IME_OVERLAP_PX=$ov (appBottom=$appBottom imeTop=$imeTop)"
  L "IME_VERDICT=$(if($ov -eq 0){'PASS overlap=0'}else{"OVERLAP=${ov}px"})"
  L "IME_full_dump_saved: $OUT\ime_window_dump.txt"
  ($w | Out-String) | Out-File "$OUT\ime_window_dump.txt" -Encoding utf8
} else { L "IME_VERDICT=INCONCLUSIVE (frame parse failed)" }
L "=== P6 DONE ==="
