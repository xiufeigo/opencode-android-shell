(() => {
  const out = {};
  out.url = location.href;
  out.bodyText = (document.body.innerText || '').slice(0, 400);
  out.hasPhantomAttr = document.querySelectorAll('[data-oc-phantom]').length;
  out.phantomNodes = Array.from(document.querySelectorAll('[data-oc-phantom]')).slice(0, 8)
    .map(n => ({ tag: n.tagName, txt: (n.innerText || '').trim().slice(0, 60), attr: n.getAttribute('data-oc-phantom') }));
  const btn = document.querySelector('button[data-component=home-projects-dropdown]');
  out.switcher = btn ? {
    found: true,
    text: (btn.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 80),
    display: getComputedStyle(btn).display,
    visibility: getComputedStyle(btn).visibility,
    opacity: getComputedStyle(btn).opacity,
    rect: (r => ({ x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }))(btn.getBoundingClientRect()),
    ariaExpanded: btn.getAttribute('aria-expanded'),
    hasPhantomAttr: btn.hasAttribute('data-oc-phantom')
  } : { found: false };
  const ls = localStorage.getItem('opencode.global.dat:server');
  out.localStorage_server = ls === null ? 'null' : ls;
  out.dpr = window.devicePixelRatio;
  out.viewport = { w: innerWidth, h: innerHeight };
  out.cw = document.querySelectorAll('button').length;
  return JSON.stringify(out, null, 1);
})()
