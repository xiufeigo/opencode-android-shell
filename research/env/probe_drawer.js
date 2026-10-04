(function () {
  function snap(tag) {
    var d = document, de = d.documentElement, res = { tag: tag, innerW: innerWidth, innerH: innerHeight, offscreen: [], wide: [], text: '' };
    var all = d.getElementsByTagName('*');
    for (var i = 0; i < all.length; i++) {
      var e = all[i], r = e.getBoundingClientRect(), cs = getComputedStyle(e);
      if (r.width <= 0 || r.height <= 0) continue;
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      // 视口外的元素（抽屉/侧栏隐藏态）
      if (r.right < -1 || r.left > de.clientWidth + 1) {
        res.offscreen.push({ t: e.tagName.toLowerCase(), cls: (e.className && e.className.toString ? e.className.toString() : '').slice(0, 60),
                             aria: e.getAttribute('aria-label'), x: Math.round(r.x), w: Math.round(r.width), tr: cs.transform, pos: cs.position });
      }
      if (res.wide.length < 8 && r.width > de.clientWidth + 1) {
        res.wide.push({ t: e.tagName.toLowerCase(), cls: (e.className && e.className.toString ? e.className.toString() : '').slice(0, 50), w: Math.round(r.width) });
      }
    }
    if (res.offscreen.length > 14) res.offscreen = res.offscreen.slice(0, 14);
    res.text = (d.body ? d.body.innerText : '').slice(0, 160);
    return res;
  }
  var before = snap('before-click');
  var btn = document.querySelector('[aria-label="Tabs"]');
  var clicked = false;
  if (btn) { btn.click(); clicked = true; }
  return new Promise(function (resolve) {
    setTimeout(function () {
      var after = snap('after-click');
      resolve(JSON.stringify({ clicked: clicked, before: before, after: after }));
    }, 1200);
  });
})()
