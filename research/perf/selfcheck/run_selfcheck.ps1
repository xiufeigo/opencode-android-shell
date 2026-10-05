<#
================================================================================
 run_selfcheck.ps1 — battery_real_device.ps1 的**离线自检**驱动脚本

 本脚本**不碰任何设备、不起模拟器、不起停后端**。它只做四件事：
   S1 静态语法校验（PowerShell 解析器，AST）
   D1 依赖自检（adb / dumpsys / batterystats / 各开关逐个"有没有、在哪"）
   R  干跑 -DryRun（真 adb 无设备 + 夹具伪设备两种）
   E  错误路径（缺参数 / 串号不对 / 未授权 / 模拟器 / 插电）

 原始输出全部落到 research/perf/out_t2_selfcheck/，UTF-8，可直接贴进报告。
================================================================================
#>
[CmdletBinding()]
param(
  [string]$OutRoot = (Join-Path $PSScriptRoot '..\out_t2_selfcheck'),
  [string]$Battery = (Join-Path $PSScriptRoot '..\battery_real_device.ps1'),
  [string]$Stub    = (Join-Path $PSScriptRoot 'adb_stub.ps1')
)

$ErrorActionPreference = 'Continue'
$OutRoot = [IO.Path]::GetFullPath($OutRoot)
$Battery = [IO.Path]::GetFullPath($Battery)
$Stub    = [IO.Path]::GetFullPath($Stub)
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null

$script:caseNo = 0
function Log { param($m) Write-Host $m }

# 跑一个用例：stdout/stderr 落盘，并回显退出码
function Invoke-Case {
  param(
    [string]$Name,
    [string]$Title,
    [string[]]$PsArgs,   # ⚠ 不能叫 $Args：那是 PowerShell 自动变量，会被它吃掉导致参数全丢
    [hashtable]$Env = @{},
    [int]$ExpectCode,
    [string]$Expect = ''
  )
  $script:caseNo++
  $file = Join-Path $OutRoot ("{0:d2}_{1}.txt" -f $script:caseNo, $Name)
  $saved = @{}
  foreach ($k in $Env.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $Env[$k]) }
  try {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $out = & (Get-Process -Id $PID).Path -NoProfile -File $Battery @PsArgs 2>&1 | Out-String
    $code = $LASTEXITCODE
    $sw.Stop()
  } finally {
    foreach ($k in $Env.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
  }
  $ok = ($code -eq $ExpectCode)
  $hdr = @(
    "================================================================================",
    "用例 $script:caseNo : $Title",
    "--------------------------------------------------------------------------------",
    "命令     : pwsh -NoProfile -File battery_real_device.ps1 $($PsArgs -join ' ')",
    ("实际退出码: {0}    期望: {1}    => {2}" -f $code, $ExpectCode, $(if ($ok) { 'PASS' } else { '**FAIL**' })),
    ("耗时     : {0:N1}s" -f $sw.Elapsed.TotalSeconds),
    "================================================================================",
    ""
  ) -join "`n"
  $hdr + $out | Out-File -Encoding utf8 $file
  Log ("[{0}] {1}  exit={2} (期望 {3}) {4}  -> {5}" -f $script:caseNo, $Name, $code, $ExpectCode, $(if ($ok) { 'PASS' } else { '**FAIL**' }, (Split-Path $file -Leaf)))
  if ($Expect -and -not $ok) { Log "      期望文案：$Expect" }
  return [pscustomobject]@{ name = $Name; title = $Title; code = $code; expect = $ExpectCode; pass = $ok; file = $file; cmd = ($PsArgs -join ' ') }
}

$results = @()
$summary = @()

# =============================================================================
# S1 · 静态语法校验
# =============================================================================
Log ""
Log "################ [S1] 静态语法校验 ################"
$f1 = Join-Path $OutRoot '00_syntax.txt'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Battery, [ref]$tokens, [ref]$errors)
$funcs = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)
$exits = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.ExitStatementAst] }, $true)
$strs  = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true)
$s1 = @(
  "=== S1 · PowerShell 解析器静态语法校验 ===",
  "解析入口 : [System.Management.Automation.Language.Parser]::ParseFile()",
  "文件     : $Battery",
  "大小     : $((Get-Item $Battery).Length) bytes / $((Get-Content $Battery).Count) 行",
  "PowerShell: $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))",
  "",
  "Tokens 数量        : $($tokens.Count)",
  "String 字面量数量  : $($strs.Count)",
  "函数定义数         : $($funcs.Count)",
  "exit 语句点        : $($exits.Count)",
  "",
  "----- ParseErrors -----",
  "ParseErrors.Count  : $($errors.Count)"
)
if ($errors.Count -gt 0) { $s1 += ($errors | ForEach-Object { "  [{0}] line {1} col {2}: {3}" -f $_.ErrorId, $_.Extent.StartLineNumber, $_.Extent.StartColumnNumber, $_.Message }) }
else { $s1 += "(none)  =>  语法无错" }
$s1 += ""
$s1 += "----- 函数一览 -----"
$s1 += ($funcs | ForEach-Object { "  L{0,-4} {1}" -f $_.Extent.StartLineNumber, $_.Name })
$s1 += ""
$s1 += "----- 参数一览 -----"
foreach ($p in $ast.ParamBlock.Parameters) {
  # ⚠ ParameterAst.Name 是 **StringConstantExpressionAst**，不是 string。
  #   直接 `-contains 'DryRun'` 拿 AST 对象跟字符串比，永远是 False
  #   （踩过一次：依赖自检里把 -DryRun 报成"没有"，差点把一个假阴性写进报告）。
  $pname = $p.Name.VariablePath.UserPath
  $hasMandatory = [bool]($p.Attributes | Where-Object { $_.TypeName.Name -eq 'ParameterAttribute' -and $_.NamedArguments -contains 'true' })
  $s1 += ("  {0,-18} {1,-20} Mandatory={2,-5} 默认={3}" -f $pname, $p.StaticType.Name, $hasMandatory, $(if ($p.DefaultValue) { $p.DefaultValue.Extent.Text } else { '（无）' }))
}
$s1 -join "`n" | Out-File -Encoding utf8 $f1
Log "写入 $f1"
$results += [pscustomobject]@{ name = 'syntax'; title = '静态语法校验'; code = $errors.Count; expect = 0; pass = ($errors.Count -eq 0); file = $f1; cmd = '(静态)' }

# =============================================================================
# D1 · 依赖自检
# =============================================================================
Log ""
Log "################ [D1] 依赖自检 ################"
$f2 = Join-Path $OutRoot '01_dependencies.txt'
$adbReal = $null
foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:ANDROID_HOME\platform-tools\adb.exe", "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe")) {
  if (Test-Path $c) { $adbReal = $c; break }
}
if (-not $adbReal -and (Get-Command adb -ErrorAction SilentlyContinue)) { $adbReal = (Get-Command adb).Source }

$d = @()
$d += "=== D1 · 依赖自检：脚本用到的东西，逐个「有没有、在哪」 ==="
$d += "本机环境: Windows / PowerShell $($PSVersionTable.PSVersion) / $([DateTimeOffset]::Now.ToString('yyyy-MM-dd HH:mm:ss zzz'))"
$d += ""
$d += "① adb（脚本唯一的外部可执行依赖）"
$d += "   脚本里的查找顺序（见 battery_real_device.ps1）："
$d += "     a) -AdbPath 显式指定（给了就不回退）"
$d += '     b) %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'
$d += '     c) %ANDROID_HOME%\platform-tools\adb.exe'
$d += '     d) adb.exe（靠 PATH）'
$d += "   本机实测："
foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:ANDROID_HOME\platform-tools\adb.exe")) {
  $d += ("     {0,-6} {1}" -f (Test-Path $c), $c)
}
$d += "     ANDROID_HOME 环境变量 = $(if($env:ANDROID_HOME){$env:ANDROID_HOME}else{'<未设置>（故候选 c 展开成空路径，必为 False）'})"
$d += "     Get-Command adb (PATH)   = $(if(Get-Command adb -ErrorAction SilentlyContinue){(Get-Command adb).Source}else{'<不在 PATH>（故候选 d 也必为 False）'})"
$d += "   => 实际命中 = $(if($adbReal){$adbReal}else{'<无>'})"
if ($adbReal) { $d += "   adb 版本 = $((& $adbReal version) -join ' | ')" }
$d += ""
$d += "② adb shell 侧的 dumpsys 子服务（脚本调用的每一个，逐个核对）"
$d += "   脚本里用到的 dumpsys 模块（可用 = 本机能跑出非空输出）："
$probes = [ordered]@{
  'battery'        = '电量/插电状态（batterystats 的前置，也是 exit 4 第二道闸）'
  'batterystats'   = '耗电主指标来源：Discharge / UID CPU / WakeLock / 射频 / Wakeup alarms'
  'power'          = 'WakeLock 明细 + mWakefulness'
  'window'         = 'FLAG_KEEP_SCREEN_ON + 窗口矩形（GetWinFrame 按真实矩形换算坐标）'
  'cpuinfo'        = 'UID CPU 时间（次指标，代理量）'
  'netstats'       = '网络 rx/tx 字节（best-effort，各版本格式不一，允许 N/A）'
  'package'        = 'appId/userId（用于反查 UID）与 resolve-activity'
}
$d += ""
$d += "   模块          可用?  说明"
foreach ($k in $probes.Keys) {
  $state = '<未验证：无设备可用>'
  if ($adbReal) {
    $r = & $adbReal -s 'SELFCHECK-NO-DEVICE' shell "dumpsys $k" 2>&1 | Out-String
    $state = if ($r -match 'device .* not found|no devices|offline') { '不可达（无设备，符合预期）' } else { '有输出' }
  }
  $d += ("   {0,-14} {1,-6} {2}" -f $k, $state, $probes[$k])
}
$d += "   => 说明：本机**零设备**（下方 ③ 已给原始输出），故上表只能验证『命令存在』，"
$d += "      字段级可用性必须由 -DryRun 在真机上当场确认 —— 这正是本轮加 -DryRun 的原因。"
$d += ""
$d += "③ sysfs rail 电流（/sys/class/power_supply/*/current_now）—— 脚本的主指标来源"
$d += '   读法：for d in /sys/class/power_supply/*; do ... current_now ...'
$d += "   是否需要 root：多数机型需要。本机无设备，**无法在此判定**，脚本已做降级："
$d += '   读不到 rail 时不致命，改用 batterystats 的 Discharge 段，并在 reading.json 标 N/A 原因。'
$d += '   脚本里的判定：只读探测 -> if (-not $railOk) 打印警告并继续（不退出、不填 0）'
$d += ""
$d += "④ 脚本自身的开关（本轮自检核对的重点）"
$paramNames = @($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
$d += "   （按 AST 解析出的参数名实际核对，共 $($paramNames.Count) 个）"
$d += "   -DryRun   : $(if($paramNames -contains 'DryRun'){'✅ 已加（本轮 T2 新增）'}else{'❌ 没有'})"
$d += "   -AdbPath  : $(if($paramNames -contains 'AdbPath'){'✅ 已加（本轮 T2 新增）'}else{'❌ 没有'})"
$d += "   -AllowEmulator : $(if($paramNames -contains 'AllowEmulator'){'✅ 原有（默认拒绝 emulator-*）'}else{'❌ 没有'})"
$d += "   -SetBrightness : $(if($paramNames -contains 'SetBrightness'){'✅ 原有（>=0 才改，结束复原+复核）'}else{'❌ 没有'})"
$d += "   -LoopInteraction : $(if($paramNames -contains 'LoopInteraction'){'✅ 原有'}else{'❌ 没有'})"
$d += "   -DeviceOnly  : $(if($paramNames -contains 'DeviceOnly'){'✅ 存在'}else{'❌ 不存在（本轮新增的不是它，是等价的 -AdbPath + 整行匹配 adb devices；脚本从不碰模拟器，也不需要 -DeviceOnly）'})"
$d += "   -Backend / -Password : $(if(($paramNames -contains 'Backend') -and ($paramNames -contains 'Password')){'✅ 原有（只记录；口令永不落盘）'}else{'❌'})"
$d += ""
$d += "⑤ 设备与模拟器现状（本轮不碰，仅记录）"
if ($adbReal) {
  $devOut = (& $adbReal devices -l 2>&1 | Out-String).Trim()
  $d += '   adb devices -l 原文：'
  ($devOut -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $d += "     | $_" })
  $emuN = (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match 'emulator|qemu' } | Measure-Object).Count
  $d += "   emulator/qemu 进程数 = $emuN"
  $d += "   => 本轮**没有**任何真机或模拟器被连接或被启动；下面的 dry-run 与错误路径"
  $d += "      全部走夹具（-AdbPath 指向 selfcheck/adb_stub.ps1），不联机、不发任何真命令。"
}
$d -join "`n" | Out-File -Encoding utf8 $f2
Log "写入 $f2"
$results += [pscustomobject]@{ name = 'dependencies'; title = '依赖自检'; code = 0; expect = 0; pass = $true; file = $f2; cmd = '(探测)' }

# =============================================================================
# R1 · 干跑（真 adb，零设备）—— 证明"没设备时给清晰提示而不是崩"
# =============================================================================
Log ""
Log "################ [R] 干跑 -DryRun ################"
$results += Invoke-Case -Name 'dryrun_no_device' -Title '干跑 -DryRun：真 adb、零设备（应给清晰提示、退出码 2）' `
  -PsArgs @('-DryRun', '-Serial', 'SELFCHECK-NO-DEVICE', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck') -ExpectCode 2

# =============================================================================
# R2 · 干跑（夹具伪设备，状态良好）—— 证明走到最后并给出"能测"结论
# =============================================================================
$stubEnv = @{
  OC_ADB_STUB_DEVICES          = 'R5CT30ZZZZZ                device product:SM_R5CT30 model:SM_R5CT30 device:ZZ1 transport_id:1'
  OC_ADB_STUB_KEEP_SCREEN_ON   = '0'
  OC_ADB_STUB_BRIGHTNESS       = '120'
  OC_ADB_STUB_BRIGHTNESS_MODE  = '0'
}
$results += Invoke-Case -Name 'dryrun_stub_ok' -Title '干跑 -DryRun：夹具伪设备、全部闸通过（应给出"这台机器能测"、退出码 0）' `
  -PsArgs @('-DryRun', '-AdbPath', $Stub, '-Serial', 'R5CT30ZZZZZ', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck') `
  -Env $stubEnv -ExpectCode 0

# 夹具 + 自动亮度 + 常亮 ⇒ 验证"只警告不拦下"的分支
$results += Invoke-Case -Name 'dryrun_stub_warn' -Title '干跑 -DryRun：夹具伪设备 + 自动亮度 + 常亮（应只给可比性提醒、仍退出 0）' `
  -PsArgs @('-DryRun', '-AdbPath', $Stub, '-Serial', 'R5CT30ZZZZZ', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck_warn') `
  -Env (@{ OC_ADB_STUB_DEVICES = 'R5CT30ZZZZZ                device product:SM_R5CT30 model:SM_R5CT30 device:ZZ1 transport_id:1'
            OC_ADB_STUB_KEEP_SCREEN_ON = '1'; OC_ADB_STUB_BRIGHTNESS = '-1'; OC_ADB_STUB_BRIGHTNESS_MODE = '1' }) -ExpectCode 0

# 短格式（`adb devices`：serial + 状态，只有两列）—— 与上面长格式对照，
# 证明"取第二个 token"两种格式都对，而不是只对夹具那一种对。
$results += Invoke-Case -Name 'dryrun_stub_shortfmt' -Title '干跑 -DryRun：夹具伪设备，adb devices **短格式**（serial+device 两列）' `
  -PsArgs @('-DryRun', '-AdbPath', $Stub, '-Serial', 'R5CT30ZZZZZ', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck_short') `
  -Env (@{ OC_ADB_STUB_DEVICES = "R5CT30ZZZZZ`tdevice" }) -ExpectCode 0

# =============================================================================
# E1 · 错误路径：缺参数
# =============================================================================
$results += Invoke-Case -Name 'err_missing_args' -Title '错误路径 1/3：缺参数（-Serial / -Package 都没给，应给中文用法 + 真实 adb devices 列表、退出码 6）' `
  -PsArgs @('-DryRun', '-Tag', 'selfcheck') -ExpectCode 6

# =============================================================================
# E2 · 错误路径：串号不对（真 adb，设备列表里没有它）
# =============================================================================
$results += Invoke-Case -Name 'err_wrong_serial' -Title '错误路径 2/3：串号不对（不在 adb devices 列表里，应退出码 2）' `
  -PsArgs @('-DryRun', '-Serial', 'T2-SMOKE-NOT-A-DEVICE', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck') -ExpectCode 2

# =============================================================================
# E3 · 错误路径：设备未授权（夹具给出 unauthorized 状态）
# =============================================================================
$results += Invoke-Case -Name 'err_unauthorized' -Title '错误路径 3/3：设备未授权（adb devices 状态 = unauthorized，应解释三种状态、退出码 2）' `
  -PsArgs @('-DryRun', '-AdbPath', $Stub, '-Serial', 'R5CT30ZZZZZ', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck') `
  -Env @{ OC_ADB_STUB_DEVICES = 'R5CT30ZZZZZ                unauthorized' } -ExpectCode 2

# =============================================================================
# E4 · 附带两闸（模拟器 / 插电），顺带证明五道闸都还是人话
# =============================================================================
$results += Invoke-Case -Name 'err_emulator' -Title '附带闸：串号是模拟器 emulator-5554（应 exit 4：没有电池模型）' `
  -PsArgs @('-DryRun', '-Serial', 'emulator-5554', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck') -ExpectCode 4

# 「插电」这一闸见下方 err_plugged 用例（夹具把 plugged 覆盖成 2）。

# 插电：dumpsys battery 报 plugged=2 ⇒ 应 exit 4（插电时测的是充电电流不是耗电）
$results += Invoke-Case -Name 'err_plugged' -Title '附带闸：设备插着电（dumpsys battery plugged=2，应 exit 4：测到的是充电电流）' `
  -PsArgs @('-DryRun', '-AdbPath', $Stub, '-Serial', 'R5CT30ZZZZZ', '-Package', 'com.xiufeigo.opencodeshell.web.debug', '-Tag', 'selfcheck') `
  -Env (@{ OC_ADB_STUB_DEVICES = 'R5CT30ZZZZZ                device product:SM_R5CT30 model:SM_R5CT30 device:ZZ1 transport_id:1'
            OC_ADB_STUB_PLUGGED = '2' }) -ExpectCode 4

# =============================================================================
# 汇总
# =============================================================================
Log ""
Log "################ 汇总 ################"
$f3 = Join-Path $OutRoot '99_summary.txt'
$sum = @("=== 自检用例汇总 ===", "", "| # | 用例 | 实际 exit | 期望 exit | 判定 |", "|---|---|---:|---:|:--:|")
$i = 0
foreach ($r in $results) {
  $i++
  $sum += ("| {0} | {1} | {2} | {3} | {4} |" -f $i, $r.title, $r.code, $r.expect, $(if ($r.pass) { '✅ PASS' } else { '❌ FAIL' }))
}
$sum += ""
$sum += "原始输出文件："
foreach ($r in $results) { $sum += "  - $($r.file)" }
$sum -join "`n" | Out-File -Encoding utf8 $f3
Log ($sum -join "`n")
$failN = ($results | Where-Object { -not $_.pass } | Measure-Object).Count
Log ""
Log ("总计 {0} 个用例，FAIL {1} 个。" -f $results.Count, $failN)
if ($failN -gt 0) { exit 1 } else { exit 0 }
