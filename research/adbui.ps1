# adbui.ps1 — 真机 UI 操作小工具（Lead 验收用）
#
# 为什么单独一个脚本：验收阶段要在两台模拟器上反复「找节点 → 点 → dump → 截图」，
# 内联 adb 脚本每轮都要重写坐标解析逻辑，既慢又容易因为坐标漂移而误判（本会话已经
# 出现过一次"点了没反应"其实是 dump 里根本没有该节点）。这里把节点查找/点击/汇总
# 固化下来，验收时只写一行。
#
# 用法：
#   pwsh -File research\adbui.ps1 -Action size                      # 机型/分辨率/密度
#   pwsh -File research\adbui.ps1 -Action summary                   # 所有 text（无 bounds，用于快速比对）
#   pwsh -File research\adbui.ps1 -Action texts                     # text + content-desc，含 bounds
#   pwsh -File research\adbui.ps1 -Action tap  -Text "撤销"          # 按 text 精确匹配 → 点中心
#   pwsh -File research\adbui.ps1 -Action desc -Text "更多选项" -Tap  # 按 content-desc 匹配 → 点中心
#   pwsh -File research\adbui.ps1 -Action shot -Name foo            # 截图到 research\final_shots\foo.png
#
# 约定：找不到节点时打印 `NOT FOUND ...` 并以退出码 2 结束 —— 明确区分"没这个节点"
# 与"点了没效果"，避免把前者误判成后者（本会话踩过）。
#
# 踩坑记录（不要再抽辅助函数）：坐标解析一律内联。函数里 `return @(a,b)` 会被
# PowerShell 拆包（调用侧索引报"不能为空数组建立索引"）；改成 `return , @(a,b)` 又变成
# 嵌套数组（输出 `tapped ,` 空参数）；把 `$nodes[0].bounds` 作为实参传给函数也会绑成空串。

param(
    [ValidateSet('texts', 'summary', 'tap', 'desc', 'shot', 'size', 'swipe', 'key')]
    [string]$Action = 'summary',
    [string]$Serial = 'emulator-5556',
    [string]$Text = '',
    [string]$Name = 'shot',
    [string]$From = '',
    [string]$To = '',
    [int]$Duration = 400,
    [int]$Code = 0,
    [switch]$Tap,
    [switch]$Contains
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp = Join-Path $env:TEMP 'adbui.xml'

function Get-Ui([string]$serial) {
    & $adb -s $serial shell uiautomator dump /sdcard/adbui.xml | Out-Null
    & $adb -s $serial pull /sdcard/adbui.xml $tmp | Out-Null
    return [xml](Get-Content $tmp -Raw)
}

switch ($Action) {
    'size' {
        "serial : $Serial"
        "wm size: $(((& $adb -s $Serial shell wm size) -join ' ').Trim())"
        "density: $(((& $adb -s $Serial shell wm density) -join ' ').Trim())"
    }
    'shot' {
        $dir = Join-Path $PSScriptRoot 'final_shots'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & $adb -s $Serial shell screencap -p /sdcard/$Name.png
        & $adb -s $Serial pull /sdcard/$Name.png (Join-Path $dir "$Name.png") | Out-Null
        "saved research/final_shots/$Name.png"
    }
    'summary' {
        $xml = Get-Ui $Serial
        ($xml.SelectNodes('//node[@text!=""]') | ForEach-Object { $_.text }) -join ' | '
    }
    'texts' {
        $xml = Get-Ui $Serial
        ($xml.SelectNodes('//node[@text!=""]') | ForEach-Object { "$($_.text)@$($_.bounds)" }) -join ' | '
        "--- content-desc ---"
        ($xml.SelectNodes('//node[@content-desc!=""]') | ForEach-Object { "$($_.GetAttribute('content-desc'))@$($_.bounds)" }) -join ' | '
    }
    'desc' {
        $xml = Get-Ui $Serial
        $hit = @()
        foreach ($n in $xml.SelectNodes('//node')) { if ($n.GetAttribute('content-desc') -eq $Text) { $hit += $n } }
        if ($hit.Count -eq 0) { "NOT FOUND (desc=$Text)"; exit 2 }
        foreach ($n in $hit) { "found $Text @$($n.bounds)" }
        if ($Tap) {
            if ($hit[0].bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { "BAD BOUNDS $($hit[0].bounds)"; exit 3 }
            $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            & $adb -s $Serial shell input tap $cx $cy
            "tapped $cx,$cy"
        }
    }
    'tap' {
        $xml = Get-Ui $Serial
        $hit = @()
        foreach ($n in $xml.SelectNodes('//node')) {
            # -Contains：按子串匹配。验收会话动作菜单时用得上 —— 新标签可能叫
            # "撤销本轮变更" 也可能叫 "撤销"，精确匹配会对同一个按钮误报 NOT FOUND。
            if ($Contains) { if ($n.text -and $n.text.Contains($Text)) { $hit += $n } }
            elseif ($n.text -eq $Text) { $hit += $n }
        }
        if ($hit.Count -eq 0) { "NOT FOUND (text=$(if($Contains){'*'}else{''})$Text)"; exit 2 }
        foreach ($n in $hit) { "found $($n.text) @$($n.bounds)" }
        if ($hit[0].bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { "BAD BOUNDS $($hit[0].bounds)"; exit 3 }
        $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb -s $Serial shell input tap $cx $cy
        "tapped $cx,$cy"
    }
    'swipe' {
        # 拖拽/滑动手势：验收终端面板改高、拖过折叠阈值时用。
        #   -From "1280,960" -To "1280,600" -Duration 600
        if ($From -notmatch '^(\d+),(\d+)$') { "BAD From (expect x,y): $From"; exit 3 }
        $fx = [int]$Matches[1]; $fy = [int]$Matches[2]
        if ($To -notmatch '^(\d+),(\d+)$') { "BAD To (expect x,y): $To"; exit 3 }
        $tx = [int]$Matches[1]; $ty = [int]$Matches[2]
        & $adb -s $Serial shell input swipe $fx $fy $tx $ty $Duration
        "swiped ($fx,$fy) -> ($tx,$ty) over ${Duration}ms"
    }
    'key' {
        & $adb -s $Serial shell input keyevent $Code
        "keyevent $Code"
    }
}
