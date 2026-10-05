$ErrorActionPreference='Continue'
# F11 常亮三段 —— 修正探针 v3：保留行结构（不再把 \r\n 压成 " | "，那是前两版 UNKNOWN 的真因）
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6290"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f11"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path "$OUT\dev3.log" -Value $s -Encoding utf8; Write-Output $s }

# 保留行结构的采样：一次 adb，取本包窗口所在块
function Sample($label) {
  $raw = A @('shell',"dumpsys window windows | grep -A 30 'Window{.*com.xiufeigo' | grep -E 'Window\\{|flags=|KEEP_SCREEN_ON|isOnScreen|mHasSurface' | head -24")
  Add-Content -Path "$OUT\keep_raw_$label.txt" -Value $raw -Encoding utf8
  $flag='NO_FLAGS'
  $m=[regex]::Match($raw,'flags=([0-9a-fA-Fx]+)')
  if($m.Success){
    $hex=$m.Groups[1].Value -replace 'x',''
    try{ $v=[Convert]::ToInt64($hex,16); $flag=if(($v -band 0x80) -ne 0){'SET(0x80)'}else{'CLEAR(0x80)'} }catch{ $flag='PARSE_FAIL' }
    L "${label} windowFlags=0x$hex  FLAG_KEEP_SCREEN_ON=${flag}"
  } else { L "${label} NO_FLAGS_IN_WINDOW_BLOCK  raw=[$raw]" }
  L "${label} KEEP_SCREEN_ON_token_count=$(([regex]::Matches($raw,'KEEP_SCREEN_ON')).Count)"
  return $flag
}
L "=== F11 keep-screen probe3 start $(Get-Date -Format o) ==="
L "R8 on device: $((A @('shell','pm','path',$PKG)))"
L "IDLE foreground check: $((A @('shell','dumpsys','activity','activities','|','grep','-m1','ResumedActivity')))"
$k1 = Sample "SEG1_t0"
Start-Sleep -Seconds 30
$k2 = Sample "SEG2_t+30s"
Start-Sleep -Seconds 60
$k3 = Sample "SEG3_t+90s"
L "KEEP_3SEG: t0=$k1 | t+30=$k2 | t+90=$k3"
$d1 = A @('shell','logcat','-d','|','grep','D1 applyKeepScreenOn','|','tail','-4')
L "D1_LINES: $d1"
# 结构性证据：常亮相关类在 R8 dex 里是否保留（R8 只在未被 keep 时改名/删除）
$map="C:\sensorsdata\main\program\opencode-android-shell\webapp\build\outputs\mapping\releaseR8\mapping.txt"
foreach($c in 'RoundActivityTracker','PowerPolicy','WebHostBridge','SecretStore'){
  $l=(Select-String -Path $map -Pattern "$c ->" -List).Line
  L "MAPPING $c : $(if($l){$l.Trim()}else{'ABSENT'})"
}
L "=== probe3 done $(Get-Date -Format o) ==="
