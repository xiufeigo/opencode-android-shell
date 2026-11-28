  // ======================================================================
  // 顶部安全区（缺陷 A：标题与状态栏/挖孔重叠）
  //
  // 根因（F18 实测）：**不是**宿主没给 inset，而是两条各断一半：
  //  1) 宿主侧：InsetsHost 的首次 publish 发生在 onCreate 的 requestApplyInsets()，
  //     那时页面还不存在 ⇒ payload 静默丢失；顶部 inset 是静态的（不像 IME 会抖动），
  //     去重逻辑于是永不重发 ⇒ 页面侧 --oc-safe-top 恒为空。
  //     已由宿主侧 InsetsHost.replay() 在 onPageFinished 补发修好。
  //  2) 页面侧（就是这一段）：即使变量到了，上游 SPA **也不消费 --oc-safe-top**
  //     （与 IME1 同构：宿主交出了正确读数，页面没接）。
  //     WebView 边到边 ⇒ env(safe-area-inset-top) 恒为 0，也不能指望。
  //
  // 所以这里用宿主实测的 --oc-safe-top 给**应用根容器**垫顶部内边距。
  // 选根容器而不是 header 自身：根容器是 h-dvh + box-sizing:border-box，
  // 垫 padding-top 后**总高仍是视口高**（内容区等量收缩、不溢出、不出滚动条）；
  // 若垫在 header 上则会把它顶下去而下方内容不动，形态更差。
  //
  // 高度不能继续靠官方的 `h-dvh`（100dvh）：Android WebView 在父层 padding
  // 把控件压矮之后，dvh 经常仍是压矮前的值，要点击页面才会重算。
  // 会话面板是 #root 里的 flex-1，根节点偏矮 ⇒ 白色底框悬空。
  // 改用宿主量到的 WebView 实高 `--oc-view-height`（没有读数时退回 100%）。
  // ======================================================================
  var TOP_STYLE_ID = "oc-top-inset";

  function installTopInsetStyle() {
    if (document.getElementById(TOP_STYLE_ID)) return;
    var css = [
      // 边到边下 env() 恒为 0；取两者较大值，与底部 --oc-safe-bottom-eff 同一口径。
      ":root{--oc-safe-top-eff:max(var(--oc-safe-top,0px),env(safe-area-inset-top,0px));}",
      // 官方 shell 读 --shell-safe-area-bottom / --safe-area-inset-bottom / env()。
      // WebView 的 env(safe-area-inset-bottom) 是整条导航带；宿主已经用父层 padding
      // 让位，这里必须钉 0，否则 <main> 再垫 32px，白底框悬空，要点击才把 env 收掉。
      "html[data-oc-ime-mode=\"resize\"]{",
      "--safe-area-inset-bottom:0px !important;",
      "--shell-safe-area-bottom:0px !important;",
      "}",
      // 官方 <main> 内联 padding-bottom 走 env(safe-area-inset-bottom)。
      // WebView 给的是整条导航带 32 CSS；变量钉 0 后仍可能算出 32（max(10px, env())）。
      "html[data-oc-ime-mode=\"resize\"] main{",
      "padding-bottom:0px !important;",
      "}",
      "html,body{height:100%;}",
      // 应用根容器：#root 是上游挂载点，body>div 是它在 DOM 上的语义形态。
      // 两者同指一个元素；写成两条是为了上游改 id 时不至于整条修法失效。
      "#root,body>div:first-of-type{",
      "padding-top:var(--oc-safe-top-eff,0px);",
      "height:var(--oc-view-height,100%);",
      "box-sizing:border-box;",
      "}",
    ].join("\n");
    var st = document.createElement("style");
    st.id = TOP_STYLE_ID;
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  }

