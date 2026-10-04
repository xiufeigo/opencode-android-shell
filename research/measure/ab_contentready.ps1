# ab_contentready.ps1 — 「官方 UI 真正可见」时刻（首帧是否含 JS 初始化的判别器）
#
# 背景：`am start -W` 的 TotalTime 对 WebView 壳只量到 **WebView 首帧**（很可能是空白/极简帧），
#        **不含** JS 下载/执行、不含会话数据渲染。本脚本量第二件事：
#        「从 am start 到官方 UI 资源全部到位（最后一次 asset 拉取）」的墙钟下界。
#        对原生壳，Compose 首帧本身就是内容，所以 TotalTime 已等于「内容可见」。
#
# 做法：清 logcat -> force-stop -> am start（记 host 侧 T0）-> 等 N 秒 -> 倒 logcat，
#       取该 app 自己 logger 的最后一条有意义记录，算 host 侧时差。
#       （设备与 host 同时钟，所以 host T0 与 logcat 时间戳可比。）

$ErrorActionPreference='Continue'
$Adb   = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Dev   = if ($env:MEASURE_DEV){$env:MEASURE_DEV}else{'emulator-5636'}
$Pkg   = if ($env:MEASURE_PKG){$env:MEASURE_PKG}else{'com.xiufeigo.opencodeshell'}
$Act   = if ($env:MEASURE_ACT){$env:MEASURE_ACT}else{'.OcActivity'}
$Label = if ($env:MEASURE_LABEL){$env:MEASURE_LABEL}else{'native'}
$Wait  = if ($env:MEASURE_WAIT){[int]$env:MEASURE_WAIT}else{12}
# 该 app 在 logcat 里的 tag 片段
$Tag   = if ($env:MEASURE_TAG){$env:MEASURE_TAG}else{'OcWebHost'}

$OutDir = Join-Path $PSScriptRoot "out_ab\$Label"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function A { & $Adb -s $Dev @args }

A shell am force-stop $Pkg | Out-Null
Start-Sleep -Seconds 2
A shell logcat -c | Out-Null
# ⚠️ 设备时钟与 host 时钟可能差一个时区（本机实测差 8h），
#    所以 T0 必须用**设备自己的** `date` 取，否则算出的差值是负数。
$t0dev = (A shell "date +%H:%M:%S.%3N" | Select-Object -First 1).Trim()
$sw = [Diagnostics.Stopwatch]::StartNew()
$o = A shell am start -W -n "$Pkg/$Act"
$sw.Stop()
$tot = if (($o -join "`n") -match '(?m)^\s*TotalTime:\s*(-?\d+)') { [int]$Matches[1] } else { $null }
Write-Output ("[{0}] device T0 = {1}" -f (Get-Date -Format 'HH:mm:ss'), $t0dev)
Write-Output ("[{0}] am start -W TotalTime = {1} ms  (host wall {2:N0} ms)" -f (Get-Date -Format 'HH:mm:ss.fff'), $tot, $sw.Elapsed.TotalMilliseconds)

Start-Sleep -Seconds $Wait

$log = A shell logcat -d -v time
$log | Out-File -Encoding utf8 (Join-Path $OutDir 'contentready_logcat.txt')
$lines = $log | Where-Object { $_ -match [regex]::Escape($Tag) }
Write-Output ("[{0}] logcat lines matching '{1}' = {2}" -f (Get-Date -Format 'HH:mm:ss'), $Tag, $lines.Count)
if ($lines.Count -gt 0) {
  Write-Output "--- first 3 ---"; $lines | Select-Object -First 3 | Write-Output
  Write-Output "--- last 5 ---";  $lines | Select-Object -Last 5  | Write-Output
  # 解析 logcat 'MM-DD HH:MM:SS.mmm'，取最后一条
  $last = $lines | Select-Object -Last 1
  if ($last -match '^\s*(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})') {
    # 直接把 T0 的 HH:mm:ss.mmm 与 logcat 的 HH:mm:ss.mmm 做**纯时刻差**（同一天、同时区）
    $tsTxt = '{0}:{1}:{2}.{3}' -f $Matches[3],$Matches[4],$Matches[5],$Matches[6]
    $t0    = [datetime]::ParseExact($t0dev,'HH:mm:ss.fff',$null)
    $ts    = [datetime]::ParseExact($tsTxt ,'HH:mm:ss.fff',$null)
    $delta = ($ts - $t0).TotalMilliseconds
    Write-Output ("[{0}] LAST app log  = {1}  ({2})" -f (Get-Date -Format 'HH:mm:ss'), $tsTxt, $last.Trim())
    Write-Output ("[{0}] device T0 = {1}  =>  content-ready LOWER BOUND = {2:N0} ms" -f (Get-Date -Format 'HH:mm:ss'), $t0dev, $delta)
    [pscustomobject]@{
      label=$Label; pkg=$Pkg; amstart_TotalTime_ms=$tot
      last_app_log=$ts.ToString('HH:mm:ss.fff'); log_count=$lines.Count
      content_ready_lower_bound_ms=[math]::Round($delta,0)
    } | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'contentready.json')
  } else { Write-Output "WARN: could not parse logcat timestamp" }
} else {
  Write-Output "WARN: no logcat lines for tag $Tag"
  [pscustomobject]@{ label=$Label; pkg=$Pkg; amstart_TotalTime_ms=$tot; log_count=0; content_ready_lower_bound_ms=$null } |
    ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'contentready.json')
}
