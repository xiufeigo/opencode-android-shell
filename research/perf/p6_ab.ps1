# p6_ab.ps1 — P6 省电 A/B：**代理量**对照（改前 baseline APK vs 改后 after APK）
#
# 纪律：同机（emulator-5940）、同镜像、同几何、同 backend(4095，只 reverse 不起停)、
#      同一段固定脚本化交互、同等后台窗口长度。
# ⚠️ 本脚本**不产生也不推断任何 mAh**。模拟器无电池模型、无 rail 电流；
#    真正的耗电只能真机量，见 BATTERY_HOWTO.md §1。
param(
  [Parameter(Mandatory=$true)][string]$Apk,
  [Parameter(Mandatory=$true)][string]$Tag,        # baseline | after
  [string]$Dev  = 'emulator-5940',
  [int]$BgSec   = 60,
  [int]$FgSec   = 25
)
$ErrorActionPreference='Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Root = Split-Path $PSScriptRoot -Parent
$Out  = "$PSScriptRoot\out_p6\$Tag"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Pkg  = 'com.xiufeigo.opencodeshell.web.debug'
$Act  = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
function A { & $Adb -s $Dev @args }
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 "$Out\_ab.log" }
"" | Out-File -Encoding utf8 "$Out\_ab.log"

# ---- app 进程的 CPU（utime+stime，clock ticks；Android 上 CONFIG_HZ=100 ⇒ ticks*10=ms）----
function Pid_ {
  (A shell pidof $Pkg 2>$null | Out-String).Trim()
}
function CpuTicks {
  $p = Pid_
  if (-not $p) { return -1 }
  $raw = (A shell cat "/proc/$p/stat" 2>$null | Out-String).Trim()
  if (-not $raw) { return -1 }
  # comm 字段可能含空格，用最后一个 ')' 切
  $close = $raw.LastIndexOf(')')
  $rest  = $raw.Substring($close + 1).Trim() -split '\s+'
  # $rest[0] = state(field3) ⇒ utime=field14 ⇒ index 11 ; stime=field15 ⇒ index 12
  $u = [int64]$rest[11]; $s = [int64]$rest[12]
  return ($u + $s)
}
function SocketsToPort {
  param([int]$p)
  if ($p -le 0) { return -1 }
  $o = (A shell cat "/proc/net/tcp" 2>$null | Out-String)
  $hex = '{0:X4}' -f $p
  $c = 0
  # /proc/net/tcp 行：  sl: local_address rem_address st tx:rx tr:when retrnsmt uid ...
  # ⚠️ 按 \s+ 切分时 **st 是第 4 个下标**（前面那个前导空白会造出一个空串），
  #    第一版误取下标 3（那是 rem_address）⇒ st 恒不等于 '01' ⇒ 恒为 0（假零）。
  #    这里改用正则按位置抓，避免再踩。
  foreach ($line in ($o -split "`r?`n")) {
    if ($line -match '^\s*\d+:\s+([0-9A-F]{8}:[0-9A-F]{4})\s+([0-9A-F]{8}:[0-9A-F]{4})\s+([0-9A-F]{2})\s') {
      $rem = $Matches[2]; $st = $Matches[3]
      if ($st -eq '01' -and $rem -match ":$hex$") { $c++ }   # 01 = ESTABLISHED
    }
  }
  return $c
}

Say "=== [$Tag] install $Apk ==="
Say ("  md5=" + (Get-FileHash $Apk -Algorithm MD5).Hash)
Say ("  install: " + (((A install -r -g $Apk 2>&1) | Out-String).Trim() -replace "`r?`n", ' ;; '))
A shell am force-stop $Pkg | Out-Null
Start-Sleep -Seconds 2

# ---- 播种口令：空则弹 setup 对话框且永不 loadUrl ⇒ 会变成"在测口令对话框" ----
Say "=== [$Tag] seed password (web) ==="
$seed = (pwsh -NoProfile -File "$PSScriptRoot\seed_password.ps1" -Side web -Dev $Dev 2>&1 | Out-String)
Say ("  " + (($seed -split "`r?`n" | Where-Object { $_ -match 'RESULT|password field|buttons found' }) -join ' ;; '))
Start-Sleep -Seconds 4

# ---- 干净起点：重置统计 + 冷启动 ----
Say "=== [$Tag] reset + cold start ==="
A shell dumpsys batterystats --reset 2>&1 | Out-Null
A logcat -c | Out-Null
A shell am force-stop $Pkg | Out-Null
Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 12

# ---- 取代理端口 ----
# ⚠️ 两条来源都要：代理自己的 "listening on ..." 行 TAG 是 `OcWebHost/Proxy`
# （LoopbackProxy.kt:99 `TAG = "OcWebHost/Proxy"`），**不是** `OcWebHost`；
# 页面 console 行（TAG=OcWebHost）里也带 `http://127.0.0.1:<port>/_assets`。
# 上一轮只抓 `OcWebHost:V` ⇒ port 解析成 -1、且 P6 日志整段丢失。
$TAGFILT = 'OcWebHost:V OcWebHost/Proxy:V OcPerf:V OcPerfJS:V'
$lc0 = (A logcat -d -s OcWebHost:V OcWebHost/Proxy:V 2>$null | Out-String)
$port = -1
if ($lc0 -match 'listening on http://127\.0\.0\.1:(\d+)') { $port = [int]$Matches[1] }
elseif ($lc0 -match 'http://127\.0\.0\.1:(\d+)/_assets') { $port = [int]$Matches[1] }
Say "  proxyPort=$port  (source: $(if ($port -gt 0) { if ($lc0 -match 'listening on http://127\.0\.0\.1:\d+') {'proxy listening line'} else {'page console line'} } else { 'NOT FOUND' }))"
$lc0 | Out-File -Encoding utf8 "$Out\startup_logcat.txt"

# ---- 固定脚本化交互（**两轮逐字相同**）----
Say "=== [$Tag] fixed scripted interaction (${FgSec}s) ==="
A shell input swipe 540 1920 540 480 250 | Out-Null; Start-Sleep -Milliseconds 250
A shell input swipe 540 480 540 1920 250  | Out-Null; Start-Sleep -Milliseconds 250
A shell input swipe 540 1920 540 480 250 | Out-Null; Start-Sleep -Milliseconds 250
A shell input swipe 540 480 540 1920 250  | Out-Null; Start-Sleep -Milliseconds 250
A shell input tap 160 1200 | Out-Null; Start-Sleep -Milliseconds 350
A shell input tap 920 360  | Out-Null; Start-Sleep -Milliseconds 350
$fg0 = Get-Date
$fgT0 = CpuTicks
Start-Sleep -Seconds $FgSec
$fgT1 = CpuTicks
Say ("  FOREGROUND ${FgSec}s: cpu_ticks $fgT0 -> $fgT1  (delta=" + ($fgT1 - $fgT0) + ", ms=" + (($fgT1-$fgT0)*10) + ")")
Say ("  established sockets to proxyPort (fg) = " + (SocketsToPort $port))
A shell dumpsys batterystats --charged $Pkg 2>&1 | Out-File -Encoding utf8 "$Out\batterystats_foreground.txt"

# ---- 关键窗口：退到后台，静置 $BgSec 秒 ----
Say "=== [$Tag] BACKGROUND ${BgSec}s (HOME) ==="
$bgT0 = CpuTicks
$sock0 = SocketsToPort $port
A shell input keyevent KEYCODE_HOME | Out-Null
$bgStart = Get-Date
Start-Sleep -Seconds 3
$sockAfter = SocketsToPort $port
Start-Sleep -Seconds ($BgSec - 3)
$bgT1 = CpuTicks
$sock1 = SocketsToPort $port
$bgEnd = Get-Date

# 窗口内的 proxy 侧证据（只有改后才有 P6 日志；注意 TAG = OcWebHost/Proxy）
A logcat -d -s OcWebHost:V OcWebHost/Proxy:V 2>&1 | Out-File -Encoding utf8 "$Out\logcat_after_bg.txt"
$p6lines = (A logcat -d -s OcWebHost:V OcWebHost/Proxy:V 2>&1 | Select-String -Pattern 'P6 (suspendStreaming|resumeStreaming|refuse SSE)')
Say ("  P6 log lines: " + (($p6lines | ForEach-Object { $_.Line.Trim() }) -join ' ;; '))
$p6lines | ForEach-Object { $_.Line } | Out-File -Encoding utf8 "$Out\p6_lines.txt"

# ---- 终点读数 ----
A shell dumpsys batterystats --charged $Pkg 2>&1 | Out-File -Encoding utf8 "$Out\batterystats_end.txt"
A shell dumpsys power 2>&1 | Out-File -Encoding utf8 "$Out\power.txt"
A shell dumpsys power 2>&1 | Select-String -Pattern 'Wake Locks:' -Context 0,14 |
  ForEach-Object { $_.Line; $_.Context.PostContext } | Out-File -Encoding utf8 "$Out\wakelocks.txt"
A shell dumpsys netstats 2>&1 | Out-File -Encoding utf8 "$Out\netstats.txt"
A shell cat "/proc/$(Pid_)/net/dev" 2>&1 | Out-File -Encoding utf8 "$Out\netdev.txt"
A shell dumpsys window 2>&1 | Out-File -Encoding utf8 "$Out\window.txt"
A shell dumpsys window 2>&1 | Select-String -Pattern 'mCurrentFocus|mFocusedApp' |
  ForEach-Object { $_.Line.Trim() } | Out-File -Encoding utf8 "$Out\focus.txt"

# ---- 常亮 flag 现场取证（改后应为空 / 改前也为空，因为两边都无人置位）----
$flag = (A shell dumpsys window windows 2>&1 | Select-String -Pattern 'KEEP_SCREEN_ON|fl=|KEEP' | Select-Object -First 8)
$flag | ForEach-Object { $_.Line.Trim() } | Out-File -Encoding utf8 "$Out\window_flags.txt"

# ---- 汇总 ----
$row = [ordered]@{
  tag              = $Tag
  apk_md5          = (Get-FileHash $Apk -Algorithm MD5).Hash
  proxy_port       = $port
  fg_sec           = $FgSec
  bg_sec           = $BgSec
  fg_cpu_ticks     = $fgT1 - $fgT0
  fg_cpu_ms        = ($fgT1 - $fgT0) * 10
  bg_cpu_ticks     = $bgT1 - $bgT0
  bg_cpu_ms        = ($bgT1 - $bgT0) * 10
  bg_window_s      = [math]::Round(($bgEnd - $bgStart).TotalSeconds, 1)
  sock_before_home = $sock0
  sock_3s_after_home = $sockAfter
  sock_bg_end      = $sock1
  p6_log_lines     = @($p6lines).Count
  p6_closed        = (($p6lines | ForEach-Object { if ($_.Line -match 'closed=(\d+)') { $Matches[1] } }) -join '+')
  p6_refused       = (($p6lines | ForEach-Object { if ($_.Line -match 'refused=(\d+)') { $Matches[1] } }) -join '+')
}
$row | ConvertTo-Json | Out-File -Encoding utf8 "$Out\summary.json"
Say "=== [$Tag] SUMMARY ==="
$row.GetEnumerator() | ForEach-Object { Say ("  {0} = {1}" -f $_.Key, $_.Value) }
Say "[$Tag] DONE"
