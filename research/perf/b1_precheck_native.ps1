# b1_precheck_native.ps1 — L2 原生壳的**修正版**前置门禁
#
# 【为什么必须修正，而不是绕过】
# p5_precheck.ps1 的原生分支判据 A/B 是**web 专用**的（要求 logcat 出现 passwordPresent=true 与
# OcPerfJS），而原生壳**根本不打印这些 tag**（走的是另一条代码路径）⇒ 判据 A/B 对原生**按构造必然失败**。
# 判据 C 里 "EditText 数量必须为 0" 也**不成立**：原生壳的正常 UI 里就有一个 composer 输入框。
# 另有一个真实污染源：web 壳进程在后台存活时会持续打 OcPerfJS / /api/info 轮询，
# 把这些行混进原生侧 logcat（B 判据被污染成 8 次）。
#   实测：原生前台时 uiautomator = nodes=63 / EditText=1 / password="true"=0，
#   可见文本是真实会话（"Todo3 code review (@oracle subagent)"）与 composer 占位
#   "Ask anything, / for commands, @ for context..." ⇒ 是真 UI，不是对话框。
#
# 【修正后的门禁：只加严、不放松 —— 用"正向识别"替代"计数为 0"】
#   A) 前台确实是 OcActivity（不是别的壳，也不是 web 壳）
#   B) uiautomator 里 password="true" 的节点数 = 0        （真·无口令对话框）
#   C) 不存在"保存并连接"按钮（口令对话框签名）
#   D) **正向识别**：可见文本含 composer 占位 "Ask anything"，且节点数 > 20 ⇒ 确认是官方原生 composer
#   E) web 壳进程**必须已停**（否则 logcat 被污染，任何基于 logcat 的判据都不可信）
# 任一条不过 => 该层数字作废。
param(
  [string]$Dev = 'emulator-5854',
  [string]$OutRoot = "$PSScriptRoot\out_b1"
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$New = "$OutRoot\L2"
New-Item -ItemType Directory -Force -Path $New | Out-Null
$Pkg='com.xiufeigo.opencodeshell'; $Act='com.xiufeigo.opencodeshell.OcActivity'
$WebPkg='com.xiufeigo.opencodeshell.web.debug'
function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

Say "=== PRECHECK [native 修正版] ==="
# E) 先确保 web 壳不在线（否则 logcat 污染）
A shell am force-stop $WebPkg | Out-Null
Start-Sleep -Seconds 2
$webPid = ((A shell pidof $WebPkg 2>$null) | Out-String).Trim()
A logcat -c | Out-Null
Say "  web shell pid after force-stop = '$webPid' (应为空)"
$e_webStopped = [string]::IsNullOrWhiteSpace($webPid)

A shell am force-stop $Pkg | Out-Null; Start-Sleep -Seconds 2
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 22

# A) 前台 activity
$resumed = ((A shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1) | Out-String).Trim()
$a_ocActivity = ($resumed -match 'com\.xiufeigo\.opencodeshell/\.OcActivity')
Say "  topResumed = $resumed"

# B/C/D) uiautomator
A shell uiautomator dump /sdcard/_b1ui.xml | Out-Null
$xml = ((A shell cat /sdcard/_b1ui.xml) | Out-String)
$xml | Out-File -Encoding utf8 "$New\native_ui.xml"
$passNodes  = ([regex]::Matches($xml,'password="true"')).Count
$editTexts  = ([regex]::Matches($xml,'android\.widget\.EditText')).Count
$nodes      = ([regex]::Matches($xml,'<node ')).Count
$dialogBtn  = [regex]::IsMatch($xml,'保存并连接')
# 正向识别：composer 占位文案（官方原生壳的输入框提示）
$composerHit = [regex]::IsMatch($xml,'Ask anything')
$b_noPass = ($passNodes -eq 0)
$c_noDialogBtn = (-not $dialogBtn)
$d_officialComposer = ($composerHit -and $nodes -gt 20)
Say "  nodes=$nodes EditText=$editTexts password=true:$passNodes saveBtn=$dialogBtn composerHint=$composerHit"

# 附带：logcat 里**不应**有口令索要痕迹
$lc = ((A logcat -d) -join "`n")
$lc | Out-File -Encoding utf8 "$New\native_logcat.txt"
$f_asking = ($lc -match 'no password in SecretStore, asking')

$verdict = ($e_webStopped -and $a_ocActivity -and $b_noPass -and $c_noDialogBtn -and $d_officialComposer -and (-not $f_asking))
$res = [ordered]@{
  side='native'; serial=$Dev; gate='b1_precheck_native (corrected: positively identifies official native composer)'
  E_web_shell_stopped=$e_webStopped; web_shell_pid=$webPid
  A_topResumedActivity=$resumed; A_is_OcActivity=$a_ocActivity
  B_password_nodes=$passNodes; B_no_password_dialog=$b_noPass
  C_save_and_connect_button=$dialogBtn; C_no_dialog_button=$c_noDialogBtn
  D_composer_hint_hit=$composerHit; D_dom_nodes=$nodes; D_is_official_composer=$d_officialComposer
  F_no_asking_dialog=$(-not $f_asking)
  VERDICT_OFFICIAL_UI=$verdict
}
$res | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 "$New\precheck_native.json"
$res | ConvertTo-Json -Depth 5 | Write-Host
if ($verdict) { Say "PRECHECK PASS (native): official native composer confirmed" } else { Say "PRECHECK FAIL: numbers VOID"; exit 1 }
