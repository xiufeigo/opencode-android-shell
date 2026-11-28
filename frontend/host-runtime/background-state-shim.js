  // ======================================================================
  // P10 · 后台"未聚焦"页面状态 shim —— 通知闸门的最后一道墙。
  //
  // 上游 notify 闸门原文（bundle `ui/oa/index-D2r3WEwY.js`）：
  //   if ((…Notification.requestPermission().catch(()=>'denied')
  //        : Notification.permission) !== 'granted'
  //       || document.visibilityState === 'visible' && document.hasFocus()) return;
  //   let r = new Notification(...)
  //
  // 即：授权 granted（P9 已修）之外，**还要求"页面不是 可见且聚焦"** 才通知。
  // 而保活路径下 WebView 切后台只 pause 渲染/JS 时钟，**不改这两个属性/方法**的取值：
  //   document.visibilityState 恒 "visible"、document.hasFocus() 恒 true
  // （P7 合成的 blur 事件是"事件"，不是这两个读数；WebView 后台不发原生
  //   visibilitychange，也不让 hasFocus() 变 false —— 这正是 P9 设备实测
  //   `ctor=0`（上游从未构造 Notification）的最后一堵墙）。
  //
  // 所以宿主在 onPause/onResume 显式改写这两个**读数**。
  //
  // 🔴 **只覆盖属性/方法，绝不派发 visibilitychange / blur / focus 事件。**
  //   P7 已证：上游 pageLifecycle 监听 visibilitychange，且在 visible 时会
  //   触发 resync → abort，把保活路径下的 4095 SSE 掐死 ⇒ "终帧必丢"的
  //   P6 死锁原样复活（P6/P7 报告 §死锁链）。blur/focus 派发则由
  //   WebHostActivity 的 dispatchPageLifecycleEvent 负责，本 shim 不碰事件面。
  //   任何"顺手补个事件让它更真"的修改都属于**回退**，见 NotificationShimTest。
  //
  // 取值语义：ocBackgrounded 由宿主在**进入后台时**置 true、回前台置 false，
  // 两条路径（回合保活 / 空闲断流）都设，不作区分 —— 闸门只读这两个值，
  // 与 SSE 是否仍活着无关。
  // ======================================================================
  // 🔴 UX15-P1 · hasFocus 覆盖改为**委托原生**：原实现 `return !ocBackgrounded`
  //   把"前台"硬编码成 true，等于替 WebView 撒了一个谎 —— 只要宿主认为在前台，
  //   系统弹窗（权限/选择器）遮挡、多窗口/分屏、系统面板拉起这些**页面确实没有
  //   焦点**的时刻，上游闸门仍会读到 hasFocus()===true，与原生 WebView 语义偏差。
  //   现在只叠加宿主**确知**的那一个事实（"我进了后台"），其余一律委托原生。
  //   visibilityState 的覆盖维持原样：原生在前台本就恒为 "visible"，WebView 后台
  //   又不发 visibilitychange（见上），所以覆盖前后取值完全一致，不引入偏差。
  var ocBackgrounded = false;
  try {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: function () {
        return ocBackgrounded ? "hidden" : "visible";
      },
    });
    var ocOrigHasFocus = document.hasFocus.bind(document);
    document.hasFocus = function () {
      return ocBackgrounded ? false : ocOrigHasFocus();
    };
  } catch (e) {}
  window.__ocSetBackground = function (v) {
    ocBackgrounded = !!v;
  };

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

