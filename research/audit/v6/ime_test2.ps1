$ErrorActionPreference='Stop'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s="emulator-6090"
$out="C:\sensorsdata\main\program\opencode-android-shell\research\audit\v6"
$ev="$out\ime_overlap2.txt"
"IME OVERLAP V6 (run#2) - 先定位真实 EditText，再断言 mInputShown" | Out-File $ev -Encoding utf8

# 1) 打开会话
& $adb -s $s shell input tap 540 500
Start-Sleep -Seconds 6
& $adb -s $s shell uiautomator dump /sdcard/i1.xml | Out-Null
& $adb -s $s pull /sdcard/i1.xml "$out\i1.xml" | Out-Null
$c=Get-Content "$out\i1.xml" -Raw
$m=[regex]::Match($c,'<node[^>]*class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if(-not $m.Success){ "NO EditText found -> i1.xml" | Out-File $ev -Append -Encoding utf8; "DONE"|Out-File "$out\ime2_done.flag" -Encoding utf8; exit 1 }
$x=[int](($m.Groups[1].ValueAsInt + $m.Groups[3].ValueAsInt)/2)
$y=[int](($m.Groups[2].ValueAsInt + $m.Groups[4].ValueAsInt)/2)
"EditText bounds = [$($m.Groups[1].Value),$($m.Groups[2].Value)][$($m.Groups[3].Value),$($m.Groups[4].Value)]  tap=($x,$y)" | Out-File $ev -Append -Encoding utf8

# 2) 真的点进去
& $adb -s $s shell input tap $x $y
Start-Sleep -Seconds 5
"=== 1) 前置断言 mInputShown ===" | Out-File $ev -Append -Encoding utf8
$shown = & $adb -s $s shell "dumpsys input_method | grep -E 'mInputShown|mShowRequested'"
($shown) | Out-File $ev -Append -Encoding utf8
$isShown = (($shown -join ' ') -match 'mInputShown=true')
"ASSERT mInputShown=true ? -> $isShown" | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

# 3) 原始行：type=ime frame=
"=== 2) type=ime frame= 原始行 (dumpsys window) ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window | grep 'type=ime'") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 3) 本应用窗口 frame（IME 避让后 WebView 实际高度）===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window windows | grep -A22 'opencodeshell.*WebHostActivity' | grep -E 'mFrame|frame=|mGivenContentInsets|requested'") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 4) 应用窗口可见内容 insets ===" | Out-File $ev -Append -Encoding utf8
(& $adb -s $s shell "dumpsys window windows | grep -B2 -A30 'opencodeshell.*WebHostActivity' | grep -E 'type=ime|InsetsSource|frame=' | head -30") | Out-File $ev -Append -Encoding utf8
"" | Out-File $ev -Append -Encoding utf8

"=== 5) 截图 ===" | Out-File $ev -Append -Encoding utf8
& $adb -s $s shell screencap -p /sdcard/ime_v6b.png
& $adb -s $s pull /sdcard/ime_v6b.png "$out\ime_v6b.png" | Out-Null
& $adb -s $s shell uiautomator dump /sdcard/i2.xml | Out-Null
& $adb -s $s pull /sdcard/i2.xml "$out\i2.xml" | Out-Null
"=== 6) IME 显示时 UI 树顶部可见文本 ===" | Out-File $ev -Append -Encoding utf8
$c2=Get-Content "$out\i2.xml" -Raw
([regex]::Matches($c2,'text="([^"]{2,40})"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Last 15) | Out-File $ev -Append -Encoding utf8
"DONE" | Out-File "$out\ime2_done.flag" -Encoding utf8
