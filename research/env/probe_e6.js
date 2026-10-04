// E6: 官方 UI 渲染判据 + 四个窄屏问题，一次性取数（纯 CSS px；meta viewport=width=device-width 时 1csspx=1dp）
(function () {
  var de = document.documentElement;
  function rect(sel) {
    var e = document.querySelector(sel);
    if (!e) return null;
    var r = e.getBoundingClientRect();
    var cs = getComputedStyle(e);
    return { sel: sel, tag: e.tagName, role: e.getAttribute('role'), aria: e.getAttribute('aria-label'),
      txt: (e.innerText || '').trim().slice(0, 24),
      x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
      display: cs.display, visibility: cs.visibility, opacity: cs.opacity, position: cs.position };
  }
  var txt = (document.body ? (document.body.innerText || '') : '').replace(/\s+/g, ' ').trim();

  // --- Q1 侧栏 ---
  var asides = [].slice.call(document.querySelectorAll('aside'));
  var navs = [].slice.call(document.querySelectorAll('nav,[role=navigation]'));
  var sidebar = {
    asideCount: asides.length, navCount: navs.length,
    asides: asides.map(function (e) {
      var r = e.getBoundingClientRect(); var cs = getComputedStyle(e);
      return { cls: (e.className || '').slice(0, 70), x: Math.round(r.x), y: Math.round(r.y),
               w: Math.round(r.width), h: Math.round(r.height), display: cs.display,
               visibility: cs.visibility, opacity: cs.opacity, position: cs.position,
               inViewport: r.x < window.innerWidth && r.right > 0, vis: r.width > 0 && r.height > 0 };
    }),
    headerButtons: [].slice.call(document.querySelectorAll('header button,[class*=header] button')).map(function (e) {
      var r = e.getBoundingClientRect();
      return { aria: e.getAttribute('aria-label'), txt: (e.innerText || '').trim().slice(0, 20),
               x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    })
  };

  // --- Q3 触摸靶 ---
  var sel = 'button, a[href], input, textarea, select, [role=button], [contenteditable=true], [tabindex]:not([tabindex="-1"])';
  var all = [].slice.call(document.querySelectorAll(sel));
  var inter = all.map(function (e) {
    var r = e.getBoundingClientRect(); var cs = getComputedStyle(e);
    return { tag: e.tagName.toLowerCase(), role: e.getAttribute('role'),
      aria: e.getAttribute('aria-label') || e.getAttribute('aria'),
      txt: (e.innerText || e.value || '').trim().slice(0, 22),
      x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
      disp: cs.display, vis: cs.visibility, op: cs.opacity };
  }).filter(function (o) { return o.disp !== 'none' && o.vis !== 'hidden' && parseFloat(o.op) > 0.05 && o.w > 0 && o.h > 0; });
  var real = inter.filter(function (o) { return !(o.w <= 2 && o.h <= 2); });
  var below = real.filter(function (o) { return o.w < 48 || o.h < 48; });

  // --- Q4 横向溢出 ---
  var vw = window.innerWidth;
  var overflow = [].slice.call(document.querySelectorAll('body *')).map(function (e) {
    var r = e.getBoundingClientRect();
    return { tag: e.tagName.toLowerCase(), cls: (e.className || '').toString().slice(0, 40),
             right: Math.round(r.right), w: Math.round(r.width) };
  }).filter(function (o) { return o.right > vw + 2; }).slice(0, 15);

  return {
    title: document.title, origin: location.origin, href: location.href,
    nodeCount: document.querySelectorAll('*').length,
    metaViewport: (document.querySelector('meta[name=viewport]') || {}).content || null,
    text200: txt.slice(0, 200),
    geom: { innerW: window.innerWidth, innerH: window.innerHeight, dpr: window.devicePixelRatio,
            screenW: screen.width, screenH: screen.height, docClientW: de.clientWidth, docClientH: de.clientHeight },
    Q1_sidebar: sidebar,
    Q2_composer: {
      composerCount: document.querySelectorAll('[contenteditable=true],[aria-label="Prompt"],[data-placeholder]').length,
      composers: [].slice.call(document.querySelectorAll('[contenteditable=true],[aria-label="Prompt"]')).map(function (e) {
        var r = e.getBoundingClientRect();
        return { tag: e.tagName.toLowerCase(), aria: e.getAttribute('aria-label'),
                 x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
      })
    },
    Q3_touch: { totalMatched: all.length, visible: inter.length,
                degenerateProxies: inter.length - real.length, realInteractive: real.length,
                realBelow48: below.length, below48: below },
    Q4_overflow: { deScrollW: de.scrollWidth, deClientW: de.clientWidth,
                   deScrollH: de.scrollHeight, deClientH: de.clientHeight,
                   bodyScrollW: document.body ? document.body.scrollWidth : null,
                   bodyClientW: document.body ? document.body.clientWidth : null,
                   horizOverflow: de.scrollWidth > de.clientWidth,
                   deltaPx: de.scrollWidth - de.clientWidth, overflowElements: overflow }
  };
})();
