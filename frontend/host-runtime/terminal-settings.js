  // ======================================================================
  // OC-TERMINAL · 终端字号设置契约
  //
  // 当前终端引擎固定为 xterm；字号缩放、字符格子和触摸滚动的权威实现都在
  // frontend/terminal/terminal.js。这里只保留设置页与终端引擎共用的存储键、
  // 默认值和档位，不再修补 canvas measureText，也不再翻译触摸 wheel。
  // ======================================================================
  var OC_TERM_SCALE_KEY = "oc.settings.terminalFontScale";
  var OC_TERM_SCALE_DEFAULT = 0.65;
  var OC_TERM_SCALE_CHOICES = [1, 0.8, 0.65, 0.55];

  /** 当前生效的终端字号比例（设置页可改；越界一律夹回 [0.5, 1]）。 */
  function ocTerminalScale() {
    try {
      var v = parseFloat(window.localStorage.getItem(OC_TERM_SCALE_KEY) || "");
      if (!v || !isFinite(v)) {
        return window.matchMedia && window.matchMedia("(max-width: 767px)").matches ? OC_TERM_SCALE_DEFAULT : 1;
      }
      return Math.min(1, Math.max(0.5, v));
    } catch (e) {
      return window.matchMedia && window.matchMedia("(max-width: 767px)").matches ? OC_TERM_SCALE_DEFAULT : 1;
    }
  }

  // ======================================================================
  // OC-SETTINGS · 把"终端字号"做成设置项（设置 → 外观）
  //
  // 为什么是"注入"而不是改上游：`assets/www/ui/**` 是上游预构建产物（红线，不许动），
  // 而手机端上游**没有**终端字号设置（只有字体族；字号在控件里写死 14px）。
  //
  // 做法（尽量少碰上游）：
  //   · 锚点用上游自己的 `data-action="settings-terminal-font"`（"终端字体"那一行），
  //     **克隆整行**后改文案 + 换控件 ⇒ 排版、配色、分隔线天然一致，也不怕上游改类名；
  //   · 值存 `localStorage[oc.settings.terminalFontScale]`，由 xterm 终端适配层在下次
  //     创建终端时读取（终端一打开就按新字号重算列数）；
  //   · 选完**整页重载**：上游 URL 自带 `?tab=appearance`，重载后仍停在同一个面板，
  //     确定性最好（否则已挂载的终端要等重新创建才会变）。
  //   · 只在 `/settings` 路径上做检查（会话流式输出时那次 DOM 抖动不参与）。
  // ======================================================================
  var OC_TERM_SIZE_ATTR = "data-oc-term-size-row";

  /**
   * 上游的界面语言：`opencode.global.dat:language` 存的是**带 locale 字段的编码对象**
   * （见上游 `language-*.js`：`decode(localStorage[…]).locale`），没存过就跟随系统。
   */
  function ocUiLang() {
    try {
      var raw = String(window.localStorage.getItem("opencode.global.dat:language") || "");
      if (raw) {
        var m = /"locale"\s*:\s*"([^"]+)"/.exec(raw);
        if (m) return m[1].toLowerCase();
        try {
          var o = JSON.parse(raw);
          if (o && typeof o === "object" && o.locale) return String(o.locale).toLowerCase();
        } catch (e) {}
      }
      return String(window.navigator.language || "").toLowerCase();
    } catch (e) {
      return "";
    }
  }

  function ocTermTexts() {
    var zh = ocUiLang().indexOf("zh") === 0;
    return {
      title: zh ? "终端字号" : "Terminal font size",
      desc: zh
        ? "只影响手机窄屏的终端（列数随字号变化）。当前 %V%，竖屏约 %C% 列。"
        : "Narrow screens only - fewer pixels per character means more columns. Now %V%, about %C% columns here.",
      apply: zh ? "已保存，正在重新载入…" : "Saved, reloading...",
    };
  }

  /**
   * 当前字号大致能出多少列（只是给用户看的估算）。
   *
   * 设备实测口径（411×914 CSS / dpr 2.625，0.65 档 = 实测 63 列）：
   *   · 面板 clientWidth 394，去掉左右 14px padding = 366 的"内容宽"
   *   · 但控件真正排格子的是 **347**（还留了约 19px 给滚动条/内边距）
   *   · 单元格 = 8.4px × 比例 ⇒ cols = floor(347 / 5.46) = 63 ✅
   * 终端面板不在当前页（正在设置页）时，用视口宽按同样的口径推。
   */
  function ocEstimateCols(scale) {
    try {
      var panel = document.querySelector('[data-component="terminal"]');
      var grid = panel ? panel.clientWidth - 47 : window.innerWidth - 65;
      if (!grid || grid < 40) return null;
      var fam = panel
        ? String(window.getComputedStyle(panel).fontFamily || "")
        : "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace";
      var ctx = document.createElement("canvas").getContext("2d");
      ctx.font = "14px " + fam;
      var m = ctx.measureText("M");
      var baseCell = m && m.width ? m.width : 0;
      if (!baseCell || !isFinite(baseCell)) return null;
      return Math.max(1, Math.floor(grid / (baseCell * scale)));
    } catch (e) {
      return null;
    }
  }

  function ocTermSizeRowStyle() {
    var id = "oc-term-size-style";
    if (document.getElementById(id)) return;
    var st = document.createElement("style");
    st.id = id;
    st.textContent = [
      "[data-oc-term-size-row] [data-oc-term-size] { display:flex; gap:6px; flex-wrap:wrap; justify-content:flex-end; }",
      "[data-oc-term-size-row] [data-oc-term-size] > button { min-height:32px; min-width:56px; padding:0 10px; cursor:pointer;",
      "  border-radius:8px; border:1px solid var(--v2-border-border-base, rgba(128,128,128,.35));",
      "  background:var(--v2-background-bg-layer-02, rgba(128,128,128,.08)); color:var(--v2-text-text-muted, inherit);",
      "  font-size:12px; line-height:1; }",
      "[data-oc-term-size-row] [data-oc-term-size] > button[data-oc-active] {",
      "  background:var(--v2-background-bg-base, rgba(128,128,128,.2)); color:var(--v2-text-text-base, inherit);",
      "  border-color:var(--v2-border-border-strong, currentColor); font-weight:600; }",
      "[data-oc-term-size-row] [data-oc-term-size] > button:active { opacity:.7; }",
    ].join("\n");
    document.head.appendChild(st);
  }

  function ocSyncTermSizeRow(row) {
    var cur = ocTerminalScale();
    var c0 = ocEstimateCols(cur);
    var t = ocTermTexts();
    var title = row.querySelector('[data-slot="settings-row-title"]');
    var desc = row.querySelector('[data-slot="settings-row-description"]');
    if (title) title.textContent = t.title;
    if (desc) {
      desc.textContent = t.desc
        .replace("%V%", String(Math.round(cur * 100)))
        .replace("%C%", c0 === null ? "?" : String(c0));
    }
    var cur0 = row.querySelector("[data-oc-term-size]");
    if (!cur0) return;
    for (var i = 0; i < cur0.children.length; i++) {
      var b = cur0.children[i];
      var v = parseFloat(b.getAttribute("data-oc-size") || "");
      if (Math.abs(v - cur) < 0.001) b.setAttribute("data-oc-active", "");
      else b.removeAttribute("data-oc-active");
    }
  }

  function ocInjectTermSizeRow() {
    // 只在设置页做（会话页每秒都在动 DOM，别跟着凑热闹）
    if (String(window.location.pathname || "").indexOf("/settings") !== 0) return;
    var anchor = document.querySelector('[data-action="settings-terminal-font"]');
    if (!anchor) return;
    var row = anchor.closest('[data-component="settings-row"]');
    if (!row || !row.parentElement) return;

    var row_ = row.parentElement.querySelector("[" + OC_TERM_SIZE_ATTR + "]");
    if (!row_) {
      ocTermSizeRowStyle();
      row_ = row.cloneNode(true);
      row_.setAttribute(OC_TERM_SIZE_ATTR, "");
      // 克隆体里不能留上游的 data-action / 输入框（否则等于给上游塞了第二个同名字段）
      var stale = row_.querySelectorAll("[data-action]");
      for (var i = 0; i < stale.length; i++) stale[i].removeAttribute("data-action");
      var control = row_.querySelector('[data-slot="settings-row-control"]');
      if (!control) return;
      control.textContent = "";
      var wrap = document.createElement("div");
      wrap.setAttribute("data-oc-term-size", "");
      for (var j = 0; j < OC_TERM_SCALE_CHOICES.length; j++) {
        var v = OC_TERM_SCALE_CHOICES[j];
        var b = document.createElement("button");
        b.type = "button";
        b.setAttribute("data-oc-size", String(v));
        b.textContent = Math.round(v * 100) + "%";
        b.addEventListener("click", function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          var val = parseFloat(this.getAttribute("data-oc-size") || "");
          try {
            window.localStorage.setItem(OC_TERM_SCALE_KEY, String(val));
          } catch (e) {}
          var d = row_.querySelector('[data-slot="settings-row-description"]');
          if (d) d.textContent = ocTermTexts().apply;
          // 让点击反馈先落地，再整页重载（URL 带 ?tab=appearance ⇒ 回来还在这一屏）
          window.setTimeout(function () {
            try { window.location.reload(); } catch (e) {}
          }, 120);
        });
        wrap.appendChild(b);
      }
      control.appendChild(wrap);
      row.parentElement.insertBefore(row_, row.nextSibling);
    }
    ocSyncTermSizeRow(row_);
  }

  function startTermSizeRowWatch() {
    if (window.__ocTermSizeWatch) return;
    window.__ocTermSizeWatch = true;
    var queued = false;
    var tick = function () {
      queued = false;
      try {
        ocInjectTermSizeRow();
      } catch (e) {}
    };
    try {
      new MutationObserver(function () {
        if (queued) return;
        queued = true;
        window.setTimeout(tick, 300);
      }).observe(document.body, { subtree: true, childList: true });
    } catch (e) {}
    tick();
  }

  // 设置页是懒挂载的，常驻观察（内部先判路径，非设置页立刻返回）。
  if (document.body) startTermSizeRowWatch();
  else document.addEventListener("DOMContentLoaded", startTermSizeRowWatch, { once: true });
