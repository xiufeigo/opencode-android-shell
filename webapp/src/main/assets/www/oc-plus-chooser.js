/* UX1 · 任务3 · **已作废，本文件不再被加载**
 *
 * 为什么存在过、为什么又作废
 * --------------------------
 * 第一版把「+」选择面板放在这个独立文件里，由 `oc-host.js` 用
 * `fetch("./oc-plus-chooser.js")` + `eval()` 拉起。
 *
 * 设备实测（emulator-6490，2026-10-05 07:43:49，logcat 原文）**这条路走不通**：
 *
 *   W OcWebHost: web[WARNING] [oc-plus-chooser] load failed Refused to evaluate a
 *   string as JavaScript because 'unsafe-eval' is not an allowed source of script
 *   in the following Content Security Policy directive:
 *   "script-src 'self' 'wasm-unsafe-eval' 'sha256-DvjbVLSCqgtwL7bnRLffODAPpW63X4inV+gVnQBZwBw='".
 *
 * 即：fetch **成功**（res #47 200, 1809 B 解码），但官方 UI 的 CSP **没有 'unsafe-eval'**，
 * `eval` 被浏览器拒绝 ⇒ 面板永远装不上。
 *
 * 现在的做法：面板代码**内联**在 `oc-host.js` 里。`oc-host.js` 是宿主用
 * `addDocumentStartJavaScript` 注册的**文档开始脚本**，**不受页面 CSP 约束**，
 * 所以内联是唯一可靠的路（详见 `oc-host.js` 里 `installPlusChooser()` 的注释）。
 *
 * ⇒ 本文件保留在此只为记录这段实测结论；**没有任何代码引用它**。
 */
