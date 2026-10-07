/*
 * OC-FIX · 幻影服务器条目（origin）注册表机制 —— **行为探针**（Node，无需设备）
 *
 * 这不是替代设备复验：它验证的是**我们这一层的读写语义**（写什么键、什么时候写、
 * 幂等、只动 hidden、坏数据不崩、reload 有界）。"上游是否消费 hidden" 必须在设备上验。
 *
 * 跑法（工作目录 = 仓库根）：
 *   node research/web/out_ocfix/origin_hidden_probe.js
 */
"use strict";

const fs = require("fs");
const vm = require("vm");
const path = require("path");

const OC_HOST = path.join(__dirname, "..", "..", "..", "webapp", "src", "main", "assets", "www", "oc-host.js");
const src = fs.readFileSync(OC_HOST, "utf8");

// 只切出注册表机制那一段（与 PhantomServerHideTest 的段边界一致），
// 从而绕开文件上半部分的 DOM/桥接代码，同时**跑的是真源码**。
const start = src.indexOf("var PHANTOM_REGISTRY_KEY");
const end = src.indexOf("function phantomOriginToken");
if (start < 0 || end <= start) throw new Error("段边界找不到：oc-host.js 结构变了");
const registrySrc = src.slice(start, end);

const REGISTRY_KEY = "opencode.global.dat:server";
const ORIGIN = "http://127.0.0.1:4173";

let failures = 0;
function check(name, cond, extra) {
  const ok = !!cond;
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${extra !== undefined ? "  :: " + extra : ""}`);
}

/**
 * 造一个 sandbox。
 *
 * ⚠️ 关键：localStorage 的方法必须**只在 Storage.prototype 上**（浏览器语义）——
 * 我们挂在 prototype 上的观察器才会被 `localStorage.setItem(...)` 命中。
 * 每个 boot 一份独立的 Storage 类，避免跨用例串味。
 */
function boot(opts) {
  const o = opts || {};
  const map = new Map();
  if (o.registry) map.set(REGISTRY_KEY, o.registry);
  const smap = new Map();
  const writes = [];
  const sessWrites = [];
  const reloads = [];
  const timers = [];

  function Storage() {}
  Storage.prototype.getItem = function (k) {
    const m = this.__map;
    return m.has(k) ? m.get(k) : null;
  };
  Storage.prototype.setItem = function (k, v) {
    this.__map.set(k, String(v));
    (this.__isSession ? sessWrites : writes).push([k, String(v)]);
  };
  Storage.prototype.removeItem = function (k) {
    this.__map.delete(k);
  };

  const ls = Object.create(Storage.prototype);
  ls.__map = map;
  ls.__isSession = false;
  const ss = Object.create(Storage.prototype);
  ss.__map = smap;
  ss.__isSession = true;

  const win = {};
  win.location = {
    origin: ORIGIN,
    href: ORIGIN + "/",
    reload() { reloads.push(Date.now()); },
  };
  win.localStorage = ls;
  win.sessionStorage = ss;
  win.setTimeout = (fn) => { timers.push(fn); return timers.length; };
  win.Storage = Storage;

  const doc = {
    readyState: o.readyState || "complete",
    querySelector() { return o.mounted === false ? null : { nodeType: 1 }; },
  };

  const sandbox = { window: win, document: doc, localStorage: ls, sessionStorage: ss, console };
  vm.createContext(sandbox);
  vm.runInContext(
    registrySrc +
      "\n;globalThis.__ocTest = { syncOriginHidden: syncOriginHidden, isOriginHiddenInRegistry: isOriginHiddenInRegistry, watchRegistryWrites: watchRegistryWrites, phantomOrigin: phantomOrigin };\n",
    sandbox,
  );
  const api = sandbox.__ocTest;
  const t = {
    api, ls, ss, reloads, timers, writes,
    raw: () => (map.has(REGISTRY_KEY) ? map.get(REGISTRY_KEY) : null),
    write: (v) => ls.setItem(REGISTRY_KEY, v),
    // 复刻文件末尾的 boot 顺序：先 sync，再装观察器。
    boot: () => {
      const changed = api.syncOriginHidden();
      api.watchRegistryWrites();
      return changed;
    },
  };
  return t;
}

// ---------------------------------------------------------------- 1. 首启
{
  const t = boot({});
  check("首启（注册表键不存在）boot 不改动任何东西", t.boot() === false);
  check("首启 boot 零写入", t.writes.length === 0, `writes=${t.writes.length}`);
  check("首启 isOriginHiddenInRegistry() = false（placeholder 保持可见）", t.api.isOriginHiddenInRegistry() === false);
}

// ------------------------------------------------- 2. 用户有自己的服务器
{
  const userServer = { type: "http", http: { url: "http://8.138.19.15:4094", password: "opencode" }, displayName: "8.138.19.15:4094" };
  const registry = JSON.stringify({
    list: [userServer],
    hidden: {},
    projects: { "http://8.138.19.15:4094": [{ worktree: "/x", expanded: true }] },
    lastProject: {},
    recentlyClosed: {},
  });
  const t = boot({ registry });
  check("有用户服务器 ⇒ boot 写入一次", t.boot() === true);
  check("boot 只写注册表这一个键", t.writes.length === 1 && t.writes[0][0] === REGISTRY_KEY, JSON.stringify(t.writes.map((w) => w[0])));
  const after = JSON.parse(t.raw());
  check("hidden[origin] = true", after.hidden && after.hidden[ORIGIN] === true, JSON.stringify(after.hidden));
  check("list 一字未动", JSON.stringify(after.list) === JSON.stringify([userServer]), JSON.stringify(after.list));
  check("projects / lastProject / recentlyClosed 原样保留",
    JSON.stringify(after.projects) === JSON.stringify({ "http://8.138.19.15:4094": [{ worktree: "/x", expanded: true }] }) &&
    JSON.stringify(after.lastProject) === "{}" && JSON.stringify(after.recentlyClosed) === "{}");
  check("isOriginHiddenInRegistry() = true（DOM 兜底层据此放行）", t.api.isOriginHiddenInRegistry() === true);
}

// --------------------------------------------------------------- 3. 幂等
{
  const registry = JSON.stringify({ list: [{ http: { url: "http://a:1" } }], hidden: { [ORIGIN]: true }, projects: {}, lastProject: {}, recentlyClosed: {} });
  const t = boot({ registry });
  check("已对齐 ⇒ boot 不再写（不制造无谓 reload）", t.boot() === false);
  check("已对齐 ⇒ 零写入", t.writes.length === 0);
}

// ------------------------------------------- 4. 用户删光自己的服务器
{
  const registry = JSON.stringify({ list: [], hidden: { [ORIGIN]: true }, projects: {}, lastProject: {}, recentlyClosed: {} });
  const t = boot({ registry });
  check("list 清空 ⇒ boot 撤掉 hidden[origin]（placeholder 回来）", t.boot() === true);
  const after = JSON.parse(t.raw());
  check("hidden 里不再有 origin", !(after.hidden && after.hidden[ORIGIN]), JSON.stringify(after.hidden));
  check("list 仍为空数组（没塞占位条目）", Array.isArray(after.list) && after.list.length === 0);
}

// --------------------------------------------- 5. 坏 JSON / localStorage 不可用
{
  const t = boot({ registry: "{corrupt json!!" });
  check("坏 JSON ⇒ 不抛异常、不写、判定为未隐藏", t.boot() === false && t.writes.length === 0 && t.api.isOriginHiddenInRegistry() === false);
}
{
  const t = boot({});
  t.ls.getItem = () => { throw new Error("SecurityError"); };
  let threw = false;
  try { t.boot(); } catch (e) { threw = true; }
  check("localStorage 不可用 ⇒ 不抛异常（保守退回首启行为）", !threw && t.api.isOriginHiddenInRegistry() === false);
}

// ------------------------- 6. 运行期：应用自己写注册表（Add 的那一刻）
{
  const t = boot({}); // 首启：还没有注册表
  t.boot();
  check("运行期起点：没有 hidden、没有写入", t.writes.length === 0);
  // 用户 Add 成功 ⇒ 应用自己写注册表（走 Storage.prototype.setItem）
  t.write(JSON.stringify({ list: [{ type: "http", http: { url: "http://8.138.19.15:4094" } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }));
  const after = JSON.parse(t.raw());
  check("Add 那一刻被观察器补写：hidden[origin]=true", after.hidden && after.hidden[ORIGIN] === true, JSON.stringify(after.hidden));
  check("补写不改 list", JSON.stringify(after.list) === JSON.stringify([{ type: "http", http: { url: "http://8.138.19.15:4094" } }]));
  check("排了一次 reload（应用内存要重新 hydration）", t.timers.length === 1, `timers=${t.timers.length}`);
  t.timers.forEach((fn) => fn());
  check("reload 真的被调用", t.reloads.length === 1, `reloads=${t.reloads.length}`);
  // 再次触发（应用后续写项目/lastProject）⇒ 已对齐 ⇒ 不再 reload
  t.write(JSON.stringify({ list: [{ type: "http", http: { url: "http://8.138.19.15:4094" } }], hidden: { [ORIGIN]: true }, projects: { x: 1 }, lastProject: {}, recentlyClosed: {} }));
  check("后续写入（projects 更新）不再排 reload", t.timers.length === 1, `timers=${t.timers.length}`);
}

// ------------------------------------- 7. 未挂载时不 reload（启动期防护）
{
  const t = boot({ mounted: false });
  t.boot();
  t.write(JSON.stringify({ list: [{ http: { url: "http://a:1" } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }));
  check("应用还没挂载 ⇒ 不排 reload（boot 路径负责）", t.timers.length === 0, `timers=${t.timers.length}`);
}

// --------------------------------------------------------- 8. reload 防环
{
  const t = boot({});
  t.boot();
  for (let i = 0; i < 6; i++) {
    // 应用每次都写回"没有 hidden"的版本（模拟 reload 前反复写）
    t.write(JSON.stringify({ list: [{ http: { url: "http://a:1" } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }));
  }
  check("同一次会话最多排 3 次 reload（防环）", t.timers.length <= 3, `timers=${t.timers.length}`);
}

// --------------------------------------- 9. 只认注册表键（不干扰别的键）
{
  const t = boot({});
  t.boot();
  t.ls.setItem("opencode.settings.dat:defaultServerUrl", "http://127.0.0.1:4095");
  t.ls.setItem("opencode.global.dat:language", "zh-CN");
  check("其它 localStorage 键的写入完全不受影响", t.timers.length === 0 && t.writes.length === 2, `timers=${t.timers.length} writes=${t.writes.length}`);
}

console.log(`\n${failures === 0 ? "ALL PASS" : failures + " FAILURE(S)"}  (oc-host.js registry section: ${registrySrc.length} chars)`);
process.exit(failures === 0 ? 0 : 1);
