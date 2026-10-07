(() => {
  // 只盯一件事：键盘开关过程中，终端面板/画布的高度是"一步到位"还是"渐变"（CSS 过渡/动画）。
  const panel = document.querySelector('[data-component="terminal"]');
  const canvas = document.querySelector('[data-component="terminal"] canvas');
  if (!panel || !canvas) return "NO_TERMINAL";
  const S = [];
  const t0 = performance.now();
  const id = setInterval(() => {
    S.push([
      Math.round(performance.now() - t0),
      Math.round(panel.getBoundingClientRect().height),
      Math.round(parseFloat(canvas.style.height) || 0),
      Math.round(canvas.getBoundingClientRect().height),
      parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--oc-ime-bottom")) || 0,
    ]);
  }, 20);
  window.__ocHeightSamples = S;
  setTimeout(() => clearInterval(id), 2000);
  const cs = getComputedStyle(panel);
  return JSON.stringify({
    sampling: true,
    panelTransition: cs.transitionProperty + " / " + cs.transitionDuration,
    panelAnimation: cs.animationName,
    canvasTransition: getComputedStyle(canvas).transitionProperty + " / " + getComputedStyle(canvas).transitionDuration,
  });
})()
