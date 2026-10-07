(() => {
  history.pushState({}, "", "/settings");
  window.dispatchEvent(new PopStateEvent("popstate"));
  return new Promise((resolve) => {
    setTimeout(() => {
      const token = location.origin.replace(/^https?:\/\//, "");
      const marks = Array.from(document.querySelectorAll("[data-oc-phantom]")).map((el) => ({
        tag: el.tagName,
        cls: String(el.className || "").slice(0, 60),
        display: getComputedStyle(el).display,
        text: String(el.innerText || "").replace(/\s+/g, " ").slice(0, 60),
        hasOrigin: String(el.innerText || "").indexOf(token) >= 0,
      }));
      let total = 0, visible = 0;
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let n;
      while ((n = walker.nextNode())) {
        if (!n.nodeValue || n.nodeValue.indexOf(token) < 0) continue;
        total++;
        const el = n.parentElement;
        const r = el ? el.getBoundingClientRect() : { width: 0, height: 0 };
        const cs = el ? getComputedStyle(el) : { display: "none", visibility: "hidden" };
        if (r.width > 0 && r.height > 0 && cs.display !== "none" && cs.visibility !== "hidden") visible++;
      }
      resolve(JSON.stringify({
        url: location.href,
        originNodes: { total, visible },
        phantomMarks: marks,
        settingsHasOpencodeRow: /Servers\s+opencode/.test(document.body.innerText.replace(/\s+/g, " ")),
      }));
    }, 2500);
  });
})()
