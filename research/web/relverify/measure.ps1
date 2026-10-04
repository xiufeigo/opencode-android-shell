# measure.ps1 — release/debug 共用的冷启动首屏测量（方法与 research/perf 一致）
# 信号：Z.firstDraw（宿主）/ paint first-contentful-paint（FCP）/ tick nodes=（DOM 挂载）
#       / OcPerfJS doc-start（navStart）/ res-total（最后一个资源，仅资源完成，不是首屏）
param(
  [Parameter(Mandatory=$true)][string]$Tag,     # 报告里用的标签，如 'release' / 'debug'
  [string]$Dev = 'emulator-5860',
  [int]$SettleSec = 22
)
$ErrorActionPreference='Continue'
$SDK="$env:LOCALAPPDATA\Android\Sdk"; $A="$SDK\platform-tools\adb.exe"
$PKG='com.xiufeigo.opencodeshell.web.debug'; $ACT='com.xiufeigo.opencodeshell.web.WebHostActivity'
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\relverify"
$raw="$OUT\logcat_$Tag.txt"

& $A -s $Dev shell am force-stop $PKG | Out-Null
Start-Sleep -Seconds 2
& $A -s $Dev logcat -c | Out-Null
Write-Output "[$Tag] cold start at $(Get-Date -Format 'HH:mm:ss.fff')"
$amOut = & $A -s $Dev shell am start -W -n "$PKG/$ACT" 2>&1
$amOut | Tee-Object -FilePath "$OUT\amstart_$Tag.txt"
Start-Sleep -Seconds $SettleSec

# 焦点证据
$focus = (& $A -s $Dev shell dumpsys window | Select-String "mCurrentFocus" | Select-Object -First 1)
Write-Output "[$Tag] FOCUS: $($focus -join '')"

# 全量 logcat 落盘
(& $A -s $Dev logcat -d -v threadtime) | Out-File -Encoding utf8 $raw

function Grab { param($pat) (& $A -s $Dev logcat -d -s OcWebHost:V OcPerf:V OcPerfJS:V 2>&1 | Select-String $pat | ForEach-Object { $_.Line }) }

Write-Output ""
Write-Output "----- [$Tag] 关键读数 -----"
Write-Output "-- launch banner --"
Grab 'home=http' | ForEach-Object { Write-Output "  $_" }
Write-Output "-- Z.firstDraw (宿主首帧) --"
$fd = Grab 'Z\.firstDraw'; $fd | ForEach-Object { Write-Output "  $_" }
Write-Output "-- FCP (first-contentful-paint) --"
$fcp = Grab 'first-contentful-paint'; $fcp | ForEach-Object { Write-Output "  $_" }
if(-not $fcp){ Write-Output "  (NONE)" }
Write-Output "-- first-paint --"
Grab 'paint first-paint' | ForEach-Object { Write-Output "  $_" }
Write-Output "-- DOMContentLoaded --"
$dcl = Grab 'DCL|domcontentloaded|dom-ready'; if($dcl){ $dcl | ForEach-Object { Write-Output "  $_" } } else { Write-Output "  (no explicit DCL line; see tick nodes below)" }
Write-Output "-- DOM 挂载 (tick nodes=) --"
$tick = Grab 'tick nodes='; if($tick){ $tick | Select-Object -First 6 | ForEach-Object { Write-Output "  $_" } } else { Write-Output "  (NONE)" }
Write-Output "-- doc-start / navStart --"
Grab 'doc-start' | ForEach-Object { Write-Output "  $_" }
Write-Output "-- 最后一个 res-total (仅资源完成，**不是首屏**) --"
Grab 'res-total' | Select-Object -Last 1 | ForEach-Object { Write-Output "  $_" }
Write-Output "-- G1 / preflight / diagnostic (应为空 = 未触发) --"
$g1 = Grab 'G1 |diagnostic|preflight|watchdog'; if($g1){ $g1 | ForEach-Object { Write-Output "  $_" } } else { Write-Output "  (NONE — 预检未误触发/该包无此代码，见报告)" }
Write-Output "-- WebView 版本 (dumpsys) --"
(& $A -s $Dev shell dumpsys webviewupdate | Select-String "Current WebView package" | Select-Object -First 1) | ForEach-Object { Write-Output "  $($_.ToString().Trim())" }

# UI 文本（官方 UI 是否渲染）
& $A -s $Dev shell uiautomator dump /sdcard/_ui_$Tag.xml | Out-Null
$xml = (& $A -s $Dev shell cat /sdcard/_ui_$Tag.xml) -join ''
$xml | Out-File -Encoding utf8 "$OUT\_ui_$Tag.xml"
$texts = [regex]::Matches($xml,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne "" }
Write-Output ""
Write-Output "----- [$Tag] 官方 UI 渲染 (uiautomator a11y 可见文本) -----"
Write-Output "  非空 text 节点数 = $($texts.Count)"
$texts | Select-Object -First 14 | ForEach-Object { Write-Output "  | $_" }
# 兜底/诊断页特征
Write-Output "  兜底页特征命中: retryBtn=$($xml.Contains('retryBtn')) d-what=$($xml.Contains('d-what')) '加载失败'=$($xml.Contains('加载失败'))"
