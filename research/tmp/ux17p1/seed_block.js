  // ======================================================================
  // UX17-P1 · 幻影服务器清除 **v2** —— 注册表 hidden-map 写入
  //  （整体替代 UX14-P6 / 15-P1 / 15-P2 的 DOM 级隐藏例程；该例程已退役）
  //
  // 【v1 为什么退役 —— UX16-P3 冒烟实测，直接采信】
  //   往注册表写 `hidden["<本机 origin>"] = true` 之后：
  //     · builtin 从可见列表消失；
  //     · Home 上下文**自动**聚焦到用户自己的服务器（4095）；
  //     · 应用立刻可用（无需重启、无需手动点切换器）。
  //   ⇒ 这是**上游自己的机制**：全界面一次生效，白送"自动切上下文"。
  //   而 v1 的 DOM 隐藏（`data-oc-phantom` + XPath + MutationObserver +
  //   display:none）是纯下游补丁：只遮"看得见的界面"、留一堆空状态，
  //   还踩过"把 <html> 打了标记 ⇒ 整页连 <style> 一起消失、屏幕全黑"的
  //   血泪坑，且每次 SPA 重渲染都要全量重扫。全面退役。
  //
  // 【键格式 —— 以 P6/P3 冒烟轮实测为准】
  //   注册表 = localStorage["opencode.global.dat:server"]，
  //   结构 {"list":[…], "hidden":{…}, …}。
  //   `hidden` 的键是**完整 origin 字符串**（P6 冒烟轮写入带 scheme 的
  //   完整 URL 字符串即生效 ⇒ 不是派生 key，也不去 scheme），值为 true。
  //
  // 【首启语义 —— P2 拍板，本轮原样保留】
  //   注册表键**不存在**（= 用户还没 Add 过任何服务器）⇒ 什么都不做：
  //   builtin 行照常可见，它正是官方自带的 onboarding 入口
  //   （P8 实证：Edit 它就能改成自己的服务器）。
  //   注册表键一旦存在 ⇒ 写入 hidden[location.origin] = true。
  //   （取舍照旧记录：用户移除**全部**自己的服务器后 list 为空但键仍在
  //   ⇒ 仍然隐藏 ⇒ 4173 不复活；避免"清空列表 ⇒ 幻影立刻冒出来"。）
  //
  // 【调用时机 —— 选"每轮传感器扫描时幂等重跑"，而不是只挂 load 事件】
  //   本文件由宿主 `addDocumentStartJavaScript` 注入 ⇒ 整段在**文档开始**
  //   就跑完，早于任何页面脚本；而官方读注册表/渲染列表发生在页面脚本里
  //   ⇒ 位置 ①（紧跟本段的一次直调）**已经早于官方 hydration**。
  //   但只跑这一次会漏掉首启那一轮：首启时键还不存在 ⇒ 早退；用户随后
  //   Add 了 4095 ⇒ 键此刻才被创建，而官方列表早已渲染完 ⇒ 只跑一次就
  //   必须重启才生效。⇒ 追加位置 ②：在**回合电源传感器的 fetch 包装**
  //   里每次触发时幂等重跑（fetch 包装 = 每轮扫描，已在位、不新增监听）。
  //   Add 成功后官方必然还有 fetch 过包装 ⇒ 下一轮即写入 hidden。
  //
  // 【不主动改 DOM】写入后不碰任何节点：官方下一次渲染读注册表即生效
  //   （v1 的教训：注入层替上游改 DOM 就是在跟上游长期对抗）。
  // ======================================================================
  var PHANTOM_REGISTRY_KEY = "opencode.global.dat:server";

  /**
   * 幂等写入 `hidden[location.origin] = true`。
   *
   * - 首启（键不存在）⇒ 早退，builtin 可见 = 官方 onboarding（P2 语义）。
   * - 已写入过 ⇒ 早退（幂等；`reg.hidden[origin]` 为真值即视作已隐藏）。
   * - **只碰 hidden 容器**：不写 `list`、不删任何条目。
   * - JSON 损坏 / 结构不对 / localStorage 不可用 ⇒ 全部静默不动。
   */
  function seedPhantomHidden() {
    try {
      var raw = localStorage.getItem(PHANTOM_REGISTRY_KEY);
      if (!raw) return;
      var reg = JSON.parse(raw);
      if (!reg || typeof reg !== "object") return;
      if (!reg.hidden || typeof reg.hidden !== "object") reg.hidden = {};
      var origin = location.origin;
      if (reg.hidden[origin]) return;
      reg.hidden[origin] = true;
      localStorage.setItem(PHANTOM_REGISTRY_KEY, JSON.stringify(reg));
    } catch (e) {}
  }

  // 位置 ①：文档开始即写入（早于官方 hydration 读注册表）。
  try {
    seedPhantomHidden();
  } catch (e) {}
