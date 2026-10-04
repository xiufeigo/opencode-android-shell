// b1_l0_inject.js — L0 基线注入：在**同一个 WebView 宿主**里用 document.open/write/close
// 就地替换文档为一个**无框架极简页**（只有一个 <textarea> + 一个 <input>）。
// 为什么不改 webapp/src：本轮只测量、不改上游资产；document.write 就地替换，
// 旧文档连同官方 SPA 的 React 树与 oc-host.js 注入监听一起被销毁 ⇒ 干净地板。
//
// ⚠️ 修订记录（B1 第 1 轮被有效性检查抓出）：第 1 版把 4000 行文本的填充放在
//    **页内联 <script>** 里（`L.join("\\n")` 经过两层字符串转义后在内联脚本里
//    实际变成了字面反斜杠），结果 textarea.value 为空 ⇒ scrollHeight==clientHeight==915
//    ⇒ 滑动时**页面无内容可滚**。有效性检查 VERDICT_SWIPES_DID_REAL_WORK=false，
//    第 1 轮 L0 数字（p50 16.32）**已作废**。
//    本版改为：**在 CDP 侧（外层）直接给 textarea 赋值**，不依赖页内联脚本，
//    并把 scrollHeight/clientHeight 一起回读做自证。
(function(){
  var html = '<!doctype html><html><head><meta charset="utf-8">'
    + '<meta name="viewport" content="width=device-width,initial-scale=1">'
    + '<title>L0-MINIMAL</title>'
    + '<style>html,body{margin:0;padding:0;background:#fff;color:#000;'
    + 'font:16px/20px monospace;overflow:hidden}'
    + '#t{width:100%;height:100vh;box-sizing:border-box;border:0;padding:8px;'
    + 'font:16px/20px monospace;resize:none;overflow-y:scroll}'
    + '#i{position:fixed;left:0;bottom:0;width:100%;height:48px;font:16px monospace;'
    + 'box-sizing:border-box;border:0;border-top:1px solid #999;padding:8px;background:#eee}</style>'
    + '</head><body><textarea id="t"></textarea><input id="i" value="">'
    + '</body></html>';
  document.open();
  document.write(html);
  document.close();
  // 外层直接赋值：绕过内联脚本的转义地狱
  var L = [];
  for (var i = 1; i <= 4000; i++) { L.push('line ' + i); }
  var ta = document.getElementById('t');
  ta.value = L.join('\n');
  var lines = ta.value.split('\n').length;
  return JSON.stringify({
    injected: 'L0-MINIMAL',
    title: document.title,
    textareaCount: document.querySelectorAll('textarea').length,
    valueLines: lines,
    valueChars: ta.value.length,
    scrollHeight: ta.scrollHeight,
    clientHeight: ta.clientHeight,
    scrollable: (ta.scrollHeight - ta.clientHeight),
    domNodes: document.getElementsByTagName('*').length
  });
})()
