(() => {
  // 走到设置页，再切到 Appearance，然后把"终端字体"那一行的 DOM 摸清楚（用于克隆注入）。
  const out = { url: location.href, steps: [] };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  return (async () => {
    history.pushState({}, "", "/settings");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await sleep(2200);
    out.urlAfterSettings = location.href;
    // Appearance 分段：找文本为 Appearance 的可点元素
    const tab = Array.from(document.querySelectorAll("button, [role=tab], a, [data-slot]")).find(
      (el) => /^(Appearance|外观)/.test((el.innerText || "").trim()),
    );
    out.appearanceTabFound = !!tab;
    if (tab) { tab.click(); await sleep(1800); }
    out.urlAfterAppearance = location.href;
    const ctrl = document.querySelector('[data-action="settings-terminal-font"]');
    out.controlFound = !!ctrl;
    if (ctrl) {
      const row = ctrl.closest('[data-component="settings-row"]');
      out.rowFound = !!row;
      if (row) {
        out.rowOuterHTML = row.outerHTML.slice(0, 2200);
        out.rowSlots = Array.from(row.querySelectorAll("*")).slice(0, 8).map((el) => ({
          tag: el.tagName,
          comp: el.getAttribute("data-component") || "",
          slot: el.getAttribute("data-slot") || "",
          action: el.getAttribute("data-action") || "",
          cls: String(el.className || "").slice(0, 70),
        }));
        out.rowParentTag = row.parentElement ? row.parentElement.tagName + "." + String(row.parentElement.className || "").slice(0, 60) : null;
      }
    }
    // Appearance 段落里都有哪些行（标题列表）→ 看我们该插在哪一行后面
    out.rows = Array.from(document.querySelectorAll('[data-component="settings-row"]')).slice(0, 14).map((el) => ({
      title: (el.innerText || "").replace(/\s+/g, " ").slice(0, 40),
      action: (el.querySelector("[data-action]") || {}).getAttribute ? (el.querySelector("[data-action]").getAttribute("data-action") || "") : "",
    }));
    return JSON.stringify(out);
  })();
})()
