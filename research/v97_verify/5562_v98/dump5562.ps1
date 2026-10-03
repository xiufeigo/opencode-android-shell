# dump5562.ps1 — 真机复验辅助：uiautomator dump + 双引号/单引号兼容解析
# 用法: .\dump5562.ps1 -Tag before
param([Parameter(Mandatory=$true)][string]$Tag)

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5562"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"
$remote = "/sdcard/w5562_$Tag.xml"
$local  = Join-Path $dir "dump_$Tag.xml"

& $adb -s $dev shell uiautomator dump $remote | Out-Null
& $adb -s $dev pull $remote $local | Out-Null
& $adb -s $dev shell "rm -f $remote" | Out-Null

[xml]$x = Get-Content -LiteralPath $local -Raw -Encoding UTF8
$nodes = $x.SelectNodes("//node")

# 签名：只取可观测语义字段，剔除 bounds 抖动以外的噪音
$sig = foreach ($n in $nodes) {
  $t = $n.GetAttribute("text"); if (-not $t) { $t = $n.GetAttribute("content-desc") }
  "{0}|{1}|{2}" -f $n.GetAttribute("class"), $t, $n.GetAttribute("bounds")
}
$set = Join-Path $dir "sig_$Tag.txt"
$sig | Set-Content -LiteralPath $set -Encoding UTF8

Write-Output ("TAG={0}  NODES={1}  SIG={2}" -f $Tag, $nodes.Count, $set)

# 打印带 bounds 的可点/有文本节点（供运行期解析坐标）
foreach ($n in $nodes) {
  $t = $n.GetAttribute("text"); if (-not $t) { $t = $n.GetAttribute("content-desc") }
  $c = $n.GetAttribute("clickable")
  $b = $n.GetAttribute("bounds")
  if ($t -or $c -eq "true") { Write-Output ("  clickable={0,-5} {1}  text='{2}'" -f $c, $b, $t) }
}
