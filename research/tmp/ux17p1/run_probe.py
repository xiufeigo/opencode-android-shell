import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
print(cdp.raw(r'''var raw = localStorage.getItem('opencode.global.dat:server');
var bodyTxt = (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 400);
var hasPhantomAttr = !!document.querySelector('[data-oc-phantom]');
JSON.stringify({
  origin: location.origin,
  registryRaw: raw,
  registryExists: !!raw,
  parsed: raw ? JSON.parse(raw) : null,
  bodyHas4173: bodyTxt.indexOf('4173') >= 0,
  bodyHas4095: bodyTxt.indexOf('4095') >= 0,
  bodyTxt: bodyTxt,
  leftoverPhantomAttr: hasPhantomAttr,
  seedFnPresent: typeof seedPhantomHidden === 'function',
  bodyLoading: /Loading/i.test(bodyTxt)
});
'''))
