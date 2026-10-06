# UX14-P4 临时"按文本/选择器找元素并真实点击"的小工具（跑完删）。
# 用法: pwsh -NoProfile -File _scratch_findtap.ps1 -Text "Server" [-Scope ".settings-sidebar"] [-Shot s03] [-Map]
param(
  [string]$Text = "",        # 元素文本（包含匹配）
  [string]$Sel = "",         # CSS 选择器（与 -Text 二选一或叠加）
  [string]$Scope = "",       # 限定在该选择器内找
  [int]$Idx = 0,             # 第几个匹配（0 起）
  [string]$Shot = "",
  [switch]$Map,
  [switch]$NoTap,
  [int]$WaitMs = 1600,
  [switch]$LongPress,
  [int]$Slider = 0           # LongPress 时横向微位移
)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$d = "emulator-6790"
$ws = "ws://127.0.0.1:9333/devtools/page/E654DBAC56359A0AB0402884D60F2E29"
$dir = "D:\XiaoZhuTouTou\opencode-android-shell"
$exprFile = "$dir\_scratch_expr_live.js"

$selJson = $Sel | ConvertTo-Json -Compress
$txtJson = $Text | ConvertTo-Json -Compress
$scopeJson = $Scope | ConvertTo-Json -Compress
$idxJson = $Idx | ConvertTo-Json -Compress

$js = @"
(function(){
  var SEL=$selJson, TXT=$txtJson, SCOPE=$scopeJson, IDX=$idxJson;
  var root = SCOPE ? document.querySelector(SCOPE) : document;
  if(!root) return JSON.stringify({err:'scope not found: '+SCOPE});
  var list = SEL ? Array.prototype.slice.call(root.querySelectorAll(SEL)) : Array.prototype.slice.call(root.querySelectorAll('button,a,[role=button],[role=tab],summary,div,span'));
  var hits = list.filter(function(n){
    if(TXT){ var t=(n.innerText||n.getAttribute('aria-label')||n.title||'').replace(/\s+/g,' ').trim(); if(t.indexOf(TXT)<0) return false; }
    var r=n.getBoundingClientRect();
    if(r.width<3||r.height<3) return false;
    if(r.bottom<0||r.top>innerHeight||r.right<0||r.left>innerWidth) return false;
    // 优先取最深（叶子）节点
    return true;
  });
  // 叶子优先：剔掉包含其他命中的祖先
  hits = hits.filter(function(n){ return !hits.some(function(m){ return m!==n && n.contains(m) && (m.innerText||'').indexOf(TXT)>=0 }); });
  var n = hits[IDX];
  if(!n) return JSON.stringify({err:'no match', hitCount:hits.length, sample:hits.slice(0,6).map(function(e){return (e.innerText||'').replace(/\s+/g,' ').trim().slice(0,40)})});
  n.scrollIntoView({block:'center'});
  var r=n.getBoundingClientRect();
  return JSON.stringify({ok:true,text:(n.innerText||'').replace(/\s+/g,' ').trim().slice(0,60),tag:n.tagName.toLowerCase(),devX:Math.round((r.left+r.width/2)*devicePixelRatio),devY:Math.round((r.top+r.height/2)*devicePixelRatio),cssX:Math.round(r.left+r.width/2),cssY:Math.round(r.top+r.height/2),hitCount:hits.length});
})()
"@
Set-Content $exprFile $js -Encoding UTF8

$res = node "$dir\_scratch_idb.mjs" $ws $exprFile
Write-Output "LOCATE: $res"
if ($res -match '"ok":true') {
  $o = $res | ConvertFrom-Json
  if (-not $NoTap) {
    if ($LongPress) { & $adb -s $d shell input swipe $o.devX $o.devY ($o.devX + $Slider) $o.devY 900 }
    else { & $adb -s $d shell input tap $o.devX $o.devY }
    Start-Sleep -Milliseconds $WaitMs
  }
  if ($Shot) {
    & $adb -s $d exec-out screencap -p > "$dir\_scratch_p4_$Shot.png"
    Write-Output "[shot] _scratch_p4_$Shot.png"
  }
  $t = "(document.body.innerText||'').replace(/\n{2,}/g,'\n').trim().slice(0,700)"
  Set-Content $exprFile $t -Encoding UTF8
  Write-Output "----- text after -----"
  node "$dir\_scratch_idb.mjs" $ws $exprFile
}
if ($Map) {
  Write-Output "----- map -----"
  node "$dir\_scratch_idb.mjs" $ws "$dir\_scratch_expr_uimap.js"
}
