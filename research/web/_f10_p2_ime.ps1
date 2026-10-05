$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6270"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f10"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p2_ime.log" -Value $s -Encoding utf8; Write-Output $s }

L "=== F10 P2: IME content-level overlap ==="
L "PWD=$pwd"

# ---------- 0. CDP 可用性（先试，失败就记原因） ----------
L "--- CDP availability probe ---"
$appPid = (Sh "pidof $PKG").Trim()
L "APP_PID=$appPid"
$socks = Sh "cat /proc/net/unix"
$dt = ([regex]::Matches($socks,'webview_devtools_remote_\d+') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "DEVTOOLS_SOCKETS_COUNT=$(@($dt).Count)  raw=$($dt -join ',')"
if (@($dt).Count -eq 0) { L "CDP_RESULT=UNAVAILABLE (no webview_devtools_remote socket on release build)" }

# ---------- 1. 进会话：先看当前页 ----------
A @('shell','uiautomator','dump','/sdcard/u2.xml') | Out-Null
$ui2 = Sh "cat /sdcard/u2.xml"
Add-Content -Path "$OUT\ui_before_nav.xml" -Value $ui2 -Encoding utf8
A @('shell','screencap','-p','/sdcard/before_nav.png') | Out-Null
A @('pull','/sdcard/before_nav.png',"$OUT\before_nav.png") | Out-Null
L "SCREEN before_nav bytes=$((Get-Item "$OUT\before_nav.png" -ErrorAction SilentlyContinue).Length)"
$txt2 = (([regex]::Matches($ui2,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' } | Select-Object -Unique) -join ' | ')
L "UI_TEXTS_BEFORE_NAV=$txt2"
L "PRECOND_HAS_SESSION_ROW=$($ui2 -match 'ses_|DRAFT|Friendly|New session')"

# ---------- 2. 原子：聚焦 composer -> 断言 mInputShown -> 抄 ime frame ----------
# $TapX/$TapY = composer 中心（设备像素），由 P2b 依据截图测得后传入
$tapX = $env:F10_TAPX; $tapY = $env:F10_TAPY
L "TAP_TARGET=($tapX,$tapY) (composer center, device px)"
$cmd = @"
st=`$(dumpsys input_method | grep -o 'mInputShown=[a-z]*' | head -1)
echo "PRE=`$st"
input tap $tapX $tapY
sleep 3
st2=`$(dumpsys input_method | grep -o 'mInputShown=[a-z]*' | head -1)
echo "POST=`$st2"
if [ "`$st2" = "mInputShown=true" ]; then
  dumpsys input_method > /data/local/tmp/f10_im.txt
  dumpsys window windows > /data/local/tmp/f10_ww.txt
  screencap -p /sdcard/f10_ime.png
  uiautomator dump /sdcard/f10_ime.xml
  echo "CAPTURED_OK"
else
  echo "CAPTURED_FAIL"
fi
"@
$o = Sh $cmd
L "DEVICE_ATOMIC_OUTPUT=$o"

if ($o -match 'CAPTURED_OK') {
  $im = Sh "cat /data/local/tmp/f10_im.txt"
  $ww = Sh "cat /data/local/tmp/f10_ww.txt"
  Add-Content -Path "$OUT\ime_input_method.txt" -Value $im -Encoding utf8
  Add-Content -Path "$OUT\ime_window_dump.txt" -Value $ww -Encoding utf8
  A @('pull','/sdcard/f10_ime.png',"$OUT\ime_shown.png") | Out-Null
  A @('pull','/sdcard/f10_ime.xml',"$OUT\ime_uia.xml") | Out-Null
  L "IME_SHOT bytes=$((Get-Item "$OUT\ime_shown.png" -ErrorAction SilentlyContinue).Length)"
  $shown = [regex]::Match($im,'mInputShown=(\w+)').Groups[1].Value
  L "ASSERT_mInputShown='$shown'  PASS=$($shown -eq 'true')"
  foreach ($m in [regex]::Matches($im,'(?m)^.*(mInputShown|mServedInputConnection|mServedView|mCurFocusedWindow).*$')) { L "IME_RAW: $($m.Value.Trim())" }

  # ---- type=ime frame 原始行 ----
  L "--- RAW type=ime lines ---"
  $imeBlk = $null
  foreach ($m in [regex]::Matches($ww,'(?m)^.*type=ime.*$')) { L "TYPEIME_RAW: $($m.Value.Trim())" }
  $fm = [regex]::Match($ww,'InsetsSource id=ime[^|]*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  if (-not $fm.Success) { $fm = [regex]::Match($ww,'type=ime[^|]*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') }
  if ($fm.Success) {
    $imeTop=[int]$fm.Groups[2].Value; $imeBot=[int]$fm.Groups[4].Value
    L "IME_FRAME_RECT=[$($fm.Groups[1].Value),$imeTop][$($fm.Groups[3].Value),$imeBot]  imeH=$($imeBot-$imeTop)"
  } else { $imeTop=$null; L "IME_FRAME_RECT=PARSE_FAIL" }
  # app window frame
  $am = [regex]::Match($ww,'(?s)Window\{[^}]*WebHostActivity\}.*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  if ($am.Success) { L "APP_FRAME_RECT=[$($am.Groups[1].Value),$($am.Groups[2].Value)][$($am.Groups[3].Value),$($am.Groups[4].Value)]" }
  foreach ($m in [regex]::Matches($ww,'mGivenContentInsets=\[[^\]]*\]\[[^\]]*\]')) { L "INSET_RAW: $($m.Value)" }
  $imf = $imeTop
  $imf | Out-File "$OUT\ime_top_px.txt" -Encoding utf8
} else {
  L "ASSERT_FAIL: IME not shown; no ime numbers read (discipline)"
  $imf = $null
}

# ---------- 3. uiautomator 节点 bounds（替代证据来源） ----------
L "--- uiautomator nodes with bounds (IME shown) ---"
$uix = ""
if ($o -match 'CAPTURED_OK') { $uix = Sh "cat /sdcard/f10_ime.xml" } else { A @('shell','uiautomator','dump','/sdcard/f10_ime2.xml') | Out-Null; $uix = Sh "cat /sdcard/f10_ime2.xml" }
Add-Content -Path "$OUT\ime_uia_dump.txt" -Value $uix -Encoding utf8
$nodes = [regex]::Matches($uix,'<node[^>]*>')
L "NODE_COUNT=$($nodes.Count)"
foreach ($nd in $nodes) {
  $v = $nd.Value
  $b = [regex]::Match($v,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($b.Success) { L ("NODE cls=$([regex]::Match($v,'class="([^"]*)"').Groups[1].Value) bounds=$($b.Value) clickable=$([regex]::Match($v,'clickable="([^"]*)"').Groups[1].Value)") }
}
L "=== F10 P2 DONE ==="
