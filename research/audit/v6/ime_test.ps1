$ErrorActionPreference='Stop'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s="emulator-6090"
$out="C:\sensorsdata\main\program\opencode-android-shell\research\audit\v6"
$ev="$out\ime_overlap.txt"

"IME OVERLAP V6" | Out-File $ev -Encoding utf8

# 打开一个会话，点输入框唤起 IME
& $adb -s $s shell input tap 540 500        # 第一个会话
Start-Sleep -Seconds 5
& $adb -s $s shell input tap 540 2100       # 输入框
Start-Sleep -Seconds 4

"=== 1) 前置断言：IME 是否真的显示 ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys input_method | grep -E 'mInputShown|mImeWindowVis|mShowRequested|mCurRootView|startInput|isInputViewShown'") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 2) ime 窗口原文 (dumpsys window: InputMethod / type=ime) ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window windows | grep -iE 'Window\{.*InputMethod|type=ime' ") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window | grep -iE 'type=ime|mFrame=|frame=' | head -40") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 3) InputMethod 窗口整块 (含 mFrame) ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window windows | grep -A20 'InputMethod' | head -40") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 4) 本应用窗口整块 (含 mFrame) ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window windows | grep -A20 'opencodeshell.*WebHostActivity' | head -24") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 5) 屏幕/insets 原文 ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window displays | grep -E 'init=|cur=|app=' | head -6") | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys input_method | grep -iE 'insets|imeFrame|frame' | head -20") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 6) 截图 ===" | Out-File $ev -Append -Encoding utf8
& $adb -s $s shell screencap -p /sdcard/ime_v6.png
& $adb -s $s pull /sdcard/ime_v6.png "$out\ime_v6.png" | Out-Null
"=== 7) UI 树（IME 显示时的可见文本）===" | Out-File $ev -Append -Encoding utf8
& $adb -s $s shell uiautomator dump /sdcard/ime_v6.xml | Out-Null
& $adb -s $s pull /sdcard/ime_v6.xml "$out\ime_v6.xml" | Out-Null
"DONE" | Out-File "$out\ime_done.flag" -Encoding utf8
