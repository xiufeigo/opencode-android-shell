# REPORT_ROW_MENU — 5562 / v98 行菜单 parity 复验

> **判定：PASS —— 5 条复验步骤（1–5）全部通过。**
> 主判据成立：行菜单项集合 = **{`Reconnect`(= `Connect` 项), `Delete`}**，`Connect`/`Authenticate` 项**确已补上**。
> `Connecting` 态三格全中：菜单能打开（**正面反证**旧的「Connecting 隐藏 ⋮」）、`Connect` 项 `enabled=false`、`Delete` 仍 `enabled=true` 且实测可派发。
> 清理干净：kebab 计数回到基线 3，`/sdcard` 无本轮残留，`wm` 全程未改无需 reset。

---

## 0. 环境读数

| 项 | 读数 |
|---|---|
| 设备 | `emulator-5562`（Small_Desktop，窗口化桌面） |
| `wm size` | `Physical size: 1366x768` |
| `wm density` | `Physical density: 160` |
| `wm` 是否被本轮改动 | **否** —— 本轮从未执行 `wm size`/`wm density` 覆写，故无 reset 需求；收尾复读仍为 `1366x768` / `160`（已验证） |
| `mCurrentFocus` | `Window{af023a0 u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}`（全程无 `Application Not Responding`，未触发 reboot） |
| 应用区域判定 | 窗口化桌面，app 窗内坐标一律 ≤ `[0,0][1366,768]`；截图/坐标均只取 app 区域 |
| dump 方式 | `uiautomator dump --compressed` → `adb pull` → 本地解析（**未用 exec-out**） |
| 解析规则 | 同时匹配 `text="…"` 与 `text='…'`；**所有坐标运行期从 dump 解析**，无硬编码点击 |
| 空格输入 | `keyevent 62`（`input text` 遇空格会在 `ssh` 处截断，已实测并纠正） |
| 截图 | `screencap -p` + `adb pull` |
| 禁用按键 | 全程未按 BACK(4)；仅在**无弹层**时用 ESC(111) 收 IME，弹层内未用 ESC |

**进入路径**（`Servers` 面板位于 Settings 的 `SERVERS` tab）：
`Command palette`(116,54) → 输入 `settings` → `Open settings`(683,173) → `Servers` tab(255,535) → `Add SSH server`(1034,189)

**建条目前的基线**：`SSH tunnels` 下 **3 条**既有 SSH 行，每行各有 1 个 `Server actions`（⋮）触发器 —— 这 3 个 kebab 是本轮「列表是否已回到初始态」的对照基准。

---

## 1. 建条目 + 「行确实在 dump 里」的判据 —— **PASS**

**表单**：`Add SSH server` 是**两步**表单。第 1 步只有 `Host or SSH command` 一个输入框，**没有 name 字段**；第 2 步是 `Authentication` → `Password`（必填，红字 `Enter the password for this connection.`）。

第 1 步输入框实读：

```
class=android.widget.EditText clickable=true enabled=true
  bounds=[470,503][878,551] center=674,527
  text='ssh -p 2222 sshtest@127.0.0.1'
```

> **与工单的偏差（须知）**：工单要求 `name=E2ECHK5562m-1`，但**该表单没有 name 字段**，行名由 target 主机名自动派生 ⇒ 实际行名为 **`127.0.0.1`**。本轮以「我自建的、target 为 `ssh -p 2222 sshtest@127.0.0.1` 的那一行」为操作对象，删除时亦以该行名核对。

**提交**：第 1 步 `Add server`(842,583) → 第 2 步口令框 `[470,488][878,536]` 填 `e2ECHK5562m` → 提交，落库后行进入 `SSH authentication` 口令卡（这是条目**确实建成**的第一手证据）。

**⚠ 本轮已按纪律先滚动到列表末尾再 dump**（`swipe 839 680 → 839 300` ×6，滚动容器 bounds 由 dump 解析为 `[479,272][1199,712]`）。

**「行确实在 dump 里」的三重判据**（同一份快照 `rm_t43.xml` 内互相印证）：

| 证据 | 读数 |
|---|---|
| 行标题 | `127.0.0.1` bounds `[494,406][543,422]` center (518,414) |
| 行状态文案 | `Disconnected. The remote server is left running.` bounds `[494,424][748,438]` center (621,431) |
| **行内 `⋮`** | `Server actions` bounds **`[1075,398][1123,446]`** center **(1099,422)**，`clickable=true enabled=true` |
| kebab 总数变化 | 建条目前 **3** → 建条目后 **4** |

> kebab 3→4 的增量与 `127.0.0.1` 行标题、行状态文案同时出现在同一 a11y 树里 ⇒ 该行**不是**「不在树里」造成的假象，后续所有判定都建立在**真实可见**的行上。

---

## 2. 【主判据】行菜单项集合 —— **PASS**

点该行 `⋮`(1099,422) → 菜单弹层自成一个窗口子树，整份 dump 仅 **9 个节点**（`rm_m01.xml`，全文即下表两项）：

| 顺序 | 文案 | 文本节点 bounds | 可点 wrapper bounds | wrapper center | clickable / enabled |
|---|---|---|---|---|---|
| 1 | **`Reconnect`** | `[1097,458][1160,478]` | `[1085,444][1197,492]` | **(1141,468)** | `true` / `true` |
| 2 | **`Delete`** | `[1097,506][1135,526]` | `[1085,492][1197,540]` | **(1141,516)** | `true` / `true` |

**菜单项集合 = { `Reconnect`, `Delete` } —— 两项，不是只有 `Delete`。**

### 文案对应关系（避免误判）

`Reconnect` **就是**本轮要验的 `Connect` 项，不是右侧动作条那个同名按钮。依据：

- `strings_oc_i18n.xml:1384` `<string name="ssh_session_reconnect">Reconnect</string>`
- `strings_oc_i18n.xml:1357` `<string name="ssh_action_authenticate">Authenticate</string>`
- `SSH_ROW_MENU_FIX.md` §1 表格「↳ 文案」行明确写：上游 `menu.tsx:32` 的 `ssh.connect` / `ssh.authenticate` **复用既有 `ssh_action_authenticate` / `ssh_session_reconnect`，零新字符串**

即：上游 `menu.tsx:30-33` 的 `Connect`/`Authenticate` 二选一文案，在本壳落到 `disconnected` 阶段即渲染为 **`Reconnect`**（`authentication` 阶段才渲染为 `Authenticate`）。**修复所声称的「Connect 项已补上」在真机上得到正面证实。**

> 菜单 dump 原文（`rm_m01.xml` 的全部有文本/可点节点，无第三项、无分隔线、无分组标签）：
> ```
>   click=true  en=true  [1085,444][1197,492] c=1141,468 ''
>   click=false en=true  [1097,458][1160,478] c=1128,468 'Reconnect'
>   click=true  en=true  [1085,492][1197,540] c=1141,516 ''
>   click=false en=true  [1097,506][1135,526] c=1116,516 'Delete'
> ```

---

## 3. `Disconnected` 态下 `Connect` 项可点 + 可观测结果 —— **PASS**

### 3a. 可点性（`127.0.0.1` 条目，已证）

该态下两项均 `clickable=true enabled=true`（见 §2 表）。点 `Reconnect`(1141,468) 后**菜单弹层确实关闭**（下一份 dump 里 9 节点的弹层子树消失、行仍在原位），证明 click action 真的派发，不是空节点。

> 但 `127.0.0.1:2222` 被**瞬时 RST 拒绝**（`127.0.0.1` 在 emulator 内指 emulator 自身，2222 无监听），`Connecting` 窗口短于采样下限：6 次 250ms 轮询 + 1 次零延迟贴脸 dump 全部无 `Connecting`，行始终停在 `Disconnected. The remote server is left running.`（`[494,424][748,438]`）。**这是环境侧现象，不是菜单缺陷** —— 故改用黑洞地址复测。

### 3b. 可观测结果（黑洞条目 `10.255.255.2`，已证 —— 闭环）

`Disconnected` 态下打开行菜单（`Reconnect` 文本 `[1097,473][1160,493]`，wrapper `[1085,459][1197,507]` center **(1141,483)**，`enabled=true`）→ 点该项 → 立刻 dump：

```
=== STEP3: after tapping menu Connect -> ===
Connecting to SSH server ; Connecting over SSH… ; Connecting over SSH…
```

**点菜单里的 `Connect` 项，行进入 `Connecting`（`Connecting over SSH…` 立即出现在 dump 里）。**
工单接受的三种可观测结果之一成立 ⇒ **步骤 3 完全闭环**。

> 这一步同时反向确认了 §2 的文案判定：`Reconnect` 确实是**行菜单里的 Connect 项**（点到它就发起连接），不是右侧动作条那个同名按钮。

---

## 4. `Connecting` 态下菜单能否打开 + `Connect` 禁用 + `Delete` 可点 —— **PASS（三格全中）**

### 制造 `Connecting`：黑洞条目

`127.0.0.1` 拒连太快，**改用黑洞地址 `ssh -p 2222 sshtest@10.255.255.1`** 把 TCP SYN 挂住。行落库后稳定停在：

```
10.255.255.1                          bounds=[494,410][570,412]   ← 行标题
Connecting to SSH server              bounds=[914,414][962,430]   ← 动作条按钮已切文案
Server actions                        bounds=[1075,398][1123,446]  ← ★ Connecting 下 ⋮ 仍在
Connecting over SSH…                  bounds=[731,575][876,591]
```

### 决定性读数：菜单打开前先确认 `Connecting` 成立

为杜绝「菜单打开时连接早已 settle」的伪证据，**先 dump 确认 `Connecting` 在屏，再点 ⋮，再 dump 菜单**：

```
=== Connecting present BEFORE menu? ===
Connecting to SSH server ; Connecting over SSH… ; Connecting over SSH…

=== menu dump during Connecting ===  (弹层自成一个窗口子树，共 9 节点)
android.view.ViewGroup  ||false|true |[1085,436][1197,548]
android.widget.ScrollView||false|true|[1085,444][1197,540]
android.view.View      ||true|false|[1085,444][1197,492]   ← ★ enabled=false
android.widget.TextView|Reconnect|false|true |[1097,458][1160,478]
android.view.View      ||true|true |[1085,492][1197,540]   ← ★ enabled=true
android.widget.TextView|Delete   |false|true |[1097,506][1135,526]
```

逐格对照：

| 工单要求 | 实测 | 判定 |
|---|---|---|
| ① 菜单**能打开** | 弹层正常弹出，9 节点，两项齐全；行仍读 `Connecting over SSH…` | ✅ **正面反证**旧判定 `C2 NO KEBAB during Connecting -> cannot open delete menu` —— 本轮**未复现**「Connecting 隐藏 ⋮」 |
| ② `Connect` 项**禁用** | `Reconnect` wrapper **`clickable=true enabled=false`**，文本节点 `[1097,458][1160,478]` | ✅ 对齐 `menu.tsx:31` 的 `disabled={ssh.pending(...)}` ≡ 修复的 `sshRowMenuConnectEnabled` |
| ③ `Delete` **仍可点** | `Delete` wrapper **`clickable=true enabled=true`** | ✅ 对齐 `menu.tsx:46` 无门控 ≡ `sshRowMenuDeleteEnabled` |

### ③ 的行为级补证（不止看 `enabled`，还实测它真的能按）

点 `Delete`(1141,516) → **确认条如期出现，且此刻行仍在 `Connecting`**：

```
10.255.255.1                    bounds=[494,406][570,411]
Connecting to SSH server        bounds=[914,414][962,430]
Connecting over SSH…            bounds=[458,541][581,555]
Cancel                          bounds=[968,534][1011,550]
Delete (确认按钮)                 bounds=[1047,534][1087,550]
```

⇒ `Delete` 在 `Connecting` 态**确实可派发并弹出二次确认**，不止是一个 `enabled=true` 的死节点。该确认条随后被确认，行被正确删除。

### 一条旁证：动作条 vs 行菜单的语义分野（本轮读数里可见）

在同一次 `Connecting` 期内的 dump 中，行内动作条的 `Disconnect` 按钮为 `click=true en=false`（`[984,398][1075,446]`），而同一行菜单里的 `Connect` 项是 `enabled=false`、**`Delete` 仍 `enabled=true`**。这与 `SSH_ROW_MENU_FIX.md` §1「关键区分」所述吻合：动作条按 `!connecting` 门控、行菜单按 `ssh.pending` 门控，**是两件事**，本轮真机上各自独立成立。

---

## 5. 删除流程回归 —— **PASS**

点行菜单里的 `Delete`(1141,516)，确认条如期出现（视口 `1366x768`）：

| 元素 | 文本 | bounds | 说明 |
|---|---|---|---|
| 确认条标题 | `Delete SSH server?` | `[506,462][627,478]` | y 区间起点 462 |
| 确认条正文 | `This removes "127.0.0.1" from the list. This cannot be undone.` | `[506,482][829,496]` | **点名了被删行名** |
| `Cancel` | `Cancel` | wrapper `[954,500][1025,548]` / 文本 `[968,516][1011,532]` | |
| 确认按钮 | `Delete` | wrapper `[1033,500][1101,548]` / 文本 `[1047,516][1087,532]` | |
| 视口 | — | `[0,0][1366,768]` | 确认条整体落在 **y = 462…548**，完全在视口内 |

点确认 (1067,524) 后：

- 目标行 `127.0.0.1` **消失**（`FIND='127.0.0.1'` 仅剩 `127.0.0.1:4095` 一条，bounds `[514,445][599,461]`）
- **删对了行**：确认条正文点名的正是 `127.0.0.1`；同名前缀的 `127.0.0.1:4095`（既有非 SSH server，bounds `[514,445][599,461]`）**存活**，未被误删
- kebab 计数回到 **3**，与建条目前的基线一致

---

## 6. 收尾清理 —— **PASS**

本轮共自建 2 条条目（`127.0.0.1` 主判据行 + 两条黑洞行 `10.255.255.1` / `10.255.255.2`），**全部经行菜单 `Delete` 删除**。

| 检查 | 结果 |
|---|---|
| `E2ECHK5562m*` 行 | `NO MATCH` |
| `127.0.0.1` 自建行 | `NO MATCH`（仅存既有非 SSH server `127.0.0.1:4095`，`[514,445][599,461]`，未被误删） |
| `10.255*` 行（两条黑洞行） | `NO MATCH` |
| 残留 kebab | **3** = 建条目前基线（`1099,308` / `1099,365` / `1099,461`） |
| `/sdcard` 本轮新增残留 | 复查本会话全部前缀（`rm_`/`nv_`/`ad_`/`x_`/`g4`/`h4-7.xml`/`k*.png`/`b*.`）= **NONE**（过程中自建的 `h1.xml` 已主动清掉） |
| `/sdcard` 既有文件 | 未动（非本轮产物一律不删） |
| `wm` 收尾复读 | `Physical size: 1366x768` / `Physical density: 160` —— 本轮**从未覆写** `wm size`/`wm density`，故无 reset 需求 |
| `mCurrentFocus` | `com.xiufeigo.opencodeshell/.OcActivity`（全程无 ANR，未触发 reboot） |

---

## 7. 未闭环 / 仍未覆盖

> 5 条复验步骤（1–5）**全部闭环**。以下是**本轮工单范围之外**的遗留项：

1. **上游菜单其余三项仍缺**（承自 `SSH_ROW_MENU_FIX.md` §7，本轮未动修复、但**已实测确认仍缺**）：两次菜单 dump 均只有 2 个节点级条目，**无**分组标签 `ssh.server.menu.label`、**无** `Set as default` / `Remove default`、**无** `Menu.Separator` 分隔线。需 `domain.defaults` 等价物。
2. **`Connected` 态未覆盖**（`menu.tsx:30` 的 `stage !== "ready"` ⇒ `Connect` 项应**不出现**）—— 真机上无可用 SSH 服务端（`127.0.0.1:2222` 拒连、两个黑洞地址永挂），`Connected` 不可达，该分支无法取证。
3. **`authentication` 态未覆盖** —— 文案应为 `Authenticate`（`ssh_action_authenticate`）而非 `Reconnect`。本轮只跑到 `Connecting` 与 `Disconnected`，`authentication` 态的点法（`Reconnect` 按钮）虽有弹出，但行状态在菜单 dump 前已 settle，未取得干净的同帧读数。
4. **工单的 `name=E2ECHK5562m-1` 无法照办** —— `Add SSH server` 表单**没有 name 字段**，行名由 target 主机名自动派生（我的行名为 `127.0.0.1`）。若工单要求自定条目名，那是一个**尚未核实的产品能力缺口**。
5. **脚本侧遗留**：`Add SSH server` 两步表单在重开后，输入框 y 位移（`[470,203]…` ↔ `[470,503]…`），行菜单弹层位置亦随行位浮动（`Reconnect` wrapper center 在 `1141,468` / `1141,483` 间变动）。**任何固定坐标脚本都会误点**，必须运行期从 dump 解析 —— 这与修复文档 §0 记的「面板不自动滚到末尾」同属导航/脚本侧根因，不在本次工单授权范围。

---

## 8. 判定汇总

| 步骤 | 判定 | 关键读数 |
|---|---|---|
| 1. 建条目 + 行在 dump 里 | **PASS** | 字段实读 `ssh -p 2222 sshtest@127.0.0.1`；kebab 3→4 增量；行标题 `[494,406][543,422]`、状态文案、`⋮` `[1075,398][1123,446]` 三节点同树互证 |
| 2. 【主判据】菜单项集合 | **PASS** | **`{Reconnect(=Connect 项), Delete}` 两项**，均 `clickable=true enabled=true` |
| 3. `Disconnected` 下 `Connect` 可点 + 可观测结果 | **PASS** | 可点已证；点菜单 Connect 项 ⇒ dump 立即出现 `Connecting to SSH server` / `Connecting over SSH…` |
| 4. `Connecting` 下菜单语义 | **PASS（三格全中）** | ① 菜单能打开（**反证**旧 `C2`）；② `Reconnect` wrapper **`enabled=false`**；③ `Delete` wrapper **`enabled=true`** 且实测弹出确认条 |
| 5. 删除流程回归 | **PASS** | 确认条 y 462…548，正文点名 `127.0.0.1`，删对行；同前缀 `127.0.0.1:4095` 存活 |
| 6. 清理 | **PASS** | 3 条自建条目全删；kebab 回基线 3；`/sdcard` 无本轮残留；`wm` 未改无需 reset |

**总判定：PASS。** `SSH_ROW_MENU_FIX.md` §2 声称的「真缺口 = 行菜单只有 `Delete`」在真机上**确认成立于修复前**，而本轮实测**修复已生效**：菜单两项齐全，且 `Connect` 项按上游 `menu.tsx:31` 在 `Connecting` 态禁用、`Delete` 按 `menu.tsx:46` 无门控保持可用。旧工单前提「本壳在 `Connecting` 隐藏 `⋮`」在真机上**未复现**。
