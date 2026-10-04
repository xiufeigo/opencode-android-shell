# p5_snap_poller.ps1 — 独立的 gfxinfo 轮询进程（抗外部 kill）
# 用独立进程而非 Start-Job：P5 实测 Start-Job 版本**一个快照都没落盘**（后台 runspace 没跑起来）。
# 参数：-Dev -Pkg -Out -Every -MaxSec
param(
  [string]$Dev = 'emulator-5842',
  [string]$Pkg = 'com.xiufeigo.opencodeshell.web.debug',
  [string]$Out = "$PSScriptRoot\out_p5\web",
  [int]$Every = 2,
  [int]$MaxSec = 180
)
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$i = 0
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $MaxSec) {
  $g = & $Adb -s $Dev shell dumpsys gfxinfo $Pkg 2>$null
  $txt = ($g -join "`n")
  if ($txt -match 'Total frames rendered:\s*(\d+)') {
    $n = $Matches[1]
    $i++
    $path = Join-Path $Out ("_snap_{0:D3}_frames_{1}.txt" -f $i, $n)
    Set-Content -Path $path -Value $txt -Encoding utf8
  } else {
    # 设备可能已被外部 kill —— 落一条记录就退出
    Add-Content -Path (Join-Path $Out "_poller_died.txt") -Value "[{0}] gfxinfo 无 Total frames 字段（设备可能已死）" -f (Get-Date -Format 'HH:mm:ss')
    break
  }
  Start-Sleep -Seconds $Every
}
Add-Content -Path (Join-Path $Out "_poller_end.txt") -Value "[{0}] poller exit, snapshots=$i" -f (Get-Date -Format 'HH:mm:ss')
