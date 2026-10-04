(function () {
  var d = document, de = d.documentElement;
  var out = {};
  out.vp = { innerW: innerWidth, innerH: innerHeight, dpr: d.defaultView.devicePixelRatio,
             screenW: screen.width, screenH: screen.height, meta: (d.querySelector('meta[name=viewport]')||{}).content };
  // NOTE: meta viewport = width=device-width, initial-scale=1  =>  1 CSS px === 1 dp
  out.overflow = { deScrollW: de.scrollWidth, deClientW: de.clientWidth,
                   deScrollH: de.scrollHeight, deClientH: de.clientHeight,
                   bodyScrollW: d.body ? d.body.scrollWidth : null,
                   bodyClientW: d.body ? d.body.clientWidth : null,
                   horizOverflow: de.scrollWidth > de.clientWidth,
                   deltaPx: de.scrollWidth - de.clientWidth };
  // 超出视口右边界的元素（横向溢出定位）
  var over = [];
  var all = d.getElementsByTagName('*');
  for (var i = 0; i < all.length && over.length < 12; i++) {
    var e = all[i], r = e.getBoundingClientRect();
    if (r.width > 0 && r.right > de.clientWidth + 1) {
      over.push({ tag: e.tagName.toLowerCase(), cls: (e.className && e.className.toString ? e.className.toString() : '').slice(0, 48),
                  left: Math.round(r.left), right: Math.round(r.right), w: Math.round(r.width) });
    }
  }
  out.overflowElements = over;
  // 侧栏候选：nav / aside / [role=navigation] / class*sidebar / class*nav
  var cands = [];
  var sel = 'nav,aside,[role="navigation"],[data-sidebar],[class*="sidebar" i],[class*="side" i],[class*="nav" i],[class*="drawer" i]';
  var ns = d.querySelectorAll(sel);
  for (var j = 0; j < ns.length && j < 25; j++) {
    var n = ns[j], rc = n.getBoundingClientRect(), cs = getComputedStyle(n);
    cands.push({ tag: n.tagName.toLowerCase(), role: n.getAttribute('role'),
                 cls: (n.className && n.className.toString ? n.className.toString() : '').slice(0, 60),
                 x: Math.round(rc.x), y: Math.round(rc.y), w: Math.round(rc.width), h: Math.round(rc.height),
                 display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
                 position: cs.position, transform: cs.transform,
                 vis: (rc.width > 0 && rc.height > 0 && cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0) });
  }
  out.sidebarCandidates = cands;
  return JSON.stringify(out);
})()
