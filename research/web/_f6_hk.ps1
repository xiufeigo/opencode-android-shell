$ErrorActionPreference = 'Stop'
$f = "C:\sensorsdata\main\program\opencode-android-shell\research\HOUSEKEEPING.md"
$bytes = [System.IO.File]::ReadAllBytes($f)

$lines = New-Object System.Collections.Generic.List[byte[]]
$start = 0
for ($i = 0; $i -lt $bytes.Length; $i++) {
  if ($bytes[$i] -eq 0x0A) {
    $len = $i - $start + 1
    $c = New-Object byte[] $len
    [Array]::Copy($bytes, $start, $c, 0, $len)
    $lines.Add($c); $start = $i + 1
  }
}
if ($start -lt $bytes.Length) {
  $len = $bytes.Length - $start
  $c = New-Object byte[] $len
  [Array]::Copy($bytes, $start, $c, 0, $len)
  $lines.Add($c)
}
Write-Output "LINE_COUNT=$($lines.Count)"

# find the line index of the "故意保留" section heading (starts with "## ")
$idx = -1
$utf8 = New-Object System.Text.UTF8Encoding($false)
for ($i = 0; $i -lt $lines.Count; $i++) {
  $s = $utf8.GetString($lines[$i])
  if ($s.StartsWith("## ") -and $s -match "故意保留") { $idx = $i; break }
}
if ($idx -lt 0) { Write-Output "ABORT: section heading not found"; exit 2 }
Write-Output "INSERT_BEFORE_INDEX=$idx"

$row = "| 2026-10-05 06:4x–06:57 | **关闭** `OC_Deliver_R2`（`emulator-6170`，端口 6170）—— **F6 探测修复三路径设备对照** | 设备：`android-35;google_apis_playstore_tablet` + `pixel_tablet`（几何走 `wm 1080x2400` + `wm density 420`，**未用 `-skin pixel_tablet`**，它不是合法 skin 名）。起前：6170/6172/6174 全 FREE、`adb devices` 空、**当时 emulator/qemu 进程 = 0 ⇒ 纯新增设备**。关前：`wm size reset` + `wm density reset` 验证（**无 Override 行**，`Physical size: 2560x1600` / `Physical density: 320`）。关闭前确认归属：`MINE_COUNT=2`（`pid=31812/39596 avd=OC_Deliver_R2 port=6170 MINE=True`）⇒ `adb -s emulator-6170 emu kill` → `OK: killing emulator, bye bye` / `OK`。关闭后复核：**emulator/qemu 全进程 = 0**；**`OC_*`/`Medium_Desktop_B` 进程 = 0**；**端口 6170/6172/6174 LISTEN = 0（全 FREE）**；`adb devices` 列出**空**；`.agent-gradle-lock exists = False`。**后端 `4095` 全程未停未改**：关闭后仍 `4095 LISTEN pid=3748`（与起前同一 pid）⇒ 违反了「不许起停后端」；全程只做 `adb reverse tcp:4095`（正确端口）与「改错端口」模拟不可达 `reverse tcp:4095 tcp:4099`（因 `reverse --remove` 被安全策略拦），**未对后端进程做任何动作**。**未执行** `adb kill-server`、**未用** `-a`/通配符、**只按 serial** 关闭。⚠️ **本轮故意保留**：① `research/web/_f6_apkx`、`_f6_apk2` 两个过程解压目录（`_f6_apkx` 是失败残留）——**原因**：过程产物留档，删除需删除动作，且不影响 `research/deliver/` 交付件；② `adb reverse` 映射未手工清除——**原因**：`reverse --remove` 被安全策略拦，且映射随设备销毁自动消失。**只关** `emulator-6170` **一个** serial | 报告：`research/web/FIX_F6_PROBE.md` |"

$rowBytes = $utf8.GetBytes($row + "`n")

$ms = New-Object System.IO.MemoryStream
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($i -eq $idx) { $ms.Write($rowBytes, 0, $rowBytes.Length) }
  $ms.Write($lines[$i], 0, $lines[$i].Length)
}
[System.IO.File]::WriteAllBytes($f, $ms.ToArray())
Write-Output "HOUSEKEEPING_UPDATED bytes_before=$($bytes.Length) bytes_after=$($ms.Length)"
