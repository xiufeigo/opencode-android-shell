(() => {
  const out = {};
  const btn = document.querySelector('button[data-component=home-projects-dropdown]');
  out.switcher = btn ? {
    text: (btn.innerText || '').trim().replace(/\s+/g, ' '),
    display: getComputedStyle(btn).display,
    rect: (r => ({ w: Math.round(r.width), h: Math.round(r.height), x: Math.round(r.x), y: Math.round(r.y) }))(btn.getBoundingClientRect()),
    ariaExpanded: btn.getAttribute('aria-expanded'),
    phantom: btn.hasAttribute('data-oc-phantom')
  } : null;
  // Enumerate every element in the document whose text mentions a server port
  const hits = [];
  document.querySelectorAll('*').forEach((el) => {
    if (el.children.length > 0) return; // leaf text nodes only
    const t = (el.textContent || '').trim();
    if (!t) return;
    if (/127\.0\.0\.1:(4173|4095)|localhost:(4173|4095)/.test(t)) {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      const row = el.closest('button,[role=menuitem],[role=option],[data-component]') || el;
      const rr = row.getBoundingClientRect();
      hits.push({
        text: t.replace(/\s+/g, ' ').slice(0, 60),
        leafTag: el.tagName,
        rowTag: row.tagName,
        rowComp: row.getAttribute('data-component') || '',
        rowRole: row.getAttribute('role') || '',
        display: cs.display,
        visibleBox: Math.round(r.width) + 'x' + Math.round(r.height),
        rowBox: Math.round(rr.width) + 'x' + Math.round(rr.height),
        rowInPhantom: row.hasAttribute('data-oc-phantom'),
        leafInPhantom: el.hasAttribute('data-oc-phantom'),
        dev: [Math.round(rr.x * devicePixelRatio + rr.width * devicePixelRatio / 2), Math.round(rr.y * devicePixelRatio + rr.height * devicePixelRatio / 2)]
      });
    }
  });
  out.portTextHits = hits;
  out.phantomCount = document.querySelectorAll('[data-oc-phantom]').length;
  out.phantomDetail = Array.from(document.querySelectorAll('[data-oc-phantom]')).map(n => ({
    tag: n.tagName, comp: n.getAttribute('data-component') || '',
    text: (n.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 50),
    inDropdown: !!n.closest('[data-component=home-projects-dropdown]')
  }));
  return JSON.stringify(out, null, 1);
})()
