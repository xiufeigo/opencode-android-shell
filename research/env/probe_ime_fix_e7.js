(function () {
  var st = document.getElementById("oc-ime-lift");
  var f = document.querySelector("form:has([contenteditable=\"true\"])");
  var ed = document.querySelector("[contenteditable=\"true\"]");
  var r = ed ? ed.getBoundingClientRect() : null;
  return JSON.stringify({
    styleInjected: !!st,
    cssSnippet: st ? st.textContent.replace(/\s+/g, " ").slice(0, 120) : null,
    imeVar: document.documentElement.style.getPropertyValue("--oc-ime-bottom"),
    safeBottom: document.documentElement.style.getPropertyValue("--oc-safe-bottom"),
    formPadBottom: f ? getComputedStyle(f).paddingBottom : null,
    composerTop: r ? Math.round(r.top) : null,
    composerBottom: r ? Math.round(r.bottom) : null,
    imeMode: window.__ocAndroid ? window.__ocAndroid.getImeMode() : null
  });
})();
