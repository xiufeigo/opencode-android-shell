$ErrorActionPreference='Continue'
# F11 收尾探针：① E1「不可达」的真实文案（uiautomator 原生层 + logcat 标记）
#            ② 常亮：单点窗口 flags 采样（保留行结构）+ D1 行
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6290"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f11"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path "$OUT\dev4.log" -Value $s -Encoding utf8; Write-Output $s }
function FindSaveBtn($xml) {
  $c=@()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $c += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2);x1=$x1} }
  }
  if($c.Count -eq 0){return $null}; return ($c | Sort-Object x1 -Descending | Select-Object -First 1)
}
function VisText($xml){
  $vals=@()
  foreach($m in [regex]::Matches(($xml|Out-String),'text="([^"]+)"')){ $v=$m.Groups[1].Value; if($v -ne ''){ $vals += $v } }
  return (($vals | Select-Object -Unique) -join ' ~ ')
}
L "=== F11 probe4 $(Get-Date -Format o) ==="

# ---------- 常亮：窗口 flags 单点采样（行结构保留）----------
$raw = A @('shell',"dumpsys window windows | grep -A 30 'Window{.*com.xiufeigo' | grep -E 'Window\\{|flags=|KEEP_SCREEN_ON|mHasSurface' | head -20")
Add-Content -Path "$OUT\keep_raw_probe4.txt" -Value $raw -Encoding utf8
L "WINDOW_RAW: $raw"
$m=[regex]::Match($raw,'flags=([0-9a-fA-Fx]+)')
if($m.Success){
  $hex=$m.Groups[1].Value -replace 'x',''
  $v=[Convert]::ToInt64($hex,16)
  L "WINDOW_FLAGS=0x$hex  FLAG_KEEP_SCREEN_ON(0x80)=$(if(($v -band 0x80) -ne 0){'SET'}else{'CLEAR'})  (idle: CLEAR is the correct expected value, cf. FIX_F4_KEEPSCREEN.md)"
} else { L "WINDOW_FLAGS=NOT_FOUND raw above" }
L "D1_LINES: $(A @('shell','logcat','-d','|','grep','D1 applyKeepScreenOn','|','tail','-3'))"

# ---------- E1 不可达：真实文案 ----------
L "########## E1 unreachable (final) ##########"
A @('reverse','tcp:4095','tcp:4099') | Out-Null
A @('shell','pm','clear',$PKG) | Out-Null
A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 8
A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
$d = Sh "cat /sdcard/d.xml"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($et.Count -lt 1) { $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
if ($et.Count -ge 1) {
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1400
  foreach ($ch in 'opencode'.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 260 }
}
A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
$b = FindSaveBtn (Sh "cat /sdcard/d2.xml")
if ($b) { Sh "input tap $($b.cx) $($b.cy)" | Out-Null; L "E1 SAVE tapped ($($b.cx),$($b.cy))" } else { L "E1 SAVE_NOT_FOUND" }
Start-Sleep -Seconds 12
A @('shell','uiautomator','dump','/sdcard/u.xml') | Out-Null
$u = Sh "cat /sdcard/u.xml"
Add-Content -Path "$OUT\ui_E1_final.xml" -Value $u -Encoding utf8
A @('shell','screencap','-p','/sdcard/E1_final.png') | Out-Null
A @('pull','/sdcard/E1_final.png',"$OUT\E1_final.png") | Out-Null
L "E1_VISIBLE: $(VisText $u)"
$lg = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log4_E1.txt" -Value $lg -Encoding utf8
$mu=[regex]::Match($lg,'(?m)^.*probe unreachable.*$'); L "E1_PROBE_LINE: $(if($mu.Success){$mu.Value.Trim()}else{'ABSENT'})"
$mc=[regex]::Match($lg,'(?m)^.*ConnectErrorPage.*$'); L "E1_ERRPAGE_LINE: $(if($mc.Success){$mc.Value.Trim()}else{'ABSENT'})"
$mr=[regex]::Match($lg,'(?m)^.*RenderProcessGone.*$'); L "E1_RENDERGONE: $(if($mr.Success){$mr.Value.Trim()}else{'NONE'})"
L "E1_probeOK=$($lg -match 'probe OK code=200') (expect False)"
L "E1_AUTHFAILED=$(([regex]::Matches($lg,'AUTH FAILED')).Count) (expect 0 - unreachable != authfail)"
$mc2=[regex]::Match($lg,'(?m)^.*[Cc]onnect.*(unreachable|refused|失败|不可达).*$'); L "E1_CONNECT_ERR: $(if($mc2.Success){$mc2.Value.Trim()}else{'ABSENT'})"
A @('reverse','tcp:4095','tcp:4095') | Out-Null
L "REVERSE restored"
L "=== probe4 done $(Get-Date -Format o) ==="
