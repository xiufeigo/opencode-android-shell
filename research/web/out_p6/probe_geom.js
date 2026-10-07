(() => {
  const DPR = window.devicePixelRatio;
  const out = [];
  const sel = 'button, [role=button], [role=menuitem], [role=option], select, a, [data-component], [value]';
  document.querySelectorAll(sel).forEach((el) => {
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return;
    if (r.y < 0 || r.y > innerHeight) return;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') return;
    const txt = (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ').slice(0, 48);
    if (!txt) return;
    out.push({
      tag: el.tagName,
      role: el.getAttribute('role') || '',
      comp: el.getAttribute('data-component') || '',
      txt: txt,
      css: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      dev: [Math.round(r.x * DPR + (r.width * DPR) / 2), Math.round(r.y * DPR + (r.height * DPR) / 2)]
    });
  });
  return JSON.stringify({ url: location.href, n: out.length, items: out }, null, 1);
})()
