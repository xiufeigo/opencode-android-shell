(() => {
  // 实验：给终端面板加 CSS zoom（layout px 变大 ⇒ 列数变多；视觉字号等比变小）。
  const Z = 0.75;
  let st = document.getElementById("oc-term-zoom-exp");
  if (!st) {
    st = document.createElement("style");
    st.id = "oc-term-zoom-exp";
    document.head.appendChild(st);
  }
  st.textContent = `[data-component="terminal"]{zoom:${Z}!important}`;
  return JSON.stringify({ applied: Z });
})()
