(() => {
  const r = (el) => { const b = el.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
  const out = { dpr: window.devicePixelRatio, vv: window.visualViewport ? { w: Math.round(window.visualViewport.width), h: Math.round(window.visualViewport.height), scale: window.visualViewport.scale } : null, innerWidth: window.innerWidth, canvases: [], panels: [], tabs: [] };
  for (const c of document.querySelectorAll("canvas")) {
    const b = c.getBoundingClientRect();
    if (b.width < 40) continue;
    out.canvases.push({
      rect: r(c), backing: { w: c.width, h: c.height },
      cssWidth: c.style.width, cssHeight: c.style.height,
      parentComp: c.parentElement ? (c.parentElement.getAttribute("data-component") || "") : "",
      parentRect: c.parentElement ? r(c.parentElement) : null,
      parentClientWidth: c.parentElement ? c.parentElement.clientWidth : null,
      fontFamily: getComputedStyle(c).fontFamily, fontSize: getComputedStyle(c).fontSize,
    });
  }
  for (const el of document.querySelectorAll('[data-component="terminal"], [data-slot="terminal"], .terminal, [data-component="panel"]')) {
    const b = el.getBoundingClientRect();
    if (b.width < 100 || b.height < 60) continue;
    out.panels.push({ comp: el.getAttribute("data-component") || "", slot: el.getAttribute("data-slot") || "", cls: String(el.className || "").slice(0, 50), rect: r(el), clientWidth: el.clientWidth, padding: getComputedStyle(el).paddingLeft + "/" + getComputedStyle(el).paddingRight });
  }
  for (const el of document.querySelectorAll('[role="tab"], [data-component="tab"], button')) {
    const t = (el.innerText || "").replace(/\s+/g, " ").trim();
    if (!/^(Session|Changes|Files|Terminal|会话|更改|文件|终端)/.test(t)) continue;
    const b = el.getBoundingClientRect();
    if (b.width < 20 || b.height < 10) continue;
    out.tabs.push({ text: t.slice(0, 16), rect: r(el) });
  }
  return JSON.stringify(out);
})()
