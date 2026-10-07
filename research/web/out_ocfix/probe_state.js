(() => {
  const KEY = "opencode.global.dat:server";
  const token = location.origin.replace(/^https?:\/\//, "");
  const out = {
    url: location.href,
    origin: location.origin,
    registry: localStorage.getItem(KEY),
    lsKeys: Object.keys(localStorage),
    tokenNodes: { total: 0, visible: 0 },
    visibleTokenParents: [],
    switcher: null,
    serverRows: [],
    loading: false,
  };
  const vis = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    if (!(r.width > 0 && r.height > 0)) return false;
    const cs = getComputedStyle(el);
    return cs.display !== "none" && cs.visibility !== "hidden" && cs.opacity !== "0";
  };
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let n;
  while ((n = walker.nextNode())) {
    if (!n.nodeValue || n.nodeValue.indexOf(token) < 0) continue;
    out.tokenNodes.total++;
    const el = n.parentElement;
    if (vis(el)) {
      out.tokenNodes.visible++;
      if (out.visibleTokenParents.length < 8) {
        out.visibleTokenParents.push((el.tagName + "." + String(el.className || "")).slice(0, 80) + " :: " + n.nodeValue.trim().slice(0, 50));
      }
    }
  }
  const sw = document.querySelector('[data-component="home-projects-dropdown"]');
  if (sw) {
    out.switcher = {
      text: sw.innerText.replace(/\s+/g, " ").slice(0, 120),
      display: getComputedStyle(sw).display,
      visible: vis(sw),
    };
  }
  const body = document.body.innerText.replace(/\s+/g, " ");
  out.bodyHead = body.slice(0, 320);
  out.loading = /Loading/.test(body);
  out.serverRows = Array.from(document.querySelectorAll("bdi, span, div"))
    .filter((e) => e.children.length === 0 && /^127\.0\.0\.1:\d+$|^\d+\.\d+\.\d+\.\d+:\d+$/.test(String(e.textContent || "").trim()) && vis(e))
    .map((e) => e.textContent.trim())
    .slice(0, 8);
  return JSON.stringify(out);
})()
