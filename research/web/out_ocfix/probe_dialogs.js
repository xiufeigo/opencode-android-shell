(() => {
  const out = [];
  for (const d of document.querySelectorAll('[role="dialog"], [data-slot="dialog-container"]')) {
    const b = d.getBoundingClientRect();
    const st = getComputedStyle(d);
    out.push({
      slot: d.getAttribute("data-slot") || "",
      comp: d.getAttribute("data-component") || "",
      rect: { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height), w: Math.round(b.width) },
      display: st.display,
      visibility: st.visibility,
      opacity: st.opacity,
      position: st.position,
      parentSlot: d.parentElement ? (d.parentElement.getAttribute("data-slot") || d.parentElement.className.toString().slice(0, 30)) : null,
      text: (d.innerText || "").replace(/\s+/g, " ").slice(0, 60),
      hitAtCenter: (() => { const el = document.elementFromPoint(Math.round(b.x + b.width / 2), Math.round(b.y + Math.min(b.height / 2, 40))); return el ? el.tagName + "." + (el.getAttribute("data-slot") || el.getAttribute("data-component") || "").slice(0, 20) : null; })(),
    });
  }
  return JSON.stringify({ count: out.length, dialogs: out }, null, 1);
})()
