import sys; sys.path.insert(0,r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1'); import cdp
js = r"""(function(){
  var raw = localStorage.getItem('opencode.global.dat:server');
  var t = (document.body.innerText||'').replace(/\s+/g,' ').slice(0,300);
  return JSON.stringify({
    origin: location.origin,
    regExists: !!raw,
    regRaw: raw,
    bodyTxt: t,
    bodyHas4173: t.indexOf('4173')>=0,
    bodyHas4095: t.indexOf('4095')>=0,
    seedFn: typeof seedPhantomHidden,
    phantomAttr: document.querySelectorAll('[data-oc-phantom]').length
  });
})()"""
print(cdp.evaluate(js))
