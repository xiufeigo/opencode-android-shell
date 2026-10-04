/**
 * G1 首屏内容探针：在**文档开始**执行，把「首屏真的有内容了」这个事实报回宿主，
 * 用来给 [WebViewPreflight] 的运行期看门狗**解除**（12 s 内没等到它 ⇒ 判定白屏/卡死）。
 *
 * ## 为什么必须用「首屏内容」而不是 `onPageFinished`
 * 实测（`emulator-5730` / WebView 83）：主文档与 250+ 个 `/_assets/` 资源**全部 200**，
 * `onPageFinished` **照常回调**，但 ESM 解析失败（`Unexpected token '=' @2`）⇒ 纯白。
 * 也就是说**「加载完成」与「用户看到东西」在坏 WebView 上是两回事**，
 * 拿 `onPageFinished` 当信号会永远抓不到这次故障。
 *
 * ## 为什么要两个信号源
 * `PerformanceObserver('paint')` 在老 WebView 上可能不存在或不回调，所以额外挂一条
 * `requestAnimationFrame` ×2 + `DOMContentLoaded` 的兜底：**任一**先到就算"有内容"。
 * 两个都是"**只上报、不干预**"，绝不影响页面本身。
 */
(function () {
  var N = window.__ocAndroidNative;
  var done = false;

  function report(kind, at) {
    if (done) return;
    done = true;
    var msg = "ocFirstContent kind=" + kind + " at=" + at;
    try { console.log(msg); } catch (e) {}
    // 走原生通道解除看门狗；没有原生（纯浏览器调试）就只留 console。
    try {
      if (N && typeof N.onFirstContent === "function") N.onFirstContent(kind, at);
    } catch (e) {}
  }

  function since() {
    try {
      return Math.round(
        (window.performance && performance.now) ? performance.now() : 0
      );
    } catch (e) { return 0; }
  }

  // ---- 信号源 1：paint timing ------------------------------------------------
  try {
    if (window.PerformanceObserver) {
      var po = new PerformanceObserver(function (list) {
        var entries = list.getEntries() || [];
        for (var i = 0; i < entries.length; i++) {
          if (entries[i].name === "first-contentful-paint") {
            report("fcp", Math.round(entries[i].startTime));
            return;
          }
        }
      });
      po.observe({ type: "paint", buffered: true });
    }
  } catch (e) {}

  // ---- 信号源 2：连画两帧 + DOM 就绪 ----------------------------------------
  // 纯白页面（含 WebView 83 的 ESM 解析失败）这两条**都不会**触发，
  // 所以它们在"坏"的方向上是真正的负信号。
  var frames = 0;
  function tick() {
    frames++;
    if (frames >= 2) { report("raf", since()); return; }
    try { requestAnimationFrame(tick); } catch (e) { report("raf", since()); }
  }
  try { requestAnimationFrame(tick); } catch (e) { /* 没有 rAF 就靠 DCL/paint */ }

  try {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", function () { report("dcl", since()); }, { once: true });
    } else {
      report("readyState", since());
    }
  } catch (e) {}
})();
