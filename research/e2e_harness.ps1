# Route C 端到端验证脚本（Lead 用）
#
# 用法示例：
#   pwsh -File research\e2e_harness.ps1 -Serial emulator-5554 -Action install
#   pwsh -File research\e2e_harness.ps1 -Serial emulator-5554 -Action send -Prompt "Reply with the single word HARNESS_OK"
#   pwsh -File research\e2e_harness.ps1 -Serial emulator-5556 -Action narrow   # 折叠屏缩到 443dp 验证移动分支
#   pwsh -File research\e2e_harness.ps1 -Serial emulator-5556 -Action reset
#
# 约定：每台模拟器的 reverse 隧道在 install 之后都会失效，所以 install 动作里会重加。

param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)]
    [ValidateSet('install', 'send', 'shot', 'narrow', 'reset', 'state')]
    [string]$Action,
    [string]$Prompt = 'Reply with the single word E2E_OK',
    [string]$Name = 'shot',
    [string]$Session = 'ses_f41f7138cffeB43nHxQAyNE1uf',
    [string]$Base = 'http://127.0.0.1:4096'
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$repo = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'

function Get-UiXml([string]$serial) {
    & $adb -s $serial shell uiautomator dump /sdcard/e2e_ui.xml | Out-Null
    & $adb -s $serial pull /sdcard/e2e_ui.xml (Join-Path $env:TEMP 'e2e_ui.xml') | Out-Null
    return Get-Content (Join-Path $env:TEMP 'e2e_ui.xml') -Raw
}

function Find-NodeBounds([string]$xml, [string]$text) {
    $idx = $xml.IndexOf('text="' + $text + '"')
    if ($idx -lt 0) { return $null }
    $start = [Math]::Max(0, $idx - 400)
    $seg = $xml.Substring($start, [Math]::Min(600, $xml.Length - $start))
    if ($seg -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        return @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    }
    return $null
}

switch ($Action) {
    'install' {
        & $adb -s $Serial install -r $apk | Select-Object -Last 1
        & $adb -s $Serial reverse tcp:4096 tcp:4096 | Out-Null
        & $adb -s $Serial shell am force-stop com.xiufeigo.opencodeshell
        & $adb -s $Serial shell monkey -p com.xiufeigo.opencodeshell 1 | Out-Null
        Start-Sleep -Seconds 8
        Write-Output "installed + relaunched on $Serial (reverse re-added)"
    }
    'send' {
        $body = @{ parts = @(@{ type = 'text'; text = $Prompt }) } | ConvertTo-Json -Depth 5 -Compress
        $code = (Invoke-WebRequest -Uri "$Base/session/$Session/prompt_async" -Method Post -ContentType 'application/json' -Body $body -UseBasicParsing).StatusCode
        Write-Output "prompt_async -> $code ; waiting for the turn to settle"
        Start-Sleep -Seconds 20
        & $adb -s $Serial shell screencap -p /sdcard/$Name.png
        & $adb -s $Serial pull /sdcard/$Name.png (Join-Path $repo "$Name.png") | Out-Null
        Write-Output "screenshot -> $Name.png"
    }
    'shot' {
        & $adb -s $Serial shell screencap -p /sdcard/$Name.png
        & $adb -s $Serial pull /sdcard/$Name.png (Join-Path $repo "$Name.png") | Out-Null
        Write-Output "screenshot -> $Name.png"
    }
    'narrow' {
        # 851.7dp 的折叠内屏按上游 768dp 语义属于桌面分支；缩到 1080 宽 → ~443dp 才是移动分支。
        & $adb -s $Serial shell wm size 1080x2152 | Out-Null
        Write-Output "wm size -> 1080x2152 (~443dp, mobile branch)"
    }
    'reset' {
        & $adb -s $Serial shell wm size reset | Out-Null
        Write-Output "wm size reset"
    }
    'state' {
        Write-Output "--- process ---"
        & $adb -s $Serial shell pidof com.xiufeigo.opencodeshell
        Write-Output "--- reverse ---"
        & $adb -s $Serial reverse --list
        Write-Output "--- wm size ---"
        & $adb -s $Serial shell wm size
        Write-Output "--- pending permissions/questions ---"
        (Invoke-WebRequest -Uri "$Base/permission" -UseBasicParsing).Content
        (Invoke-WebRequest -Uri "$Base/question" -UseBasicParsing).Content
    }
}
