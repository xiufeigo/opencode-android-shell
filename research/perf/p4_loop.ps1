# p4_loop.ps1 — P4 自愈式分块驱动。
#
# 背景：设备在测量期间被**外部**反复 kill（21:37:44 / 21:42:00 / 21:45:59，三次，
# 都不是本轮发起的 kill；boot log 无崩溃、端口释放、进程消失）。
# 所以这里对每个块做"活着就测、死了就重启再测"，块与块之间允许重来，
# 但**不为了好看削减任何交互次数 / 不跳冷启 / 不改参数**。
#
# 块顺序按"存活窗口越短越优先"排：输入延迟(~40s) 先落盘，帧时(~50s) 后落盘。
param([int]$MaxSeconds = 900)
$ErrorActionPreference='Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$perf = $PSScriptRoot
$dev='emulator-5830'; $port=5830; $avdName='OC_Perf_Test'
$t0 = Get-Date
$log = New-Object System.Collections.Generic.List[string]
function Note { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; $log.Add($s)
  ($log -join "`n") | Out-File -Encoding utf8 "$perf\out_p4\_loop_status.txt" }

function Ensure-Device {
  $st = (& $adb -s $dev shell getprop sys.boot_completed 2>$null | Out-String).Trim()
  if ($st -eq '1') { return $true }
  Note "device not ready (state='$st') -> relaunch $avdName on $port"
  $lg = "$perf\_emulator_boot_loop.log"
  $p = Start-Process -FilePath "$sdk\emulator\emulator.exe" `
    -ArgumentList "-avd $avdName -port $port -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect -no-metrics" `
    -RedirectStandardOutput $lg -RedirectStandardError "$lg.err" -PassThru -WindowStyle Hidden
  Note "EMU_PID=$($p.Id)"
  $sw=[System.Diagnostics.Stopwatch]::StartNew()
  $dl=(Get-Date).AddSeconds(150); $ok=$false
  while((Get-Date) -lt $dl){
    Start-Sleep -Seconds 3
    $t=(& $adb -s $dev shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if($t -eq '1'){ $ok=$true; $sw.Stop(); Note ("REBOOT_OK_WALL_SEC="+[math]::Round($sw.Elapsed.TotalSeconds,1)); break }
    if($p.HasExited){ Note "EXITED EARLY code=$($p.ExitCode)"; break }
  }
  if(-not $ok){ Note "REBOOT_FAILED"; return $false }
  & $adb -s $dev shell wm size 1080x2400 | Out-Null
  & $adb -s $dev shell wm density 420 | Out-Null
  & $adb -s $dev reverse tcp:4095 tcp:4095 | Out-Null
  Start-Sleep -Seconds 2
  Note ("geom: " + ((& $adb -s $dev shell wm size) -join ' | ') + " / " + ((& $adb -s $dev shell wm density) -join ' | '))
  return $true
}

function Ensure-Creds {
  & $adb -s $dev logcat -c | Out-Null
  & $adb -s $dev shell am start -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity | Out-Null
  Start-Sleep -Seconds 7
  $w = (& $adb -s $dev logcat -d -s OcWebHost:V) -join "`n"
  if ($w -match 'passwordPresent=true') { Note "web creds PRESENT" }
  elseif ($w -match 'no password in SecretStore') { Note "web creds MISSING" }
  & $adb -s $dev shell am force-stop com.xiufeigo.opencodeshell.web.debug
}

# 优先级：两侧输入延迟先（短块），再两侧帧时
$plan = @(
  @{ side='web';    what='input' },
  @{ side='native'; what='input' },
  @{ side='web';    what='ft'    },
  @{ side='native'; what='ft'    }
)
$done = @{}
foreach ($step in $plan) {
  if (((Get-Date) - $t0).TotalSeconds -gt $MaxSeconds) { Note "TIME BUDGET reached, stop"; break }
  $k = "$($step.side)/$($step.what)"
  $f = "$perf\out_p4\$($step.side)\$(if($step.what -eq 'input'){'inputlatency.json'}else{'gfxinfo_parsed.json'})"
  if ((Test-Path $f) -and ((Get-Item $f).Length -gt 10) -and -not (Select-String -Path $f -Pattern '"N/A"' -Quiet)) {
    Note "SKIP $k (already have valid result)"; $done[$k]=$true; continue
  }
  Note ">>> CHUNK $k"
  if (-not (Ensure-Device)) { Note "cannot bring device up for $k"; continue }
  Ensure-Creds
  & pwsh -NoProfile -File "$perf\chunk.ps1" -Side $step.side -What $step.what -Dev $dev
  if ($LASTEXITCODE -eq 0) { $done[$k]=$true; Note "OK $k" } else { Note "FAIL $k exit=$LASTEXITCODE" }
}
Note ("DONE. completed=" + (($done.Keys | Sort-Object) -join ','))
Note ("elapsed=" + [math]::Round(((Get-Date)-$t0).TotalSeconds,0) + "s")
