(() => {
  // 高频采样：键盘开/关过程中终端画布的尺寸与"当前绘制字号"，用来抓闪烁/字体缩放。
  const c = document.querySelector('[data-component="terminal"] canvas');
  const panel = document.querySelector('[data-component="terminal"]');
  if (!c) return "NO_CANVAS";
  const ime = () => parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--oc-ime-bottom")) || 0;
  const S = [];
  const t0 = performance.now();
  const ctx = c.getContext("2d");
  const id = setInterval(() => {
    const styleH = parseFloat(c.style.height) || 0;
    const styleW = parseFloat(c.style.width) || 0;
    S.push({
      t: Math.round(performance.now() - t0),
      ime: Math.round(ime() * 10) / 10,
      innerH: window.innerHeight,
      attr: c.width + "x" + c.height,
      style: Math.round(styleW) + "x" + Math.round(styleH),
      rectH: Math.round(c.getBoundingClientRect().height),
      // 位图被 CSS 拉伸的判据：attr 高 应 == style 高 × dpr
      stretch: Math.abs(c.height - styleH * (window.devicePixelRatio || 1)) > 2 ? Math.round(c.height / (styleH * (window.devicePixelRatio || 1)) * 100) / 100 : 1,
      font: ctx.font,
      panelH: panel ? Math.round(panel.getBoundingClientRect().height) : null,
    });
  }, 25);
  window.__ocImeSamples = S;
  setTimeout(() => clearInterval(id), 2500);
  return JSON.stringify({ sampling: true, dpr: window.devicePixelRatio });
})()
