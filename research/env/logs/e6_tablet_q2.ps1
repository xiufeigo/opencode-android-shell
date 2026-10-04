$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = 'emulator-5824'
$p = 'com.xiufeigo.opencodeshell.web.debug'
$cdp = "C:\sensorsdata\main\program\opencode-android-shell\research\env\cdp_run_e6.ps1"

function Alive { (& $adb devices | Select-String 'emulator-5824\s+device') -ne $null }
function Eval-Js($js) { & $cdp -Serial $s -Port 9322 -Js $js 2>&1 | Select-Object -Last 1 }

if (-not (Alive)) {
  "### relaunching"
  $log = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\e6_oc_narrow_boot_run3.log"
  if (-not (Get-NetTCPConnection -LocalPort 5824 -State Listen -ErrorAction SilentlyContinue)) {
    Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList @(
      "-avd","OC_Narrow_Test","-port","5824","-no-window","-no-audio","-no-boot-anim",
      "-no-snapshot-load","-no-snapshot-save","-cores","4","-memory","3072","-gpu","swiftshader_indirect"
    ) -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden
  }
  for ($i=0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 8
    if (((( & $adb -s $s shell getprop sys.boot_completed 2>&1) | Out-String).Trim()) -eq '1') { "BOOT_COMPLETED"; break }
  }
  Start-Sleep -Seconds 6
}

"### geometry -> tablet 1366x768 @160"
& $adb -s $s shell wm size 1366x768
& $adb -s $s shell wm density 160
& $adb -s $s reverse tcp:4095 tcp:4095 | Out-Null
& $adb -s $s shell wm dismiss-keyguard
& $adb -s $s shell am force-stop $p
Start-Sleep -Seconds 2
& $adb -s $s shell "am start -n $p/com.xiufeigo.opencodeshell.web.WebHostActivity" 2>&1 | Select-Object -Last 1
Start-Sleep -Seconds 18
"alive after home load = $(Alive)"
"home geom: " + (Eval-Js 'JSON.stringify({innerW:innerWidth,innerH:innerHeight,dpr:devicePixelRatio,aside:document.querySelectorAll("aside").length})')

"### open NEW session (lightweight, avoids the 4000-line session)"
$nav = Eval-Js '(()=>{var b=document.querySelector("button[aria=\"New session\"]")||[].slice.call(document.querySelectorAll("button")).filter(e=>/New session/.test(e.innerText||""))[0];if(!b)return "no new-session btn";b.click();return "clicked";})()'
"nav -> $nav   alive=$(Alive)"
Start-Sleep -Seconds 10
"alive after nav = $(Alive)"

$jsComp = '(()=>{var e=document.querySelector("[contenteditable=true]")||document.querySelector("[aria-label=Prompt]");if(!e)return "no composer";var r=e.getBoundingClientRect();return JSON.stringify({innerW:innerWidth,innerH:innerHeight,dpr:devicePixelRatio,composer:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),bottom:Math.round(r.bottom)}});})()'
"### composer BEFORE ime"
$pre = Eval-Js $jsComp
$pre
"### ASSERT mInputShown BEFORE"
(& $adb -s $s shell "dumpsys input_method | grep -E 'mInputShown|mServedInputConnection='" 2>&1 | Out-String).Trim()

$xy = Eval-Js '(()=>{var e=document.querySelector("[contenteditable=true]")||document.querySelector("[aria-label=Prompt]");var r=e.getBoundingClientRect();return Math.round(r.x+r.width/2)+","+Math.round(r.y+r.height/2);})()'
"tap target = $xy (dpr=1 => css px == device px)"
if ($xy -match '^\d+,\d+$') {
  $cx = [int]($xy -split ',')[0]; $cy = [int]($xy -split ',')[1]
  & $adb -s $s shell input tap $cx $cy
  Start-Sleep -Seconds 6
  "### ASSERT mInputShown AFTER"
  (& $adb -s $s shell "dumpsys input_method | grep -E 'mInputShown|mServedView='" 2>&1 | Out-String).Trim()
  "### RAW ime inset line"
  (& $adb -s $s shell "dumpsys window" 2>&1 | Out-String) -split "`n" | Select-String 'type=ime' | Select-Object -First 2 | ForEach-Object { $_.ToString().Trim() }
  "### composer + viewport AFTER ime"
  Eval-Js $jsComp
}
"### FINAL alive = $(Alive)"
