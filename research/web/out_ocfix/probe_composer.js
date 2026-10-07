(() => {
  const out = { url: location.href, sessionRows: [], composerButtons: [], dialogs: [], focused: null };
  const rect = (el) => { const r = el.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  const vis = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };

  // 1) Home 的会话行（用于点进一个会话）
  for (const el of document.querySelectorAll('[data-component="home-session-row-container"], [data-slot="home-session-row"]')) {
    if (!vis(el)) continue;
    out.sessionRows.push({ text: (el.innerText || "").replace(/\s+/g, " ").slice(0, 60), rect: rect(el) });
  }

  // 2) 视口底部区域的所有可点元素（找模型选择器芯片）
  const vh = window.innerHeight;
  for (const el of document.querySelectorAll("button, [role=button], [data-component]")) {
    if (!vis(el)) continue;
    const r = el.getBoundingClientRect();
    if (r.top < vh - 200) continue;
    const t = (el.innerText || "").replace(/\s+/g, " ").trim().slice(0, 40);
    if (!t) continue;
    out.composerButtons.push({
      tag: el.tagName, comp: el.getAttribute("data-component") || "", slot: el.getAttribute("data-slot") || "",
      text: t, rect: rect(el),
    });
  }

  // 3) 当前打开的弹窗/抽屉
  for (const el of document.querySelectorAll('[data-component="dialog-v2"], [data-slot="dialog-container"], [role="dialog"], [data-component="mobile-panel-drawer"]')) {
    if (!vis(el)) continue;
    out.dialogs.push({
      comp: el.getAttribute("data-component") || "", slot: el.getAttribute("data-slot") || "",
      variant: el.getAttribute("data-variant") || "", rect: rect(el),
      inner: (el.innerText || "").replace(/\s+/g, " ").slice(0, 80),
    });
  }

  // 4) 焦点 + 输入框
  const ae = document.activeElement;
  out.focused = ae ? { tag: ae.tagName, slot: ae.getAttribute("data-slot") || "", autofocus: ae.hasAttribute("autofocus"), inputmode: ae.getAttribute("inputmode"), value: String(ae.value || "").slice(0, 20) } : null;
  out.innerHeight = vh;
  out.visualViewport = window.visualViewport ? { h: Math.round(window.visualViewport.height) } : null;
  return JSON.stringify(out);
})()
