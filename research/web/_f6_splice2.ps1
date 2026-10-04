$ErrorActionPreference = 'Stop'
$f = "C:\sensorsdata\main\program\opencode-android-shell\research\web\DELIVER_REBUILD_R2.md"
$bytes = [System.IO.File]::ReadAllBytes($f)

# --- split into lines at the byte level, KEEPING each line's exact bytes (incl. CR) ---
$lines = New-Object System.Collections.Generic.List[byte[]]
$start = 0
for ($i = 0; $i -lt $bytes.Length; $i++) {
  if ($bytes[$i] -eq 0x0A) {
    $len = $i - $start + 1
    $chunk = New-Object byte[] $len
    [Array]::Copy($bytes, $start, $chunk, 0, $len)
    $lines.Add($chunk)
    $start = $i + 1
  }
}
if ($start -lt $bytes.Length) {
  $len = $bytes.Length - $start
  $chunk = New-Object byte[] $len
  [Array]::Copy($bytes, $start, $chunk, 0, $len)
  $lines.Add($chunk)
}
Write-Output "LINE_COUNT=$($lines.Count)"

# --- verify the target block by matching ASCII anchors on lines 89..92 (index 88..91) ---
$ascii = [System.Text.Encoding]::ASCII
$block = $ascii.GetString($lines[88]) + "|" + $ascii.GetString($lines[89]) + "|" + $ascii.GetString($lines[90]) + "|" + $ascii.GetString($lines[91])
if ($block -notmatch "5aa0b63657cb599cd288ebc5597e9d21" -or $block -notmatch "de1a476c32dc25c53d1034ea01ca1e6f") {
  Write-Output "ABORT: anchor mismatch on lines 89..92"
  exit 2
}
# also confirm the surrounding headings so we splice the right section
if ($ascii.GetString($lines[85]) -notmatch "^## 2\." -or $ascii.GetString($lines[87]) -notmatch "^### 2\.1") {
  Write-Output "ABORT: not inside section 2.1"
  exit 2
}
Write-Output "ANCHORS_OK"

# --- build the replacement block as UTF-8 bytes with CRLF ---
$newText = @(
"| 产物 | 产物路径 | 体积 | md5 | sha256 |",
"|---|---|---|---|---|",
"| **release（F6，R8 未开启）** | ``research/deliver/opencode-shell-web-release-nor8.apk`` | **3,975,389 B**（约 3.79 MB） | ``76ca064b7de9246ee446f765a9198b37`` | ``293f25bfd2266a0e2fd47c497e3b67af104851f434527718926868f4aa2c9892`` |",
"| **debug** | ``research/deliver/opencode-shell-web.apk`` | **5,275,479 B**（约 5.03 MB） | ``16c6cf8b7330e5d02879f30333800ad9`` | ``6308275a082c9885c35a62d8ad08ad5214bc7a7e2e4a18a9b8d6e582dd90cc0a`` |",
"",
"> **F6 显式作废旧值（2026-10-05 22:5x–23:0x）**：上表为本轮唯一有效值。以下**全部作废，不得再被引用**：",
"> - F3 件：release ``5aa0b63657cb599cd288ebc5597e9d21``（3,968,173 B）/ debug ``de1a476c32dc25c53d1034ea01ca1e6f``（5,200,219 B）",
"> - F5 / 规范 release（``554feb4``）：release ``627f8ed8a38aaa8caa24a39c6fe1678d0``（3,975,357 B）",
"> - R2 件：release ``7b578ceec3d7e35e2fffa4da67fe2d3b``（3,961,217 B）/ debug ``b716cbeb4c66e55fcb0f497640ae5e0c``（5,145,604 B）",
">",
"> 体积变化（相对 F5 规范件 +32 B）只来自 BackendProbe 一处改动：Base64 由 android.util 换成 java.util。"
) -join "`r`n"
$newBytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($newText + "`r`n")

# --- splice ---
$ms = New-Object System.IO.MemoryStream
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($i -eq 88) { $ms.Write($newBytes, 0, $newBytes.Length); continue }
  if ($i -ge 89 -and $i -le 91) { continue }
  $ms.Write($lines[$i], 0, $lines[$i].Length)
}
[System.IO.File]::WriteAllBytes($f, $ms.ToArray())
Write-Output "SPLICE_OK bytes_before=$($bytes.Length) bytes_after=$($ms.Length)"
