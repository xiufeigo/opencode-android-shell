# p5_close.ps1 — P5 收尾（HOUSEKEEPING.md 硬规矩）
# 1) 谁起谁关：只按 **AVD 名（OC_* / Medium_Desktop_B）** + **serial** 定位并关
# 2) 禁 adb kill-server；禁 -a / 通配符杀进程
# 3) 复核：进程 0 残留 + 端口已释放 + adb devices 不再有本轮 serial
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Out = "$PSScriptRoot\out_p5"
$CloseLog = Join-Path $Out "_closeout.txt"
"" | Out-File -Encoding utf8 $CloseLog
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 $CloseLog }

$OurPorts = @(5840, 5842)     # 本轮我自己用过的端口
$OurAvds  = @('OC_Perf_Test') # 本轮我自己起的 AVD

Say "=== [A] 关停前基线：当前全部 emulator 进程（只列，不动别人的） ==="
Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
  $cl = $_.CommandLine
  $avd = if ($cl -match '-avd\s+(\S+)') { $Matches[1] } else { '' }
  $port = if ($cl -match '-port\s+(\d+)') { $Matches[1] } else { '' }
  $ours = if ($OurAvds -contains $avd) { '  <== 本轮的' } else { '  <== 别人的/来源不明，未碰' }
  Say ("  pid={0,-6} name={1,-22} avd='{2}' port='{3}'{4}" -f $_.ProcessId, $_.Name, $avd, $port, $ours)
}
foreach ($p in $OurPorts) {
  $n = (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count
  Say "=== 端口 $p 关停前监听数 = $n ==="
}

Say "=== [B] 关闭本轮自建实例（只按 AVD 名 + serial） ==="
$ourProcs = Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
if (-not $ourProcs) { Say "  本轮 AVD 无残留进程（可能已被外部 kill）" }
foreach ($p in $ourProcs) {
  $port = if ($p.CommandLine -match '-port\s+(\d+)') { $Matches[1] } else { $null }
  $avd  = if ($p.CommandLine -match '-avd\s+(\S+)') { $Matches[1] } else { '?' }
  if ($port) {
    Say "  adb -s emulator-$port emu kill  (avd=$avd pid=$($p.ProcessId))"
    & $adb -s "emulator-$port" emu kill 2>&1 | ForEach-Object { Say "    $_" }
  }
}
Start-Sleep -Seconds 8

# 若 AVD 进程已经不在（被外部 kill），仍对已知 serial 做一次幂等的 emu kill
foreach ($port in $OurPorts) {
  $st = ((& $adb -s "emulator-$port" get-state 2>$null) | Out-String).Trim()
  if ($st) { Say "  补一次幂等 emu kill: emulator-$port (state=$st)"; & $adb -s "emulator-$port" emu kill 2>&1 | ForEach-Object { Say "    $_" } }
}
Start-Sleep -Seconds 5

Say "=== [C] 复核 1：进程（本轮 AVD 应 0 残留） ==="
$left = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
$mine = $left | Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
Say "  残留本轮 AVD 进程数 = $($mine.Count)"
$left | ForEach-Object {
  $cl=$_.CommandLine
  $avd = if ($cl -match '-avd\s+(\S+)') { $Matches[1] } else { '' }
  $port = if ($cl -match '-port\s+(\d+)') { $Matches[1] } else { '' }
  $ours = if ($OurAvds -contains $avd) { '  <== 本轮的（应为空！）' } else { '  <== 别人的，未碰' }
  Say ("  pid={0,-6} name={1,-22} avd='{2}' port='{3}'{4}" -f $_.ProcessId, $_.Name, $avd, $port, $ours)
}
$qemu = ($left | Where-Object { $_.Name -eq 'qemu-system-x86_64.exe' }).Count
Say "  qemu-system-x86_64 进程数 = $qemu   (这才是 CPU 幽灵占用的判据)"

Say "=== [D] 复核 2：端口 ${OurPorts} 监听（应为空） ==="
foreach ($p in $OurPorts) {
  $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
  if ($c) { Say "  端口 $p 仍在监听：$($c | ForEach-Object { $_.OwningProcess } | Join-String -Separator ',')" }
  else { Say "  Get-NetTCPConnection -LocalPort $p -State Listen => <空>，端口已释放" }
}
$tw = Get-NetTCPConnection -LocalPort $OurPorts -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'TimeWait' }
if ($tw) { Say "  （另有 $($tw.Count) 条 TimeWait，是 TCP 正常挥手残留，非监听）" }

Say "=== [E] 复核 3：adb devices（不应再有本轮 serial；未执行 adb kill-server） ==="
& $adb devices | ForEach-Object { Say "  $_" }
Say "  （全程未执行 adb kill-server；未使用 -a / 通配符；非 OC_* 实例一次都没碰）"
Say "=== CLOSEOUT DONE ==="
