(() => {
  // IME 状态与"屏幕坐标上"的遮挡量。
  // ⚠️ 关键：屏幕坐标的 IME 上沿 = **整窗高** − ime，而不是 innerHeight − ime：
  // resize 模式下 innerHeight 已经被压矮，再减一次就是双重扣减（第一版本探针就错在这）。
  const px = (v) => parseFloat(v) || 0;
  const cs = getComputedStyle(document.documentElement);
  const ime = px(cs.getPropertyValue("--oc-ime-bottom"));
  const windowH = px(cs.getPropertyValue("--oc-window-height")) || window.innerHeight;
  const imeTop = ime > 0 ? windowH - ime : windowH;
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), w: Math.round(b.width), h: Math.round(b.height) }; };
  const occl = (el) => (el ? Math.max(0, Math.round(el.getBoundingClientRect().bottom - imeTop)) : null);
  const panel = document.querySelector('[data-component="terminal"]');
  const canvas = document.querySelector('[data-component="terminal"] canvas');
  const form = document.querySelector("form");
  const out = {
    imeMode: document.documentElement.getAttribute("data-oc-ime-mode"),
    hostImeMode: window.__ocAndroid && window.__ocAndroid.getImeMode ? window.__ocAndroid.getImeMode() : null,
    imeBottom: Math.round(ime * 100) / 100,
    windowH: Math.round(windowH),
    innerH: window.innerHeight,
    imeTop: Math.round(imeTop * 100) / 100,
    visualViewport: window.visualViewport ? { h: Math.round(window.visualViewport.height), top: Math.round(window.visualViewport.offsetTop) } : null,
    terminalPanel: r(panel),
    terminalCanvas: r(canvas),
    canvasBacking: canvas ? { w: canvas.width, h: canvas.height } : null,
    composer: r(form),
    occluded: {
      terminalPanel: occl(panel),
      terminalCanvas: occl(canvas),
      composer: occl(form),
    },
    focused: document.activeElement ? document.activeElement.tagName + "." + (document.activeElement.getAttribute("data-slot") || "") : null,
  };
  return JSON.stringify(out);
})()
