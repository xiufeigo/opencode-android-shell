(() => {
  // 用页面自己的字体度量复算终端单元格宽度 ⇒ 反推列数（与 ghostty 的 measureFont 同法）。
  const canvas = document.querySelector("canvas");
  const fam = '"IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace';
  const dpr = window.devicePixelRatio || 1;
  const measure = (cssPx, family) => {
    const c = document.createElement("canvas").getContext("2d");
    c.font = `${cssPx * dpr}px ${family}`;
    return { m: c.measureText("M").width / dpr, mg: c.measureText("Mg").width / dpr };
  };
  const out = { dpr, canvasCSSWidth: canvas ? parseFloat(canvas.style.width) : null, canvasBackingWidth: canvas ? canvas.width : null, sizes: {} };
  for (const size of [14, 16, 18, 20]) out.sizes[size] = measure(size, fam);
  if (canvas) {
    const cell14 = measure(14, fam).m;
    out.colsIf14px = Math.round(out.canvasCSSWidth / cell14);
    out.colsBackingIf14px = Math.round(out.canvasBackingWidth / (cell14 * dpr));
    const cell16 = measure(16, fam).m;
    out.colsIf16px = Math.round(out.canvasCSSWidth / cell16);
  }
  out.fontsReady = document.fonts ? document.fonts.status : "n/a";
  out.plexLoaded = document.fonts ? document.fonts.check('14px "IBM Plex Mono"') : null;
  return JSON.stringify(out);
})()
