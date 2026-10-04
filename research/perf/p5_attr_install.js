// p5_attr_install.js — 输入路径归因采集器（P5）
// 装到 window.__ocP5。**只测不注入**：不改页面行为、不加事件处理器阻断，监听器全为 passive。
// 采集 6 条原始读数：
//   1) longtask（PerformanceObserver）：长任务次数/总时长/最长 —— 判"主线程被打断"
//   2) event timing（PerformanceEventTiming）：startTime(硬件时间戳) -> processingStart/End -> duration
//      processingStart-startTime = 事件从硬件到 JS 处理开始的排队延迟；duration = 处理耗时
//   3) 每帧 rAF 间隔分布：判"掉帧/长帧"
//   4) 每个输入事件 -> 处理完成 -> 下一个 rAF（= 输入到首帧的近似）
//   5) DOM 变更计数（MutationObserver）：判"每次按键全量重渲染"
//   6) INP（interactionId 关联的真实输入延迟）
(function () {
  if (window.__ocP5) return "ALREADY_INSTALLED";
  var D = {
    longtasks: [],        // {start,dur}
    events: [],           // {name,dur,procStart,procEnd,queue,interId}
    rafGaps: [],          // 相邻 rAF 间隔 ms
    inputToFrame: [],     // 输入事件 -> 下一帧
    mutations: 0,         // 累计 DOM 变更节点数
    marks: [],            // 自定义打点
    t0: performance.now()
  };
  window.__ocP5 = D;

  // 1) longtask
  try {
    new PerformanceObserver(function (l) {
      for (var e of l.getEntries()) D.longtasks.push({ start: e.startTime, dur: e.duration });
    }).observe({ entryTypes: ["longtask"] });
  } catch (e) {}

  // 2) event timing（INP 口径）
  try {
    new PerformanceObserver(function (l) {
      for (var e of l.getEntries()) {
        D.events.push({
          name: e.name, dur: e.duration,
          procStart: e.processingStart, procEnd: e.processingEnd,
          startTime: e.startTime,
          queue: e.processingStart - e.startTime,   // 硬件->JS 排队
          interId: e.interactionId || 0
        });
      }
    }).observe({ type: "event", buffered: true, durationThreshold: 0 });
  } catch (e) {}

  // 3) 帧间隔
  (function loop(t) { requestAnimationFrame(function (n) { if (D.rafGaps.length < 20000) D.rafGaps.push(n - t); loop(n); }); })(performance.now());

  // 5) DOM 变更（被动观察，不改行为）
  try {
    new MutationObserver(function (recs) { D.mutations += recs.length; })
      .observe(document.documentElement, { childList: true, subtree: true, attributes: true, characterData: true });
  } catch (e) {}

  // 4) 输入事件 -> 下一帧（只对 keydown/pointerdown/touchstart/wheel 计时，passive+capture，不 preventDefault）
  var pending = 0;
  function onInput(ev) {
    if (pending) return;            // 同一帧内多个事件只记第一个，避免重复计
    pending = 1;
    var t = performance.now();
    var type = ev.type + (ev.isTrusted ? "" : "(untrusted)");
    // 等到下一帧：rAF 回调 = 该帧的渲染时机
    requestAnimationFrame(function (frameTs) {
      // 再等一个宏任务 ≈ 该帧已提交
      setTimeout(function () {
        D.inputToFrame.push({ type: type, t: t, frame: performance.now() - t });
        pending = 0;
      }, 0);
    });
  }
  ["keydown", "pointerdown", "touchstart", "wheel"].forEach(function (t) {
    document.addEventListener(t, onInput, { capture: true, passive: true });
  });

  return "INSTALLED t0=" + Math.round(D.t0);
})();
