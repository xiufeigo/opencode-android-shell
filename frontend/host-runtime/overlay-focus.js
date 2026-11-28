  // ======================================================================
  // OC-KEYBOARD · 弹层里的"自动聚焦"不许再弹软键盘
  //
  // ## 现象（2026-10-07 产品负责人报单，本机同机型复现）
  //
  // 点作曲家的模型芯片：菜单打开的同时**软键盘自己弹起来**。菜单本身是 popper
  // 锚在芯片上的 `[data-component=menu-v2-content]`（宽 284），键盘一弹，可视高度
  // 被压掉一半 ⇒ popper 只能把菜单顶到屏幕中上部 ⇒ 看起来像"从底部往上弹的大面板"，
  // 而不是贴着芯片的下拉。**两件抱怨其实是同一个根因：键盘。**
  //
  // 设备实测（411×914 CSS，dpr 2.625；菜单里的激活元素 = `input[placeholder=Search models]`）：
  //   · 修前：`mInputShown=true`，菜单 rect {x:61, y:230, w:284, h:286}（顶到半屏）
  //   · 修后：`mInputShown=false`，菜单 rect {x:61, y:566, w:284, h:286}（紧贴芯片 y=858 上方）
  //
  // ## 机制
  //
  // 上游给菜单/对话框里的搜索框加了 `autofocus`，弹层打开时由 `onOpenAutoFocus`
  // 主动 `el.focus()`；Android WebView 只要**可编辑元素**获得焦点就拉 IME。
  //
  // ## 修法：只拦"程序化 + 在浮层里 + 不是用户点出来的"这三种条件同时成立的那一次 focus
  //
  //   ① 目标可编辑（input / textarea / contenteditable）；
  //   ② 目标位于浮层内（menu / dialog / popper / 移动抽屉）；
  //   ③ 这次 focus 不是用户刚点出来的（1s 内、且点的就是它自己或它的祖先）。
  // ⇒ 不聚焦 ⇒ 不弹键盘；菜单于是拿回整屏空间，popper 自然贴回芯片。
  // ⇒ 用户真去点输入框时走的是**浏览器原生聚焦**，我们完全不介入 ⇒ 键盘照常弹。
  //    设备实测：点搜索框 `mInputShown=true`，输入 "fl" 能过滤出 MiMo-V2.6-Flash。
  // ⇒ 主界面（作曲家、新建会话）的自动聚焦**不在范围内**，行为一字不改
  //    （设备实测：点进会话本来就不弹键盘）。
  //
  // ## 为什么不用其它写法（都试过，逐条留档）
  //
  // · `inputmode="none"`：静音期间用户点进来还要补一次 blur+focus 才能唤起 IME，
  //   于是多一套"谁在静音"的状态要维护，漏一处就变成"点了也不弹"。
  // · focus 后立刻 blur：会先弹一下再收，设备上肉眼可见闪一下。
  // · 宿主侧关 IME：WebView 只有**整页**开关，关了会把作曲家的键盘一起废掉。
  // · 监听 keydown 之类做"用户意图"识别：`InputPathHotspotTest` 明令禁止逐键监听
  //   （每次按键都在主线程上多跑一遍我们的代码）；pointerdown 是**非逐键**事件，
  //   一次交互只触发一次，所以这里只挂它。
  // ======================================================================
  var OC_OVERLAY_FOCUS_SELECTOR =
    '[role="menu"],[role="dialog"],[data-component="menu-v2-content"],' +
    '[data-slot="dialog-container"],[data-popper-positioner],[data-corvu-drawer-content]';
  var OC_FOCUS_GESTURE_MS = 1000;
  var ocLastPointer = { at: 0, target: null };

  /** 可编辑元素判定（与上游的输入控件对齐，含 contenteditable）。 */
  function ocEditable(el) {
    if (!el) return false;
    var tag = (el.tagName || "").toUpperCase();
    return tag === "INPUT" || tag === "TEXTAREA" || el.isContentEditable === true;
  }

  /** 是否位于"临时浮层"内（这些浮层的自动聚焦正是键盘自己弹起来的来源）。 */
  function ocInOverlay(el) {
    try {
      return !!(el.closest && el.closest(OC_OVERLAY_FOCUS_SELECTOR));
    } catch (e) {
      return false;
    }
  }

  /** 这次 focus 是不是用户刚点出来的（点的是它自己 / 它的祖先）。 */
  function ocFocusFromGesture(el) {
    var g = ocLastPointer;
    if (!g.target) return false;
    if (Date.now() - g.at > OC_FOCUS_GESTURE_MS) return false;
    return g.target === el || el.contains(g.target);
  }

  /**
   * 装 focus 守卫。只拦"程序化 + 浮层内 + 非用户点击"的那一次；
   * 其余一律 `orig.apply` 原样放行（含参数透传，别把 preventScroll 吃掉）。
   */
  function installOverlayFocusGuard() {
    try {
      var proto = window.HTMLElement && window.HTMLElement.prototype;
      if (!proto || proto.__ocFocusGuard) return;
      var orig = proto.focus;
      if (typeof orig !== "function") return;
      proto.__ocFocusGuard = true;
      proto.focus = function () {
        if (ocEditable(this) && ocInOverlay(this) && !ocFocusFromGesture(this)) {
          // 调试计数（设备上用 CDP 读 window.__ocFocusGuard 即可确认这条生不生效）
          try {
            window.__ocFocusGuard = window.__ocFocusGuard || { blocked: 0, last: "" };
            window.__ocFocusGuard.blocked++;
            window.__ocFocusGuard.last = String(this.getAttribute("placeholder") || this.tagName);
          } catch (e) {}
          return; // 不聚焦 ⇒ 不拉 IME
        }
        return orig.apply(this, arguments);
      };
    } catch (e) {}
  }

  // 手势记录：必须**先于**任何自动聚焦注册（文档开始），否则第一次弹层会被误判成
  // "没有手势"而静音 —— 那正是我们要的，但用户点输入框那一下必须先能被记到。
  try {
    document.addEventListener(
      "pointerdown",
      function (e) {
        ocLastPointer = { at: Date.now(), target: e.target };
      },
      true
    );
  } catch (e) {}
  installOverlayFocusGuard();

