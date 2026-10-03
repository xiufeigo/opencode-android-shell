# sigdiff.ps1 — 替代 uiautomator dump 的"点前/点后签名 diff"
# dump 在本机不可用（UiTestAutomationBridge null root），改用逐像素差分作为可观测签名
param([string]$A, [string]$B)
Add-Type -AssemblyName System.Drawing
$ia = [System.Drawing.Bitmap]::FromFile($A)
$ib = [System.Drawing.Bitmap]::FromFile($B)
$w = [Math]::Min($ia.Width, $ib.Width); $h = [Math]::Min($ia.Height, $ib.Height)
$diff = 0; $total = $w * $h
$rows = @{}
for ($y = 0; $y -lt $h; $y += 1) {
  for ($x = 0; $x -lt $w; $x += 1) {
    $pa = $ia.GetPixel($x, $y); $pb = $ib.GetPixel($x, $y)
    if ([Math]::Abs($pa.R - $pb.R) + [Math]::Abs($pa.G - $pb.G) + [Math]::Abs($pa.B - $pb.B) -gt 12) {
      $diff++
      $band = [Math]::Floor($y / 64) * 64
      if (-not $rows.ContainsKey($band)) { $rows[$band] = 0 }
      $rows[$band]++
    }
  }
}
$pct = [Math]::Round(100.0 * $diff / $total, 4)
Write-Output ("A={0}" -f (Split-Path $A -Leaf))
Write-Output ("B={0}" -f (Split-Path $B -Leaf))
Write-Output ("SIZE={0}x{1}  DIFF_PIXELS={2} / {3}  ({4}%)" -f $w, $h, $diff, $total, $pct)
Write-Output "--- 变化按 64px 行带分布 (y: 像素数) ---"
$rows.GetEnumerator() | Sort-Object Name | ForEach-Object { "  y={0,-5} {1}" -f $_.Name, $_.Value }
$ia.Dispose(); $ib.Dispose()
