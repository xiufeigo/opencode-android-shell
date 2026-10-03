param([Parameter(ValueFromRemainingArguments=$true)][string[]]$A)
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$s="emulator-5554"
$pkg="com.xiufeigo.opencodeshell"
switch($A[0]){
 "dev"  { & $adb devices -l }
 "focus"{ & $adb -s $s shell dumpsys window windows | Select-String "mCurrentFocus|mFocusedApp" }
 "pid"  { $p=& $adb -s $s shell pidof $pkg; $p }
 "wm"   { & $adb -s $s shell wm size; & $adb -s $s shell wm density }
 "sh"   { & $adb -s $s shell $A[1..($A.Length-1)] }
 "uia"  { & $adb -s $s shell uiautomator dump --compressed /sdcard/_e2e.xml | Out-Null; & $adb -s $s pull /sdcard/_e2e.xml $A[1] | Out-Null; "DUMPED -> $($A[1])" }
 "log"  { $p=& $adb -s $s shell pidof $pkg; & $adb -s $s logcat -d --pid=$p 2>$null | Select-String -Pattern "FATAL|IllegalArgumentException|maxHeight" }
}
