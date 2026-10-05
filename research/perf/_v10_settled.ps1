$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"; $PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10"
function A($argv){ $o=& $adb -s $S @argv 2>&1; return (($o|Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
$cells=@(@{n="settled_360dp";w=720;h=1280;d=320}, @{n="settled_412dp";w=824;h=1640;d=320})
foreach($c in $cells){
  A @('shell','wm','size',"$($c.w)x$($c.h)")|Out-Null
  A @('shell','wm','density',"$($c.d)")|Out-Null
  Start-Sleep -Seconds 3
  A @('shell','am','force-stop',$PKG)|Out-Null
  Start-Sleep -Seconds 2
  A @('shell','am','start','-n',$ACT)|Out-Null
  Start-Sleep -Seconds 9
  A @('shell','screencap','-p',"/sdcard/$($c.n).png")|Out-Null
  $p=Join-Path $OUT "$($c.n).png"
  A @('pull',"/sdcard/$($c.n).png",$p)|Out-Null
  "$($c.n) bytes=$((Get-Item $p).Length)"
}
"--- the single 401 line ---"
A @('shell','logcat','-d','-s','OcWebHost/Proxy') | Select-String -Pattern '401' | Select-Object -First 3
"--- DONE ---"
