  // 视口底带表面色（见上方 surfaceColor 段注释）：盯主题属性变化 + 首帧推一次。
  try {
    var surfaceObserver = new MutationObserver(function () {
      pushSurfaceColor();
    });
    surfaceObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme", "data-color-scheme", "class", "style"],
    });
    var installBodyObserver = function () {
      if (!document.body) return;
      surfaceObserver.observe(document.body, {
        attributes: true,
        attributeFilter: ["class", "style"],
      });
    };
    if (document.body) installBodyObserver();
    else document.addEventListener("DOMContentLoaded", installBodyObserver, { once: true });
    window.addEventListener("load", pushSurfaceColor, { once: true });
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(pushSurfaceColor);
  } catch (e) {}

})();

