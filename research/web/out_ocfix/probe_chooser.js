(() => {
  const r = (el) => { const b = el.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
  const attrs = (el) => Array.from(el.attributes).map((a) => `${a.name}=${String(a.value).slice(0, 60)}`).slice(0, 8);
  const out = { innerHeight: window.innerHeight, visualViewport: window.visualViewport ? Math.round(window.visualViewport.height) : null, focused: null, chain: [], overlays: [] };
  const ae = document.activeElement;
  if (ae) out.focused = { tag: ae.tagName, attrs: attrs(ae), rect: r(ae) };
  let el = ae;
  for (let i = 0; el && el !== document.body && i < 8; i++, el = el.parentElement) {
    const cs = getComputedStyle(el);
    out.chain.push({
      tag: el.tagName, attrs: attrs(el), cls: String(el.className || "").slice(0, 90),
      rect: r(el), position: cs.position, zIndex: cs.zIndex, display: cs.display,
      inset: `${cs.top}/${cs.bottom}/${cs.left}/${cs.right}`, maxHeight: cs.maxHeight, borderRadius: cs.borderRadius,
      pointerEvents: cs.pointerEvents,
    });
  }
  for (const e of document.querySelectorAll("body *")) {
    const cs = getComputedStyle(e);
    if ((cs.position === "fixed" || cs.position === "absolute") && parseInt(cs.zIndex || "0", 10) >= 40) {
      const b = e.getBoundingClientRect();
      if (b.width > 120 && b.height > 100) {
        out.overlays.push({
          tag: e.tagName, attrs: attrs(e), cls: String(e.className || "").slice(0, 70),
          rect: r(e), zIndex: cs.zIndex, position: cs.position, bg: cs.backgroundColor,
          borderRadius: cs.borderRadius, text: (e.innerText || "").replace(/\s+/g, " ").slice(0, 60),
        });
      }
    }
  }
  return JSON.stringify(out);
})()
