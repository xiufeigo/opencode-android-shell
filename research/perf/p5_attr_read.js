// p5_attr_read.js — 读取 window.__ocP5 采集结果，输出分位与汇总（原始读数）
(function () {
  var D = window.__ocP5;
  if (!D) return "NO_COLLECTOR";
  function pct(a, p) {
    if (!a || !a.length) return null;
    var s = a.slice().sort(function (x, y) { return x - y; });
    var i = Math.min(s.length - 1, Math.max(0, Math.ceil(p * s.length) - 1));
    return Math.round(s[i] * 100) / 100;
  }
  function sum(a) { return a && a.length ? Math.round(a.reduce(function (x, y) { return x + y; }, 0) * 100) / 100 : 0; }

  var ltDur = D.longtasks.map(function (x) { return x.dur; });
  var evDur = D.events.map(function (e) { return e.dur; });
  var evQueue = D.events.map(function (e) { return e.queue; });
  var evProc = D.events.map(function (e) { return Math.max(0, e.procEnd - e.procStart); });
  var i2f = D.inputToFrame.map(function (x) { return x.frame; });
  var gaps = D.rafGaps.filter(function (g) { return g > 0; });
  var byType = {};
  D.inputToFrame.forEach(function (x) { (byType[x.type] = byType[x.type] || []).push(x.frame); });
  var typeOut = {};
  for (var k in byType) typeOut[k] = { n: byType[k].length, p50: pct(byType[k], .5), p90: pct(byType[k], .9), max: pct(byType[k], 1) };
  var evByName = {};
  D.events.forEach(function (e) {
    (evByName[e.name] = evByName[e.name] || []).push(e.dur);
  });
  var evNameOut = {};
  for (var n in evByName) evNameOut[n] = { n: evByName[n].length, p50: pct(evByName[n], .5), p90: pct(evByName[n], .9), max: pct(evByName[n], 1) };

  return JSON.stringify({
    window_ms: Math.round(performance.now() - D.t0),
    // 1) longtask
    longtask: { n: D.longtasks.length, total_ms: sum(ltDur), max_ms: Math.max.apply(null, ltDur.concat([0])), p50: pct(ltDur, .5), p90: pct(ltDur, .9), all: D.longtasks.slice(0, 40) },
    // 2) event timing (INP 口径)
    event_timing: {
      n: D.events.length,
      dur_p50: pct(evDur, .5), dur_p90: pct(evDur, .9), dur_p99: pct(evDur, .99), dur_max: pct(evDur, 1),
      queue_p50: pct(evQueue, .5), queue_p90: pct(evQueue, .9), queue_max: pct(evQueue, 1),
      handler_p50: pct(evProc, .5), handler_p90: pct(evProc, .9), handler_max: pct(evProc, 1),
      by_name: evNameOut
    },
    // 3) 帧间隔
    raf: { n: gaps.length, gap_p50: pct(gaps, .5), gap_p90: pct(gaps, .9), gap_p99: pct(gaps, .99), gap_max: pct(gaps, 1), over16: gaps.filter(function (g) { return g > 16.7; }).length, over50: gaps.filter(function (g) { return g > 50; }).length },
    // 4) 输入 -> 下一帧
    input_to_frame: { n: i2f.length, p50: pct(i2f, .5), p90: pct(i2f, .9), p95: pct(i2f, .95), max: pct(i2f, 1), by_type: typeOut },
    // 5) DOM 变更
    mutations: { total_records: D.mutations, per_s: Math.round(D.mutations / Math.max(1, (performance.now() - D.t0) / 1000) * 100) / 100 },
    // 6) 运行环境
    env: { dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight,
           nodes: document.getElementsByTagName('*').length, title: document.title,
           hc: navigator.hardwareConcurrency, mem: navigator.deviceMemory || null }
  }, null, 1);
})();
