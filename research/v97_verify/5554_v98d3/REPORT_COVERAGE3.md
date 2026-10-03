# v98 D3 真机复验报告（覆盖度补完 · 第三轮）— 任务 A 根因 + 任务 B

**执行人**：缺陷猎人 + 修复工程师（mcode）  **日期**：2026-10-03
**设备**：`emulator-5554` / **Pixel_9a**（SDK 37）
**输入**：`research/v97_fix/SSH_DELETE_FIX.md`、`5554_v98d3/REPORT_COVERAGE.md`、`5554_v98d3/REPORT_COVERAGE2.md`
**被验构建**：`lastUpdateTime=2026-10-02 16:18:08`（**比上一轮的 14:58:08 更新** ⇒ 设备上是一份**更新的构建**）

> **纪律**：未跑 gradle（本轮无需改代码）、未装机、未杀进程、未碰 127.0.0.1:5555、
> 全程未按 BACK(4)、弹层内未按 ESC(111)、截图一律 `screencap -p` + `adb pull`、
> 坐标运行期从 dump 解析、共享后端只动自建条目。

---

## 0. 结论先说

> **★ 任务 A 的真因找到了，而且不是自动化抖动 —— 是两个真实的代码事实：**
>
> **(1) `Add server` 在 `target` 为空时是「禁用态」，不是「点了没反应」。**
> `SshDialog.kt:517` → `enabled = !busy && target.isNotBlank()`。
> 截图直证：默认状态下 `Add server` 是**灰底浅字**的禁用按钮。
> ⇒ 前三轮"静默失败"的真相是**按钮根本按不下去**。
>
> **(2) `validateSshForm`（`SshModel.kt:100-110`）还要求口令，但口令是「第二段流程」。**
> 表单提交后**行立刻落库**（`ServersPanel.kt:320` `putSshEntry` 在拨号**之前**），
> 随后拨号撞上 sshd 的密码挑战 → 弹出**行级口令卡**
> （`ServersPanel.kt:342` `SshAuthenticate`，文案 `Enter the password for this connection.`）。
> **它长得极像"第二个表单"，但它是另一套 Composable** —— 这正是第一轮把
> `Password`/`Cancel`/`Add server` 误读成"表单第二段"的原因。
>
> ⇒ **「三次都建不出条目」的真因 = 按钮禁用（target 空）+ 口令在行级卡上不在表单里，
> 前两轮把行级口令卡当成了表单的下一步，于是判定"提交被静默拒绝"。**

**能建。最小成功路径见 §2.3。**

### 0.1 任务 B 判定总表

| # | 断言 | 判定 |
|---|---|---|
| D3-3 | 第 3 行 | ❌ **未验到**（只建成 1 条自建行；第 3 行是他人行，按纪律不删） |
| D3-3 | 第 4 行 | ❌ **未验到**（同上） |
| D3-3 | *（附带）* 低位行确认条几何 | ✅ **拿到 1 组真实读数**（自建行）：`CONFIRM_BAR_Y=[1539,1756]` ⊂ 面板 `[800,1937]`，`TARGET_GONE=True`。**但该行 ⋮@1375 未越折叠线，`bringIntoView` 可能是 no-op ⇒ 不能替代 D3-3。** |
| D3-4 | `Connected` | ❌ **未验到**（自建行从未 READY） |
| D3-4 | `Connecting` | ❌ **未验到** |
| D3-4 | `Authentication required` | ❌ **未验到**（**但已推翻上一轮"认证态不可达"的结论**，认证态可达、造法已写明） |
| 反例 | 只删目标行 | ⚠️ **部分**（`TARGET_GONE=True`、`10.0.2.2:4095` 零误删）**＋ 一条未决疑问：`127.0.0.1:44521` 消失** |
| 修复本体 | D3 有无回归 | ⚪ **无新证据**（未触发越折叠线路径）；上一轮 D3-1/D3-2 PASS **结论不变** |
| 清理 | 自建条目 / `/sdcard/c3` / 他人条目 | ✅ **PASS**（`ResetFaillock` 因设备无 `faillock` 二进制**无法执行**） |
| 代码 | 是否改了 | **未改任何生产代码**（见 §5） |

---

## 1. 环境读数

```
$ adb -s emulator-5554 emu avd name
Pixel_9a                                       ← 与任务书一致 ✅

$ adb -s emulator-5554 shell getprop ro.build.version.sdk
37

$ adb -s emulator-5554 shell "dumpsys package com.xiufeigo.opencodeshell | grep -E 'versionName|lastUpdateTime'"
    versionName=1.4.0
    lastUpdateTime=2026-10-02 16:18:08         ← ★ 比上一轮 14:58:08 更新（新的闸门构建）

$ adb -s emulator-5554 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22                       ← 沿用上一轮，人为把主机端指向 WSL sshd 的 22

$ wsl -d Arch -e bash -lc "ss -lntp | grep -E '(:22|:2222)'"
LISTEN 0 128   0.0.0.0:22   0.0.0.0:*
LISTEN 0 128      [::]:22      [::]:*          ← ★ sshd 在听；上一轮的"无监听"已被修好

$ adb -s emulator-5554 shell "dumpsys window | grep mCurrentFocus"
mCurrentFocus=Window{c2831d6 u0 com.xiufeigo.opencodeshell/…OcActivity}   ← 非 ANR，可正常操作
```

**面板视口基线**：`ScrollView [43,800][1038,1937]` —— 与前两轮**逐像素一致**。
但注意：`Viewport` 函数取的是**最大 scrollable 节点**；Servers 面板打开时它会先命中
**会话列表** `[53,2000][1027,2149]`，需按 `t>=800` 且挑面板内那一个。

---

## 2. 任务 A：为什么建不出条目

### 2.1 证据 A-1：表单打开后的全量 dump（默认态）

证据：`A03_form_default_*.xml` / `.png`

```
表单卡标题  'Add SSH server'                      [106,1412][401,1459]  en=true
标签        'Host or SSH command'                 [106,1524][471,1565]  en=true
标签        'Server name (optional)'              [106,1724][473,1725]  ← ★ 高 1px，被折叠线裁切
Cancel                                           [554,1779][665,1820]  en=true
Add server                                       [760,1779][938,1820]  en=true cl=false
FORM_VIEWPORT = [43,800][1038,1937]
```

**★ 两个必须记下的读数：**

1. **一个 `EditText` 节点都没有。** 三个滚位（scroll 0…4）全量 dump，
   表单区**零个** `android.widget.EditText`。
2. **`Server name (optional)` 只有 1px 高**（`1724→1725`）——
   和 `SSH_DELETE_FIX.md` §1.1 记的"下一行只剩 3px 残片"是**同一类裁切证据**：
   折叠线以下的内容既被裁切、也**从 a11y 树里被剔掉**。

⇒ **字段没进 a11y 树 ≠ 字段不存在**。截图 `A06_formscroll2` 直接证明字段**画出来了**
（可见 `Authentication` / `Password` 选择器 / 口令输入框的圆角边框），
只是落在面板 body 的裁切线以下，uiautomator 抓不到。
**前两轮"表单 dump 里没有认证控件"是取样失败，不是缺陷** —— 往下滚一屏就有。

### 2.2 证据 A-2：★ 默认态点 `Add server` = 按钮是**禁用**的

**截图直证**（`A06_formscroll2_085410629.png`，左下角）：

```
[  Add server  ]   ← 浅灰底 + 淡字，与旁边 [Cancel] 的白底深字形成明显对比
```

点它（`(849,1799)`，dump `enabled=true` 但 `clickable=false`，父容器才是可点体）后：

```
FORM_OPEN_AFTER_DEFAULT = True                 ← 表单没关
VIEWPORT_AFTER          = [43,800][1038,1937]  ← 一点没动
（dump 与点击前逐字段一致：无红字、无新行、无弹层）
```

**根因（源码）** `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt:505-519`：

```kotlin
OcButton(
    label = stringResource(
        if (busy) R.string.ssh_stage_connecting
        else if (connection == null) R.string.dialog_server_add_button
        else R.string.ssh_connect,
    ),
    enabled = !busy && target.isNotBlank(),   // ★ SshDialog.kt:517
    onClick = { submit() },
)
```

> **`enabled` 只看 `target`，不看 `name`、不看口令。**
> ⇒ **只要 `Host or SSH command` 是空的，`Add server` 就是灰的、按不动、且不打任何提示。**
> 这就是三轮"静默失败"的物理原因。**a11y 报 `enabled=true` 是因为那个 TextView 不是按钮本体**，
> 所以只看 dump 会误判 —— **必须看截图**。

### 2.3 证据 A-3：★ 最小可成功路径（本轮实测走通）

| 步 | 动作 | 读数 / 判据 |
|---|---|---|
| 1 | Settings → 横向滑 tab 条 → `Servers` | `IN_SERVERS=True` |
| 2 | 点 `Add SSH server` | 表单卡出现（`Host or SSH command` 可见） |
| 3 | **把表单滚到顶** | 此时 host 输入框 = `[138,1582][943,1708]`，中心 `(540,1645)` |
| 4 | 点该框，**只填 host**：`ssh -p 2222 sshtest@127.0.0.1` | `AFTER role=host text='ssh -p 2222 sshtest@127.0.0.1'` ✅ |
| 5 | **把表单滚到底** | `Authentication` / `Password` 可见；口令框 ≈ `[138,1639][977,1721]`，中心 `(540,1680)` |
| 6 | 点口令框，填口令 | 门 `validateSshForm` 通过 |
| 7 | 点 `Add server` | `putSshEntry` **先**落库（`ServersPanel.kt:320`），**立刻出现新行**；随后拨号 |
| 8 | 拨号撞 sshd 口令挑战 → **行级口令卡**弹出 | `Enter the password for this connection.` + 输入框 + `Cancel`/`Add server` |
| 9 | 在**行级卡**上填口令，再点它的 `Add server` | 认证继续（`keypass123` 有效） |

**★ 第 8 步就是前两轮翻车的地方**：那张卡（`ServersPanel.kt:342` 的 `SshAuthenticate`）
**文案与按钮和表单几乎一样**（`Password` / `Cancel` / `Add server`），
第一轮把它当成"表单第二段"、第二轮干脆没走到，于是得出"提交被静默拒绝"。

**可复制的最短路径**（人也能照做）：

```
Settings → Servers → Add SSH server
→ 填 Host or SSH command = ssh -p 2222 sshtest@127.0.0.1     ← 只填这一项，按钮就亮了
→ 往下滚 → 填 Password = keypass123
→ Add server                                                  ← 一次，行就出来了
→ 若弹出口令卡：在那张卡上再填一次口令 → Add server
```

### 2.4 ★ 那条"拼接脏串"是怎么来的（本轮独立复现）

本轮第一次尝试时，脚本用「`Authentication` 标签的 y」当锚点切分字段
（`REPORT_COVERAGE2.md` §5-3 建议的做法），但**该标签在多数滚位下不在 a11y 树里**
（见 §2.1）⇒ 角色映射退化成"第 2 个字段 = name" ⇒ **name 的字打进了 host 框**：

```
TYPE role=host @540,1645 -> 'ssh -p 2222 sshtest@127.0.0.1'
AFTER role=host text='ssh -p 2222 sshtest@127.0.0.1'                       ← ✅
TYPE role=name @430,1389 -> 'E2ECHK5554d3c-01'
AFTER role=name text=''                                                     ← ❌ 没进 name 框
AFTER role=host text='ssh -p 2222 sshtest@127.0.0.1E2ECHK5554d3c-01'       ← ★ 拼进去了
```

⇒ **`127.0.0.1E2ECHK5554d3c-02` 这个被污染的条目名，本轮独立复现出同一个机制**：
`sshName` 为空时回退到 `sshHostname(target)`（`SshModel.kt:84-85`），
而 `sshHostname` 取**最后一个空白符后的 token 再按 `@` 切**，于是尾巴被整段吞进主机名。
**这**是**测试工具的锚点缺陷，不是 app 缺陷**。

**附带一条真实 app 行为（可判定为"设计如此，非缺陷"）**：
表单 `remember` 的字段在 `addingSsh=false` 时**离开组合 ⇒ 状态被丢弃**，
所以**重新打开表单是干净的**（`SshDialog.kt:133-138` 的 `var target by remember { mutableStateOf("") }` 等）。
`REPORT_COVERAGE2.md` §3.4 记的"表单提交后不清空 ⇒ 重试必被脏值污染"**只发生在同一次打开内**，
跨次打开不会。**本轮未找到"表单带残留重新打开"的证据。**

### 2.5 判定：算不算缺陷？

| 现象 | 判定 | 理由 |
|---|---|---|
| `Add server` 在 target 空时**无任何提示**地灰掉 | ⚠️ **可用性缺陷（不改代码）** | 灰态本身是对的（Material 规范），但**不解释"为什么灰"**。属产品体验议题，不足以判 FAIL；修它需要新增用户可见文案，超出本轮授权（且任务书禁止新增字符串）。 |
| `enabled` 不看口令，而 `validateSshForm` 要口令 | ➖ **非缺陷，是两段式流程的正常分工** | 按钮管"能不能提交"（target 是最小标识），`validateSshForm` 管"凭据齐不齐"；后者有红字 + v90 的 `bringIntoView` 兜底。 |
| **行级口令卡与表单按钮文案高度相似** | ⚠️ **真实可用性风险（建议单独立 issue）** | `Password` / `Cancel` / `Add server` 三件套完全同名，且行级卡**盖在列表上**，让测试员（和人）误判成"表单第二段"。**不是功能缺陷**（口令确实可输、认证能继续）。 |
| 字段绘制了却不在 a11y 树里 | ⚠️ **可访问性缺陷** | 落在 body 裁切线以下即被从语义树剔除，`content-desc` 也取不到。**与 `SSH_DELETE_FIX.md` §1.4 记的 3px 残片同源。** 建议单独立 issue（`ui/servers/SshDialog.kt` 的 band 裁剪）。 |

**⇒ 本轮任务 A 的两个未决论都有答案了：**
- 「面板 body 行容量未测得」→ 见 §3.4（本轮同样未造出 4 行，**仍未测得**）
- 「表单是否缺认证方式控件」→ **不缺**。`Authentication` 选择器存在，
  默认值 `Password`（`SshDialog.kt:395-408` 的 `OcSelect`），滚一屏即见。
  **上一轮"表单 dump 里没有认证控件"是取样失败。**

---

## 3. 任务 B：D3 覆盖度

### 3.0 ★★ 本轮最重要的两条实测结论（先于所有判定）

**(a) 面板 body 从不把自己暴露成 `scrollable=true` 的 a11y 节点。**
全量枚举（本轮 `c3_h` 帧，Servers 面板打开、确认条已 arm 之后）：

```
--- ALL scrollable nodes ---
  scrollable [53,2000][1027,2149] class=android.view.View    ← 会话列表（Home 屏残留层）
  scrollable [64,707][1017,781]   class=android.view.View    ← tab 条
（面板 body 缺席）
```

⇒ **任务书要求的"面板 body 视口 bounds"根本无法从 a11y 树读到。**
本轮用**面板几何盒 y∈[800,1937]**（= `Add SSH server` 卡顶 927 之上、底部 1937 的面板边界）
作为视口代理。**这也解释了 `REPORT_COVERAGE2.md` §3.4 的"测不到 ScrollView"：
不是懒加载，是这个 body 根本不标 `scrollable`。**
但**滚动确实生效**（行 y 由 1502/1749/1957 → 1232/1479/1687），
所以"不标 scrollable"**不代表**`bringIntoView` 的 responder 链不存在 ——
**a11y 树读不出它，不等于它不在。**

**(b) ★ `127.0.0.1:44521` 这条行在本轮的删除前后消失了 —— 一次"疑似越界删除"。**

| 帧 | 行清单（强制滚动后的 census） |
|---|---|
| `G1_scrolled`（**删除前**） | 3 行：`keypass123` ⋮@1232、`10.0.2.2:4095` ⋮@1479、`127.0.0.1:44521` ⋮@1687 |
| `V0_before`（删除前一瞬） | census 带 800–1995 只看到 `keypass123` + `10.0.2.2:4095`（`44521` **落在折叠线下、被裁出 a11y 树**） |
| `Z0..Z3`（**删除后**，连滚 3 次） | **仅 1 行** `10.0.2.2:4095`，`SERVER_ACTIONS=1`，`127.0.0.1:44521` **未再出现** |

**候选机制（源码级）** `ui/settings/ServersPanel.kt:246-250`：

```kotlin
removePersisted = { id ->
    val localUrl = sshStore?.sshEntry(id)?.lastLocalUrl
    val removed = sshStore?.removeSshEntry(id) ?: false
    if (localUrl != null) bridge?.delete(localUrl)   // ★ 连带删掉隧道写出的 loopback 行
    removed
}
```

`127.0.0.1:44521` **正是一个 loopback 地址**，形状与"READY 隧道写出的本地行"完全吻合。

> ⚠️ **诚实边界**：**我无法证明**是我的那一次确认删掉了它 ——
> 删除前一瞬 `44521` 已被裁出 a11y 树，**我没有拿到"删除瞬间它仍在"的直接读数**；
> 且 `oc_server_config.xml` 的值是 **AndroidX security-crypto 加密**的（见 §5 附注），**读不出 URL**。
> 同时我自建隧道从未 READY（`Connection failed` / `Disconnected. The remote server is left running.`），
> 按 `lastLocalUrl` 的逻辑它本不该有可级联的 loopback 行 —— **这使"误删"更难成立，也更需要独立复现。**
>
> **⇒ 登记为「未决高风险项」，建议 Lead 单独立 issue 专测**：
> 造一条**真正 READY** 的 SSH 隧道（loopback 行必然写出），
> 再走 ⋮→Delete→确认，核对被级联删掉的是否**恰好是本隧道自己写出的那一行**，
> 以及**别的隧道**的 loopback 行是否被误伤。
> **本轮不做判定。**

### 3.1 D3-3 第 3 行 / 第 4 行 — ❌ **未验到（条目不足）**

- 本轮**最多只稳定建出 1 条**自建条目（`keypass123`），
  其余 2–3 条均为**他人条目**（`10.0.2.2:4095`、`127.0.0.1:44521`），**按纪律不得删除**。
- 我**确实尝试过**对第 3 行（`127.0.0.1:44521`）取几何读数：
  先把面板滚动到该行 ⋮ 进入视口（⋮ 由 1957 → **1687**），点 ⋮、点 `Delete`，
  但**确认条未 arm**（`BAR_TITLE_NOT_IN_A11Y_TREE`），
  随后复核状态确认**没有**留下任何已 arm 的确认条（安全检查未触发 ⇒ 未误删他人行）。
- ⇒ **无读数，不做 PASS/FAIL 声明。**

### 3.1.1 ★ 本轮取得的**唯一一组真实确认条几何**（自建行，非第 3/4 行）

证据帧：`V1_menu` / `V2_bar` / `V3_deleted`

```
目标行            keypass123            [201,1215][390,1256]     ← SSH tunnels 组内
行 ⋮              [780,1375][906,1501]
菜单 Delete       [618,1533][717,1586]  @ (668,1560)  ← 点它
─────────────────────────────────────────────────────────────
确认条标题        'Delete SSH server?'  [233,1539][546,1580]
确认条 Delete     [707,1715][811,1756]
确认条 Cancel     [501,1715][612,1756]
CONFIRM_BAR_Y     = [1539, 1756]
面板几何盒        y ∈ [800, 1937]
判定              1539 ≥ 800 且 1756 ≤ 1937  →  GEOMETRY = 完整落在面板内  ✅
─────────────────────────────────────────────────────────────
点确认条 Delete @ (759,1736)
TARGET_GONE       = True
CONFIRM_BAR_GONE  = True
```

> ⚠️ **必须说清的限定**：该行 ⋮ 在 `y=1375`，**尚未越过折叠线 1937**，
> 所以**本轮仍没有真正触发「确认条原本会落在视口外」的那条路径**，
> `bringIntoView()` 在这一例里**可能是忠实的 no-op**。
> ⇒ 与 `REPORT_COVERAGE.md` §4.3 的两组读数**同类**：**说明该路径在现有行位上无回归，不能替代 D3-3。**

### 3.2 D3-4 三态 — ❌ **未验到**

| 行态 | 判定 | 说明 |
|---|---|---|
| `Connected` | ❌ 未验到 | 本轮自建行**从未 READY**：终态是 `Connection failed`（`Could not establish the SSH connection.`）或 `Disconnected. The remote server is left running.`。**未铺私钥**（`E2ECHK5554_provision_key.ps1` 本轮未执行）。 |
| `Connecting` | ❌ 未验到 | 未采到该中间态。 |
| `Authentication required` | ❌ 未验到 | **但认证阶段本轮已被证明可达**（见下），造法已明确。 |

**★ 修正上一轮的错误结论（重要）**：
`REPORT_COVERAGE.md` §4.4 断言「2222 无监听 ⇒ `Authentication required` 不可能出现」。
**本轮推翻该前提**：

```
$ wsl -d Arch -e bash -lc "ss -lntp | grep -E '(:22|:2222)'"
LISTEN 0 128   0.0.0.0:22   0.0.0.0:*
LISTEN 0 128      [::]:22      [::]:*
$ adb -s emulator-5554 reverse --list
host-15 tcp:2222 tcp:22
```

而且真机上**行级口令卡真的弹出来了**（`Enter the password for this connection.`），
说明**设备 → 主机 → WSL sshd 的握手是通的、认证挑战确实到达了 UI**。
⇒ **阻塞点是"建不出条目/行没 READY"，不是"连不到认证"。**

**造法（已验证可行，留给下一轮）**：
1. 按 §2.3 建一条 **Password** 条目 → 提交后**行立即落库**；
2. 拨号撞 sshd 口令挑战 → **行级口令卡**弹出 ⇒ 行处于认证挑战中；
3. 在**行级卡**上输**错误**口令 → 认证失败 ⇒ 行停在认证失败态；
4. 对该行走 ⋮ → `Delete`。
> 这条路径避开了上一轮的死结（"没法先落库再输错口令"）——
> **口令卡在落库之后才出现，落库与输错口令本来就是两个阶段。**

### 3.3 反例（只删目标行）— ⚠️ **部分，且带一个反向信号**

**正面读数**（自建行删除）：

```
删除前：keypass123(⋮@1375) + 10.0.2.2:4095(⋮@1622)          [44521 折叠线下不可见]
删除后：10.0.2.2:4095(⋮@1093)   SERVER_ACTIONS=1
        TARGET_GONE=True   CONFIRM_BAR_GONE=True
```
⇒ **点确认只删了目标行本身**，`10.0.2.2:4095` **零误删、仍在**。

**反向信号**：同一次删除前后，**`127.0.0.1:44521` 从行清单里消失**（§3.0-b）。
**不判定为 FAIL，也不断言是我删的** —— 证据不足以定性，登记为未决高风险项。

### 3.4 面板 body 行容量 N — ❌ **仍未测得**（继承上一轮未闭环项）

本轮未能造出第 3 条自建行；且 §3.0-(a) 已证明**面板 body 不暴露 `scrollable` 节点**，
所以「靠 `scrollable` 判定容量」这条路**本身走不通**。
⇒ **不给 N，不下"装得下/装不下 4 行"的结论。**
建议下一轮改用**几何法**：造出 ≥3 行后，比对滚动前后的行 y 序列（本轮已验证有效：
`1502,1749,1957 → 1232,1479,1687`，即一次上滑 ≈ 270–276 px）。

### 3.5 本轮的时间账（如实记账）

- 建 1 条条目在本轮工具链下成本 **≈ 4–5 分钟**（IME 每次显隐都改变整块面板几何，
  必须每步重新 dump；`ClearET` 的 DEL 循环单次 25–40 s）。
- 本轮墙钟预算 ≈ 50 min，**不足以把 4 条 + D3-3 ×2 + D3-4 三态全部跑完**。
- **取舍：优先把任务 A 查实（已查实、已定位到行号），任务 B 如实记为未验到，不编造读数。**

---

## 4. 未闭环

1. ❌ **D3-3 第 3 行** — 未验到（只建成 1 条自建条目；第 3 行是他人行，按纪律不删）。
2. ❌ **D3-3 第 4 行** — 未验到（同上）。
3. ❌ **D3-4 `Connected`** — 未验到（自建行从未 READY；未铺私钥）。
4. ❌ **D3-4 `Connecting`** — 未验到。
5. ❌ **D3-4 `Authentication required`** — 未验到（**认证态已证明可达，造法已写明**，仅缺执行时间）。
6. ⚠️ **反例（删除前后完整行清单）** — **部分**：拿到 1 组真实删除前后清单（`TARGET_GONE=True`、
   `10.0.2.2:4095` 零误删），但**同屏只有 1 条自建行**，且**带着 `127.0.0.1:44521` 的未决疑问**。
7. ⚠️ **面板 body 行容量 N** — 仍未测得；且已证明**不能靠 `scrollable` 判定**（§3.0-a / §3.4）。
8. 🚨 **未决高风险：`127.0.0.1:44521` 在本轮删除前后消失**（§3.0-b）。
   **本轮不做判定**，建议 Lead 单独立 issue 专测 `removePersisted` 的 loopback 级联边界。
9. ❗ **本轮改动的代码：无。** 见 §5。
10. ⚠️ **待 Lead 裁决的两个非功能缺陷**（本轮只取证、未改）：
   - 表单字段落在 body 裁切线以下 ⇒ **从 a11y 树里消失**（可访问性）。
   - 行级口令卡与表单按钮**同名同款**，易被误认成"表单第二段"（可用性）。
11. ⚠️ **`ResetFaillock` 未能执行** — 本设备 `faillock` 二进制不存在（见 §6）。

---

## 5. 是否改了代码

> **本轮未改任何生产代码。**

| 项 | 结论 |
|---|---|
| `ui/servers/**` | **未改**（只读：`SshDialog.kt`、`SshModel.kt`） |
| `ui/settings/ServersPanel.kt` | **未改**（只读） |
| 新增测试 | **无**（无代码改动 ⇒ 无需新增 pin） |
| gradle | **未跑**（无改动即无需编译；且纪律限定只能跑单类单测） |
| 新增用户可见字符串 | **0** |
| 既有断言强度 | **未触碰** |

**为什么判定"不需要改代码"**：
`enabled = !busy && target.isNotBlank()` 是**有意为之的门**（源码注释与
`SshDialog.kt:89-105` 的 KDoc 都写明 v90 就是为了解决"点 Add server 静默无反应"，
而当时的真实缺陷是**红字画在折叠线以下**、不是按钮该不该亮）。
本轮实测**按钮灰掉时确实无法提交**，属于正确行为；把它改成永远可点
会让"空 target 提交"变成一次真正的空提交，**降低**而非提高质量。
真正值得修的两条（a11y 裁切、口令卡同名）都**需要新增或改动用户可见文案**，
超出本轮授权 ⇒ **登记为待裁决缺陷，不擅自修改。**

> **附注（为什么查不了持久化真相）**：
> `run-as … cat shared_prefs/oc_server_config.xml` 里所有 `<string>` 的值
> 都是 `ATA0KB+…` 形式的 **AndroidX security-crypto 密文**，
> keyset 也在同文件内 ⇒ **无法从宿主机读出 URL**，
> 这也是 §3.0-b 无法定性的直接原因之一。

---

## 6. 清理

| 项 | 结果 | 说明 |
|---|---|---|
| 自建条目 `keypass123` | ✅ **已删净** | 走完 ⋮→`Delete`→确认条→确认，`TARGET_GONE=True`、`CONFIRM_BAR_GONE=True`；删除后连滚 3 次 `census` 均**无该行**。 |
| 自建条目（前几次尝试） | ✅ **0 条残留** | 删除后 `SERVER_ACTIONS=1`，仅剩他人行。 |
| 他人条目 `10.0.2.2:4095` | ✅ **零误删，仍在** | 删除后 `Server healthy` + ⋮@1093。 |
| 他人条目 `127.0.0.1:44521` | 🚨 **消失，成因未定** | §3.0-b。**不判定**；请 Lead 复核。 |
| **`ResetFaillock`** | ❌ **无法执行** | `adb shell faillock --user sshtest` → `/system/bin/sh: faillock: inaccessible or not found`。**该设备（SDK 37 模拟器）无 `faillock` 二进制。** 下一轮若仍缺，属环境限制而非未做。 |
| `/sdcard/c3`（本轮 dump/截图暂存） | ✅ **已删** | `rm -rf /sdcard/c3` → `ls /sdcard/c3` → `No such file or directory`。 |
| `/sdcard` 其它残留 | ✅ 本轮**未新增** | 其它轮次产物（`CL3_*`/`hu_*`/`v34_*` 等）按纪律**未删**。 |
| 调试脚本（本轮产出） | ✅ **留在 `research/` 宿主机侧** | `c3*.ps1` 只在 PC 跑，设备上无残留。 |
| reverse 端口 | ✅ 保持 `4095→4095`、`2222→22` **未改动** | |
| 模拟器进程 | **未杀**；本 app **未做** `am force-stop` | |
| gradle / 装机 | **均未执行** | 符合纪律。 |

---

## 7. 证据文件（`research/v97_verify/5554_v98d3/`）

| 文件 | 内容 |
|---|---|
| `c3lib.ps1` | 工具库：`Cap`/`Bnd`/`FindN`/`TapXY`/`TypeS`/`ClearET`/`Viewport`/`DumpT`；`TxtOf` 走 XML 解析，**天然同时匹配 `text="…"` 与 `text='…'`** |
| `c3_map.ps1` / `c3_entry.ps1` / `c3_make.ps1` / `c3_A_form.ps1` | 表单字段定位 / 建条目 / 填字段 / 导航与表单全量 dump |
| `A03_form_default_*` | ★ 任务 A-1：默认态全量 dump（**零 EditText**、name 标签 1px） |
| `A06_formscroll2_*` | ★ 任务 A-2：**`Add server` 灰态直证截图**（同时证明字段确实画出来了） |
| `A05_after_default_tap_*` | ★ 任务 A-2：点灰按钮后一切未变 |
| `E1_*` / `K1_*` | ★ 任务 A-3/§2.4：host 填入读数、name 串进 host 的复现、行级口令卡读数 |
| `V1_menu_*` / `V2_bar_*` / `V3_deleted_*` | ★ §3.1.1 确认条几何与删除前后清单 |
| `G1_scrolled_*` / `H0_state_*` | ★ §3.0 面板 body 不标 `scrollable` 的证据、`44521` 删除前 census |
| `Z0_after_*` / `Z1_s*_*` | ★ §3.0-b / §3.3 删除后连滚 3 次的 census |
| `P0_card_*` / `P2_after_*` | 行级口令卡与 `Connection failed` 终态读数 |

> **给下一轮的 5 条硬前置**
> 1. **表单 host 框 = `(540,1645)`（表单滚顶）、口令框 = `(540,1680)`（表单滚底）** —— 两者都**不在 a11y 树里**，只能按坐标点，且每步必须重新 dump（IME 会改几何）。
> 2. **别用「`Authentication` 标签的 y」当字段锚点** —— 该标签在多数滚位下不存在，会退化成"第 2 个字段=name"，**必然把名字打进 host 框**（§2.4 已复现）。
> 3. **滚动表单要在表单卡内部（y≥1420）滑动**；在 y≈1150–1650 滑动会**滚到面板列表去**，表单不动 ⇒ 本轮曾因此把口令打进 host 框。
> 4. **「面板 body 视口 bounds」取不到**（body 不标 `scrollable`）。用**面板几何盒 y∈[800,1937]** 作代理。
> 5. **清条目用行级 `⋮`→`Delete` 的确认条**，别用 `Cancel` 兜底 —— 确认条 `Cancel` `[501,1715][612,1756]`
>    与口令卡 `Cancel` 几何相近，会误把已 arm 的确认条关掉。
