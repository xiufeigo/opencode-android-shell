(() => {
  // 实验：只拦"没有用户手势的、且位于浮层内"的程序化 focus。
  // 目的：验证「不聚焦 ⇒ 不弹键盘 ⇒ popper 有整屏空间 ⇒ 菜单贴着芯片」。
  if (window.__ocFocusPatch) return "ALREADY";
  window.__ocFocusPatch = true;
  window.__ocLastGesture = { t: 0, target: null };
  document.addEventListener("pointerdown", (e) => {
    window.__ocLastGesture = { t: Date.now(), target: e.target };
  }, true);
  const editable = (el) => el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable);
  const inOverlay = (el) =>
    !!(el.closest && el.closest('[role="menu"],[role="dialog"],[data-component="menu-v2-content"],[data-slot="dialog-container"],[data-popper-positioner],[data-corvu-drawer-content]'));
  const orig = HTMLElement.prototype.focus;
  window.__ocFocusOrig = orig;
  HTMLElement.prototype.focus = function () {
    if (editable(this) && inOverlay(this)) {
      const g = window.__ocLastGesture;
      const fromGesture = g.target && (g.target === this || this.contains(g.target)) && Date.now() - g.t < 1000;
      if (!fromGesture) {
        window.__ocFocusBlocked = (window.__ocFocusBlocked || 0) + 1;
        return;
      }
    }
    return orig.apply(this, arguments);
  };
  return "PATCHED";
})()
