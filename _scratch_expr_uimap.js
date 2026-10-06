// UX14-P4: 枚举页面上"可交互元素"的可见文本 + 矩形，用来定位真实点击坐标。
(function () {
  const out = [];
  const dpr = window.devicePixelRatio;
  const vh = window.innerHeight, vw = window.innerWidth;
  const sel = 'button,a,[role=button],[onclick],summary,[tabindex],[data-state]';
  const nodes = document.querySelectorAll(sel);
  for (const n of nodes) {
    const r = n.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) continue;
    if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;   // 视口外
    const t = (n.innerText || n.getAttribute('aria-label') || n.title || '').replace(/\s+/g, ' ').trim();
    if (!t) continue;
    out.push({
      tag: n.tagName.toLowerCase(),
      role: n.getAttribute('role') || '',
      aria: n.getAttribute('aria-label') || '',
      text: t.slice(0, 60),
      // CSS px；乘 dpr 得到设备物理 px（假设 webview 铺满全屏、无内缩）
      x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2),
      w: Math.round(r.width), h: Math.round(r.height),
    });
  }
  return JSON.stringify({ dpr, vw, vh, scrollY: window.scrollY, bodyTextLen: (document.body.innerText || '').length, els: out.slice(0, 70) }, null, 1);
})()
