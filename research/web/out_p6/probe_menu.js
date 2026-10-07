(() => {
  const DPR = devicePixelRatio;
  const pick = (el) => {
    const r = el.getBoundingClientRect();
    return { tag: el.tagName, comp: el.getAttribute('data-component') || '', role: el.getAttribute('role') || '',
      txt: (el.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40),
      box: Math.round(r.width) + 'x' + Math.round(r.height),
      dev: [Math.round(r.x * DPR + r.width * DPR / 2), Math.round(r.y * DPR + r.height * DPR / 2)] };
  };
  const menu = Array.from(document.querySelectorAll('[role=menuitemradio]')).map(pick);
  const colorRows = [];
  document.querySelectorAll('*').forEach(el => {
    if (el.children.length) return;
    const t = (el.textContent || '').trim();
    if (/color scheme|theme|appearance|system|dark|light/i.test(t) && t.length < 40) {
      const row = el.closest('[data-component=settings-row],[data-component=settings-list]') || el;
      colorRows.push({ txt: t, ...pick(row) });
    }
  });
  return JSON.stringify({ url: location.href, menu, colorRows: colorRows.slice(0, 12) }, null, 1);
})()
