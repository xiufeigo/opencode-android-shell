(function () {
  const res = {};
  // 1) Server 元素的位置与祖先链
  const all = document.querySelectorAll('span,div,button,a,h1,h2,h3');
  const srv = [];
  all.forEach(n => {
    const t = (n.innerText || '').trim();
    if (t === 'Server' || t === 'Servers') {
      const r = n.getBoundingClientRect();
      const chain = [];
      let p = n;
      for (let i = 0; i < 5 && p; i++) {
        chain.push(p.tagName.toLowerCase() + (p.className ? '.' + p.className.toString().split(/\s+/).join('.') : ''));
        p = p.parentElement;
      }
      srv.push({ text: t, x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), visible: r.bottom > 0 && r.top < innerHeight, chain });
    }
  });
  res.serverSpans = srv;
  // 2) 面板顶部的 tab / 分区按钮（y < 200 的可点元素）
  res.topBar = [];
  document.querySelectorAll('button,a,[role=tab],[role=button]').forEach(n => {
    const r = n.getBoundingClientRect();
    if (r.top < 0 || r.top > 220) return;
    const t = (n.innerText || n.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
    if (!t) return;
    res.topBar.push({ text: t.slice(0, 40), x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), role: n.getAttribute('role') || '', sel: n.getAttribute('aria-selected') || '' });
  });
  // 3) 所有 role=tab / 分区标题
  res.tabs = [];
  document.querySelectorAll('[role=tab],[role=tablist]').forEach(n => {
    res.tabs.push({ role: n.getAttribute('role'), text: (n.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 40) });
  });
  // 4) 分组标题（settings section header）
  res.sections = [];
  document.querySelectorAll('h1,h2,h3,h4,[class*=section],[class*=group]').forEach(n => {
    const t = (n.innerText || '').trim();
    if (t && t.length < 40) res.sections.push({ tag: n.tagName.toLowerCase(), cls: (n.className || '').toString().slice(0, 40), text: t });
  });
  return JSON.stringify(res, null, 1);
})()
