(() => {
  const out = {};
  out.url = location.href;
  out.bodyText = (document.body.innerText || '').replace(/\n{2,}/g, '\n').slice(0, 600);
  out.phantomCount = document.querySelectorAll('[data-oc-phantom]').length;
  out.phantomDetail = Array.from(document.querySelectorAll('[data-oc-phantom]')).map(n => {
    const cs = getComputedStyle(n);
    const r = n.getBoundingClientRect();
    return {
      tag: n.tagName, comp: n.getAttribute('data-component') || '', role: n.getAttribute('role') || '',
      text: (n.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40),
      display: cs.display, box: Math.round(r.width) + 'x' + Math.round(r.height)
    };
  });
  out.has4095Anywhere = /4095/.test(document.body.innerText || '');
  out.has4173Visible = Array.from(document.querySelectorAll('*')).some(el => {
    if (el.children.length) return false;
    const t = (el.textContent || '').trim();
    if (!/4173/.test(t)) return false;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.display !== 'none' && cs.visibility !== 'hidden' && !el.closest('[data-oc-phantom]');
  });
  out.theme = { colorScheme: document.documentElement.dataset.colorScheme, theme: document.documentElement.dataset.theme, bg: getComputedStyle(document.documentElement).backgroundColor };
  return JSON.stringify(out, null, 1);
})()
