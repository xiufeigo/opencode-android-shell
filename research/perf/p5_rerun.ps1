# p5_rerun.ps1 — 设备被外部 kill 后的自愈重测（只补 P5 缺的格子）
# 纪律：只 -s emulator-5840；只 adb reverse；不 kill 别人设备。
param(
  [string]$Dev = 'emulator-5840',
  [int]$MaxBoot = 200
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Out = "$PSScriptRoot\out_p5"
$Log = Join-Path $Out "_rerun.log"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
function A { & $Adb -s $Dev @args }
function Say { param($m) $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Write-Output $s; $s | Out-File -Append -Encoding utf8 $Log }
function BootWait {
  param([int]$to = 200)
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $to) {
    $b = ((A shell getprop sys.boot_completed 2>$null) | Out-String).Trim()
    if ($b -eq '1') { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}
function Prep {
  Say "  prep: geometry + reverse + credential check"
  A shell wm size 1080x2400 | Out-Null; A shell wm density 420 | Out-Null; Start-Sleep -Seconds 2
  A reverse --remove-all 2>&1 | Out-Null; A reverse tcp:4095 tcp:4095 2>&1 | Out-Null
  $pkg = 'com.xiufeigo.opencodeshell.web.debug'
  A shell am force-stop $pkg | Out-Null; Start-Sleep -Seconds 1
  A logcat -c | Out-Null
  A shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
  Start-Sleep -Seconds 12
  $lc = (A logcat -d -s OcWebHost:V) -join "`n"
  $lc | Out-File -Encoding utf8 (Join-Path $Out "credcheck.log")
  $present = ($lc -match 'passwordPresent=true')
  $asking  = ($lc -match 'no password in SecretStore, asking')
  Say "  credential: passwordPresent=$present askingDialog=$asking  (asking=True => 必须先播种，数字作废)"
  return ($present -and -not $asking)
}
function DoChunk {
  param([string]$Side, [string]$What)
  Say "  -> $Side/$What"
  $r = (pwsh -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\p5_chunk.ps1" -Side $Side -What $What -Dev $Dev -OutRoot $Out 2>&1) -join "`n"
  $r | Out-File -Encoding utf8 (Join-Path $Out "chunk_${Side}_${What}.log")
  ($r -split "`r?`n") | Where-Object { $_ -match 'RESULT_|DEVICE GONE|EMPTY|CHUNK OK|n=\d|wall =' } | ForEach-Object { Say "     $_" }
  $ok = ($r -match "CHUNK OK") -and ($r -notmatch 'DEVICE GONE') -and ($r -notmatch 'EMPTY')
  # 再验一次落盘文件是不是 N/A
  $j = if ($What -eq 'ft') { Join-Path $Out "$Side\gfxinfo_parsed.json" } else { Join-Path $Out "$Side\inputlatency.json" }
  if (Test-Path $j) { if ((Get-Content $j -Raw) -match '"N/A"|":null|": *null') { Say "     !! $j contains N/A/null => treat as FAIL"; $ok = $false } }
  return $ok
}

# 要补的格子（web/input 已成功）
$todo = @(
  @{ side='native'; what='input' },
  @{ side='web';    what='ft' },
  @{ side='native'; what='ft' }
)
foreach ($t in $todo) {
  $done = $false
  for ($try = 1; $try -le 3; $try++) {
    Say "=== [$($t.side)/$($t.what)] attempt $try $(Get-Date -Format 'HH:mm:ss') ==="
    if (-not (BootWait $MaxBoot)) { Say "  BOOT TIMEOUT"; break }
    if (-not (Prep)) { Say "  CREDENTIALS MISSING -> cannot measure (would measure dialog)"; break }
    if (DoChunk $t.side $t.what) { $done = $true; break }
    Say "  attempt $try failed (device likely externally killed); relaunching our own AVD"
    $alive = ((A get-state 2>$null) | Out-String).Trim()
    if ($alive -eq 'device') { A emu kill 2>&1 | Out-Null; Start-Sleep -Seconds 3 }
    Start-Process -FilePath "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
      -ArgumentList '-avd','OC_Perf_Test','-port','5840','-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','swiftshader_indirect','-no-metrics' `
      -RedirectStandardOutput "$PSScriptRoot\_p5_emu_boot_try.log" -RedirectStandardError "$PSScriptRoot\_p5_emu_boot_try.err" -WindowStyle Hidden
    Start-Sleep -Seconds 5
  }
  if ($done) { Say "  == $side/$($t.what) OK ==" } else { Say "  == $($t.side)/$($t.what) STILL MISSING after 3 attempts ==" }
}
Say "RERUN DONE $(Get-Date -Format 'HH:mm:ss')"
