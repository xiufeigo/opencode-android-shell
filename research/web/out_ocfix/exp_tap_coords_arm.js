(() => {
  const c = document.querySelector("canvas");
  if (!c) return "NO_CANVAS";
  if (!window.__ocTapProbe) {
    window.__ocTapProbe = [];
    c.addEventListener("pointerdown", (e) => {
      const r = c.getBoundingClientRect();
      window.__ocTapProbe.push({ offsetX: e.offsetX, offsetY: e.offsetY, clientX: e.clientX, clientY: e.clientY, rect: { x: r.x, y: r.y, w: r.width, h: r.height } });
    }, true);
  }
  return JSON.stringify({ armed: true, rect: (() => { const r = c.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })(), clientWidth: c.clientWidth, styleWidth: c.style.width, dpr: window.devicePixelRatio });
})()
