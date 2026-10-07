(() => {
  const px = (v) => parseFloat(v) || 0;
  const cs = getComputedStyle(document.documentElement);
  const ime = px(cs.getPropertyValue("--oc-ime-bottom"));
  const windowH = px(cs.getPropertyValue("--oc-window-height")) || window.innerHeight;
  const imeTop = ime > 0 ? windowH - ime : windowH;
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height) }; };
  const form = document.querySelector("form");
  const editable = document.querySelector('[contenteditable="true"], form textarea');
  const out = {
    url: location.pathname.slice(0, 30),
    imeBottom: Math.round(ime * 100) / 100,
    imeMode: document.documentElement.getAttribute("data-oc-ime-mode"),
    windowH: Math.round(windowH),
    innerH: window.innerHeight,
    imeTop: Math.round(imeTop * 100) / 100,
    composer: r(form),
    editable: r(editable),
    lifted: form ? getComputedStyle(form).paddingBottom : null,
    occludedPx: form ? Math.max(0, Math.round(form.getBoundingClientRect().bottom - imeTop)) : null,
    // 双倍留白判据：composer 底沿离键盘上沿的"空档"（正常应 ≈0~8px，双垫会 ≈一整块键盘高）
    gapPx: form && ime > 0 ? Math.round(imeTop - form.getBoundingClientRect().bottom) : null,
    focused: document.activeElement ? document.activeElement.tagName + "." + (document.activeElement.getAttribute("data-slot") || "") : null,
  };
  return JSON.stringify(out);
})()
