(function () {
  var d = document;
  var sel = 'button,a[href],input,textarea,select,[role="button"],[role="tab"],[tabindex]:not([tabindex="-1"])';
  var els = d.querySelectorAll(sel), real = [], degenerate = 0, small = [];
  for (var i = 0; i < els.length; i++) {
    var e = els[i], r = e.getBoundingClientRect(), cs = getComputedStyle(e);
    if (r.width <= 0 || r.height <= 0) continue;
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    if (r.width <= 2 || r.height <= 2) { degenerate++; continue; }   // 1x1 代理元素，非真实触摸靶
    var row = { tag: e.tagName.toLowerCase(), aria: e.getAttribute('aria-label'),
                txt: (e.innerText || e.value || '').trim().replace(/\s+/g, ' ').slice(0, 20),
                x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    real.push(row);
    if (row.w < 48 || row.h < 48) small.push(row);
  }
  return JSON.stringify({ innerW: innerWidth, innerH: innerHeight, degenerateProxies: degenerate,
    realInteractive: real.length, realBelow48: small.length, smallList: small.slice(0, 20) });
})()
