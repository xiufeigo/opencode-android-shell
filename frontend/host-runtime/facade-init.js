  if (document.readyState === "loading") {
    document.addEventListener(
      "DOMContentLoaded",
      function () {
        installImeLiftStyle();
        installTopInsetStyle();
        if (lastInsets) doApply(lastInsets);
      },
      { once: true }
    );
  } else {
    installImeLiftStyle();
    installTopInsetStyle();
  }
  // focusin 委托：composer 是 SPA 动态挂载的，不能只绑一次。
  document.addEventListener("focusin", onImeFocusIn, true);
  // insets 每变一次都刷新一次（IME 收起时 --oc-ime-bottom 归 0，留白自动撤掉）。
  if (lastInsets) doApply(lastInsets);

