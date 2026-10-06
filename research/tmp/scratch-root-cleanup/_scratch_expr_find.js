(function () {
  const out = [];
  document.querySelectorAll('button,a,[role=button],summary,[tabindex],[data-state]').forEach(n => {
    const r = n.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    if (r.bottom < 0 || r.top > window.innerHeight) return;
    const t = (n.innerText || n.getAttribute('aria-label') || n.title || '').replace(/\s+/g, ' ').trim();
    out.push({ text: t.slice(0, 50), x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), tag: n.tagName.toLowerCase() });
  });
  // 任何叶子节点里出现 server / remove / hide / 移除 / 隐藏 的文字
  const srv = [];
  document.querySelectorAll('*').forEach(n => {
    if (n.children.length > 3) return;
    const t = (n.innerText || '').trim();
    if (!t || t.length > 90) return;
    if (/server|remove|hide|移除|隐藏/i.test(t)) srv.push({ tag: n.tagName.toLowerCase(), text: t.slice(0, 80) });
  });
  const uniq = {}; srv.forEach(s => uniq[s.text] = s);
  return JSON.stringify({ clickable: out.filter(e => e.text), serverish: Object.values(uniq) }, null, 1);
})()
