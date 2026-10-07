(() => {
  const proto = CanvasRenderingContext2D.prototype;
  const panel = document.querySelector('[data-component="terminal"]');
  const fam = panel ? getComputedStyle(panel).fontFamily : null;
  const c = document.createElement("canvas").getContext("2d");
  const dpr = window.devicePixelRatio || 1;
  const out = {
    patched: !!proto.__ocTermScale,
    panelFamily: fam,
    narrow: window.matchMedia ? window.matchMedia("(max-width: 767px)").matches : null,
    dpr,
  };
  // 用"面板字体栈"量一次 M（应当被缩放：8.4 × 0.75 = 6.3）
  c.font = `${14 * dpr}px ${fam}`;
  out.fontString = c.font;
  out.measuredM = c.measureText("M").width;
  out.measuredM_css = c.measureText("M").width / dpr;
  // 用一个明显不同的字体再量一次（不应被缩放）
  c.font = `${14 * dpr}px sans-serif`;
  out.measuredSans = c.measureText("M").width / dpr;
  // 画布上的实际字体（终端画字形用的那支笔）
  const cv = document.querySelector('[data-component="terminal"] canvas');
  if (cv) {
    const ctx = cv.getContext("2d");
    out.canvasFontNow = ctx.font;
    ctx.font = `14px ${fam}`;
    out.canvasFontAfterSet = ctx.font;
  }
  return JSON.stringify(out);
})()
