# F7 · 交接文档刷新改动摘要

- **执行 agent**：mavis（F7 · 交接文档维护者）　日期：2026-10-05（Asia/Shanghai）　硬期限：2026-10-07 之前
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **性质**：**纯文档任务**。未碰设备、未起模拟器、未跑 gradle、未改生产代码。
- **写入文件（仅此两个）**：
  1. `HANDOFF_WEB_SHELL.md`（主线交接文档，覆盖更新）
  2. `research/web/HANDOFF_REFRESH_F7.md`（本文件）
- **未碰**：`app/**`、`webapp/**`、`_upstream/**`、`.github/**`、`research/deliver/**`，以及 `research/**` 下任何别人的报告。

---

## 1. 我复核到的两个 md5（`Get-FileHash -Algorithm MD5` 实跑）

| 产物 | 体积 | **我复核到的 md5** | 与派单给的值 | 与 `DELIVER_REBUILD_R2.md` §2.1 | 与 `webapp/build/outputs/apk/**` |
|---|---|---|---|---|---|
| `research/deliver/opencode-shell-web-release-nor8.apk` | **3,975,389 B** | **`76ca064b7de9246ee446f765a9198b37`** | ✅ 一致 | ✅ 一致 | ✅ 逐位相同 |
| `research/deliver/opencode-shell-web.apk` | **5,275,479 B** | **`16c6cf8b7330e5d02879f30333800ad9`** | ✅ 一致 | ✅ 一致 | ✅ 逐位相同 |

**⇒ 两个派单值均复核通过，无需以复核值覆盖。**

顺带把 `research/deliver/` 其余两份一并核了（写进文档对比栏）：

| 文件 | 体积 | md5 |
|---|---|---|
| `opencode-shell-debug.apk`（**原生壳** debug） | 44,198,253 B | `bd21ff21e72e75db62fc371d290f438e` |
| `opencode-shell-release-debugsigned.apk`（**原生壳** release） | 29,393,986 B | `b6073ac63f5d8796708fb269fc855a8d` |

> ⚠️ 注：`app/build/outputs/apk/debug/app-debug.apk` 与 `research/deliver/opencode-shell-debug.apk` **体积相同（44,198,253 B）但 md5 不同**（`04cc6e9d…` vs `bd21ff21…`）⇒ 两份不是同一份字节（签名时间戳差异）。文档中我未把这两份混为一谈。

---

## 2. 新增了哪几节

| 节 | 标题 | 要点 |
|---|---|---|
| **§0.2** | **⚠️ 文档与磁盘的裂缝（本轮发现，需上报）** | 新增。列 5 条裂缝（见本文 §5） |
| **§3** | **接入健壮性、配对、相机（本轮新增/改写的三节）** | 新增的顶层节，把三件事并成一处，但每件独立成小节 |
| **§3.1** | **接入健壮性（错误态）—— 三条路径全 PASS** | 正确口令⇒直接进官方 UI（`-> 200` + `HAS_AUTH_DIALOG=False`）· 错口令⇒「登录没通过」+「用户名或口令不对」+ 三按钮 · 不可达⇒「连不上服务器」+ 友好页。逐条对账：不吐裸 JSON / 不泄露 URL 端口 / **503** / **`no-store`** / **≥48dp 重试**。**单列一段写明「口令不进日志 = 三处核对的结论」**（日志串 / `Result` 字段反射 / dex 字面量扫描） |
| **§3.2** | **F6 修掉的那个缺陷（新功能压坏旧行为的教科书案例）** | 新增。`probeAsync` 匿名 `Call` 顶替带凭据 builder 的完整因果链（行号）· 旧日志 `authHeaderSent` 的欺骗性 · `java.util.Base64` 为何是"让绊线成立的前提" · **并明确保留 V8 那一格"界面 PASS / 判定逻辑 FAIL"的判法** |
| **§3.3** | **配对 / 扫码路径的定性 —— ✅ 上游行为（我们一致）** | 新增。四段代码证据（`web.ts:63` / `entry.tsx:97` / `registry.tsx:213-215` / `routes.tsx:79`）· **关键判据 = `routes.tsx` 是共享路由层，桌面浏览器同一份 bundle 同样不可见** · **需要时如何进入配对**（方式 A = `VITE_OPENCODE_SERVER_MODE=none`；方式 B = 新客户端身份）· 纠正"清 localStorage 就够了"的误解 · **"不改上游行为"是有意为之（一致性优先）** |
| **§3.4** | **相机能力的现状 —— 两件事，必须分清** | 新增。**第一件**能力在宿主里（F3 `gUM=OK … state=live`，但走 `oc_debug_eval` 注入、自建 `<video>`、**F3 自述未走到 `PairingScanner`**）· **第二件**用户可达路径未打通（release 三条退路全断 + 根因是 §3.3）· **第三件**这属上游 fallback 行为。开头置顶「❗ 别把这两件事混成一句」 |
| **§3.5** | **回合常亮 —— 从"从未生效"到"置起 + 释放"** | 新增。V4 死代码 → D1 置起 → D1 阶段 3 兜底失效 → **V5 抓到回归（五种办法全试过全失败）** → F4 字节层认 idle + 心跳不续命 → V6/V8 独立复现。含三条必须一起读的诚实标注 |

---

## 3. 改写了哪几节

| 节 | 改法 |
|---|---|
| **§0.1 当前可交付** | **重写**。md5 全部换成我复核到的值并标注"本轮复核"；旧 md5 改成**按代次分列的作废表**（7 行，含此前散落的 4 个值）；**订正 `bf542769…` 的分类错误**（它是 `oc-host.js` 的 md5，不是 APK 的）；补上 `release` 变体用 debug 包名 + Android Debug 证书这条对外分发风险；补上构建目录副本与 `deliver/` 逐位相同 |
| **§0.3 最新一批验证** | **重写**。去掉过时项，换成三态错误态 / 常亮三段（**并标注"仅对 `bc7f3018…`/`627f8ed8…` 成立"**）/ 配对定性 / N-7 剪贴板 / 上游地基 |
| **§1.1 原"交付产物清单（E1 发布工程）"** | **删除**。该表仍挂着 R2 件（`3,961,217 B / 7b578cee…` 与 `5,145,604 B / b716cbeb…`）并自称"R2 重建、已含 `f989907`"，**这两值已被 `DELIVER_REBUILD_R2.md` §2.1 明令作废** ⇒ 留着就是第二个裂缝。其有效内容（`webapp` release 构建类型 / `-Pwebapp.r8=true` 开关）并入新的 §2，R8 风险并入 §5② |
| **§1 体积对比表** | **改**。`3,948,209 B` → `3,975,389 B`；`8,636,901 B` → `5,275,479 B` |
| **§2 架构与实现** | **补**。把 `ConnectErrorPage.kt` / `BackendProbe.kt` 加进核心文件清单；首启流程补上 `probeThenLaunch()` 的三态分流；闸门命令从 §7.2 并入此处 |
| **§4.2 其它三量** | **改**。首屏换成 G2 的 5 次中位数 1,676 ms；耗电行写明"套件已就绪、拒绝编数字"并指到 §5① |
| **§5 未闭环** | **重写**。改成"**两条硬骨头 + 14 条其余**"的结构：① 耗电 mAh（**必须真机** · 套件两个文件已就绪 · P6 原文"本轮没有任何耗电数字" · CPU −5.6% 判为噪声）② **R8 未产出**（12 项风险 · Tink 静默降级 · 设备验证前不要分发）· 其余照报告补，每条带出处 |
| **§6 血泪教训** | **重写**。从 8 条扩到 3 组 20 条：§6.1 关于"测到了"（含"0 命中 = 空集真命题"、"修一半"、"假绿"、矛盾时以更强信源为准…）· §6.2 关于交付物（命名不许另起后缀 / md5 必须与磁盘一致 / 区分 APK md5 与资产 md5 / 坏字节文件不许文本级往返写回…）· §6.3 设备与安全纪律（8 条硬规矩 + **两条环境坑成表** + 另两条测量坑） |
| **§7 关键文件索引** | **重写**。补入全部新增来源报告（F5/F6/V7/V8/配对定性/结账/常亮/耗电套件/本摘要） |
| **§9 分支与远端** | **补**一条：`origin/v2` 这个 ref 在本 clone **不存在**（`git rev-parse --verify origin/v2` → `fatal: Needed a single revision`），上游证据走 `git show origin/v2 -- …` 或 `_upstream/` 只读检出 |

---

## 4. 复用了哪些来源（都已读过，均以它们为准）

`FIX_F5.md` · `FIX_F6_PROBE.md` · `V7_CLOSEOUT.md` · `V8_VERIFY.md` · `research/audit/PAIRING_ROUTE_VERDICT.md` · `research/audit/PARITY_LEDGER_A4.md` · `FIX_D1.md` · `FIX_F4_KEEPSCREEN.md` · `POWER_ACTIONS_P6.md` · `RELEASE_VERIFY_V5.md` · `RELEASE_VERIFY_V6.md` · `DELIVER_REBUILD_R2.md` · `GAP_CLOSE_G2.md` · `GAP_CLOSE_G3.md` · `research/HOUSEKEEPING.md`。
另核了磁盘现状：`research/deliver/*` 全部 APK、`webapp/build/outputs/apk/**`、`app/build/outputs/apk/**`、`research/perf/{battery_real_device.ps1,BATTERY_HOWTO.md}` 存在性与体积。

---

## 5. 我发现的不一致（**要上报的裂缝**）

### 5.1 磁盘 vs 文档：**派单给的两个 md5 全部复核通过，无裂缝** ✅

### 5.2 文档之间 / 文档内部的裂缝（5 条，已写进 `HANDOFF_WEB_SHELL.md` §0.2）

| # | 裂缝 | 性质 |
|---|---|---|
| **①** | **两个被设备复验过的 release 包从未登记进交付台账** —— V5 复验 `b063148d…`（3,965,033 B）、V6 复验 `bc7f3018…`（3,968,477 B）、V8 复验 `627f8ed8…`（3,975,357 B），**这三个 md5 在 `DELIVER_REBUILD_R2.md` §2.1 的现行表与作废清单里两栏都不在**（那两栏只覆盖 F6/F5/F3/R2 四个代次） | **最需要上报的一条。** 后果：§3.1「错误态三态 PASS」与 §3.5「常亮三段 PASS」**都不是在当前交付包 `76ca064b…` 上验的**。代码差异很小（F6 只改 `BackendProbe` 一处、+32 B），但**严格讲当前交付包本身没有单独跑过这两组结论** |
| **②** | `DELIVER_REBUILD_R2.md` **内部自相矛盾** —— §2.1 宣布 R2 件（`7b578cee…` / `b716cbeb…`）"全部作废，不得再被引用"，同一文件 **§8 交接 diff 与 §11 产物清单仍按旧值陈述** | 文件内部 |
| **③** | **相机权限的取证时间差** —— `PARITY_LEDGER_A4.md`（取证于 02:43–02:45）记 `onPermissionRequest` / `RESOURCE_VIDEO_CAPTURE` / `CAMERA` scoped grep **零命中**；但 `FIX_F5.md`（05:2x）记 `WebHostActivity` 已有 `onPermissionRequest` 授 `RESOURCE_VIDEO_CAPTURE`，`RELEASE_VERIFY_V6.md` 记设备端 `CAMERA: granted=true` | **A4 的 N-2 结论已过期**，需重取 grep 才能重新结账。**注意：N-2 的另一半（用户可达路径未打通）依然成立** |
| **④** | **`dumpsys window` 侧证口径两轮互斥** —— V5 拿到 `fl=KEEP_SCREEN_ON …` 行（0→1→仍 1）；V6 全窗口 grep 计数**恒为 0**（含已确知 `flagSet=true` 的那 43 s）。V6 明确"矛盾时以 `flagSet` 为准" | 引用常亮结论时只能用宿主 `D1 applyKeepScreenOn` 日志这一路信源 |
| **⑤** | **8,636,901 B 这个数字指向两份不同的二进制** —— G3 §4-3 指出 `RELEASE_DEVICE_VERIFY.md` 记的那版 debug `classes.dex` 与今天逐字节相同、**但当时没有 `classes5.dex`** | 引用旧体积数字必须带上这句限定 |

### 5.3 旧版 `HANDOFF_WEB_SHELL.md` 自身的问题（本轮已修）

| # | 问题 | 本轮处理 |
|---|---|---|
| a | §1.1 那张表把**已作废**的 R2 值（`7b578cee…` / `b716cbeb…`）当现行值陈列，并自称"推荐对外分发" | **整节删除**，有效内容并入 §2/§5② |
| b | §0 把 `bf542769…`（13,804 B）列进"已作废的 APK md5" | **订正**：它是 `assets/www/oc-host.js` 的 md5（R2 §2.4：source/debug APK/release APK 三者该资产 md5 均为此值） |
| c | §0 把 `bf542769…` 说成"对应 D1 那份" | **删除**：那是 R2 轮的自查记录，不是 APK |
| d | §1 体积表 `3,948,209 B` / §1.1 `5,145,604 B` | **换成当前值** |
| e | §5 耗电条写"脚本已存在于 `research/measure/`" | **改为** `research/perf/battery_real_device.ps1` + `BATTERY_HOWTO.md`（已核实两文件存在，40,542 B / 21,138 B） |
| f | §5 未提"当前交付包未单独复验结论" | **新增**（对应裂缝 ①） |
| g | §6 血泪教训缺本轮全部新教训 | **重写**（3 组 20 条） |

### 5.4 源文件本身的物理损坏（非本轮造成，**无法修复，已如实标注**）

`research/web/DELIVER_REBUILD_R2.md` **含历史编码破损**：第 88 行（§2.1 标题）、第 101–117 行等多处中文字符**在磁盘上就是坏字节**，任何工具都读不回原字（F6 轮曾因"读成字符串再整体写回"把坏字节洗成替换字符，diff 炸到 160+/152-，已 `git checkout --` 还原；改用**按字节切行**才重做成功）。
⇒ 文档中凡引用该文件处，我**只引用可辨读的数值与英文标识符**，不臆造坏字节处的原文。§2.1 标题的四个坏字节位置已在本文与主文档中标注。

---

## 6. 我没能确认的条目（如实列）

| # | 条目 | 为什么没确认 |
|---|---|---|
| 1 | **`ROUND_END` 标记快路径在设备上是否触发** | F4 §5.4 明确**未被观测到触发**；设备上实际生效的是"心跳不续命 ⇒ 35 s 判据可达"，释放延迟 +42~59 s。抓一次**回合期间**的真实 `/api/event` 帧才能定，需另起一轮 |
| 2 | **30 min 硬天花板** | V5 §6.5 记"未等到"（回合起 `20:11:01`、天花板 `20:41:01`，超预算）；D1/F4 沿用此未闭环。**只有单测覆盖** |
| 3 | **「左边缘抽屉 → Settings → Add server → Scan QR code」是否真不可达** | V7 §1.3b 记为「**未测到**」而非「已排除」—— 探测在设备掉线时中断。**不能声称它不存在** |
| 4 | **V5/V6 复验的包与 F3/F5/F6/R2 四代次的对应关系** | 两个 md5 都不在 `DELIVER_REBUILD_R2.md` §2.1 任何一栏；V5 自称 `e360904` 重建、V6 自称含 F3+F4，**但文件之间没有把这两份包登记进代次表**。我只能如实写"未登记" |
| 5 | **V5 / V6 是否跑过任何自动化测试** | 两文件对 `tests|passed|failed|actionable|BUILD SUCCESSFUL|gradlew|junit|suite` 检索**均零命中**。我写的是"文件中没有"，**不是**"未跑" |
| 6 | **release 与 debug 的 dex 字节级等价** | G2 只做了**方法签名集合**比对（差异 0 条），并明确「"release 的这两个类与 debug 逐指令相同"**未被证明**」；且 5/5 dex 条目**全部不同**。我不把它写成"等价" |
| 7 | **配对判定的 `origin/v2` 行号** | `origin/v2` 在本 clone **不是 ref**（`git rev-parse --verify origin/v2` → `fatal: Needed a single revision`）。`PAIRING_ROUTE_VERDICT.md` §6 的行号取自 **`_upstream/` 工作区检出**。**但因果链与"哪个 git ref"无关**，我在文档中如实标注了取证口径 |
| 8 | **8,636,901 B 旧 debug APK 的逐 entry 明细** | 旧件既不在 git（`.gitignore` 含 `*.apk`）也不在磁盘；G3 只能证明"Δ 唯一可能落在 `classes*.dex`"（上界证明），**产不出明细**。需要一份 8,636,901 B / `8dcffc572ad469f472f61594f12ea990` 的副本 |
| 9 | **N-1 通知静音门控 / A6 策略差异** | 属**待产品裁决**，非技术未确认；但 A4 记为 `OPEN`，我照抄状态 |
| 10 | **耗电 mAh** | 无真机。**按规矩拒绝给任何数字** |
| 11 | **R8 版能否构建成功** | 本轮**未跑 gradle**（纯文档任务）。`WebHostActivity.kt` 的 7 个 `Unresolved reference` 是 R2 轮的记录，是否仍存在**本轮未复核** |
| 12 | **源码中 `WebHostActivity.kt` / `BackendProbe.kt` 的当前行号** | 本轮**未读源码**（写权限不含 `webapp/**`）。文档中引用的行号**全部来自各份报告的记载**，并标了出处；**若源码已被后续轮次改动，行号需重取** |

---

## 7. 自查：没有编造的东西

- **没有编数字**：文中每一个体积、md5、耗时、计数、用例数都指回具体报告路径；`Get-FileHash` 的两个值是我本轮实跑的。
- **没有编结论**：凡是报告里没有的，写"**测不到 + 原因**"或"**未闭环**"或"**文件中没有**"，不写"应该 / 大概 / 可能"。
- **没有改别人的报告**：`git status` 侧只写了这一个文件（主文档 + 本摘要）。
- **没有越权**：`app/**`、`webapp/**`、`_upstream/**`、`.github/**`、`research/deliver/**` 一律未碰；未起设备、未跑 gradle。

---

## 8. 本轮产物

| 文件 | 说明 |
|---|---|
| `HANDOFF_WEB_SHELL.md` | 覆盖更新后的主线交接文档（交接给产品负责人） |
| `research/web/HANDOFF_REFRESH_F7.md` | 本文件（改动摘要） |
