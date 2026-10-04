# p5_all.ps1 — P5 主跑：起设备（自建 port 5842）→ 播种/校验凭据 → 补齐缺的格子 + CDP 输入路径归因
# 只 -s emulator-5842；只 adb reverse；不起停后端；不 kill 别人设备。
param([int]$Port = 5842, [int]$MaxBoot = 200)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$Dev = "emulator-$Port"
$P = "$PSScriptRoot"
$Out = "$P\out_p5"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Log = Join-Path $Out "_all.log"
function A { & $Adb -s $Dev @args }
# 注意：必须用 Write-Host（不进 success stream）。
# 若用 Write-Output，本函数会把日志行写进调用者的返回值 =>
# 返回值变成非空数组 => `if (Func ...)` 恒为真 => 失败被误判成成功（P5 实测踩到）。
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Host $s; $s | Out-File -Append -Encoding utf8 $Log }

function StartEmu {
  Say "  launching OC_Perf_Test on port $Port"
  Start-Process -FilePath $Emu -ArgumentList '-avd','OC_Perf_Test','-port',"$Port",'-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect','-no-metrics' `
    -RedirectStandardOutput "$P\_p5_emu_$Port.log" -RedirectStandardError "$P\_p5_emu_$Port.err" -WindowStyle Hidden
}
function BootWait {
  param([int]$to = 200)
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $to) {
    $b = ((A shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
    if ($b -eq '1') { Say ("  boot_completed=1 in {0:N1}s" -f $sw.Elapsed.TotalSeconds); return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}
function Prep {
  Say "  prep: geometry 1080x2400@420 + adb reverse 4095"
  A shell wm size 1080x2400 | Out-Null; A shell wm density 420 | Out-Null; Start-Sleep -Seconds 2
  A reverse --remove-all 2>&1 | Out-Null; A reverse tcp:4095 tcp:4095 2>&1 | Out-Null
  $sz = (A shell wm size) -join ' '
  $dn = (A shell wm density) -join ' '
  Say "  wm: $sz  $dn"
  Say "  reverse: $((A reverse --list) -join ' | ')"
}
function CredCheck {
  $pkg = 'com.xiufeigo.opencodeshell.web.debug'
  A shell am force-stop $pkg | Out-Null; Start-Sleep -Seconds 1
  A logcat -c | Out-Null
  A shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
  Start-Sleep -Seconds 14
  $lc = (A logcat -d -s OcWebHost:V) -join "`n"
  $lc | Out-File -Encoding utf8 (Join-Path $Out "credcheck_$Port.log")
  $present = ($lc -match 'passwordPresent=true'); $asking = ($lc -match 'no password in SecretStore, asking')
  Say "  credential: passwordPresent=$present askingDialog=$asking"
  return ($present -and -not $asking)
}
function Chunk {
  param([string]$Side, [string]$What)
  Say "  -> CHUNK $Side/$What"
  $r = (pwsh -NoProfile -ExecutionPolicy Bypass -File "$P\p5_chunk.ps1" -Side $Side -What $What -Dev $Dev -OutRoot $Out 2>&1) -join "`n"
  $r | Out-File -Encoding utf8 (Join-Path $Out "chunk_${Side}_${What}.log")
  ($r -split "`r?`n") | Where-Object { $_ -match 'RESULT_|DEVICE GONE|EMPTY|CHUNK OK|wall =' } | ForEach-Object { Say "     $_" }
  $j = if ($What -eq 'ft') { Join-Path $Out "$Side\gfxinfo_parsed.json" } else { Join-Path $Out "$Side\inputlatency.json" }
  if (-not (Test-Path $j)) { return $false }
  if ((Get-Content $j -Raw) -match 'N/A') { Say "     !! N/A in $j => FAIL"; return $false }
  return $true
}
function EnsureDevice {
  $st = ((A get-state 2>$null) | Out-String).Trim()
  if ($st -eq 'device' -and (BootWait 20)) { return $true }
  if ($st -eq 'device') { Say "  emu kill our own $Dev"; A emu kill 2>&1 | Out-Null; Start-Sleep -Seconds 4 }
  StartEmu
  return (BootWait $MaxBoot)
}

# ---- 主流程：按优先级逐格补，每格最多 3 次 ----
$todo = @(
  @{ side='native'; what='input' },   # 同机输入延迟对照（核心）
  @{ side='web';    what='ft' },      # P4 缺的 web 帧时
  @{ side='native'; what='ft' }       # 同机原生帧时
)
foreach ($t in $todo) {
  $done = $false
  for ($try = 1; $try -le 3; $try++) {
    Say "=== [$($t.side)/$($t.what)] attempt $try ==="
    if (-not (EnsureDevice)) { Say "  BOOT FAIL"; continue }
    if (-not (Prep)) { continue }
    if (-not (CredCheck)) { Say "  CRED MISSING -> would measure dialog, SKIP"; break }
    if (Chunk $t.side $t.what) { $done = $true; break }
    Say "  attempt $try failed (device externally killed?) -> relaunch our own AVD"
  }
  if ($done) { Say "  == $($t.side)/$($t.what) OK ==" } else { Say "  == $($t.side)/$($t.what) MISSING after 3 attempts ==" }
}

# ---- CDP 输入路径归因（web 侧） ----
Say "=== CDP ATTRIBUTION (web) ==="
try {
  A shell am force-stop com.xiufeigo.opencodeshell.web.debug | Out-Null; Start-Sleep -Seconds 2
  A shell am start -n "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
  Start-Sleep -Seconds 20
  $inst = (pwsh -NoProfile -ExecutionPolicy Bypass -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile "$P\p5_attr_install.js" 2>&1) -join "`n"
  Say "  install -> $(($inst -split "`r?`n" | Select-Object -Last 1))"
  # 脚本化交互：与 chunk.ps1 逐字相同的窗口相对滑动手势
  $raw = (A shell "dumpsys window windows | grep -A30 'com.xiufeigo.opencodeshell.web.debug/'") -join "`n"
  if ($raw -match 'Frames:\s*parent=\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $L=[int]$Matches[1];$T=[int]$Matches[2];$Wd=([int]$Matches[3]-$L);$H=([int]$Matches[4]-$T)
  } else { $L=0;$T=0;$Wd=1080;$H=2400 }
  $cx=[int]($L+$Wd*0.5)
  for ($i=0;$i -lt 10;$i++) { A shell input swipe $cx ([int]($T+$H*0.80)) $cx ([int]($T+$H*0.20)) 250 | Out-Null; Start-Sleep -Milliseconds 250 }
  for ($i=0;$i -lt 10;$i++) { A shell input swipe $cx ([int]($T+$H*0.20)) $cx ([int]($T+$H*0.80)) 250 | Out-Null; Start-Sleep -Milliseconds 250 }
  Start-Sleep -Seconds 3
  $rd = (pwsh -NoProfile -ExecutionPolicy Bypass -File "$P\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile "$P\p5_attr_read.js" 2>&1) -join "`n"
  $rd | Out-File -Encoding utf8 (Join-Path $Out "attr_read.txt")
  Say "  attr read bytes=$(($rd).Length)"
  ($rd -split "`r?`n" | Select-Object -First 4) | ForEach-Object { Say "   $_" }
} catch { Say "  ATTR ERROR: $($_.Exception.Message)" }

Say "P5 ALL DONE $(Get-Date -Format 'HH:mm:ss')"
