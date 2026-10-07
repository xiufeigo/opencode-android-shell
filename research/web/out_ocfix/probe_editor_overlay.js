(() => {
  const SEL = '[role="menu"],[role="dialog"],[data-component="menu-v2-content"],[data-slot="dialog-container"],[data-popper-positioner],[data-corvu-drawer-content]';
  const ed = document.querySelector('[data-component="composer-editor"]');
  const out = { found: !!ed, insideOverlay: false, matches: [], chain: [] };
  if (ed) {
    const hit = ed.closest(SEL);
    out.insideOverlay = !!hit;
    out.matches = hit ? [hit.tagName, hit.getAttribute("data-component") || "", hit.getAttribute("data-slot") || "", hit.getAttribute("role") || ""] : [];
    let el = ed;
    for (let i = 0; el && el !== document.body && i < 10; i++, el = el.parentElement) {
      out.chain.push({
        tag: el.tagName,
        comp: el.getAttribute("data-component") || "",
        slot: el.getAttribute("data-slot") || "",
        role: el.getAttribute("role") || "",
        drawer: el.hasAttribute("data-corvu-drawer-content") ? "yes" : "",
      });
    }
  }
  const ae = document.activeElement;
  out.activeElement = ae ? { tag: ae.tagName, comp: ae.getAttribute("data-component") || "", slot: ae.getAttribute("data-slot") || "" } : null;
  return JSON.stringify(out);
})()
