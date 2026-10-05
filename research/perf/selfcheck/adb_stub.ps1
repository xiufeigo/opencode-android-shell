<#
================================================================================
 adb_stub.ps1 — **自检夹具**，不是测量工具，也不是设备模拟器

 它是什么：一个"假 adb"，只把 battery_real_device.ps1 的**前置检查分支**喂上
          可控输入，从而在不插手机、不起模拟器的前提下，验证错误提示是人话。

 它不是什么：
   * 它**不产生任何耗电数字**。全仓库不存在由本夹具生成的 mAh；它的输出里连
     "Discharge / rail 积分"这类字段都没有结构可被解析成读数。
   * 它**不是** AVD、不占端口、不连网络、不下发任何真实命令。
   * 它**不能**用来"造一份报告"。DRYRUN.md / reading.json 只在真机跑出来的才作数。

 用法（唯一）：
   $env:OC_ADB_STUB_DEVICES = "R5CT30ZZZZZ      device product:x model:SM_R5CT30"
   $env:OC_ADB_STUB_KEEP_SCREEN_ON = "0"
   pwsh -NoProfile -File research\perf\battery_real_device.ps1 -DryRun `
        -AdbPath research\perf\selfcheck\adb_stub.ps1 -Serial R5CT30ZZZZZ ...

 环境变量（都由自检脚本在调用前设好）：
   OC_ADB_STUB_DEVICES       `adb devices` 表格**数据行**部分（可为空 ⇒ 模拟"没插设备"）
   OC_ADB_STUB_KEEP_SCREEN_ON  "1" 时 dumpsys window 输出里带 FLAG_KEEP_SCREEN_ON
   OC_ADB_STUB_BRIGHTNESS / _BRIGHTNESS_MODE / _WAKEFULNESS   可覆盖只读探测值
================================================================================
#>
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)]$Rest)

$all = @($Rest) + @($args)
$j = ($all -join ' ')

# 默认值：夹具必须能被单独调用也不至于返回半空（**全部是无测量含义的常量**）
if (-not $env:OC_ADB_STUB_LEVEL)         { $env:OC_ADB_STUB_LEVEL = '80' }
if (-not $env:OC_ADB_STUB_TEMP)          { $env:OC_ADB_STUB_TEMP = '300' }
if (-not $env:OC_ADB_STUB_HEALTH)        { $env:OC_ADB_STUB_HEALTH = '2' }
if (-not $env:OC_ADB_STUB_BRIGHTNESS)    { $env:OC_ADB_STUB_BRIGHTNESS = '120' }
if (-not $env:OC_ADB_STUB_BRIGHTNESS_MODE) { $env:OC_ADB_STUB_BRIGHTNESS_MODE = '0' }
if ($null -eq $env:OC_ADB_STUB_PLUGGED)     { $env:OC_ADB_STUB_PLUGGED = '0' }
if (-not $env:OC_ADB_STUB_WAKEFULNESS)   { $env:OC_ADB_STUB_WAKEFULNESS = 'Awake' }
if ($null -eq $env:OC_ADB_STUB_KEEP_SCREEN_ON) { $env:OC_ADB_STUB_KEEP_SCREEN_ON = '0' }

# --- 设备列表：唯一真正被脚本用来判分支的输入 ---------------------------------
if ($j -match '\bdevices\b') {
  Write-Output 'List of devices attached'
  $rows = $env:OC_ADB_STUB_DEVICES
  if (-not [string]::IsNullOrWhiteSpace($rows)) { Write-Output $rows }
  exit 0
}

# --- 只读探测：全部是常量，返回值本身没有测量含义 -----------------------------
switch -Regex ($j) {
  'pm list packages' {
    @(
      'package:com.android.settings'
      'package:com.xiufeigo.opencodeshell'
      'package:com.xiufeigo.opencodeshell.web.debug'
    ) | Write-Output
    exit 0
  }
  'resolve-activity' {
    # 真实 `cmd package resolve-activity --brief <pkg>` 的输出形态
    Write-Output 'priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false'
    Write-Output 'com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity'
    exit 0
  }
  'dumpsys batterystats' {
    # 必须排在 'dumpsys battery' **前面**：switch -Regex 是子串匹配，
    # "dumpsys batterystats" 里含 "dumpsys battery" ⇒ 顺序反了会走错分支
    # （踩过一次：`currently on battery` 取到空值，误以为脚本的闸有问题）。
    Write-Output 'Battery Stats (since last unplug):'
    Write-Output '  currently on battery: true'
    Write-Output '  on battery: false'
    Write-Output '  (selfcheck fixture: no counters, no readings)'
    exit 0
  }
  'dumpsys battery' {
    Write-Output 'Current Battery Service state:'
    Write-Output '  AC powered: false'
    Write-Output '  USB powered: false'
    Write-Output '  Wireless powered: false'
    Write-Output '  status: 2'
    Write-Output "  health: $env:OC_ADB_STUB_HEALTH"
    Write-Output "  level: $env:OC_ADB_STUB_LEVEL"
    Write-Output "  plugged: $env:OC_ADB_STUB_PLUGGED"
    Write-Output "  temperature: $env:OC_ADB_STUB_TEMP"
    exit 0
  }
  'power_supply' {
    # 形状照抄 sysfs，只为让 `current_now=` 那一道闸能过
    Write-Output 'battery/current_now=0'
    Write-Output 'battery/voltage_now=0'
    Write-Output 'battery/capacity=0'
    Write-Output '(selfcheck fixture: 固定 0，不参与任何积分)'
    exit 0
  }
  'getprop' {
    if ($j -match 'ro\.product\.model') { Write-Output 'SELFCHECK-STUB' }
    elseif ($j -match 'ro\.product\.brand') { Write-Output 'selfcheck' }
    elseif ($j -match 'ro\.build\.version\.release') { Write-Output '0' }
    elseif ($j -match 'ro\.build\.version\.sdk') { Write-Output '0' }
    elseif ($j -match 'ro\.build\.fingerprint') { Write-Output 'selfcheck/stub' }
    exit 0
  }
  'screen_brightness_mode' { Write-Output "$env:OC_ADB_STUB_BRIGHTNESS_MODE"; exit 0 }
  'screen_brightness'      { Write-Output "$env:OC_ADB_STUB_BRIGHTNESS"; exit 0 }
  'dumpsys power'          { Write-Output "  mWakefulness=$env:OC_ADB_STUB_WAKEFULNESS"; exit 0 }
  'dumpsys window' {
    Write-Output '  WINDOW MANAGER POLICY STATE (selfcheck fixture)'
    if ($env:OC_ADB_STUB_KEEP_SCREEN_ON -eq '1') { Write-Output '  mAttrs=FLAG_KEEP_SCREEN_ON' }
    exit 0
  }
  'date'       { Write-Output '0'; exit 0 }
  'uptime'     { Write-Output '0.0 0.0'; exit 0 }
  default      { exit 0 }   # 夹具对未知命令一律沉默（与"没权限/没输出"同形）
}
