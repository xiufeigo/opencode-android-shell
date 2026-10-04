(function () {
  var d = document, out = {};
  // body 子树骨架：逐层列出有尺寸的元素
  function desc(e, depth, maxDepth) {
    if (depth > maxDepth) return null;
    var r = e.getBoundingClientRect(), cs = getComputedStyle(e);
    var o = {
      t: e.tagName.toLowerCase(),
      cls: (e.className && e.className.toString ? e.className.toString() : '').slice(0, 70),
      id: e.id || null,
      role: e.getAttribute('role'),
      aria: e.getAttribute('aria-label'),
      x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
      d: cs.display, pos: cs.position, ov: cs.overflow,
      kids: []
    };
    if (r.width > 0 && r.height > 0) {
      for (var i = 0; i < e.children.length && o.kids.length < 14; i++) {
        var k = desc(e.children[i], depth + 1, maxDepth);
        if (k) o.kids.push(k);
      }
    }
    return o;
  }
  out.tree = desc(d.body, 0, 4);
  // 所有带 aria-label 的元素（常用于侧栏/按钮/导航）
  var al = [];
  var A = d.querySelectorAll('[aria-label]');
  for (var j = 0; j < A.length && j < 40; j++) {
    var a = A[j], ra = a.getBoundingClientRect();
    al.push({ tag: a.tagName.toLowerCase(), label: a.getAttribute('aria-label').slice(0, 30),
              cls: (a.className && a.className.toString ? a.className.toString() : '').slice(0, 40),
              x: Math.round(ra.x), y: Math.round(ra.y), w: Math.round(ra.width), h: Math.round(ra.height) });
  }
  out.ariaLabels = al;
  return JSON.stringify(out);
})()
