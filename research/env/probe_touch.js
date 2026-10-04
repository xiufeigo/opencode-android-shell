(function () {
  var d = document, de = d.documentElement;
  var sel = 'button,a[href],input,textarea,select,[role="button"],[role="tab"],[role="menuitem"],[tabindex]:not([tabindex="-1"])';
  var els = d.querySelectorAll(sel), rows = [], small = [];
  for (var i = 0; i < els.length; i++) {
    var e = els[i], r = e.getBoundingClientRect(), cs = getComputedStyle(e);
    if (r.width <= 0 || r.height <= 0) continue;
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    // 1 CSS px === 1 dp (meta viewport width=device-width, initial-scale=1)
    var row = { tag: e.tagName.toLowerCase(), role: e.getAttribute('role'),
                aria: e.getAttribute('aria-label'),
                txt: (e.innerText || e.value || e.getAttribute('placeholder') || '').trim().slice(0, 18),
                x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    rows.push(row);
    if (row.w < 48 || row.h < 48) small.push(row);
  }
  // 统计
  var minH = 999, minW = 999;
  for (var k = 0; k < rows.length; k++) { if (rows[k].h < minH) minH = rows[k].h; if (rows[k].w < minW) minW = rows[k].w; }
  // composer
  var comp = [];
  var cs2 = d.querySelectorAll('textarea,input[type="text"],input:not([type])');
  for (var m = 0; m < cs2.length && m < 10; m++) {
    var c = cs2[m], rc = c.getBoundingClientRect();
    if (rc.width > 0 && rc.height > 0) comp.push({ tag: c.tagName.toLowerCase(), aria: c.getAttribute('aria-label'),
      ph: (c.getAttribute('placeholder') || '').slice(0, 22), x: Math.round(rc.x), y: Math.round(rc.y),
      w: Math.round(rc.width), h: Math.round(rc.height), vis: c.offsetParent !== null });
  }
  return JSON.stringify({
    innerW: innerWidth, innerH: innerHeight, dpr: devicePixelRatio,
    totalInteractive: rows.length, below48: small.length,
    minW: minW, minH: minH,
    below48List: small.slice(0, 25),
    composer: comp,
    sample: rows.slice(0, 8)
  });
})()
