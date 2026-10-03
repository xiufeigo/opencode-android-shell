
---

## 2. B — D3-3 / D3-4（卡修复后已可做）

### 2.0 前置：卡阻塞已解 —— 本轮**连续建到 3 条**

第六轮的 1/4 条卡在"核验卡挡住 `Add SSH server`"，本轮三次连续建条全部成功（每条建完都在卡上点 `Cancel`，**两跳**后回到面板）：

| 建条 | 目标 | 核验卡 | 处理 | 隧道键 len |
|---|---|---|---|---|
| `E2ECHK5554d3d-1` | `ssh -p 2222 sshtest@127.0.0.1` | 弹（`cardUp=TRUE`） | 点 `Cancel` → 卡退 / 行 `Disconnected` | 76 → **268**（+192） |
| `E2ECHK5554d3d-2` | 同上 | 弹 | 改点 `Trust and connect` | 268 → **464**（+196） |
| `E2ECHK5554d3d-3` | 同上 | 弹 | 点 `Cancel` → 卡退 | 464 → **660**（+196） |

**第 3 行真实存在** —— 滚动遍历读数（每步先断言 `mInputShown=false`，`dump` 只报可见节点）：

```
[B1 s0] IME=false panelRows=[E2ECHK5554d3d-1|E2ECHK5554d3d-2|E2ECHK5554d3d-3]
[B1 s1] IME=false panelRows=[E2ECHK5554d3d-2|E2ECHK5554d3d-3]
...
=== PANEL ROWS ENUMERATED ===
  ROW E2ECHK5554d3d-1
  ROW E2ECHK5554d3d-2
  ROW E2ECHK5554d3d-3
PANEL_ROWCOUNT=3
```

行锚点用**左边界 `x=201`**（面板行标签位）——这一步很关键：第一版用 `E2ECHK5554d3d-\d+$` 匹配时**把对话框里的 `Connect to E2ECHK5554d3d-3` 标题和表单的 name 字段值也算成了"行"**，`ROWCOUNT=3` 因此不可信（真值当时恰为 3，撞上了，掩盖了污染）。收紧到 `l∈[190,210]` 后读数才成立。

> **B1 判定：PASS —— ≥3 条达成，第 3 行真实存在；落库 76→268→464→660 与 UI 行数 1→2→3 同步。**

### 2.1 D3-3 第 3 行（`E2ECHK5554d3d-3`）—— ✅ PASS

| 步骤 | 读数 |
|---|---|
| 第 3 行 | `E2ECHK5554d3d-3 [201,1280][517,1321]` |
| 行内 ⋮ | `cd=Server actions clk=true [780,1440][906,1566]` → tap @ **843,1503** |
| 菜单项 `Delete` | `[618,1598][717,1651]` → tap @ **668,1624** |
| **确认条标题** | `Delete SSH server? [233,1604][546,1645]` |
| **确认条正文** | `This removes "E2ECHK5554d3d-3" from the list. This cannot be undone. [233,1656][848,1726]` |
| **确认条按钮 y 区间** | `Delete [707,1780][811,1821]`、`Cancel [501,1780][612,1821]` ⇒ **y = 1780..1821** |
| **视口** | 屏高 **2424** ⇒ **完全落在视口内 = True**（且不与固定页脚重叠） |
| 确认 | tap @ **759,1800** |
| 删除后行清单 | `E2ECHK5554d3d-1 [201,955][510,996]`、`E2ECHK5554d3d-2 [201,1280][516,1321]` ⇒ **COUNT 3 → 2** |
| `TARGET_GONE(d3d-3)` | **YES** |
| 落库 | 隧道键 **660 → 464（Δ −196）**，与建条 +196 严格对称 |

> **D3-3 第 3 行：PASS** —— 确认条可见可点（**y = 1780..1821**，落在 2424 视口内），**删的正是第 3 行**，落库同步回退一条。

### 2.2 D3-3 第 4 行 —— ❌ **未取证（不编造）**

需 ≥4 行；删掉第 3 行后只剩 2 行，补建第 4 条时 **`EnsurePanel` 在"面板已滚下去"的状态下找不到 `Add SSH server`**（该按钮固定在面板顶部，滚走后不在 a11y 树里），连打 2 轮 `no panel` 后本轮时间预算耗尽，遂中止该分支转去做 D3-4 与清理。

**失败原因与核验卡无关**（`cardUp=FALSE`、`focus=OcActivity` 正常），是**导航脚本没先滚回面板顶部**。修法见 §4.6 ⑥。**判定：未取证。**

### 2.3 D3-4 三态

| 态 | 判定 | 证据 |
|---|---|---|
| **`Connecting`** | ⚠️ **态已观测，但"Connecting 中删除"未取证** | 态本身有真读数：建条瞬间 `Connecting over SSH… [201,1261][510,1296]` + 全屏 `Connecting to SSH server [384,1339][801,1349]`（A1 `U11_card`）。但**删除窗口没抓住**：11:33:36 点 `Reconnect @ 408,1100` 后连采 3 次（0.7s/次）全部已是 `Disconnected. The remote server is left running.`，`cardUp=FALSE` —— `Connecting` 窗口比一次 dump 还短。**原因（可解释）**：`Trust` 之后 `127.0.0.1:2222` 已进 `known_hosts`，重连**不再弹核验卡**、直接进认证/失败，`Connecting` 只闪一下。 |
| **`Connected`** | ❌ **未造出** | 本轮**未铺私钥**（`E2ECHK5554_provision_key.ps1` 未执行）。且 `keypass123` 不是 `sshtest` 的有效口令，重连只会落到失败/认证态，**到不了 `Connected`**。 |
| **`Authentication required`** | ❌ **未造出（试过什么，全写在此）** | ① 建条自动进入连接流程 ⇒ 弹的是**主机身份核验卡**，不是口令挑战；② 点 `Trust and connect` 后推进到的是 **`SSH authentication` / `Password:` 挑战卡**（A4）—— **这是认证态的卡片形态，但不是行状态字符串 `Authentication required`**；③ 走完该卡需要有效口令，`keypass123` 无效；④ 11:33:36 点 `Reconnect` 复现 ⇒ 3 次采样直接落 `Disconnected`，**未落 `Authentication required`**。**未证实行状态能否出现该串，不下结论。** |

> **D3-4 判定：BLOCKED** —— 指定三态中，**没有一态拿到"该态下完成删除"的完整链路**。
> **与第六轮的本质差别**：第六轮三态全空是因为**核验卡卡死面板、按钮都点不着**；本轮**核验卡不再阻塞**，造态手段已打通到"**认证挑战卡**"这一层，卡在"**没有有效凭据**造不出 `Connected` / 行状态 `Authentication required`"。

### 2.4 反例（点确认只删目标行、其它行仍在）—— ✅ PASS

点确认前（落库 660）：

```
ROWS_BEFORE_CONFIRM=[E2ECHK5554d3d-2[201,955][516,996] | E2ECHK5554d3d-3[201,1280][517,1321]]
PANEL_ROWCOUNT=3   (E2ECHK5554d3d-1 在上方，滚动遍历已收齐)
```

点确认后（落库 464）：

```
ROW_AFTER E2ECHK5554d3d-1 [201,955][510,996]
ROW_AFTER E2ECHK5554d3d-2 [201,1280][516,1321]
PANEL_ROWCOUNT_AFTER=2
TARGET_GONE(d3d-3)=YES
--- 同屏非自建行 ---
  '10.0.2.2:4095' [254,1709][474,1750]   'Server healthy' [201,1739][222,1760]   'Default' [674,1732][769,1767]
  'Server actions' [780,1687][906,1813]  <- 该行 ⋮ 完好
```

> **反例判定：PASS** —— 只删了 `E2ECHK5554d3d-3`；`E2ECHK5554d3d-1` / `-2` **各留一条**，非自建行 **`10.0.2.2:4095` 连同它的 `Server actions` ⋮ 完好保留**。清理终态再次确认：`10.0.2.2:4095 [254,1115][474,1156]` + `Server actions [780,1093][906,1219]` 仍在。

---

## 3. C — 清理 —— ✅ 已闭环

| 项 | 状态 | 证据 |
|---|---|---|
| 删净 `E2ECHK5554d3d*` | ✅ **删净** | 清理 3 轮：`pass1 rows=[-1\|-2] → pass2 rows=[-2] → pass3 rows=[] count=0`；终态 `CLEANUP RESULT rows=[] count=0` |
| 落库回到基线 | ✅ | 隧道键 value **660 → 464 → 76**，与本轮开始的 **76（空表基线）** 逐位一致；`<string>` 键数 **全程恒为 7**（再次印证"数 `<string>` 行数必得 0"）；文件 **2042 B**（基线 2040 B，差 2 B，见 §4.5） |
| app 私有目录无残留 | ✅ | `run-as … grep -rl E2ECHK5554d3d .` → **空** |
| `/sdcard` 无本轮残留 | ✅ | 本轮 dump/截图一律"pull 完立即 `rm -f`"；另补删 3 个早期 pull 失败留下的 `_S0.xml`(03:11)、`_EP2.xml`(03:32)、`_B2d6.xml`(03:29)。复核 `/sdcard/_*.xml` 现存仅 `_h.xml`、`_v91/93/94.xml` —— **均属往轮遗留**（`_im.txt` 10-01 属他轮，未动） |
| `reverse` 复核 | ✅ | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` —— **本轮一条都没建**（全程只连设备本地 `127.0.0.1:2222`） |
| 只动自建条目 | ✅ | 只创建/删除了 `E2ECHK5554d3d-1..3`；`10.0.2.2:4095` 全程未动（§2.4 反例佐证） |
| 设备健康 | ✅ | 全程 `focus=OcActivity`，**从未出现 `Application Not Responding`**，**未 reboot**；仅对本 app 做过 `am force-stop`（纪律允许） |

---

## 4. 未闭环 / 给下一轮

1. **【最高】核验卡修复本身：全绿，无遗留。** `Cancel` 三判据、严格两跳、`Trust`（含 `known_hosts` 落盘硬证据）全部真机 PASS。**修复报告 §6.2"Trust 在新鲜卡上有效未被单独取证"这一条，本轮已闭环** —— A4 是第一次在**未被 Cancel 污染过**的新鲜卡上点 Trust。
2. **D3-3 第 4 行：未取证**（§2.2）。卡在我的导航脚本没先滚回面板顶部，**不是产品缺陷**；下一轮补建一条即可，删除链路已在第 3 行证明可用。
3. **D3-4 三态：均未拿到"该态下完成删除"的完整链路**（§2.3）。下一轮造态配方建议：
   - `Connected`：先跑 `research\v97_verify\5554\E2ECHK5554_provision_key.ps1` 铺 `files/id_ed25519`，用 `PrivateKeyPath`、keypath `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`、passphrase 留空、卡里输 `keypass123`；**但必须先独立确认对端真的接受该密钥** —— 本轮 `keypass123` 对 `sshtest` 无效，是没造出 `Connected` 的首要嫌疑。
   - `Authentication required`：需要"对端可达但口令故意填错"。**本轮未能确认 `127.0.0.1:2222` 后端是否真有 sshd 在监听**（`adb reverse` 存在 ≠ 对端在监听），建议下一轮先在 `adb shell` 侧独立验证再进 UI。
   - `Connecting` 中删除：应改用**建条自动进入连接流程**的那段窗口（该段 `Connecting` 稳定存在），而不是事后点 `Reconnect`。
4. **`known_hosts` 已被本轮写入且未清理**（`[127.0.0.1]:2222 ssh-ed25519 AAAAC3…`，98 B，mtime 03:25）。它既是 A4 的证据、也属自建条目的信任记录，**但会让下一轮重建同名条目时不再弹核验卡** —— 这正是本轮 D3-4 `Connecting` 抓不住窗口的**直接原因**。**建议下一轮开跑前先记录并清掉它**，否则 A 段"弹卡"前提失效。
5. **未解释的 2 字节**：清理后 `oc_server_config.xml` = 2042 B，基线 2040 B（第六轮记的是"差 10 B"）。隧道键 value 已精确回到 76、`grep` 全树无 `E2ECHK5554d3d`，**推测是 XML 序列化/属性顺序差异，非数据丢失**，**未逐字节证实**。
6. **工具侧教训（本轮踩到）**：
   - ① **表单 fill 必须用 TAB 焦点路由，不能靠坐标 tap**。name 框未滚动到位时是 **6px 残片**（`h=6`/`h=12`），tap 其中心不聚焦（`foc=false`），连续 8 次滚动重试 bounds 纹丝不动；改用 **TAB1→name、TAB2→认证 chip、TAB3→password** 后一次通过，**全程零坐标**。这正面印证了 v98 的 IME-next 路由（`SshImeNextRoutingF13aTest`）在真机可用。
   - ② **focused 之后字段会位移**：聚焦 target 后它从 `[138,1576][943,1702]` 移到 `[138,1186][943,1312]`，**用旧 bounds 反查会 tap 错框**（本轮把 name 追加进了 target：`ssh -p 2222 sshtest@127.E2ECHK5554d3d-10.0.1`）。**每步重新 dump 取 bounds。**
   - ③ PowerShell 保留变量 `$host` 赋值会抛错并中断整段脚本。
   - ④ 双引号串里的 `$_` 插值会触发 ParserError；先把 `$_` 结果存进变量再拼串。
   - ⑤ `am force-stop` 与 `am start` 相隔 1.5s 会抢跑，`dumpsys window` 报 `FOCUS=null`；要隔 **≥3s**。
   - ⑥ **面板滚下去后 `Add SSH server` 离树**（顶部固定项）—— 导航必须先**向上滚到顶**再找它（D3-3 第 4 行死在这）。
   - ⑦ **确认条 `Delete` 文本节点自身 `clickable=false`**（可点的是父容器），按 `clickable=true` 过滤会找不到按钮；应按"同名取最后一个 + 与菜单项 bounds 不同"定位。
   - ⑧ **`input text` 无焦点时不报错、只是丢字**：早期一次 tap 失败后紧跟的 `input text` 把 `ssh -p 2222 …` 灌进了**会话创建输入框**，在 Home 侧建出一个 `Untitled` 会话。**每步 `input text` 前必须断言 `focused=true`。**

---

## 附：本轮证据文件

`research/v97_verify/5554_v98d3/`
- `D00_run.log` —— 全程带时间戳日志（主证据）
- `c7lib.ps1` —— dump/解析/Tap/截图/行计数/落库读数helper（双引号+单引号同时匹配）
- `a1b.ps1` —— A1 建条（TAB 路由版，可复现）
- `a2.ps1` / `a4.ps1` —— A2/A3 三判据 / A4 Trust
- `b_funcs.ps1` / `b2.ps1` / `b4.ps1` —— 建条、行枚举（x=201 锚点）、删除、D3-4、清理
- `U11_card` / `C20_card_before` / `C21_after_card_cancel` / `A40_before_trust` / `A42_after_trust` / `D2_confirm_bar` / `B1_three_rows` / `CL_final` —— 关键截图
- `_prefs_*.txt` / `_prefs_final.txt` —— 落库快照（隧道键 len 全程轨迹：76→268→464→660→464→76）
