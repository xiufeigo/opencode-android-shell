(() => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  return (async () => {
    history.pushState({}, "", "/settings");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await sleep(2000);
    const tab = Array.from(document.querySelectorAll("button, [role=tab], a")).find((el) => /^(Appearance|外观)/.test((el.innerText || "").trim()));
    if (tab) { tab.click(); await sleep(1800); }
    const out = {};
    const ctrl = document.querySelector('[data-action="settings-terminal-placement"]');
    out.found = !!ctrl;
    if (ctrl) {
      const row = ctrl.closest('[data-component="settings-row"]');
      out.controlTag = ctrl.tagName;
      out.controlAttrs = Array.from(ctrl.attributes).map((a) => a.name + "=" + String(a.value).slice(0, 60));
      out.rowHTML = row ? row.outerHTML : null;
      out.rowText = row ? (row.innerText || "").replace(/\s+/g, " ").slice(0, 120) : null;
      out.parentHTML = row && row.parentElement ? row.parentElement.outerHTML.slice(0, 700) : null;
    }
    return JSON.stringify(out);
  })();
})()
