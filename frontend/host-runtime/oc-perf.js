/*
 * P2 性能归因探针（仅 DEBUG 测量用，release 无害 —— 只 console.log）。
 *
 * 通过 WebViewCompat.addDocumentStartJavaScript 注册，**先于页面任何脚本**执行。
 * 所有输出走 console.log，被宿主的 onConsoleMessage 打进 logcat，
 * 落点是 `OcWebHost: web[log] OcPerfJS …`，grep 关键字 `OcPerfJS` 即可。
 *
 * 给的是**逐段**的浏览器侧视角：
 *  - `paint`      ：first-paint / first-contentful-paint
 *  - `DCL`/`load` ：DOMContentLoaded / load
 *  - `res #n`     ：每一个资源/接口的 start / duration / encodedBodySize / decodedBodySize
 *                   —— encoded vs decoded 的差就是**压缩收益的直接证据**。
 *  - `tick`       ：每 500ms 采一次 DOM 规模，`nodes` 出现跳变 = 真的开始出内容了
 *                   （比"最后一个 asset GET 时刻"更接近人眼可见）。
 */
(function () {
  try {
    if (location.protocol !== 'http:' && location.protocol !== 'https:') return;
    var L = function (s) { try { console.log('OcPerfJS ' + s); } catch (e) {} };
    L('doc-start href=' + location.href + ' navStart=' + performance.now().toFixed(0));

    try {
      new PerformanceObserver(function (list) {
        list.getEntries().forEach(function (e) {
          L('paint ' + e.name + ' at=' + e.startTime.toFixed(0));
        });
      }).observe({ type: 'paint', buffered: true });
    } catch (e) {}

    var n = 0, transfer = 0, decoded = 0;
    try {
      new PerformanceObserver(function (list) {
        list.getEntries().forEach(function (e) {
          n++;
          transfer += e.transferSize || 0;
          decoded += e.decodedBodySize || 0;
          var u = e.name.split('?')[0];
          var kind = u.indexOf('/_assets/') >= 0 ? 'asset'
            : u.indexOf('/api/') >= 0 ? 'api'
            : u.indexOf('/event') >= 0 ? 'sse' : 'other';
          L('res #' + n + ' ' + kind
            + ' start=' + e.startTime.toFixed(0)
            + ' dur=' + e.duration.toFixed(0)
            + ' enc=' + e.encodedBodySize
            + ' dec=' + e.decodedBodySize
            + ' xfer=' + e.transferSize
            + ' ' + u.slice(-58));
        });
        L('res-total n=' + n + ' xfer=' + transfer + ' dec=' + decoded
          + ' ratio=' + (decoded ? (transfer / decoded).toFixed(2) : 'na'));
      }).observe({ type: 'resource', buffered: true });
    } catch (e) {}

    document.addEventListener('DOMContentLoaded', function () {
      L('DCL at=' + performance.now().toFixed(0));
    }, { once: true });
    window.addEventListener('load', function () {
      L('load at=' + performance.now().toFixed(0));
    }, { once: true });

    /* DOM 规模采样：nodes 从 0/个位数跳到上百 = Svelte 真的挂载完 = 出内容。 */
    var ticks = 0, lastNodes = -1;
    var iv = setInterval(function () {
      ticks++;
      var root = document.getElementById('app') || document.body;
      var nodes = root ? root.getElementsByTagName('*').length : 0;
      if (nodes !== lastNodes) {
        L('tick+' + ticks + ' t=' + performance.now().toFixed(0) + ' nodes=' + nodes);
        lastNodes = nodes;
      }
      if (ticks >= 40) clearInterval(iv);
    }, 500);
  } catch (e) {
    try { console.log('OcPerfJS ERR ' + e); } catch (x) {}
  }
})();
