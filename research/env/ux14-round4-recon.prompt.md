# 轮次：UX14-P4 —— 只读探查：注册表存储模式全量转储 + 所有服务器菜单面的"移除/隐藏"出口排查

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：产品负责人确认 4173 内置条目"设为默认"后仍**既删不掉也藏不掉**（"..."菜单只有 编辑/设为默认）。上游 bundle 里 `dialog.server.menu.remove/hide` 文案存在但没渲染——要修掉这条幻影条目，必须先拿到**注册表的确切存储模式**（localStorage 键值全量 / IndexedDB 库名）。
**本轮只读探查，不改任何代码。先建报告 `research/web/UX14_P4_REGISTRY_RECON.md`，每步写盘。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（批 #6）；后端 `127.0.0.1:4095`（opencode/opencode，pid 先查后记）已在跑**不许起停**；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；注入 `am start --es oc_debug_eval "b64:..."`（不能 async）。
- 纪律：只关自建 emulator-6790；不 kill-server；不改代码；不碰 gradle。

## 步骤

### 0. 起机（install -r → pm clear → am start → 5s）——全新首启态

### 1. localStorage 全量转储（注入，一次拿全）
b64：
```
var o={},i; for(i=0;i<localStorage.length;i++){var k=localStorage.key(i); try{o[k]=String(localStorage.getItem(k)).slice(0,400)}catch(e){o[k]="ERR"}} JSON.stringify(o)
```
把每个键名+值（截断 400 字符）原样抄进报告。**重点找**：哪个键里存着服务器列表（找含 `127.0.0.1:4173` 或 `servers`/`list` 结构的值）。再单独注入读 `opencode.settings.dat:defaultServerUrl` 确认首启为 null。

### 2. IndexedDB / 其它存储排查
注入：
```
var r={idb:[],caches:0}; try{indexedDB.databases().then(function(d){})}catch(e){} 
// 同步部分先拿：
try{var req=indexedDB.open('dummy-not-create',1); req.onblocked=null; req.onupgradeneeded=function(e){e.target.transaction.abort()}; }catch(e){}
JSON.stringify({lsLen:localStorage.length, hasIdb:('indexedDB' in window), ua:navigator.userAgent.slice(0,60)})
```
（异步 databases() 拿不到就改用：注入逐个 `indexedDB.open` 常见名探测不可行——改为）**改用 CDP**：`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` → `curl /json` → 用 Node 脚本（临时 `_scratch_idb.mjs`，跑完删）开 CDP，跑 `indexedDB.databases()` 与 `caches.keys()`，把库名/表名全列出。找不到现成脚本就写一个最小 WebSocket 客户端（cx 或裸 ws）。

### 3. 菜单出口排查（真实 UI 全扫）
在**首启态**（只有 4173 一条）与**加 4095 后**（两条）两个状态下分别扫：
1. 设置 → 4173 服务器页 → "..." 菜单（已知：编辑/设为默认）；
2. Home 顶栏服务器切换器（All projects ▾）里 4173 行的 "..." 或长按；
3. 设置左侧导航 4173 行长按 / 右键（模拟器无右键，用长按 `input swipe` 极小位移模拟）；
4. 每条发现的菜单都截图+记录全部菜单项文本。
判定：任何一个面出现「移除/隐藏」即记录路径；都没有 ⇒ 结论"上游无任何出口"，作为 P5 宿主清障的依据。

### 4. （顺手）确认全局流 4173 空转在首启态存在
logcat 10 秒窗口数 `127.0.0.1:4173/api/event` 出现次数。

### 5. 收尾（同前纪律）

## 汇报（最后一行）
注册表键名+存储介质（localStorage/IDB）一句话 · 该键值结构（截断样例）· 菜单出口 有/无（哪几个面）· 4173 空转计数 · 报告路径 · 碰过的 serial。
