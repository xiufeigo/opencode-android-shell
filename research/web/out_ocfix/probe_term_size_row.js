(() => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  return (async () => {
    if (location.search.indexOf("tab=appearance") < 0) {
      location.href = "/settings?tab=appearance";
      return "NAVIGATING_APPEARANCE";
    }
    await sleep(600);
    const row = document.querySelector("[data-oc-term-size-row]");
    const out = {
      url: location.href,
      injected: !!row,
      styleTag: !!document.getElementById("oc-term-size-style"),
      anchor: !!document.querySelector('[data-action="settings-terminal-font"]'),
      lang: localStorage.getItem("opencode.global.dat:language"),
      stored: localStorage.getItem("oc.settings.terminalFontScale"),
    };
    if (row) {
      out.title = (row.querySelector('[data-slot="settings-row-title"]') || {}).textContent;
      out.desc = (row.querySelector('[data-slot="settings-row-description"]') || {}).textContent;
      out.buttons = Array.from(row.querySelectorAll("[data-oc-term-size] > button")).map((b) => ({
        label: b.textContent,
        size: b.getAttribute("data-oc-size"),
        active: b.hasAttribute("data-oc-active"),
      }));
      out.staleActions = row.querySelectorAll("[data-action]").length;
      const r = row.getBoundingClientRect();
      out.rect = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    }
    return JSON.stringify(out);
  })();
})()
