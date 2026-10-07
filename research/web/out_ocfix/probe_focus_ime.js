(() => {
  // 焦点元素是否被键盘遮挡（任何界面通用）：报告焦点矩形 + 所在对话框/面板矩形。
  const px = (v) => parseFloat(v) || 0;
  const cs = getComputedStyle(document.documentElement);
  const ime = px(cs.getPropertyValue("--oc-ime-bottom"));
  const windowH = px(cs.getPropertyValue("--oc-window-height")) || window.innerHeight;
  const imeTop = ime > 0 ? windowH - ime : windowH;
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height), w: Math.round(b.width) }; };
  const occl = (el) => (el ? Math.max(0, Math.round(el.getBoundingClientRect().bottom - imeTop)) : null);
  const fe = document.activeElement;
  const dialog = document.querySelector('[role="dialog"], [data-slot="dialog-container"], [data-component="menu-v2-content"], [data-popper-positioner]');
  const panel = document.querySelector('.settings-screen, [data-component="settings-panel"], [data-component="terminal"]');
  return JSON.stringify({
    url: location.pathname.slice(0, 26) + location.search.slice(0, 18),
    imeBottom: Math.round(ime * 100) / 100,
    imeMode: document.documentElement.getAttribute("data-oc-ime-mode"),
    innerH: window.innerHeight,
    imeTop: Math.round(imeTop * 100) / 100,
    focused: fe ? { tag: fe.tagName, slot: fe.getAttribute("data-slot") || fe.getAttribute("aria-label") || "", rect: r(fe), occludedPx: occl(fe) } : null,
    dialog: dialog ? { tag: dialog.tagName, role: dialog.getAttribute("role") || dialog.getAttribute("data-slot"), rect: r(dialog), occludedPx: occl(dialog) } : null,
    panel: panel ? { slot: panel.getAttribute("data-component") || panel.className.slice(0, 20), rect: r(panel), occludedPx: occl(panel) } : null,
  });
})()
