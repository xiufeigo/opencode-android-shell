param()
# UX16-P1 helper (read-only eval hook; tag OcWebHost/Eval; real component ns com.xiufeigo.opencodeshell.web)
$global:ADB  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$global:S    = "emulator-6790"
$global:SHOT = "D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux16p1"
$global:PKG  = "com.xiufeigo.opencodeshell.web.debug"
$global:ACT  = "com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity"

function Shot([string]$n) {
    $p = Join-Path $global:SHOT "$n.png"
    & $global:ADB -s $global:S exec-out screencap -p > $p
    return $p
}

# read-only JS probe -> returns value logged under OcWebHost/Eval
function Eval([string]$js, [int]$wait = 3) {
    $b = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($js))
    & $global:ADB -s $global:S logcat -c 2>&1 | Out-Null
    & $global:ADB -s $global:S shell am start -n $global:ACT --es oc_debug_eval "b64:$b" 2>&1 | Out-Null
    Start-Sleep -Seconds $wait
    $o = & $global:ADB -s $global:S logcat -d -s "OcWebHost/Eval:D" 2>&1
    $line = ($o | Where-Object { $_ -match 'eval=' } | Select-Object -Last 1)
    if ($line -match '-> (.*)$') { return $Matches[1].Trim() } else { return "NO_EVAL_OUTPUT" }
}

function TapXY([int]$x, [int]$y, [int]$settle = 2) {
    & $global:ADB -s $global:S shell input tap $x $y
    Start-Sleep -Seconds $settle
}

function DumpPage {
    $js = @'
JSON.stringify((function(){
  var out={url:location.href,sheet:documentElement.dataset.colorScheme||null,
    vh:(window.visualViewport?Math.round(window.visualViewport.height):null),
    txt:document.body.innerText.replace(/\n{2,}/g,"\n").slice(0,900),anchors:[]};
  var sel='a,button,[role=button],[role=row],[role=listitem],input,textarea,[onclick],[tabindex]';
  document.querySelectorAll(sel).forEach(function(e,i){
    if(i>70)return;
    var r=e.getBoundingClientRect();
    if(r.width<4||r.height<4)return;
    out.anchors.push([i,(e.tagName+(e.getAttribute('role')?'['+e.getAttribute('role')+']':'')).slice(0,18),
      (e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').slice(0,30),
      Math.round(r.x+r.width/2),Math.round(r.y+r.height/2)]);
  });
  return out;
})())
'@
    return (Eval $js 4)
}