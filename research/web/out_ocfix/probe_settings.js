(() => {
  // 走真实路由到设置页（solid-router 监听 popstate），再回读服务器表面。
  history.pushState({}, "", "/settings");
  window.dispatchEvent(new PopStateEvent("popstate"));
  return new Promise((resolve) => {
    setTimeout(() => {
      const token = location.origin.replace(/^https?:\/\//, "");
      const vis = (el) => {
        if (!el) return false;
        const r = el.getBoundingClientRect();
        if (!(r.width > 0 && r.height > 0)) return false;
        const cs = getComputedStyle(el);
        return cs.display !== "none" && cs.visibility !== "hidden";
      };
      let total = 0, visible = 0;
      const parents = [];
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let n;
      while ((n = walker.nextNode())) {
        if (!n.nodeValue || n.nodeValue.indexOf(token) < 0) continue;
        total++;
        const el = n.parentElement;
        if (vis(el)) {
          visible++;
          if (parents.length < 6) parents.push((el.tagName + "." + String(el.className || "")).slice(0, 70));
        }
      }
      const body = document.body.innerText.replace(/\s+/g, " ");
      resolve(JSON.stringify({
        url: location.href,
        originToken: token,
        originNodes: { total, visible, visibleParents: parents },
        bodyHead: body.slice(0, 420),
        lists4095: (body.match(/127\.0\.0\.1:4095/g) || []).length,
        lists4173: (body.match(/127\.0\.0\.1:4173/g) || []).length,
      }));
    }, 2500);
  });
})()
