// F4: 导航形态判据（侧栏 vs 移动端视图导航栏 vs 都没有）
(function () {
  function q(s) { return document.querySelectorAll(s); }
  function one(s) {
    var e = document.querySelector(s);
    if (!e) return null;
    var r = e.getBoundingClientRect(); var cs = getComputedStyle(e);
    return { box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
             display: cs.display, visibility: cs.visibility, opacity: cs.opacity, position: cs.position,
             txt: (e.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 60) };
  }
  var mobileNav = q('[data-slot=session-mobile-view-navigation]');
  var asides = q('aside');
  var navEl = q('nav,[role=navigation]');
  // 移动导航栏的逐个子控件
  var sub = [];
  if (mobileNav.length) {
    var root = mobileNav[0];
    var all = root.querySelectorAll('button,[role=tab],a[href]');
    for (var i = 0; i < all.length; i++) {
      var e = all[i], r = e.getBoundingClientRect();
      sub.push({ tag: e.tagName.toLowerCase(), slot: e.getAttribute('data-slot'),
                 aria: e.getAttribute('aria-label'), sel: e.getAttribute('aria-selected'),
                 txt: (e.innerText || '').trim().slice(0, 16),
                 box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] });
    }
  }
  // 侧栏首个可见内容（判断"侧栏在不在"）
  var asideInfo = [];
  for (var j = 0; j < asides.length; j++) {
    var a = asides[j], ar = a.getBoundingClientRect(), acs = getComputedStyle(a);
    asideInfo.push({ cls: (a.className || '').toString().slice(0, 60),
      box: [Math.round(ar.x), Math.round(ar.y), Math.round(ar.width), Math.round(ar.height)],
      display: acs.display, visibility: acs.visibility, opacity: acs.opacity,
      inViewport: ar.x < window.innerWidth && ar.right > 0,
      txt: (a.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 60) });
  }
  var form = 'NONE';
  if (mobileNav.length > 0) form = 'MOBILE_NAV';
  else if (asides.length > 0) form = 'SIDEBAR';
  return {
    form: form,
    geom: { innerW: window.innerWidth, innerH: window.innerHeight, dpr: window.devicePixelRatio },
    mq_min768: window.matchMedia('(min-width:768px)').matches,
    mq_max767: window.matchMedia('(max-width:767px)').matches,
    counts: { mobileViewNav: mobileNav.length, mobileViewTabs: q('[data-slot=session-mobile-view-tabs]').length,
              aside: asides.length, nav: navEl.length, reviewToggle: q('[data-slot=session-review-toggle]').length,
              textarea: q('textarea').length, contenteditable: q('[contenteditable=true]').length },
    mobileNavBox: one('[data-slot=session-mobile-view-navigation]'),
    mobileNavSubtree: sub,
    asides: asideInfo,
    composer: one('[aria-label="Prompt"],[contenteditable=true]'),
    url: location.href.slice(0, 120),
    domNodes: document.querySelectorAll('*').length
  };
})();
