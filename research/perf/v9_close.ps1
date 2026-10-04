param([string]$Serial = 'emulator-6190', [string]$AvdName = 'OC_Deliver_R2', [int]$Port = 6190)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = "$PSScriptRoot\out_v9"
$log = "$out\_closeout.txt"
function Say($m) { Write-Host $m; Add-Content $log $m }
Set-Content $log ("# V9 closeout  " + (Get-Date -Format s))
function Procs {
  Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue |
    ForEach-Object {
      $avd = if ($_.CommandLine -match '-avd\s+(\S+)') { $Matches[1] } else { '?' }
      $prt = if ($_.CommandLine -match '-port\s+(\d+)') { $Matches[1] } else { '?' }
      [pscustomobject]@{ pid = $_.ProcessId; name = $_.Name; avd = $avd; port = $prt }
    }
}
Say "[A] 关停前基线：全部 emulator 进程（只列，不动别人的）"
$base = Procs
if (-not $base) { Say "  emulator/qemu 进程数 = 0" }
foreach ($p in $base) { $tag = if ($p.avd -match '^(OC_|Medium_Desktop_B)') { ' <== 本轮的' } else { ' <== 别人的/来源不明，未碰' }; Say "  pid=$($p.pid) name=$($p.name) avd='$($p.avd)' port='$($p.port)'$tag" }
$listen0 = (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count
Say ""
Say "=== 端口 $Port 关停前监听数 = $listen0 ==="
Say "[B] wm 复原（reset）"
Say ("  `$(wm size reset)    => " + (((& $adb -s $Serial shell wm size reset 2>&1) | Out-String).Trim() -replace "`r?`n", ' | '))
Say ("  `$(wm density reset) => " + (((& $adb -s $Serial shell wm density reset 2>&1) | Out-String).Trim() -replace "`r?`n", ' | '))
Start-Sleep -Seconds 3
Say "  复原后读回:"
foreach ($l in (((& $adb -s $Serial shell wm size 2>&1) | Out-String) -split "`r?`n"))    { if ($l.Trim()) { Say "    $($l.Trim())" } }
foreach ($l in (((& $adb -s $Serial shell wm density 2>&1) | Out-String) -split "`r?`n")) { if ($l.Trim()) { Say "    $($l.Trim())" } }
Say ""
Say "[C] 关闭本轮自建实例（只按 AVD 名 + serial，不带 -a、不用通配符）"
$mine = @($base | Where-Object { $_.avd -eq $AvdName -and $_.port -eq "$Port" })
if ($mine.Count -eq 0) { Say "  !! 未在本轮 AVD 名下找到进程，不执行任何 kill" }
else {
  foreach ($m in $mine) { Say "  目标: pid=$($m.pid) name=$($m.name) avd='$($m.avd)' port='$($m.port)' MINE=True" }
  Say "  adb -s $Serial emu kill"
  Say ("  => " + (((& $adb -s $Serial emu kill 2>&1) | Out-String).Trim() -replace "`r?`n", ' | '))
}
Say "  （等待 qemu 退出 ≤30s…）"
for ($i=0; $i -lt 30; $i++) {
  $r = @((Procs) | Where-Object { $_.avd -eq $AvdName })
  if ($r.Count -eq 0) { Say "  [${i}s] 本轮 AVD 进程已归零" ; break }
  Start-Sleep -Seconds 1
}
Start-Sleep -Seconds 3
Say ""
Say "[D] 复核 1：进程"
$after = Procs
$mineAfter = @($after | Where-Object { $_.avd -eq $AvdName })
Say "  残留本轮 AVD ($AvdName) 进程数 = $($mineAfter.Count)"
if (-not $after) { Say "  emulator/qemu 全局进程数 = 0" }
foreach ($p in $after) { $tag = if ($p.avd -match '^(OC_|Medium_Desktop_B)') { ' <== 本轮的' } else { ' <== 别人的/来源不明，未碰' }; Say "  pid=$($p.pid) name=$($p.name) avd='$($p.avd)' port='$($p.port)'$tag" }
$qemuMine = @($after | Where-Object { $_.avd -eq $AvdName -and $_.name -eq 'qemu-system-x86_64.exe' })
Say "  本轮 AVD 的 qemu-system-x86_64 进程数 = $($qemuMine.Count)   (这才是 CPU 幽灵占用的判据)"
Say ""
Say "[E] 复核 2：端口"
$listen = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listen) { Say "  Get-NetTCPConnection -LocalPort $Port -State Listen => 仍在监听 pid=$($listen.OwningProcess)" } else { Say "  Get-NetTCPConnection -LocalPort $Port -State Listen => <空>，端口已释放" }
Say ""
Say "[F] 复核 3：adb devices（未执行 adb kill-server）"
$dev = (& $adb devices -l 2>&1) | Out-String
foreach ($l in ($dev -split "`r?`n")) { if ($l.Trim()) { Say "  $($l.Trim())" } }
Say "  （全程未执行 adb kill-server；未使用 -a / 通配符）"
Say ""
Say "[G] 纪律自查"
$b = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
if ($b) { Say "  后端 127.0.0.1:4095：LISTEN pid=$($b.OwningProcess)（本轮 0 次启停，只做过 adb reverse tcp:4095）" } else { Say "  后端 4095 未监听（异常：Lead 应已起好）" }
Say "  故意保留的实例：无"
$gl = Test-Path "C:\sensorsdata\main\program\opencode-android-shell\.agent-gradle-lock"
Say "  .agent-gradle-lock exists = $gl"
Say ""
Say "=== CLOSEOUT DONE ==="
