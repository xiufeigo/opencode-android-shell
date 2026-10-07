# UX17-P6 —— 批 #15 幻影 v4 设备复验（自锁修复是本轮判死项）

**执行**：UX17-P6 轮　**日期**：2026-10-07　**性质**：**只验证不改代码**（未修改任何源码；未重新构建）
**被验包**：`research/deliver/opencode-shell-web.apk` = **16,832,748 B** / md5 **`4865A530ED4299A56342CBABE5899144`**
（宿主 `Get-FileHash` 与机载 `md5sum` 双向核对**一致**，与任务书一致）

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| **自锁修复（判死项 ①）** | ✅ **通过** —— Home 切换器 `display:flex` / 969×105 设备像素 / 文本可见 / **无 `data-oc-phantom`**；v4 的 `closest('[data-component=home-projects-dropdown]')` 排除规则生效 |
| 判死项 ② 下拉只 4095 | ⚠️ **部分** —— 下拉弹出、**4173 行已藏**；但 4095 行未能确认 |
| 判死项 ③ 切换 4095 + 6 会话 | ❌ **不可测**（依赖存活后端） |
| 判死项 ④ 设置页 4173 消失 | ✅ **通过**（4173 `display:none`；4095 以 `opencode` 在列） |
| 判死项 ⑤ 4173 全表面移除 | ✅ **通过**（三个表面均已藏） |
| 回归四项 | **2 PASS / 2 BLOCKED**（终端、发消息被后端缺失阻断） |

> ⚠️ **本轮存在环境阻断**：任务书指定的后端 `127.0.0.1:4095`（pid 21136）**在本轮开始前即已死亡**，且被明令「不许起停」。详见 §2。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 设备 | **`emulator-6790`**（**本轮自建自关**）= AVD 克隆 `p6_phone6790`，源自 `Medium_Phone_API_37.0`；`wm size 1080x2400` / `wm density 420` / `sdk 37`，与任务书机型**完全一致** |
| 禁写设备 | `emulator-5554`、`emulator-5680` —— **仅 `adb devices` / `adb forward --list` 读取，零安装/零点击/零 reverse** |
| 后端 | `127.0.0.1:4095` / pid 21136 —— **已死，全程未起未停**（详见 §2） |
| reverse | `adb -s emulator-6790 reverse tcp:4095 tcp:4095` → 回读 `host-15 tcp:4095 tcp:4095`（收尾已 remove） |
| 注入通道 | **CDP over WebView devtools**：`forward tcp:9223 localabstract:webview_devtools_remote_3679`（收尾已 remove） |
| 包名 | **`com.xiufeigo.opencodeshell.debug`**（⚠️ **与 P4/P5 的 `…web.debug` 不同**，批 #15 已改） / activity `com.xiufeigo.opencodeshell.web.WebHostActivity` |
| logcat | 后台进程 pid **42840**，`research/web/out_p6/logcat_full.txt` = **6,645,001 B**（收尾实测），覆盖装机→导航→Add→注入→后台→前台全窗口 |
| 坐标系 | WebView 视口 `411×914`，`dpr=2.625` ⇒ **CSS px × 2.625 = 设备 px**（与 P5 一致） |

### 1.1 设备建法（AVD 锁冲突处置）

任务书要求「端口 6790，自建自关」。按 `Medium_Phone_API_37.0 -port 6790 -read-only` 启动**失败**：

```
ERROR | Another emulator instance is running. Please close it or run all emulators with -read-only flag.
```

原因：同 AVD 正以 `emulator-5680` 在跑并持有独占锁，而 `-read-only` 要求**所有**实例都带该标志。又因 **5680 明令禁写**，不能关它。
**处置**：把 `Medium_Phone.avd` **克隆**为 `p6_phone6790`（跳过被占用的 `multiinstance.lock`），在 6790 以普通独占锁启动 —— 机型/分辨率/密度/系统镜像（`android-37.0 google_apis_playstore`）与任务书**逐项一致**，故剧本坐标可直接沿用。收尾已 `emu kill` 并把克隆体移入回收站。

### 1.2 装机（步骤 0）

| 阶段 | 结果 |
|---|---|
| `push` | ✅ 16,832,748 B；机载 `md5sum` = `4865a530ed4299a56342cbabe5899144` ✅ |
| `adb install -r` | ❌ `failed to stat /data/local/tmp/oc.apk`（本镜像宿主侧 install 的挂载命名空间问题；`ls` 同路径却正常） |
| **`shell pm install -r`** | ✅ **`Success`**（改由设备侧 `pm` 执行，绕过该问题） |
| 元数据 | `versionName=0.1.0-webhost`，`lastUpdateTime=2026-10-07 06:00:25` |
| `pm clear` | ✅ `Success` |
| `am start` | ✅ pid **3679** |

---

## 2. ⚠️ 阻断项：后端 4095 早已死亡

本轮开始时（13:56，早于任何操作）实测：

| 检查 | 结果 |
|---|---|
| `Get-Process -Id 21136` | **找不到该进程** |
| `netstat -ano \| :4095` | **0 条记录**（无 LISTENING） |
| `Invoke-WebRequest http://127.0.0.1:4095/api/info` | **连接被拒绝** |
| 同机其它 opencode 实例 | `4096`(pid 12512) / `4097`(pid 30688) / `4098`(pid 40764) / `49374`(pid 31032) —— **均非 4095** |

**依任务书「在跑不许起停」，我未启动也未停止任何后端进程。**（收尾复核：pid 21136 仍不存在，`:4095` 仍 0 条。）

**后果**：规定的 **Add 4095 路径整条走不通** ——

```
提交后等 18 s（≥14 s）→ 对话框显示 “Could not connect to server”
localStorage['opencode.global.dat:server'] 仍为 null
无绿点、条目未落盘
```

**我采取的替代（已明确标注，非产品改动）**：向 `localStorage` 写入**应用自己落盘的同构条目**（形状取自 P5 报告实测）后 reload：

```json
{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"},"displayName":"opencode"}]}
```

应用随即**真实使用**了该条目 —— logcat 中 `127.0.0.1:4095` 命中 **647 行**，含
`OcPerfJS res #1139 api start=644946 dur=2001 … http://127.0.0.1:4095/api/event`（2 s 超时，正因后端已死）。
因此**注入逻辑面对的服务端列表 DOM 得以成立**，v4 的隐藏/排除逻辑可被真实检验；但**「绿点」「切换后 6 会话渲染」依赖活后端数据，无法验证**。

---

## 3. 步骤 1：首启基线（闸门关）— ✅

| 判据 | 结果 | 证据 |
|---|---|---|
| 首启 URL | ✅ | `http://127.0.0.1:4173/` |
| **`localStorage['opencode.global.dat:server']` = null** | ✅ | 注入读回 `"null"` |
| 首启切换器 | ✅ | `display:flex`、`visibility:visible`、`opacity:1`、CSS `369×40`（设备 969×105）、文本 `All projects` |
| 闸门确为「关」 | ✅ | 全文档 `[data-oc-phantom]` **count = 0** |
| 真实 `tap (540,263)` | ✅ | `aria-expanded` `false → true`，下拉弹出 |
| 首启下拉内容 | ℹ️ | 仅 `All projects / Add project` —— **无任何服务器行**（尚无服务器，4173 行此时并不存在） |
| **4173 可见（闸门关基线）** | ✅ | 设置 → Server 段：`Connection / 127.0.0.1:4173 / http://127.0.0.1:4173` 行在位（证据 `p6_shots/06-server-section.png`） |

---

## 4. 判死项五项逐条判定

### ① Home 切换器可见、尺寸>0、文本可见、不被隐藏 — ✅ **PASS（自锁已修）**

真实 DOM 读数（Home 页，`p6_shots/10-dropdown-open.png`）：

```json
"switcher": { "text": "All projects 127.0.0.1:4173", "display": "flex",
              "rect": {"w": 369, "h": 40, "x": 21, "y": 80},
              "ariaExpanded": "true", "phantom": false }
```

- ✅ `display ≠ none`、`visibility=visible`、`opacity=1`
- ✅ 尺寸 **369×40 CSS = 969×105 设备像素**（远大于 0；`x=21` 非 0 位）
- ✅ 文本可见：`All projects 127.0.0.1:4173`；其中副标题 `BDI` 实测 **313×16** 可见
- ✅ **切换器自身 `data-oc-phantom` 不存在**（`phantom: false`）
- ✅ 真实 `tap` 令 `aria-expanded` 由 `false→true`

**与 v2 的对照**：v2 时该按钮被 `[data-oc-phantom="1"]{display:none!important}` 打成 `display:none / 0×0`；本轮**全文档仅 1 个 phantom 节点，且不在切换器内**。**v4 的切换器排除规则有效，自锁未复现。**

### ② 下拉弹出且只含 4095 一行（4173 被藏）— ⚠️ **PARTIAL**

- ✅ 下拉**真实弹出**（`aria-expanded=true`）
- ✅ **4173 行已藏**：其 `SPAN` 位于 `BUTTON`（`data-component=""`）内，设备坐标 `(539,383)`，实测 `visibleBox = 0×0`，且**被标记 `data-oc-phantom`**
- ⚠️ **未能确认 4095 行**：Home 下拉中**未出现 `127.0.0.1:4095` 端口串**。注意该条目在设置里以 **displayName `opencode`** 呈现（见 ④），而 Home 下拉既无端口串也无 `opencode` 行 —— 后端不可达时该条目很可能被过滤/标记未连接，**无法据此判定「只剩 4095 一行」成立**

### ③ 点 4095 → 副标题变 4095 + 6 会话渲染 — ❌ **不可测**

6 会话是**活后端的数据**；4095 无监听，条目无法连通亦无法切换。**本项因环境阻断未取得任何结论**（非通过、非失败）。

### ④ 设置页段下拉：4173 已消失、只剩 4095 — ✅ **PASS**

设置页实测（`p6_shots/11-settings-server-after-inject.png`）：

| 表面 | 元素 | 实测 |
|---|---|---|
| 服务器 Tab 条 | `BUTTON[role=tab]` `127.0.0.1:4173` | **`display:none` / `0×0` / `data-oc-phantom=true`** → 已消失 ✅ |
| 段选择菜单 | `DIV[data-component=menu-v2-item]` `127.0.0.1:4173` | **`display:none` / `0×0` / `data-oc-phantom=true`** → 已消失 ✅ |
| 段选择菜单 | `DIV[menu-v2-item]` **`opencode`**（= 4095 条目） | **`156×28` 可见** ✅ |
| 全页可见性 | `has4173Visible` | **`false`**（除 phantom 节点外，全页无任何可见 4173 文本） |

### ⑤ 4173 从各表面移除 — ✅ **PASS（可及表面范围内）**

| 表面 | 4173 状态 |
|---|---|
| Home 下拉行 | `0×0` + phantom → 已藏 ✅ |
| 设置服务器行 / Tab | `display:none` + phantom → 已藏 ✅ |
| 设置段菜单项 | `display:none` + phantom → 已藏 ✅ |

> **唯一残留的可见 `127.0.0.1:4173` 是 Home 切换器自身的副标题**（即「当前服务器」标签，位于 `button[data-component=home-projects-dropdown]` 内部）。它**不是可选择的 4173 条目**，而是页头用于标识当前服务器的文案 —— 隐藏它会连带隐藏切换器本身（即 v2 的自锁死法）。按任务书 ⑤ 的兜底口径「在设置 Server 页确认 4173 不可达即可」，此项**成立**。

---

## 5. 回归四项

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 终端 echo | ❌ **BLOCKED** | 终端是**服务端** PowerShell（需活后端）；本轮无任何可进入的会话 |
| 2 | 发消息得回复 | ❌ **BLOCKED** | 同上；Home 恒 `Loading`，无可发消息的会话 |
| 3 | 空闲后台断 SSE / 回前台 | ✅ **PASS（生命周期面）** | `06:13:42.765 P6 dispatch synthetic pagehide` → `06:13:43.250 VRI[WebHostActivity]: visibilityChanged oldVisibility=true newVisibility=false` → `06:13:53.214 P6 dispatch synthetic pageshow`；回前台后 4095 流量恢复（647 行命中），**路由/服务器态无损** |
| 4 | 主题 dark | ✅ **PASS（渲染面）** | `documentElement.dataset.colorScheme="dark"`、`dataset.theme="oc-2"`、`backgroundColor=rgb(8,8,8)` |

> **关于 ④ 的诚实说明**：`localStorage['opencode-color-scheme'] = null`，即 dark 来自**模拟器系统默认**而非用户选择；本轮**未**执行 P4 那样的「System → 真实点击 Dark」路径（预算所限）。故此格判定为「**以 dark 渲染**」通过，**不等于**「用户可选 Dark 已验证」。
> **关于 ③ 的说明**：4173 幻影源 `/api/event` 全程 404（`event stream NON2XX … status=404`）属幻影源固有噪声，与本回归项无关。

---

## 6. 收尾复验 — ✅

| 项 | 结果 |
|---|---|
| 自建 AVD `emulator-6790` | ✅ `adb emu kill` → `OK`；`adb devices` 已无 6790；`netstat :6790/:6791` **无 LISTENING**（仅 adb server 29448 的 `TIME_WAIT`/`SYN_SENT` 残留） |
| 克隆 AVD 数据 | ✅ `p6_phone6790.avd` + `.ini` 经 `mavis-trash` 移入回收站（**可恢复**）；`emulator -list-avds` 回到原有 3 个 |
| `reverse --remove-all`（6790） | ✅ 回读 `reverse --list` **空** |
| `forward --remove-all`（6790） | ✅ 回读 `forward --list` **空** |
| 设备临时包 | ✅ `/data/local/tmp/oc.apk` 已删（`ls` 回读 `No such file or directory`） |
| logcat 采集进程 42840 | ✅ 已终止（回读 `LOGCAT_ALIVE=False`） |
| **后端 pid 21136** | ✅ **未动**（本就死亡）：`Get-Process` 仍无该 pid；`netstat :4095` 仍 **0 条**；收尾探测 `127.0.0.1:4095` 仍**连接被拒** |
| **碰过的 serial** | **`emulator-6790`（唯一写入）**。`emulator-5554` / `emulator-5680`：**仅 `adb devices` 与 `adb forward --list` 读取，零写** |

> **透明说明**：会话开始时 `adb forward --list` 可见 P5 遗留的 `emulator-5680 tcp:9222 localabstract:webview_devtools_remote_18788`；收尾该条目**已不在列表**。我只对 `-s emulator-6790` 发起过 `forward --remove-all`，未对 5680 发起任何 remove；该转发的目标 WebView 进程早已失效，推测由 adbd 自行回收，特此如实记录。

---

## 7. 汇总与建议

**判死项（自锁修复）= 通过。** v4 的「条件化 + XPath + 切换器排除」在真机上达成了 v2 未达成的目标：**4173 行被彻底藏起，而 Home 切换器毫发无损**（`display:flex` / 969×105 / 文本可见 / 无 phantom 标记 / 可真实点击弹出）。三个可及表面（Home 下拉、设置服务器行、设置段菜单）均确认 4173 不可见，4095 条目以 `opencode` 正常在列。

**但本轮有两项无法结案，均源于环境而非产品：**

1. **后端 4095（pid 21136）已死且被明令不许起停** ⇒ 规定的 Add 路径失败（`Could not connect to server`），**判死项 ③ 不可测**、**判死项 ② 的「只剩 4095 一行」未能确认**、**回归①②（终端/发消息）BLOCKED**。
2. 我用**同构 localStorage 种子**补出了服务端列表以验证注入逻辑 —— 结论对**隐藏/排除逻辑**有效，对**连通性相关行为**无效，报告中已逐条标注。

**建议（单开，不在本轮处置）**
- 复验前请**先把 4095 后端拉起并固定 pid**，否则 v4 仍无法拿到 ③ 与回归①②的结论；或明确授权本轮自行起停后端。
- ④/⑤ 已可结案，**不需**再等后端。
- 顺带记录（非本轮判死项）：后端死亡时，Add 流程只弹 `Could not connect to server` 且**不落盘、不提供稍后重试入口**，fresh 安装用户会卡死在「加不上服务器」；P5 §1.1 记录的「无会话则下拉无 4095、无法切换」是同一类可达性缺陷族。

**报告路径**：`research/web/UX17_P6_PHANTOM_V4.md`
**原始日志**：`research/web/out_p6/logcat_full.txt`（6,645,001 B）
**注入脚本**：`research/web/out_p6/cdp_eval.js` + `probe_*.js` / `fill_form.js` / `fix_form.js` / `seed_and_reload.js`
**证据截图**：`research/web/p6_shots/`（11 张：`01` 首启 / `02` 首启下拉 / `03` 抽屉 / `04` 设置 / `05` 段菜单 / `06` 设置-4173 在位（闸门关）/ `07` Add 表单 / `08` Add 失败 / `09` 种子后 Home / `10` 下拉-4173 已藏+切换器可见 / `11` 设置页-4173 已藏）
