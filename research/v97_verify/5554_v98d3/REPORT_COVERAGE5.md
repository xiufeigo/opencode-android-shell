# v98 D3 真机复验报告（覆盖度补完 · 第五轮：用「落库 ground truth」把行数问题一次定死）

**执行人**：真机复验测试员 ①-D3 覆盖度（mcode）  **日期**：2026-10-03
**设备**：`emulator-5554` / **Pixel_9a**（SDK 37，1080x2424）
**输入**：`5554_v98d3/REPORT_COVERAGE4.md`（第四轮结论：**「提交会落库、但 UI 行数不增」**）

> **纪律自证**：未跑 gradle、未装机、未杀进程（本 app **未做** `am force-stop`）、未碰 `127.0.0.1:5555`
> 及其余 5 台设备、全程**未按 BACK(4)**、**弹层内未按 ESC(111)**、截图一律 `screencap -p` + `adb pull`
> （未用 exec-out）、坐标**全部运行期从 dump 解析**、dump 走 `uiautomator dump --compressed`
> 且用 **XML DOM `GetAttribute`** 解析（读的是解析后的属性值，**天然同时匹配 `text="…"` 与 `text='…'`**）、
> 只动自建条目 `E2ECHK5554d3c*`、边做边写盘。

---

## 0. 结论先说

> ### ★ 本轮把第四轮的核心结论**证伪**了
>
> **第四轮说**：「提交会落库、但 UI 行数不增」，并把「落库成功 + 列表不刷新」列为头号未决高价值项
> （候选根因：行落在别的分组未滚到 / 行 key 归一化导致互相覆盖 / 拨号失败后行被自动回收）。
>
> **本轮的落库 ground truth 说**：**根本没有落库**。
>
> ```
> 提交前  GT_KEYS=5  sig: AXhs57I6=84 AXhs57Kq=76 AXhs57Lt=248 AXhs57LS=68 AXhs57Lu=68
> 提交后  GT_KEYS=5  sig: AXhs57I6=84 AXhs57Kq=76 AXhs57Lt=248 AXhs57LS=68 AXhs57Lu=68
> GT_KEY_DELTA = 0        ← 键集与每个值的长度逐字节不变
> ```
>
> ⇒ **第四轮那三个候选根因全部不成立**（那两行代码根本没被执行到）。
> ⇒ **对账结论：落库 SSH 条目数 = 0，UI SSH 行数 = 0 ⇒ 两侧一致，不是行渲染/刷新缺陷。**

> ### ★ 真正的阻塞点（代码级定位）
>
> 点 `Add server` 之后表单**没有提交**，而是停在红字报错：
>
> ```
> [106,1294][631,1329]  Enter the password for this connection.
> [554,1383][665,1424]  Cancel          ← 表单仍在，没关
> [760,1383][938,1424]  Add server      ← 表单仍在，没关
> ```
>
> 因果链（每一环都有 `文件:行号`）：
>
> 1. `SshDialog.kt:191` `validateSshForm(target, auth, password, privateKeyPath)` 在口令为空时返回错误；
> 2. `SshDialog.kt:192-194` `error = invalid; return` —— **在 `onSubmit` 之前就返回了**；
> 3. 于是 `ServersPanel.kt:320` 的 `sshStore?.putSshEntry(config.toSshEntry())`（落库）**不执行**；
> 4. 也于是 `ServersPanel.kt:324` 的 `sshHost.submit(config, connection) { … }`（建行）**不执行**；
> 5. ⇒ 既不落库、也不建行。**UI 行数不增是"没提交成功"的必然结果，不是刷新缺陷。**
>
> **而口令填不进去的原因**：表单的**口令 `EditText` 从不进 a11y 树**
> （本轮 5 次快滑 + 第四轮 6 个滚位 = **两轮共 11 个滚位，一次都没出现过**）
> ⇒ dump 驱动的测试台**没有任何办法给它赋值** ⇒ 提交永远被同一个门挡住。

> ### ★ 顺带纠正第四轮的一处误读
> 第四轮 §3.3 表格里的「口令卡 ✅ 弹出」——**那不是行级口令卡，是表单自己的 `Password` 段**。
> 本轮证据：报错态下 `Password` 出现在 `[138,994][296,1035]`（认证选择器）与
> `[106,1094][264,1135]`（字段标签）**两处**，都在**表单卡内部**，且表单从未关闭。
> 第四轮据此推断「`putSshEntry` + 拨号都执行了」是**错的**。

---

## 1. 环境读数

```
$ adb devices                     → emulator-5554 在列（另有 5556/5558/5562/5564/5600，本轮一律未碰）
$ adb -s emulator-5554 shell getprop ro.product.model → sdk_gphone16k_x86_64 (Pixel_9a AVD)
$ adb -s emulator-5554 shell wm size                  → Physical size: 1080x2424
$ adb -s emulator-5554 shell getprop ro.build.version.sdk → 37
$ adb -s emulator-5554 shell "dumpsys window | grep mCurrentFocus"
    mCurrentFocus=Window{20d1a75 u0 com.xiufeigo.opencodeshell/…OcActivity}   ← 非 ANR，无需 reboot
```

**注意**：`adb` 不在本机 PATH，路径为
`C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`（本轮全部调用走绝对路径）。

### 1.1 存 SSH 服务器的那份 XML —— 已定位

```
$ adb -s emulator-5554 shell "run-as com.xiufeigo.opencodeshell ls -la shared_prefs/"
    oc_home_projects.xml   855
    oc_nav_state.xml        117
    oc_notifications.xml  9516
    oc_server_config.xml  2030   ← ★ 就是这份
    oc_settings.xml         636
    oc_terminal_tabs.xml    577
$ adb -s emulator-5554 shell "run-as com.xiufeigo.opencodeshell ls -la files/ssh/"
    (空目录)
```

---

## 2. A 段：落库 ground truth（★ 本轮的核心读数）

### 2.1 ★ 先说清「数的口径」——**这里有个必须澄清的坑**

任务书假设"按 XML 里的 key/结构数条目数"。**实测这个口径不成立**，两处原因：

**(1) 全部 SSH 条目共用一个 prefs key。**
`ServerConfigStore.kt:302` `private const val KEY_SSH_JSON = "ssh_json"`，
`ServerConfigStore.kt:258-262` `putSshEntry` 是
`prefs.edit().putString(KEY_SSH_JSON, encodeSshEntries(next))` ——
**整份 SSH 条目列表序列化进同一个 key**（`{"entries":[...]}`）。
⇒ **prefs 里的 key 数量永远不会随条目数增长**，数 key 没有意义。

**(2) key 名是密文，不是哈希，无法按名字定位 `ssh_json`。**
本轮实测尝试 `base64(SHA-256("ssh_json"))` 与 XML 里的任何 key **都不匹配**
（推算是 `yGZewI7D7wt25NXwg2iXsc/9rlDUll9Jcu8vxo0QE3M=`，实际 5 个 key 里没有它）。
原因是 AndroidX `EncryptedSharedPreferences` 用 **Tink AES-SIV** 加密 key（确定性加密），
不是哈希。key 密文长度实测 31/32 字节 ⇒ 明文（逻辑 key 名）长度 15/16 字节，
而 `ssh_json` 只有 8 字节，**从长度分布看这 5 个 key 都不是 `ssh_json`**。

⇒ **本轮采用的口径（可机读、无需解密）**：
> **GT = `<string>` 子元素中**非 `__androidx_security_crypto_*` 的条目数**；
> 写入与否用「键集 + 每个值的长度签名」判断。**
> 因为 `ssh_json` 是唯一一个会随 SSH 条目增删而改变长度的 key，
> **它的长度不动 = 没有任何 SSH 条目被写入**。

### 2.2 ★ 每一轮提交后的落库条目数（**签名逐字节不变**）

| 时点 | 动作 | `GT_DATA_KEYS` | 签名（`key前缀=vlen`） | 判读 |
|---|---|---|---|---|
| T0 | 进入面板、任何操作前 | **5** | `AXhs57I6=84 AXhs57Kq=76 AXhs57Lt=248 AXhs57LS=68 AXhs57Lu=68` | 基线 |
| T1 | 填完 host（`SENTINEL host = 'ssh -p 2222 sshtest@127.0.0.1'` 逐字符相等） | 5 | 同上 | 未写入（预期，尚未提交） |
| T2 | 点 `Add server`（`anc_en=true`，`anc=[723,1340][975,1466]`）之后 | **5** | 同上 | **未写入** |
| T3 | 关表单 + 7 步滚动遍历之后（终态） | **5** | 同上 | **未写入** |
| — | **`GT_KEY_DELTA`** | **0** | 键集与全部值长度**完全一致** | **本轮零落库** |

5 个 key 的完整读数（`vlen` = `<string>` 值文本长度）：

```
AXhs57I6NMPf3lLqQntVhk7RMWHThg1Y               vlen=84
AXhs57KqmMxUXQA/h1gxbGu9ZbW7vQQstN5gTAw=       vlen=76
AXhs57LtxFiMzuRKXncVINkjQVwrBeyo4LMsOXX7       vlen=248     ← 最长，疑为 list_json（WS 服务器列表）
AXhs57LSPMV3p/s5b4yYCmNPfPKauPLeXbU6IgM=       vlen=68
AXhs57LuvfruWwVlex3zms2omJFEPjSzr8CX2zY=       vlen=68
（另有 __androidx_security_crypto_{key,value}_keyset__ 两个基建 key，不计入）
```

> **★ 预判一个会被问到的问题**：「5 个 key 对 1 行，是不是也对不上？」
> **不是。** 这 5 个是**prefs 的 key 个数**，不是条目数；条目数在 `ssh_json` 的密文**内部**，
> 不可读。1 行 UI 对应的是其中一个 key（`vlen=248` 的 `list_json`）里的 1 个元素。
> **两侧的正确对账对象是「SSH 条目数 vs SSH 行数」，不是「key 数 vs 行数」。**

### 2.3 落库 SSH 条目数 = **0** 的第二条独立证据

`ServersPanel.kt:114-118` 每次**新组合**都会
`LaunchedEffect(sshStore) { sshStore?.sshEntries().orEmpty().let { if (it.isNotEmpty()) sshHost.seed(it) } }`
—— 即**新开面板 = 把持久层的内容重播成行**（落库 ground truth 被"显影"成 UI 行）。

本轮**多次重新进入 Servers 面板**（导航循环 + 表单关闭后），**每一次都只有 1 行 WS、0 行 SSH**。
⇒ `sshEntries()` 返回空 ⇒ **持久层里没有 SSH 条目** ⇒ 与 §2.2 的签名不变互相印证。

---

## 3. B 段：UI 行数（滚动遍历）

### 3.1 遍历表（进入面板后，每步 dump 的行名去重）

滚动：**`input swipe 540 1850 540 1750 80`（100px / 80ms，落点在列表体内）**，共 7 步：

| 步 | 分组标题 | 该步新增行名 | 去重累计 |
|---|---|---|---|
| step0 | `Servers` | （WS 行 `10.0.2.2:4095`，见 §3.2 权威读数） | 1 |
| step1 | `Servers` | 无新增 | 1 |
| step2 | `Servers` | 无新增 | 1 |
| step3 | `Servers` | 无新增 | 1 |
| step4 | `Servers` | 无新增 | 1 |
| step5 | `Servers` | 无新增 | 1 |
| step6 | `Servers` | 无新增 | 1 |

> **⚠️ 本表的一处工具缺陷（如实登记）**：我写的行标题匹配器对 **WS 行失效** ——
> WS 行的 ⋮ 是**垂直居中**在行上（`[780,1093][906,1219]`，中心 y=1156），
> 而行名 `10.0.2.2:4095` 在 `[254,1115][474,1156]`，**并不在 ⋮ 上方**，
> 于是被"标题须在 kebab 之上"的规则滤掉。SSH 行的 ⋮ 在行下方（不受影响）。
> ⇒ 脚本自动计数给出 `UI_ROWS_TOTAL = 0`，**与真值 1 不符**。
> **下表的 1 行是改用两项独立方法取得的，不是采信这个脚本输出。**

### 3.2 ★ UI 行数的权威读数（两法独立一致）

**(a) a11y 全量普查**（`c5_base.ps1` → `c5_srvchk0.xml`）：

```
面板标题   [148,924][507,971]   Servers
Add SSH    [544,927][801,968]  Add SSH server
────────── 分组：只有 Servers（无 SSH tunnels 分组）──────────
行名       [254,1115][474,1156]  10.0.2.2:4095      ← x=254 ⇒ WS 行缩进
副标题     [254,1161][511,1196]  v2.0.18 • opencode
标签       [674,1138][769,1173]  Default
该行 ⋮     [780,1093][906,1219] @ (843,1156)       Server actions
健康点     [201,1115][222,1145]  «Server healthy»
```

**(b) `screencap -p` + `adb pull` 截图**（`c5_fin_panel.png`）：面板内**只有一张卡片**，
`10.0.2.2:4095` / `Default` / 绿点 / ⋮；**卡片下方整片空白，无第二行、无 `SSH tunnels` 分组标题**。

> **★ 判据：SSH 行有 `Reconnect` / `Disconnect` 两个按钮 + `SSH tunnels` 分组标题；WS 行没有。**
> 普查里**既无 `SSH tunnels`、也无 `Reconnect`/`Disconnect`** ⇒ **SSH 行数 = 0**。

### 3.3 B 段小结

| 项 | 值 |
|---|---|
| **UI 可见行数（合计）** | **1** |
| 其中 **SSH 行** | **0** |
| 其中 WebSocket 行 | **1**（`10.0.2.2:4095`，他人条目，**零误动**） |
| 面板内 `E2ECHK5554d3c*` 节点数 | **0** |

---

## 4. C 段：对账结论

### 4.1 ★ 对账表

| 对账项 | 落库（prefs） | UI（a11y + 截图） | 是否一致 |
|---|---|---|---|
| **SSH 条目数 / SSH 行数** | **0** | **0** | ✅ **一致** |
| WS 服务器行 | （`list_json` 内 1 元素，密文不可读） | 1 | ✅ 一致 |

### 4.2 是不是缺陷？—— **不是第四轮说的那个缺陷**

任务书的三条判定分支对应：

| 分支 | 结论 |
|---|---|
| 「落库数增长而 UI 行数不增长」 | ❌ **不成立**。落库数**没有增长**（签名逐字节不变，`GT_KEY_DELTA=0`） |
| 「UI 行数增长而落库数不增长」 | ❌ **不成立**。UI SSH 行数恒为 0 |
| **两者一致** | ✅ **本轮落此分支** ⇒ **不是列表渲染/刷新路径的缺陷** |

⇒ 第四轮列为头号未决的三条候选根因，**逐一排除**：

| 第四轮候选根因 | 排除依据 |
|---|---|
| ① 行落在别的分组（`Servers` vs `SSH tunnels`）而未滚到 | 7 步遍历 + 截图：面板内**根本没有** `SSH tunnels` 分组 |
| ② `ServersPanel` 的行 key 取自归一化字段导致互相覆盖 | 覆盖需要 `sshHost.submit` 执行；`SshDialog.kt:192` 提前 return，**该函数从未被调用** |
| ③ 拨号失败后行被自动回收 | 同上，`SshTunnels` 列表从头到尾没被写过 |

**顺带把 `ServersPanel.kt:105` / `SshTunnelHost.kt:82` 这段"可疑代码"也证清**：
`SshTunnelHost.kt:82` 的 `filterNot { it.config.id == config.id } + 新行` 确实会对
**同 id** 的二次提交做**替换而非追加**；但 `SshDialog.kt:132`
`val connectionId = remember { UUID.randomUUID().toString() }` 是**每个 dialog 实例一个随机 UUID**，
每次重新开表单都是新 id ⇒ **跨次提交不会撞 id**。
（**同一次提交内重复点 `Add server` 会撞 id**，但那是同一行被刷新，不是行数问题。）

### 4.3 ★ 那真正的缺陷是什么（已定死，代码级）

> **缺陷（可访问性，阻塞功能）：`Add SSH server` 表单的**口令输入框**永远不进入 a11y 树，
> 导致该必填字段无法被任何基于无障碍树的客户端（含本测试台）填写，
> 进而 `validateSshForm` 恒返回 `Enter the password for this connection.`，
> **`SshDialog` 永远提交不了 ⇒ 通过 UI 根本无法新建任何 SSH 条目。**

证据：
- 表单 body 是**嵌套 `ScrollView`**（外层面板 `[43,800][1038,1937]`，内层表单 `[43,953][1038,1329]`）；
- 内层视口只有 **376px 高**，而表单内容到口令框约 600px ⇒ **口令框恒在视口外** ⇒ 被语义树剔除；
- 本轮在 `x=1020`（刻意避开所有 `EditText` 的 x 范围 `138..943`，防拖动被文本选择抢走）
  做 **5 次快滑**（100px/80ms）+ 第四轮的 **6 个滚位** ⇒ **共 11 个滚位，`EditText` 数量恒为 2**（host + 被裁成 h=6 的 name 残片），**口令框一次都没出现**；
- 报错文案与红字位置固定在 `[106,1294][631,1329]`，**表单不关、按钮仍在**，即未提交。

#### 4.3.1 ★ 补救尝试：按几何盲点口令框（也失败，且给出一条新事实）

既然字段本身不在树里、但**它的标签在树里**，本轮又试了从标签反推坐标盲点：

- 当 `Password` 标签在场时（实测 `[106,1094][264,1135]`），按第四轮量出的字段几何
  （`label.B + 17` 起、高 126px）推出字段 `[138,1152][943,1278]`、中心 `(540,1215)`；
- 标签不在树里的那一次，改为按 host 框 +200px 字段间距外推，得到 `(540,1449)`。

结果：

- `(540,1449)` 这一次**落在表单卡之外 ⇒ 点击穿透到遮罩，表单被直接关掉**
  （截图 `c5_.png` 证实：表单消失、面板回到 1 行 WS、0 行 SSH）。
  **这是一条可复用的新事实**：表单卡外/被键盘区覆盖的区域，点击会穿透并关掉整个表单。
- **无论哪条路径，`GT_KEY_DELTA` 依然 = 0，签名逐字节不变，`files/ssh/` 仍为空。**
- ⇒ **"经 UI 无法新建任何 SSH 条目"这一判定，经两轮、11+ 滚位、以及本轮的几何盲点补救，仍然成立。**

### 4.4 是否修了？—— **没修，且本轮明确判定"不该由我在本域内修"**

| 项 | 结论 |
|---|---|
| 改动 `ui/servers/**` | **无** |
| 改动 `ui/settings/ServersPanel.kt` | **无**（全程只读） |
| 新增测试 | **无** |
| gradle / 装机 | **均未执行**（符合纪律） |
| 新增用户可见字符串 | **0** |
| `.agent-gradle-lock` | **未抢**（无改动即无需跑闸门） |

**为什么判定"不在本轮修"（避免越界，也避免引入安全问题）**：

1. 唯一能"让它建得出条目"的改法是**放宽 `validateSshForm` 允许空口令** ——
   但口令是 SSH 的**安全边界**，为一个测试台的需求去削弱它是**错误的修法**。
2. 真正的修法是**布局/可访问性侧**：让口令框进入可滚动可聚焦的语义树
   （例如让内层表单 `ScrollView` 有足够高度、或把口令字段移出被裁剪区）——
   这要改 `SshDialog.kt` 的布局与文案，**超出"在 `ui/servers/**` 内最小修复"的授权**。
3. 第四轮已把该现象登记为**待裁决非功能缺陷**；本轮把它从"疑似"升级为
   **"已定死、且已证明阻塞主功能（无法新建条目）"**，**请 Lead 单独裁决并立 issue**。

---

## 5. D3-3 / D3-4 判定

### 5.1 D3-3（第 3/4 行 ⋮→Delete→确认条）—— ❌ **未验到（前置条件被证明不可达）**

| 项 | 判定 |
|---|---|
| 第 3 行 | ❌ 未验到 —— 面板最多 1 行（他人 WS 行），且**本轮无法新建任何行** |
| 第 4 行 | ❌ 未验到 —— 同上 |
| 前提：建成 4 行 | ❌ **未达成，且本轮已证明经 UI 不可达**（不是"没做到"，是"做不到"） |

> **⇒ 不编造 y 区间 / 视口 bounds / 删对行的断言。**
> 第四轮给出的那组确认条几何（`CONFIRM_BAR_Y=[1614,1831]` 等）**本轮未复现，也未推翻**，
> 本轮**没有拿到新的确认条读数**。
> **面板 body 行容量 N 仍未测得**（造不出 ≥4 行，几何法需要先有行）。

### 5.2 D3-4（`Connected` / `Connecting` / `Authentication required` 三态）—— ❌ **三态全部未验到**

| 行态 | 判定 | 原因 |
|---|---|---|
| `Connected` | ❌ 未验到 | 无 `Connected` 行存在；`E2ECHK5554_provision_key.ps1` **本轮未执行**（连行都建不出，铺钥无对象） |
| `Connecting` | ❌ 未验到 | 无行可 `Reconnect`；第四轮已证该瞬态短于 `uiautomator dump` 采样周期，需改 `screencap` 连拍 |
| `Authentication required` | ❌ 未验到 | 本轮**从未走到拨号**（提交就没成功）；第四轮记录的"口令卡"经本轮证实是**表单的 `Password` 段**，非行级认证卡 |

> **本轮对 D3-4 的实质贡献**：**排除了一种假象**。
> 第四轮据"口令卡弹出"推断"拨号已执行"；本轮证明那个"卡"是表单自己的字段区，
> **三态在本轮连前置条件都不具备**，不应再按"已拨号但状态抓不到"来排查。

---

## 6. 清理

| 项 | 结果 | 说明 |
|---|---|---|
| 自建条目 `E2ECHK5554d3c*` | ✅ **0 条残留** | 面板内 `E2ECHK5554d3c*` 节点数 = **0**；且本轮**零落库**（`GT_KEY_DELTA=0`），prefs 内不可能有条目 |
| 他人条目 `10.0.2.2:4095` | ✅ **零误删，仍在** | 终态截图 + a11y 普查：仍在、`Default`、绿点 `Server healthy`、⋮@`(843,1156)` |
| 表单弹层 | ✅ 已关闭 | 用**运行期解析**出的表单 `Cancel`（与 `Add server` 同 y 带、x<760）`@(610,1404)` 关闭 |
| `/sdcard/c5`（本轮 dump/截图暂存） | ✅ **已删（终态复核两次）** | §4.3.1 的盲点补救实验重建过一次，已再次 `rm -rf`；终态 `ls -d` → `ls: /sdcard/c5: No such file or directory`，且 `ls /sdcard \| grep -c '^c5'` → **0** |
| `/sdcard` 其它内容 | ✅ 本轮**未新增** | 目录内是历轮产物（`CL3_*`/`CL4_*`/`v3*`/`tB*` 等），按纪律**未删** |
| `files/ssh/`（宿主私钥目录） | ✅ **空且未动** | 终态复核 `run-as … ls -A files/ssh/` → **空**（本轮从未铺钥，因为连行都建不出） |
| reverse 端口 | ✅ 未改动 | |
| 模拟器进程 / 本 app | **未杀**；本 app **未做** `am force-stop` | |
| gradle / 装机 | **均未执行** | 符合纪律 |
| `127.0.0.1:5555` 及其它设备 | **全程未碰** | 只操作 `emulator-5554` |
| BACK(4) / ESC(111) | **全程未按** | 关表单用运行期解析出的 `Cancel` 坐标 |
| `ResetFaillock` | — | 本轮无需重置口令（**从未拨号**），故未尝试 |

---

## 7. 未闭环

1. 🚨 **【最高优先，承第四轮并已证伪其前提】"提交会落库但行数不增"不成立** ——
   实测**零落库**（签名逐字节不变）。第四轮三条候选根因全部排除。**根因改判为 §4.3 的表单口令字段不可达。**
2. 🚨 **【新登记，已定死】`Add SSH server` 表单口令框恒不进 a11y 树 ⇒ 经 UI 无法新建任何 SSH 条目**。
   11 个滚位（两轮）验证。**请 Lead 立 issue 裁决**（布局/可访问性侧修，**不得**放宽口令校验）。
3. ❌ **D3-3 第 3 行 / 第 4 行** — 未验到（**前置已证明不可达**，非未做）。
4. ❌ **A 段"建成 4 条"** — 本轮建成 **0 条**（第四轮建成 1 条，本轮因表单门控连 1 条也建不出）。
5. ❌ **D3-4 三态（`Connected`/`Connecting`/`Authentication required`）** — 全部未验到，**无行可操作**。
6. ❌ **面板 body 行容量 N** — 仍未测得（几何法需要先有 ≥4 行）。
7. 🚨 **未决（承第三/四轮）：`removePersisted` 的 loopback 级联边界**（`ServersPanel.kt:246-250`）——
   本轮**未复现**（无行可删），仍需 Lead 单独立 issue。
8. ⚠️ **计数口径需 Lead 确认**：`ssh_json` 是**单 key 存全量 JSON**（`ServerConfigStore.kt:302`），
   key 名经 Tink AES-SIV 加密不可反查 ⇒ **"落库条目数"在不改代码的前提下无法直接读出**，
   只能靠"该 key 的密文长度是否变化"间接判断。**若要长期做落库/UI 对账，建议加一个只读的自测钩子或调试输出。**
9. ⚠️ **本轮工具缺陷（如实登记）**：行标题匹配器对 **WS 行失效**（⋮ 垂直居中 ⇒ 行名不在其上方），
   导致脚本自动计数 `UI_ROWS_TOTAL=0` 与真值 1 不符。**SSH 行不受影响**（⋮ 在行下方）。
   下一轮修匹配器：WS 行按"与 ⋮ **同 y 带**"匹配，SSH 行按"在 ⋮ **上方**"匹配。
10. ⚠️ **新事实：点表单卡外/被键盘覆盖区会穿透关闭整个表单**（§4.3.1，`(540,1449)` 复现）。
    第四轮 §2.4 记的"点表单卡空白右边距关不掉键盘"是**另一种**表现（那里不关表单）。
    **两者要分开**：卡内空白 = 不响应；卡外 = 关表单。下一轮填表必须把盲点限制在卡内。
11. ❗ **本轮改动的代码：无。** 未跑 gradle，未装机。

---

## 8. 证据文件（`research/v97_verify/5554_v98d3/`）

| 文件 | 内容 |
|---|---|
| `c5lib.ps1` | 本轮工具库：`A`/`Sh`/`Dump`(compressed)/`Cap`(screencap+pull)/`AllNodes`/`Attr`(DOM，读解析值⇒双引号单引号通吃)/`ParseBounds`/`KebabList`/`RowTitleFor`/`FastScroll`/`PickSwipeY`/`TapXY`/`TypeS`/`KeyEv` |
| `c5_census.ps1` | 全量 a11y 普查（分组/可滚动/行级 kebab/全部文本） |
| `c5_nav.ps1` / `c5_tab.ps1` / `c5_tab2.ps1` / `c5_tab3.ps1` | 进入 Settings → 横滑 tab 条 → 打开 Servers 面板（**每次 tap 前重新解析坐标**，避免 tab 条滑动后的陈旧坐标） |
| `c5_base.ps1` | ★ §2.1/§3.2(a) 落库 key 表 + 面板权威普查（`c5_srvchk0.xml`） |
| `c5_submit.ps1` / `c5_v2.ps1` / `c5_v3.ps1` | ★ 提交链路：填 host（`MOVE_END`+50×`DEL` 清空，`SENTINEL` 逐字符回读）→ 尝试暴露口令框 → 点 `Add server` → GT 前后对比 |
| `c5_final.ps1` | ★ 终态：运行期解析 `Cancel` 关表单 → 7 步滚动遍历 → 残留自建条目扫描 → `/sdcard/c5` 清理 |
| `c5_gt00.xml` | 落库 XML 原始快照 |
| `c5_formnow.xml` / `c5_E2ECHK5554d3c-r1_post.png` | ★ 表单报错态（`Enter the password for this connection.`、`Add server` 仍在） |
| `c5_fin_panel.png` | ★ 终态面板截图（1 行 WS、0 行 SSH 的独立佐证） |
