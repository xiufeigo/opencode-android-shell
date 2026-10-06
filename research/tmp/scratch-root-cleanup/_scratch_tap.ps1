# UX14-P4 临时 UI 驱动小工具（跑完删）：真实 input tap + 截图 + CDP 读 DOM。
# 用法: pwsh -NoProfile -File _scratch_tap.ps1 -X 66 -Y 120 -Shot s01 -Map
param(
  [int]$X, [int]$Y,          # 设备物理坐标
  [string]$Shot = "",        # 截图名（不含扩展名）
  [switch]$Map,              # 打完输出 UI 元素图
  [switch]$LongPress,        # 长按（模拟右键/上下文菜单）
  [int]$WaitMs = 1400,
  [string]$Text = ""         # 额外输出 body.innerText
)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d = "emulator-6790"
$ws = "ws://127.0.0.1:9333/devtools/page/E654DBAC56359A0AB0402884D60F2E29"
$dir = "D:\XiaoZhuTouTou\opencode-android-shell"

if ($X -ge 0 -and $Y -ge 0) {
  if ($LongPress) {
    # 极小位移的长按：模拟长按/上下文菜单
    & $adb -s $d shell input swipe $X $Y ($X + 1) ($Y + 1) 900
  } else {
    & $adb -s $d shell input tap $X $Y
  }
  Start-Sleep -Milliseconds $WaitMs
}
if ($Shot) {
  & $adb -s $d exec-out screencap -p > "$dir\_scratch_p4_$Shot.png"
  Write-Output "[shot] _scratch_p4_$Shot.png"
}
if ($Text) {
  $js = "(document.body.innerText||'').replace(/\n{2,}/g,'\n').trim().slice(0,900)"
  $f = "$dir\_scratch_expr_text.js"
  Set-Content $f $js -Encoding UTF8
  Write-Output "----- visible text -----"
  node "$dir\_scratch_idb.mjs" $ws $f
}
if ($Map) {
  Write-Output "----- element map -----"
  node "$dir\_scratch_idb.mjs" $ws "$dir\_scratch_expr_uimap.js"
}
