(() => {
  // 把当前屏里每个可聚焦输入都点一遍，量"焦点元素底沿 vs 键盘上沿"，并给出是否被遮挡。
  // 判据与宿主 ImeOcclusion 同口径（CSS px）。
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const imeBottom = () => {
    const v = getComputedStyle(document.documentElement).getPropertyValue("--oc-ime-bottom").trim();
    return parseFloat(v) || 0;
  };
  const rect = (el) => { const b = el.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height) }; };
  return (async () => {
    const out = { url: location.pathname + location.search, innerH: window.innerHeight, vvH: window.visualViewport ? Math.round(window.visualViewport.height) : null, imeBottom: imeBottom(), fields: [] };
    const inputs = Array.from(document.querySelectorAll('input:not([type=hidden]), textarea, [contenteditable="true"]'))
      .filter((el) => el.getBoundingClientRect().height > 8 && el.getBoundingClientRect().width > 40)
      .slice(0, 6);
    for (const el of inputs) {
      const target = el.closest("form") || el;
      el.focus();
      el.click?.();
      await sleep(1100);
      const r = rect(target);
      const ime = imeBottom();
      const imeTop = ime > 0 ? window.innerHeight - ime : window.innerHeight;
      out.fields.push({
        tag: el.tagName.toLowerCase(),
        slot: el.getAttribute("data-slot") || el.getAttribute("aria-label") || (el.placeholder || "").slice(0, 18),
        rect: r,
        imeBottomNow: ime,
        innerHNow: window.innerHeight,
        overflowPx: Math.max(0, Math.round(r.bottom - imeTop)),
        covered: ime > 0 && r.bottom > imeTop,
      });
      el.blur();
      await sleep(350);
    }
    return JSON.stringify(out, null, 1);
  })();
})()
