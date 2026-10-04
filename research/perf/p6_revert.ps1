# p6_revert.ps1 — P6 反向验证：逐条撤回 P6 改动，确认对应绊线**真红**
#
# 纪律：不删断言、不放宽断言、不改用例数。每个 revert 都是**最小可编译**改动，
#      跑完立刻还原，最后再跑一次确认恢复全绿。
$ErrorActionPreference='Continue'
$Root = 'C:\sensorsdata\main\program\opencode-android-shell'
Set-Location $Root
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$Out = "$Root\research\perf\out_p6\revert"
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$ActF = "$Root\webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt"
$PolF = "$Root\webapp\src\main\java\com\xiufeigo\opencodeshell\web\host\PowerPolicy.kt"
$bak  = "$Root\research\perf\out_p6\revert\backup"
New-Item -ItemType Directory -Force -Path $bak | Out-Null
Copy-Item $ActF "$bak\WebHostActivity.kt" -Force
Copy-Item $PolF "$bak\PowerPolicy.kt" -Force
Write-Host "backup -> $bak"

function Restore {
  Copy-Item "$bak\WebHostActivity.kt" $ActF -Force
  Copy-Item "$bak\PowerPolicy.kt" $PolF -Force
}

function RunTests {
  param([string]$Name)
  $log = "$Out\$Name.log"
  & .\gradlew.bat :webapp:testDebugUnitTest --console=plain 2>&1 |
    Select-String -Pattern 'FAILED|tests completed|BUILD (SUCCESSFUL|FAILED)|^\s+\w+ > ' |
    ForEach-Object { $_.Line.Trim() } | Tee-Object -FilePath $log
  Write-Host "--- [$Name] exit=$LASTEXITCODE (log: $log) ---"
}

# ===== R1：撤回「onPause 掐断流式连接」接线 =====
Write-Host "`n########## R1: revert onPause -> suspendStreaming (P6 刀 2 接线) ##########"
$t = [IO.File]::ReadAllText($ActF, [Text.UTF8Encoding]::new($false))
if ($t -notmatch [regex]::Escape('proxy?.suspendStreaming() ?: 0')) { Write-Host "!! R1 anchor not found"; exit 9 }
$t2 = $t.Replace('proxy?.suspendStreaming() ?: 0', '0')
[IO.File]::WriteAllText($ActF, $t2, [Text.UTF8Encoding]::new($false))
RunTests 'R1_onPause_suspendStreaming_removed'
Restore

# ===== R2：撤回「回合结束就清常亮」=====
Write-Host "`n########## R2: revert shouldKeepScreenOn -> ignore roundActive ##########"
$t = [IO.File]::ReadAllText($PolF, [Text.UTF8Encoding]::new($false))
if ($t -notmatch [regex]::Escape('foreground && roundActive')) { Write-Host "!! R2 anchor not found"; exit 9 }
$t2 = $t.Replace('foreground && roundActive', 'foreground')
[IO.File]::WriteAllText($PolF, $t2, [Text.UTF8Encoding]::new($false))
RunTests 'R2_shouldKeepScreenOn_ignores_roundEnd'
Restore

# ===== R3：撤回「离开前台就清常亮」=====
Write-Host "`n########## R3: revert shouldKeepScreenOn -> ignore foreground ##########"
$t = [IO.File]::ReadAllText($PolF, [Text.UTF8Encoding]::new($false))
$t2 = $t.Replace('foreground && roundActive', 'roundActive')
[IO.File]::WriteAllText($PolF, $t2, [Text.UTF8Encoding]::new($false))
RunTests 'R3_shouldKeepScreenOn_ignores_foreground'
Restore

# ===== R4：撤回「后台拒绝流式请求」（只关不拒的拉锯回归）=====
Write-Host "`n########## R4: revert allowStreaming -> always true (只关不拒) ##########"
$t = [IO.File]::ReadAllText($PolF, [Text.UTF8Encoding]::new($false))
if ($t -notmatch [regex]::Escape('fun allowStreaming(foreground: Boolean): Boolean = foreground')) { Write-Host "!! R4 anchor not found"; exit 9 }
$t2 = $t.Replace('fun allowStreaming(foreground: Boolean): Boolean = foreground',
                 'fun allowStreaming(foreground: Boolean): Boolean = true')
[IO.File]::WriteAllText($PolF, $t2, [Text.UTF8Encoding]::new($false))
RunTests 'R4_allowStreaming_always_true'
Restore

# ===== 收尾：确认还原后全绿 =====
Write-Host "`n########## FINAL: restored tree must be green ##########"
RunTests 'FINAL_restored'
Write-Host "REVERT-VERIFY DONE"
