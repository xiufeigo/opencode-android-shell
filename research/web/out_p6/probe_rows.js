(() => {
  const DPR = devicePixelRatio;
  const out = { url: location.href };
  const sw = document.querySelector('button[data-component=home-projects-dropdown]');
  out.switcher = sw ? { text: (sw.innerText || '').trim().replace(/\s+/g, ' '), display: getComputedStyle(sw).display,
    box: (r => Math.round(r.width) + 'x' + Math.round(r.height))(sw.getBoundingClientRect()),
    ariaExpanded: sw.getAttribute('aria-expanded'), phantom: sw.hasAttribute('data-oc-phantom') } : null;
  const rows = [];
  document.querySelectorAll('[role=menuitem],[role=option],[role=menuitemradio],button,li').forEach(el => {
    const r = el.getBoundingClientRect();
    const t = (el.innerText || '').trim().replace(/\s+/g, ' ');
    if (!t || t.length > 40) return;
    if (el.querySelector('button,[role=menuitem]')) return;
    rows.push({ tag: el.tagName, comp: el.getAttribute('data-component') || '', role: el.getAttribute('role') || '',
      txt: t, box: Math.round(r.width) + 'x' + Math.round(r.height),
      phantom: el.hasAttribute('data-oc-phantom') || !!el.closest('[data-oc-phantom]'),
      inDropdownAncestor: !!el.closest('[data-component=home-projects-dropdown]'),
      dev: [Math.round(r.x * DPR + r.width * DPR / 2), Math.round(r.y * DPR + r.height * DPR / 2)] });
  });
  out.rows = rows;
  return JSON.stringify(out, null, 1);
})()
