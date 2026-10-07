import sys, json
sys.path.insert(0, r'D:\XiaoZhuTouTou\opencode-android-shell\research\tmp\ux17p1')
import cdp

JS = r"""
(function(){
  var vv = window.visualViewport;
  var vh = vv ? vv.height : window.innerHeight;
  // find the composer: a textarea/contenteditable near the bottom
  var cands = Array.prototype.slice.call(
    document.querySelectorAll('textarea,[contenteditable="true"],form,input[type=text]'));
  var best = null;
  cands.forEach(function(el){
    var r = el.getBoundingClientRect();
    if (r.height === 0 || r.width === 0) return;
    if (!best || r.bottom > best.rect.bottom) best = {el: el, rect: r};
  });
  if (!best) return {found:false, vh: vh, innerHeight: window.innerHeight};
  var r = best.rect;
  return {
    found: true,
    tag: best.el.tagName,
    composerBottom: Math.round(r.bottom),
    composerTop: Math.round(r.top),
    visualViewportHeight: Math.round(vh),
    visualViewportOffsetTop: vv ? Math.round(vv.offsetTop) : null,
    innerHeight: window.innerHeight,
    fitsWithinVV: r.bottom <= vh + 1,
    overflowPx: Math.round(r.bottom - vh)
  };
})()
"""

print(json.dumps(cdp.evaluate(JS), ensure_ascii=False, indent=1))