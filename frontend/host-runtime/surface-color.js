  // ======================================================================
  // 视口底带表面色
  //
  // resize 模式把 WebView 压矮后，露出的那条带子显示的是宿主窗口底色
  // （DayNight 资源，跟**系统**模式走）。页面主题与系统不一致时
  // （系统深色 + 页面浅色）带子就是一整块黑色——"小白条所在位置直接全黑"
  // （MIUI 平板浅色主题实测）。
  //
  // 把页面解析后的表面底色报给宿主刷那条带子：条带跟页面主题走。
  // 主题切换 / 深浅切换都不改 insets，单靠 doApply 推不到 → 另挂
  // MutationObserver 盯 html/body 的主题属性，再补一次首帧推送。
  // ======================================================================
  var lastSurfaceColor = "";
  function surfaceColor() {
    var els = [document.getElementById("root"), document.body, document.documentElement];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!el) continue;
      try {
        var bg = getComputedStyle(el).backgroundColor;
        if (bg && bg !== "transparent" && bg !== "rgba(0, 0, 0, 0)") return bg;
      } catch (e) {}
    }
    return "";
  }
  function pushSurfaceColor() {
    var c = surfaceColor();
    if (!c || c === lastSurfaceColor) return;
    lastSurfaceColor = c;
    try {
      N.reportSurfaceColor(c);
    } catch (e) {}
  }

