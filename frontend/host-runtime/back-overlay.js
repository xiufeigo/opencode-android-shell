  // ======================================================================
  // BACK：只关闭真正显示在视口内的浮层；常驻展开区域不是返回层级。
  // 手机抽屉预挂载在视口外，关闭或退出动画中的节点不应吞掉返回键。
  // ======================================================================
  var OVERLAY_OPEN_SELECTORS = [
    "[role='menu']",
    "[role='listbox']",
    "[role='dialog']:not([data-closed]):not([data-closing])",
  ].join(",");

  function visibleBackLayer(el) {
    var r = el.getBoundingClientRect();
    var style = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && r.top < window.innerHeight && r.bottom > 0 &&
      r.left < window.innerWidth && r.right > 0 &&
      style.visibility !== "hidden" && style.display !== "none" &&
      !el.closest("[aria-hidden='true'],[inert],[data-closed]");
  }

  function closeTopOverlayOnBack() {
    // The trigger becomes expanded before a drawer's entrance animation reaches
    // the viewport. It still owns Back during that animation.
    var layers = document.querySelectorAll(OVERLAY_OPEN_SELECTORS + ",[aria-haspopup][aria-expanded='true']");
    if (!Array.prototype.some.call(layers, visibleBackLayer)) return false;
    var target = document.activeElement || document.body;
    ["keydown", "keyup"].forEach(function (type) {
      target.dispatchEvent(new KeyboardEvent(type, {
        key: "Escape",
        code: "Escape",
        keyCode: 27,
        which: 27,
        bubbles: true,
        cancelable: true,
        composed: true,
      }));
    });
    return true;
  }

