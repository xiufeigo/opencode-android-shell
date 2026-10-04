# E7: IME composer 遮挡 —— 原子取证（断言 -> ime frame 原文 -> composer bounds -> 重叠像素量）
# 纪律：任何 ime 数值读取之前，先断言 dumpsys input_method 的 mInputShown=true。
# 用法: .\ime_repro_e7.ps1 -Serial emulator-5824 -Tag before -Geom phone
param(
  [string]$Serial = 'emulator-5824',
  [string]$Tag    = 'before',
  [string]$Geom   = 'phone',        # phone | tablet
  [int]$Port      = 9222,
  [switch]$OpenSession              # 进已有大会话（平板几何会打死实例，默认走 New session）
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
$act = "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity"
$shot = "C:\sensorsdata\main\program\opencode-android-shell\research\env\e7_${Tag}_${Geom}.png"
$log  = "C:\sensorsdata\main\program\opencode-android-shell\research\env\logs\e7_${Tag}_${Geom}.log"
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
function Say($m) { $m | Tee-Object -FilePath $log -Append }

# ---------- 0. 几何 ----------
if ($Geom -eq 'phone') { & $adb -s $Serial shell wm size 1080x2400; & $adb -s $Serial shell wm density 420 }
else                  { & $adb -s $Serial shell wm size 1366x768;  & $adb -s $Serial shell wm density 160 }
Start-Sleep -Seconds 3
Say "=== [$Tag/$Geom] wm ==="
Say ((& $adb -s $Serial shell wm size) -join ' | ')
Say ((& $adb -s $Serial shell wm density) -join ' | ')

# ---------- 1. 冷启 + 进会话 ----------
& $adb -s $Serial reverse tcp:4095 tcp:4095 | Out-Null
& $adb -s $Serial shell am force-stop $pkg
& $adb -s $Serial shell am start -n $act | Out-Null
Start-Sleep -Seconds 9

function Cdp($js) {
  $appPid = ((& $adb -s $Serial shell pidof $pkg) | Out-String).Trim()
  if (-not $appPid) { return 'ERR_NO_PID' }
  $unix = (& $adb -s $Serial shell "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1" | Out-String).Trim()
  & $adb -s $Serial forward --remove "tcp:$Port" 2>&1 | Out-Null
  & $adb -s $Serial forward "tcp:$Port" "localabstract:$unix" 2>&1 | Out-Null
  $list = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json/list" -TimeoutSec 10
  $t = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
  if (-not $t) { return 'ERR_NO_PAGE_TARGET' }
  $ws = [System.Net.WebSockets.ClientWebSocket]::new()
  $ws.ConnectAsync([Uri]$t.webSocketDebuggerUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
  $payload = @{ id = 1; method = 'Runtime.evaluate'; params = @{ expression = $js; returnByValue = $true } } | ConvertTo-Json -Depth 8 -Compress
  $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
  $ws.SendAsync([ArraySegment[byte]]::new($bytes), 'Text', $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
  $buf = New-Object byte[] 262144
  $acc = ''
  do {
    $r = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $acc += [Text.Encoding]::UTF8.GetString($buf, 0, $r.Count)
  } while (-not $r.EndOfMessage)
  $ws.Dispose()
  $j = $acc | ConvertFrom-Json
  if ($j.result.exceptionDetails) { return 'EXC:' + ($j.result.exceptionDetails.text) }
  return $j.result.result.value
}

# 进会话：点第一行会话（OpenSession 模式）或 "New session"
$nav = @'
(function(){
  var t=document.body.innerText||"";
  var rows=Array.prototype.slice.call(document.querySelectorAll('button, [role=button], a'));
  var hit=rows.filter(function(b){return /opencode-android-shell|DRAFT|New session/i.test(b.innerText||"")});
  return JSON.stringify({title:document.title, n:hit.length, sample:(hit[0]?hit[0].innerText.slice(0,40):null), hasTextarea:document.querySelectorAll('textarea').length});
})()
'@
Say ("=== NAV: " + (Cdp $nav))

if ($OpenSession) {
  $click = @'
(function(){
  var rows=Array.prototype.slice.call(document.querySelectorAll('button,[role=button],a'));
  var hit=rows.filter(function(b){return /opencode-android-shell|DRAFT__OK/i.test(b.innerText||"")});
  if(!hit.length) return "NO_ROW";
  var r=hit[0].getBoundingClientRect();
  var d=window.devicePixelRatio;
  return JSON.stringify({x:Math.round((r.x+r.width/2)*d), y:Math.round((r.y+r.height/2)*d)});
})()
'@
  $c = Cdp $click
  Say ("=== CLICK_ROW: $c")
  if ($c -match '^\{') { $j = $c | ConvertFrom-Json; & $adb -s $Serial shell input tap $j.x $j.y; Start-Sleep -Seconds 7 }
} else {
  $click = @'
(function(){
  var b=document.querySelector('button[aria="New session"]');
  if(!b) { var all=Array.prototype.slice.call(document.querySelectorAll('button')); b=all.filter(function(x){return /New session/i.test(x.innerText||"")})[0]; }
  if(!b) return "NO_BTN";
  var r=b.getBoundingClientRect(); var d=window.devicePixelRatio;
  return JSON.stringify({x:Math.round((r.x+r.width/2)*d), y:Math.round((r.y+r.height/2)*d)});
})()
'@
  $c = Cdp $click
  Say ("=== CLICK_NEW: $c")
  if ($c -match '^\{') { $j = $c | ConvertFrom-Json; & $adb -s $Serial shell input tap $j.x $j.y; Start-Sleep -Seconds 6 }
}

# ---------- 2. IME 未起时的 composer ----------
$probe = @'
(function(){
  var s=document.documentElement.style;
  function vis(e){var r=e.getBoundingClientRect();return r.width>1&&r.height>1&&r.top<window.innerHeight&&r.bottom>0;}
  var eds=Array.prototype.slice.call(document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]'));
  var ed=eds.filter(vis).pop();
  if(!ed) return JSON.stringify({err:"NO_EDITABLE",n:eds.length});
  function chain(e){var out=[],c=e;for(var i=0;i<8&&c&&c!==document.body;i++){var r=c.getBoundingClientRect();
    out.push({tag:c.tagName.toLowerCase(),cls:String(c.className||"").slice(0,60),pos:getComputedStyle(c).position,
      x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),top:Math.round(r.top),bottom:Math.round(r.bottom)});
    c=c.parentElement;}return out;}
  var r=ed.getBoundingClientRect();
  return JSON.stringify({dpr:window.devicePixelRatio, innerW:window.innerWidth, innerH:window.innerHeight,
    visualViewportH:(window.visualViewport?Math.round(window.visualViewport.height):null),
    vvOffsetTop:(window.visualViewport?Math.round(window.visualViewport.offsetTop):null),
    imeVar:s.getPropertyValue("--oc-ime-bottom"), safeVar:s.getPropertyValue("--oc-safe-bottom"),
    edTag:ed.tagName.toLowerCase(), edAria:ed.getAttribute("aria-label")||ed.getAttribute("placeholder")||null,
    ed:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),top:Math.round(r.top),bottom:Math.round(r.bottom)},
    chain:chain(ed)});
})()
'@
$before = Cdp $probe
Say "=== BEFORE (IME up=false) ==="
Say $before
$b = $before | ConvertFrom-Json
if (-not $b.ed) { Say "!! 找不到 composer，终止"; exit 1 }

# ---------- 3. 真实手指 tap 聚焦 ----------
$dpr = [double]$b.dpr
$tapX = [int][math]::Round(($b.ed.x + $b.ed.w/2) * $dpr)
$tapY = [int][math]::Round(($b.ed.y + $b.ed.h/2) * $dpr)
Say "=== TAP device($tapX,$tapY) from CSS($($b.ed.x),$($b.ed.y)) dpr=$dpr ==="
& $adb -s $Serial shell input tap $tapX $tapY
Start-Sleep -Seconds 3

# ---------- 4. 先断言 mInputShown ----------
Say "=== [1] ASSERT mInputShown (读任何 ime 数值前必做) ==="
$before4 = (& $adb -s $Serial shell "dumpsys input_method | grep -m1 mInputShown" | Out-String).Trim()
$conn    = (& $adb -s $Serial shell "dumpsys input_method | grep -m1 mServedInputConnection" | Out-String).Trim()
Say "  mInputShown=$before4"
Say "  mServedInputConnection=$conn"
if ($before4 -notmatch 'mInputShown=true') { Say "!! IME 未弹起，终止（不读 ime 数值）"; exit 1 }
Say "  ASSERT PASS"

# ---------- 5. ime frame 原始行 ----------
Say "=== [2] RAW ime lines ==="
$imeLine = (& $adb -s $Serial shell "dumpsys window | grep -m1 'InsetsSource id=.* type=ime'" | Out-String).Trim()
$ctlLine = (& $adb -s $Serial shell "dumpsys window | grep -m1 'InsetsSourceControl: {.* mType=ime'" | Out-String).Trim()
Say "  $imeLine"
Say "  $ctlLine"
$m = [regex]::Match($imeLine, 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
if (-not $m.Success) { Say "!! 解析 ime frame 失败"; exit 1 }
$iTopDev = [int]$m.Groups[2].Value; $iBotDev = [int]$m.Groups[4].Value
$imeTopCss = $iTopDev / $dpr
Say ("  ime frame top=$iTopDev bottom=$iBotDev height=$($iBotDev-$iTopDev)px  => CSS imeTop=$([math]::Round($imeTopCss,1))")

# ---------- 6. IME 起后的 composer ----------
$after = Cdp $probe
Say "=== AFTER (IME up=true) ==="
Say $after
$a = $after | ConvertFrom-Json

# ---------- 7. 重叠像素量 ----------
# 交集 = [max(composerTop, imeTop), min(composerBottom, imeBottom)] 的高度。
# 坐标系统一 CSS px：imeTop = 1517/2.625, imeBottom = 2400/2.625。
# （与 webapp 的 ImeOcclusion.overlapPx 同一口径。）
$imeTopCss  = $iTopDev / $dpr
$imeBotCss  = $iBotDev / $dpr
$ov = 0.0; $gap = 0.0
if ($a.ed) {
  $hi  = [math]::Max($a.ed.top, $imeTopCss)
  $lo  = [math]::Min($a.ed.bottom, $imeBotCss)
  $ov  = [math]::Max(0.0, $lo - $hi)
  $gap = [math]::Round($imeTopCss - $a.ed.bottom, 1)   # >0 = composer 整条在键盘上方
}
$ovPx = [math]::Round($ov, 1)
Say "=== [3] OVERLAP ==="
Say ("  composer(css) top=$($a.ed.top) bottom=$($a.ed.bottom)")
Say ("  ime(css)     top=$([math]::Round($imeTopCss,1)) bottom=$([math]::Round($imeBotCss,1))")
Say ("  innerH before=$($b.innerH) after=$($a.innerH)   visualViewportH=$($a.visualViewportH) vvOffsetTop=$($a.vvOffsetTop)")
Say ("  --oc-ime-bottom = $($a.imeVar)   (CSS px)")
Say ("  OVERLAP_PX = $ovPx     (目标 0)")
Say ("  clearance (imeTop - composerBottom) = $gap CSS px")
Say ("  composerFullyVisible = $($ovPx -eq 0)")

& $adb -s $Serial shell screencap -p /sdcard/e7.png | Out-Null
& $adb -s $Serial pull /sdcard/e7.png $shot | Out-Null
& $adb -s $Serial shell rm -f /sdcard/e7.png
Say "SHOT=$shot"
Say "=== SUMMARY $Tag/$Geom OVERLAP_PX=$ovPx ==="
