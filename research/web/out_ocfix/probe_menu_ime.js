(() => {
  const px = (v) => parseFloat(v) || 0;
  const cs = getComputedStyle(document.documentElement);
  const ime = px(cs.getPropertyValue("--oc-ime-bottom"));
  const windowH = px(cs.getPropertyValue("--oc-window-height")) || window.innerHeight;
  const imeTop = ime > 0 ? windowH - ime : windowH;
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), h: Math.round(b.height), w: Math.round(b.width) }; };
  const menu = document.querySelector('[data-component="menu-v2-content"], [data-popper-positioner], [role="menu"], [role="listbox"]');
  const chip = document.querySelector('[data-component="menu-trigger"], button[aria-haspopup]');
  return JSON.stringify({
    imeBottom: Math.round(ime * 100) / 100,
    innerH: window.innerHeight,
    imeTop: Math.round(imeTop * 100) / 100,
    menu: menu ? { slot: menu.getAttribute("data-component") || menu.getAttribute("role"), rect: r(menu), occludedPx: Math.max(0, Math.round(menu.getBoundingClientRect().bottom - imeTop)) } : null,
    menuFullyVisible: menu ? menu.getBoundingClientRect().bottom <= imeTop && menu.getBoundingClientRect().top >= 0 : null,
    chip: r(chip),
    searchInMenu: r(document.querySelector('[data-component="menu-v2-content"] input, [data-popper-positioner] input')),
    focused: document.activeElement ? document.activeElement.tagName + ":" + (document.activeElement.getAttribute("placeholder") || "") : null,
  });
})()
