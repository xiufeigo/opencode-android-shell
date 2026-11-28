  // ======================================================================
  // 系统深浅色桥（宿主 → matchMedia）
  //
  // 上游 UI 判断"跟随系统"只走 `matchMedia("(prefers-color-scheme: dark)")`
  // （`packages/ui/src/theme/context.tsx` 的 `getSystemMode()` 与 `onMount` 里给
  // 该媒体查询挂的 change 监听）。Android WebView **不保证**这个媒体查询在
  // `uiMode` 翻转后更新：Chromium 只有 embedder 允许 algorithmic darkening 时
  // 才把它当深色，默认可以一直停在 light —— 真机表现就是系统切深色后页面不变。
  // 宿主本来就知道真值：`InsetsHost.publish` 每帧带 `scheme`，
  // `onConfigurationChanged` 又强制重推一次 payload。于是这里把那个媒体查询换成
  // 宿主驱动的实现：`matches` 跟随 payload，翻转时派发 change。上游一行不用改。
  //
  // 只接管 `(prefers-color-scheme: dark)`；`prefers-reduced-motion` /
  // `display-mode` 等其它查询继续走浏览器原生实现。
  var hostDark = (function () {
    // 宿主在注入本文件前会先放 `window.__ocHostSchemeInitial`（创建时的 uiMode
    // 真值）。它比 WebView 的媒体查询可靠，也让首帧底色不再闪。
    var initial = window.__ocHostSchemeInitial;
    if (initial === "dark" || initial === "light") return initial === "dark";
    try {
      return !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
    } catch (e) {
      return false;
    }
  })();
  var schemeListeners = [];
  var schemeQuery = null;

  function emitSchemeChange() {
    var ev = {
      type: "change",
      media: "(prefers-color-scheme: dark)",
      matches: hostDark,
      target: schemeQuery,
      currentTarget: schemeQuery,
    };
    var fns = schemeListeners.slice();
    for (var i = 0; i < fns.length; i++) {
      try {
        fns[i].call(schemeQuery, ev);
      } catch (e) {}
    }
    if (typeof schemeQuery.onchange === "function") {
      try {
        schemeQuery.onchange.call(schemeQuery, ev);
      } catch (e) {}
    }
  }

  function installSchemeQuery() {
    if (schemeQuery || !window.matchMedia) return;
    var real = window.matchMedia.bind(window);
    schemeQuery = {
      media: "(prefers-color-scheme: dark)",
      get matches() {
        return hostDark;
      },
      onchange: null,
      addEventListener: function (type, fn) {
        if (type === "change" && typeof fn === "function" && schemeListeners.indexOf(fn) < 0) schemeListeners.push(fn);
      },
      removeEventListener: function (type, fn) {
        var i = schemeListeners.indexOf(fn);
        if (type === "change" && i >= 0) schemeListeners.splice(i, 1);
      },
      addListener: function (fn) {
        schemeQuery.addEventListener("change", fn);
      },
      removeListener: function (fn) {
        schemeQuery.removeEventListener("change", fn);
      },
      dispatchEvent: function () {
        emitSchemeChange();
        return true;
      },
    };
    window.matchMedia = function (query) {
      if (String(query).indexOf("prefers-color-scheme: dark") >= 0) return schemeQuery;
      return real(query);
    };
  }

  function applyHostScheme(scheme) {
    var dark = String(scheme).toLowerCase() === "dark";
    if (dark === hostDark) return;
    hostDark = dark;
    emitSchemeChange();
  }

  installSchemeQuery();

