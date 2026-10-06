# UX13 循环收敛报告 —— 功能 / 界面与官方桌面端的差距清单

> **收件人**：产品负责人
> **日期**：2026-10-06（Asia/Shanghai）
> **性质**：**纯文档轮**（UX13-Docs）—— 未改任何代码字节、未跑 gradle、未碰设备/后端、未 commit
> **一句话**：UX13 这一循环把"还差什么"逐项走完并归类；**宿主能修的都修完了**，剩下的是**上游固有**、**架构差异**、**缺环境条件**，以及**你自己那台 http 服务器**——那一项被明确排除在本循环之外，由你收尾。

---

## 0. 循环目标与范围

**目标**：把"功能与界面相比官方桌面端还差什么"逐项走一遍，每项给出 **结论 + 证据 + 归类**，直到剩下的都是**宿主无法单独消除**的项为止 —— 即**收敛**。

**范围**：UX13-P1 ~ P13 共 13 轮（诊断 3 轮、修复 5 轮、复验 5 轮），产物从 UX12 批推进到 **UX13 产物批 #5**。

**用户排除项（本循环明确不做）**：

| 排除项 | 谁排除 | 为什么 | 影响 |
|---|---|---|---|
| **在公网 http 服务器上做端到端验收** | **产品负责人（你）** | 本循环只在 `127.0.0.1:4095` 本机后端上验证；公网那台服务器当前不可用（见 §5 遗留 ①） | "明文放行能否直连真实公网 http 后端"这一项**没有宿主侧证据**，需要你用自己的服务器收尾 |

> ⚠️ **诚实边界**：UX13 **没有任何一项结论是在公网 http 后端上取得的**。选项 C（全局明文放行）的**构建期与包内证据是齐的**（§4），但**端到端实连没有做**，原因就是上面这条排除项。

---

## 1. 逐项结论表（差距清单最终归类）

### 1.1 ✅ 已修复（宿主缺陷，本循环闭环）

| # | 项 | 结论 | 证据报告 | 关键读数 |
|---|---|---|---|---|
| 1 | **B-6** 官方「添加服务器」拒 http | 已修复（UX12 落地，UX13 保持） | `UX12_PLAN_A_FULL.md` | 页面 origin 恒 `http:` ⇒ 上游 `isMixedContent()` 入口闸不触发 |
| 2 | **相机扫码被拒** | **根因 = 可信 origin 白名单缺 4173**；补入后转绿 | `UX13_P3_CAMERA_FIX.md` · `UX13_P5_VERIFY.md` | `GRANTED origin=http://127.0.0.1:4173/`、**`DENIED` 计数 0**、视频 `readyState=4` / `720x1280` **真实出画**（非黑屏） |
| 3 | **通知 · 段 1 回合保活** | 回合中 HOME **只派 blur 不派 pagehide**（SSE 不断流）；空闲 HOME 照旧断流；回合结束补派 pagehide 收口 | `UX13_P7_NOTIFY_FIX.md` | 212 用例全绿 |
| 4 | **通知 · 段 1b 权限死锁** | 首次回合在前台**主动申请** `POST_NOTIFICATIONS`，打破"权限等终帧、终帧等后台" | `UX13_P7_NOTIFY_FIX.md` | `POST_NOTIFICATIONS granted=true`、`USER_SET` 为真·用户点击痕迹 |
| 5 | **通知 · 段 2 权限 shim 失真** | shim 的 `Notification.permission` 是**静态属性、授权后永不更新** ⇒ 改为 `requestPermission()` 回写 + `oc:notifyPerm` 宿主推送真值 | `UX13_P9_NOTIFY_PERMISSION_FIX.md` | 直接 `new Notification` 投递链路端到端验证通过；219 用例 |
| 6 | **通知 · 段 3 后台闸门读数** | bundle 原文钉死闸门判据 `!(granted) \|\| (visible&&hasFocus)` ⇒ 后台读数 shim，**只改读数、零事件派发** | `UX13_P10_BGSTATE_SHIM.md` | **后台通知端到端首成**（点击回会话通）；222 用例 |
| 7 | **通知 · 段 4 冷启竞态** | `__ocSetBackground(true)` 排在 `webView.onPause()` **之后** ⇒ JS 线程被节流；**纯顺序调换**修复 | `UX13_P13_COLD_BG_NOTIFY.md` | onPause → `visibilityState` 翻 hidden：**2768 ms → 11 ms**；**冷启后后台通知复验出** ✅；226 用例 |
| 8 | **页面重载后权限回落** | 每次 `onPageFinished` 按宿主真值重推权限 | `UX13_P11_PERM_REPUSH.md` | 重启后 `Notification.permission` 仍 `granted`；223 用例 |
| 9 | **文件选（onShowFileChooser）** | **整条链路通** | `UX13_P1_CAPABILITIES.md` 步骤 4a | 系统 `DocumentsUI PickActivity` → 回传 → **composer 出现附件缩略图** |

### 1.2 ⛔ 不复现（无法证伪，留档）

| # | 项 | 结论 | 证据报告 | 关键读数 |
|---|---|---|---|---|
| 10 | **B-2** → 到行尾后焦点跳边栏 | **不复现**：焦点全程留在 composer | `UX13_P12_PARITY.md` §1 · `BUG_QUEUE.md` B-2 | 连按 15 次 `keyevent 22`（5 次探测全 `inComposer=true`）；终态 caret `anchorOffset=5`/`nodeLen=5`（**确已抵行尾**）后再 10 次仍不跳出；Enter 发送正常 |

### 1.3 🔺 上游固有（维持观察，宿主不可单独消除）

| # | 项 | 结论 | 证据报告 | 关键读数 |
|---|---|---|---|---|
| 11 | **D5** 冷启首屏无 `New session` | **触发条件被改写为"首帧数据未就绪"**（不是"按钮被删"）；连接就绪后按钮会出现 | `UX13_P12_PARITY.md` §2 | 冷启/Loading 态无；连接就绪后 Home 右上角出现 `+ New session`（`p12_s04_reentry_home.png`） |
| 12 | **D3** Tab 条挤压（360dp） | 与 V14 **一字不差**，未变好未变坏 | `UX13_P12_PARITY.md` §3 | `Terminal` 右缘 ↔ `≡` 左缘净空 24 CSS px ≈ 63 设备 px，补回字形内缩 ≈75 px == V14 |
| 13 | 扫码"用户可达路径打不通" | **上游 connect 页 `fallback` 语义，我们与官方一致，不是缺陷** | §3.3 · UX13 未推翻 | P3 修的是权限闸门，**不是**可达路径 |
| 14 | 4173 的 ~1 次/秒空转 | 上游在无默认服务器时把 SDK base 指向页面 origin，宿主按红线 404 拒绝 | `UX13_P6_NOTIFY_ROOTCAUSE.md` | 不影响功能正确性，**本轮未改**（改它要动红线 3 语义） |

### 1.4 🏗 架构差异（换壳形态的固有代价）

| # | 项 | 结论 | 证据报告 |
|---|---|---|---|
| 15 | **last-route 恢复** | **上下文恢复 ✅ / last-route ❌** —— `AndroidMemoryRouter` 的同源变体把路由全活在**内存 history**，进程被杀即丢失且不落盘；桌面端是 Tauri 窗口、路由由窗口恢复 | `UX13_P12_PARITY.md` §4 |
| 16 | **包体 ×3–×9** | 自带官方前端的代价（自主权换界面一致性），已知情接受 | §0.1 HANDOFF |

### 1.5 🌐 环境受限（缺条件，非缺陷 —— 不是"测不到"就写"通过"）

| # | 项 | 为什么受限 | 出处 |
|---|---|---|---|
| 17 | **耗电 mAh** | 模拟器无电池模型、无 rail 电流。**本仓至今没有任何 mAh 数字** | HANDOFF §5① · `BATTERY_HOWTO.md` |
| 18 | **首屏中位数** | 每格只冷启 1 次，未取中位数；R8 首屏 n=3 不足以定论 | HANDOFF §4.3 · §5 第 21 条 |
| 19 | **对外分发的包名 + 证书** | 现为 `com.xiufeigo.opencodeshell.web.debug` + Android Debug 证书 ⇒ **仅内部灰度** | HANDOFF §5② |
| 20 | 顶部 inset 真挖孔机 | 几何覆盖下 `displayCutout` 恒为 0 | HANDOFF §5 第 27 条 |
| 21 | release ⇄ debug 的 dex 逐条比对 | 只做了方法签名集合比对；dex 字节级不等价 | HANDOFF §5 第 7 条 |

### 1.6 📋 待你裁决 / 用户排除

| # | 项 | 结论 |
|---|---|---|
| 22 | **公网 http 服务器端到端** | **用户排除项**，见 §5 遗留 ① —— 明文已放行（选项 C），剩下的是把那台服务器修好然后你自己实测 |
| 23 | **文件"保存"入口** | 上游 UI **四处穷举未找到**可达 `launchSave` 的控件（如实记录，不硬凑）—— 见 §5 遗留 ⑤ |

---

## 2. 关键决策：选项 C（全局明文放行）

| 项 | 内容 |
|---|---|
| **决策人 / 日期** | **产品负责人**，2026-10-06 |
| **内容** | manifest `android:usesCleartextTraffic="true"` **＋** `network_security_config.xml` `base-config cleartextTrafficPermitted="true"` —— 与**官方桌面端同形态**，可直连**任意** http 后端，换地址不必改 XML 重装 |
| **知情接受的代价** | **所有** http:// 目标一律明文传输，**包括公网 http 目标里的 Basic 口令** |
| **未放宽的约束** | 明文放行 **≠** 放宽证书信任：`trust-anchors` 仍只认系统 CA（`src="user"` 被绊线禁止），自签证书依旧不被接受；混合内容是另一条独立通路，未动 |
| **钉死方式** | `CleartextPolicyTest`（3 用例）。⚠️ **真正生效的是 NSC 而非 manifest**（NSC 存在时覆盖 manifest），故两处必须一致；绊线按"开标签 + 剥 XML 注释"断言以防假绿 |
| **报告** | `UX13_P4_CLEARTEXT_GLOBAL.md` |

> ⚠️ **日后若要改主意，请连同绊线一起改**，不要只改 XML —— 否则 `testDebugUnitTest` 可能报 UP-TO-DATE 静默放过（这正是 P4 §4.2 踩过的坑：改 XML 不动 Kotlin，Gradle **不把这类运行时读取登记为 task 输入**）。**改完务必 `--rerun-tasks`。**

---

## 3. 红线自检汇总

| 红线 | 结果 |
|---|---|
| **凭据扫描各轮 0 命中** | ✅ `aapt2 dump strings`（`password="` / `sk-*` / `api_key="` / `Bearer …` / `token="` / `BEGIN PRIVATE KEY`）逐包扫描 debug / release / releaseR8，**各轮总命中 0**（P10/P11 量得字符串条数 2404 / 2404 / 1322，**两轮完全一致** ⇒ 注入面没有意外膨胀） |
| **`ui/` 官方前端资源字节未动** | ✅ `git status --porcelain -- webapp/src/main/assets/www/ui/` **恒为空**；三份包内 `ui/` 条目恒为 **1613**（`oa/` 1590） |
| **`app/**` 零改动** | ✅ `git status --porcelain -- app/` 为空 |
| **不代理 API** | ✅ `refuseApiPath()` 对 `/api`、`/event` 一律 404，**宿主不做任何转发**；只绑回环 `127.0.0.1` |
| **未 commit** | ✅ 全程仅工作区改动 |
| **未起停 4095 后端** | ✅ 各轮收尾复测 pid **15096** 仍在 LISTENING（同一 pid 未变） |
| **设备纪律** | ✅ 只关自建模拟器（`emulator-6790`）；**未 kill adb server**；收尾遇见的非自建 serial（如 `emulator-5554`）**全程未碰** |
| **本轮（UX13-Docs）** | ✅ **纯文档**：未碰 `webapp/**`、`app/**` 任何字节，未跑 gradle，未碰设备/后端 |

---

## 4. 最终产物批 #5

`research/deliver/`，**`Get-FileHash` 于本轮亲手实测**：

| 产物 | 体积 | **md5** | SHA256 |
|---|---:|---|---|
| `opencode-shell-web.apk`（debug） | **16,867,075 B** | **`6D93ECB1B8E505DAA98438C9CB2DF9FB`** | `50F4A668E86A3734F07BA10EE55678E6AF4E59D84165035EF5C2D6C6C92DDE3C` |
| `opencode-shell-web-release-nor8.apk` | **14,306,922 B** | **`297A71CD5988831EA763BDD4C3BE9DFC`** | `883AACDCADF9089BAED02AC772D1383E1273AC8ACBA6489D93CB9DAE50F8C177` |
| `opencode-shell-web-release-r8.apk` | **12,026,852 B** | **`DD26B0AFC8A4A083DA15E2D15BFB14BA`** | `594BA3056624E6965DF991B95237EB5543C17CDA15EDFAB41850CDB6E189C33C` |

- ⚠️ **三份都是 debug 签名（`CN=Android Debug`）+ 带 `.debug` 包名 ⇒ 仅内部灰度**（对外分发需换证改包名，HANDOFF §5②）。
- **`oc-host.js` 最终 md5 = `232E916B5B16E99A6D94C3C4561A9B8A`（62,186 B）**，源文件与包内一致。⚠️ 该值与 UX12 批的 `8A3B3E93…`（57,236 B）**不是换行符差异、而是不同字节体**（P9/P10 加了权限 shim 与后台读数 shim）—— 引用资产 md5 **必须同时带体积**。
- **相对 UX12 批增量**：debug +71,037 B / nor8 +2,784 B / R8 +2,572 B —— 只加了宿主侧逻辑与两个 XML 旋钮，**前端资源一字未动**。
- **🔴 UX12 批及以下全部作废**，不得再被引用（作废清单见 `HANDOFF_WEB_SHELL.md` §0.1）。

---

## 5. 遗留事项（诚实清单 —— 这轮没解决的，如实列）

**① 公网 http 服务器 —— 需要你收尾（用户排除项）**
- 你那台服务器当前 **502**。⚠️ **地址有一个必须先澄清的矛盾**：`BUG_QUEUE.md` B-6 当时记的是 **`8.138.19.15:4094`**，而你现在给的是 **`8.138.191.15:4094`** —— **少一位、多一位，是两台不同的机器**。**请先确认到底是哪个 IP**，再去修网关。
- 修好后**用产物批 #5 直接测**即可 —— **选项 C 已全局放行明文**，不再有"逐条 domain 加白名单"的限制，加 http 后端不用改代码、不用重装。
- ⚠️ **安全提醒**：选项 C 下**公网 http 的 Basic 口令走明文**。这是你知情接受的代价；若只是内网/自建用途无妨，**但不要把它当作可以放心暴露公网的理由**。

**② 耗电 mAh 与首屏中位数 —— 必须真机/多轮采样**
- 模拟器**无电池模型、无 rail 电流**，前几轮全是代理量。**本仓至今没有任何 mAh 数字**，我们拒绝编。
- 完整跑法见 `research/perf/BATTERY_HOWTO.md` §0（三步命令，跑完把 `out_battery/` 贴回来即可）。
- 首屏中位数同理：每格只冷启 1 次。要下"该选 R8 还是非 R8"的结论需 **n≥5 同机同轮复测**。

**③ D5 / D3 —— 上游固有，维持观察**
- 两条**都复现、都没变**，但**都不是我们能修的**：D5 收敛为"首帧数据未就绪"，D3 与 V14 读数一字不差。**建议归入"定期复查"而非"待修"**。
- 若要彻底解决，路径是**推上游改**，不是在本壳里打补丁。

**④ last-route 恢复 —— 架构差异**
- 上下文恢复 ✅、last-route ❌。根因是 `AndroidMemoryRouter` 把路由活在**内存 history**、进程被杀即丢。
- **本壳不可修**。要修就是**换路由器形态**（例如让路由落盘），那是架构级改动，不属于本循环范围。

**⑤ 文件"保存"入口 —— 上游 UI 未找到可达路径**
- 已真实点过 `Files` 段文件树、文件查看器、查看器 `More options`（只有 Usage / Session details）、消息行复制图标 —— **四处都没有能触发 `launchSave` 的控件**；`/sdcard/Download` 无新文件、logcat 无 `download`/`launchSave`/`setDownloadListener` 行。
- **这既可能是 UI 层面确实没做导出功能，也可能是做在未覆盖的路径上** —— 如实记为"未找到可达路径"，**不硬凑**。

---

## 6. 一句话收口

**宿主能修的都修完了。** 本循环**新修 7 项**（相机 + 通知四段 + 权限重推 + 明文放行）、**证伪 1 项**（B-2 不复现）、**定性 4 项为上游固有**（D5/D3/扫码 fallback/4173 空转）、**认下 2 项架构差异**（last-route / 包体）。**剩下的都缺条件或缺环境** —— 最需要你动手的只有一件：**把那台 http 服务器修好，然后用产物批 #5 去测。**

---

## 附：UX13 报告索引

| 轮 | 报告 | 一句话 |
|---|---|---|
| P1 | `UX13_P1_CAPABILITIES.md` | 能力复验：相机红 / 通知红 / 文件选通·存无入口 |
| P2 | `UX13_P2_NOTIFY_DIAG.md` | 通知死锁链诊断 |
| P3 | `UX13_P3_CAMERA_FIX.md` | 相机可信 origin 加入 4173（反向验证真红 2 条） |
| P4 | `UX13_P4_CLEARTEXT_GLOBAL.md` | **选项 C 落地**（全局明文放行 + 3 条绊线） |
| P5 | `UX13_P5_VERIFY.md` | 相机转绿（真实出画）；通知断点定位 |
| P6 | `UX13_P6_NOTIFY_ROOTCAUSE.md` | 通知死锁链逐环定位（根因） |
| P7 | `UX13_P7_NOTIFY_FIX.md` | 回合感知保活 + 首回合主动申请权限（212 用例） |
| P8 | `UX13_P8_NOTIFY_E2E.md` | 通知端到端验收 |
| P9 | `UX13_P9_NOTIFY_PERMISSION_FIX.md` | 权限 shim 静态属性修复（219 用例） |
| P10 | `UX13_P10_BGSTATE_SHIM.md` | 后台读数 shim + **后台通知端到端首成**（222 用例） |
| P11 | `UX13_P11_PERM_REPUSH.md` | 页面加载完成重推权限（223 用例） |
| P12 | `UX13_P12_PARITY.md` | B-2 不复现 / D5·D3 定性 / 重进恢复（只验证，未改代码） |
| P13 | `UX13_P13_COLD_BG_NOTIFY.md` | 冷启竞态 2768 ms → 11 ms，**后台通知复验出**（226 用例） |
| **本轮** | **`UX13_CONVERGENCE.md`** | **收敛报告（你正在读的这份）** |