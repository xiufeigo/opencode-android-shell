$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p3.log" -Value $s -Encoding utf8; Write-Output $s }

# uiautomator 截断多字节字（"保存并连接" → "保存并连?"），所以**不能按 text 找按钮**。
# 改为按 bounds 找「底部按钮行里最右那个可点节点」= 保存并连接。
function FindSaveBtn($xml) {
  $cands = @()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $v=$m.Value
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $y1 -le 1100 -and $x1 -ge 600) {
      $cands += [pscustomobject]@{ cx=[int](($x1+$x2)/2); cy=[int](($y1+$y2)/2); x1=$x1; y1=$y1; x2=$x2; y2=$y2; raw=$v }
    }
  }
  if ($cands.Count -eq 0) { return $null }
  return ($cands | Sort-Object x1 -Descending | Select-Object -First 1)
}
function FindRetryBtn($xml) {
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $v=$m.Value
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -lt 600) { return [pscustomobject]@{ cx=[int](($x1+$x2)/2); cy=[int](($y1+$y2)/2) } }
  }
  return $null
}
function UiDump($tag) {
  A @('shell','uiautomator','dump','/sdcard/u.xml') | Out-Null
  $d = Sh "cat /sdcard/u.xml"
  Add-Content -Path "$OUT\ui_$tag.xml" -Value $d -Encoding utf8
  A @('shell','screencap','-p',"/sdcard/$tag.png") | Out-Null
  A @('pull',"/sdcard/$tag.png","$OUT\$tag.png") | Out-Null
  return $d
}
function TypePw($pw) {
  A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
  $d = Sh "cat /sdcard/d.xml"
  $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($et.Count -lt 1) { $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
  if ($et.Count -lt 1) { return $false }
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2)
  $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1400
  foreach ($ch in $pw.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 260 }
  return $true
}
function Seed($pw,$tag) {
  A @('shell','pm','clear',$PKG) | Out-Null
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 7
  $ok = TypePw $pw
  A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
  $d2 = Sh "cat /sdcard/d2.xml"
  $dots = [regex]::Match($d2,'password="true"[^>]*bounds')
  $b = FindSaveBtn $d2
  L "  seed[$tag]: typed=$ok btnFound=$($null -ne $b)"
  if ($b) { Sh "input tap $($b.cx) $($b.cy)" | Out-Null; L "  seed[$tag]: TAPPED SAVE ($($b.cx),$($b.cy)) from bounds [$($b.x1),$($b.y1)][$($b.x2),$($b.y2)]" }
  else { L "  seed[$tag]: SAVE_BTN_NOT_FOUND" }
  Start-Sleep -Seconds 12
}

# ================= CASE A: correct password =================
L "=== CASE A: correct password opencode ==="
Seed "opencode" "A"
$logA = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_A_correct.txt" -Value $logA -Encoding utf8
L "A_probeOK=$($logA -match 'probe OK code=200')  A_AUTH_FAILED=$(([regex]::Matches($logA,'AUTH FAILED')).Count)"
$g=([regex]::Matches($logA,'GET http://127\.0\.0\.1:4095/api/[^ ]*')|ForEach-Object{$_.Value}|Select-Object -Unique)
L "A_API_GETS_UNIQUE=$($g.Count)"; foreach($x in $g){ L "  A_API $x" }
foreach($k in 'J.g1.preflightOk','J.g1.blockedOldWebview','M.g1.firstContent','probe OK','proxy listening'){
  $m=[regex]::Match($logA,"(?m)^.*$([regex]::Escape($k)).*$")
  L "A_LINE[$k]: $(if($m.Success){$m.Value.Trim()}else{'ABSENT'})"
}
$uiA = UiDump "A_correct_ui"
$ta=(([regex]::Matches($uiA,'text="([^"]+)"')|ForEach-Object{$_.Groups[1].Value}|Where-Object{$_ -ne '' -and $_ -notmatch 'resource-id'}|Select-Object -Unique) -join ' | ')
L "A_UI_VISIBLE_TEXTS: $ta"
L "A_HAS_AUTH_DIALOG=$($uiA -match '用户名或口令不对')  (expect False)"
L "A_REACHED_OFFICIAL=$($logA -match 'M\.g1\.firstContent')"
$port = -1
$pm=[regex]::Match($logA,'proxy listening[^\r\n]*?(\d{4,5})')
foreach($mm in [regex]::Matches($logA,'(?m)^.*(?:proxy|127\.0\.0\.1):(\d{4,5}).*$')){ $port=[int]$mm.Groups[1].Value; break }
L "PROXY_PORT_DETECTED=$port"
$port | Out-File "$OUT\proxy_port.txt" -Encoding utf8
L "=== P3 DONE ==="
