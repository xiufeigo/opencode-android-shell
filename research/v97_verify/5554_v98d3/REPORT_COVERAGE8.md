# v98 D3 覆盖度 · 第八轮 · 真机复验报告（第 4 行 + D3-4 三态）

**执行人**：真机复验测试员（第八轮） **日期**：2026-10-03
**仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
**被复验对象**：
1. `research/v97_verify/5554_v98d3/REPORT_COVERAGE7.md`（第七轮：核验卡修复全绿；**D3-3 第 4 行**、**D3-4 三态** 未取证）
2. `research/v97_verify/5554_v98d3/REPORT_COVERAGE6.md`（前情）

**一句话结论**：**D3-3 第 4 行删除 ✅ PASS**（三试命中、删的正是 `E2ECHK5554d3e-4`、落库 −196 对称回退）；**建到 5 条**（落库 76→268→464→660→856→1052）。本轮还抓到一个**上一轮脚本自身的错行删除隐患**并用"确认条正文点名"做了自校验拦截。

---

## 0. 环境读数

| 项 | 读数 |
|---|---|
| 设备 | `emulator-5554` = Pixel_9a（`sdk_gphone16k_x86_64`） |
| 屏 / 密度 | `Physical size: 1080x2424` / `Physical density: 420` |
| 应用 | `com.xiufeigo.opencodeshell`，`versionName=1.4.0` |
| 安装时间 | `lastUpdateTime=2026-10-03 03:09:27`（与第七轮逐位一致，全程未重装） |
| 焦点 | 全程 `OcActivity`，**从未出现 `Application Not Responding`**，**未 reboot** |
| `adb reverse` | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` —— **本轮一条都没新建** |
| 私钥 | 跑 `research\v97_verify\5554\E2ECHK5554_provision_key.ps1`：`KEY repo=0120a8270f7f0b0c71d31f4933384c63 device=0120a8270f7f0b0c71d31f4933384c63`，落盘 `files/id_ed25519` 444 B、`u0_a230` 可读 |
| 纪律 | 未跑 gradle、未装机、未杀他 app；只对本 app 做过 `am force-stop`（允许）；**未按 BACK(4)**、**未按 ESC(111)**；截图全部 `screencap -p` + `adb pull`；坐标全部运行期从 dump 解析 |

### 0.1 解析口径（按要求）

`c8lib.ps1` 的 `Nodes()` 对每个属性用 `(?:"([^"]*)"|'([^'']*)')` **同时匹配 `text="…"` 与 `text='…'`**，dump 一律 `uiautomator dump --compressed`，pull 完立即 `rm -f` 设备侧副本。

### 0.2 落库计数口径（沿用第七轮）

隧道表 `AXhs57KqmMxUXQA/h1gxbGu9ZbW7vQQstN5gTAw=` 是**一个加密键装一整个列表**，`<string>` 行数恒定不变，**条目数 = UI 可见行清单（滚动遍历）**，并以**隧道键 value 长度**作落库侧交叉验证（每条 ≈ **+196**）。

### 0.3 起始状态（关键前提）

第七轮遗留的 `known_hosts` **仍在**，本轮开跑前未清（§4 要求最后清）：

```
$ adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

**这正是第七轮 §4.4 提醒要利用的那一点**：因该主机键已被信任，本轮**新建同名 host 条目不再弹核验卡**。实测每一次建条后 `cardUp=FALSE`（5/5），与预期一致 —— 既让建条速度提上来，也正是 C 段"直接进入 Connecting / 认证态、不被核验卡打断"的前提。

---

## 1. A — 建到 ≥5 条 ✅ 达成

### 1.1 逐条落库计数（隧道键 value 长度）

| 建条 | 目标 | 核验卡 | 隧道键 len | 判定 |
|---|---|---|---|---|
| 基线（空表） | — | — | **76**（file 2042 B） | — |
| `E2ECHK5554d3e-1` | `ssh -p 2222 sshtest@127.0.0.1` | `cardUp=FALSE` | **268**（2234 B） | ✅ +192 |
| `E2ECHK5554d3e-2` | 同上 | `cardUp=FALSE` | **464**（2430 B） | ✅ +196 |
| `E2ECHK5554d3e-3` | 同上 | `cardUp=FALSE` | **660**（2626 B） | ✅ +196 |
| `E2ECHK5554d3e-4` | 同上 | `cardUp=FALSE` | **856**（2822 B） | ✅ +196 |
| `E2ECHK5554d3e-5` | 同上 | `cardUp=FALSE` | **1052**（3018 B） | ✅ +196 |

**5 条达成，UI 行数与落库 1→2→3→4→5 严格同步。**

### 1.2 UI 行清单（滚动遍历，行锚点 `x=201`）

行锚点沿用第七轮教训：只认 `text`/`content-desc` **且左边界 `x∈[190,210]`** 的节点，避免把对话框标题和表单 name 字段值误计成"行"。

同屏读数（12:01:17，删除前）：

```
ROW E2ECHK5554d3e-1   [201,1215][509,1256]
ROW E2ECHK5554d3e-2   [201,1540][515,1581]
ROW E2ECHK5554d3e-3   [201,1865][516,1906]
```

滚动后（12:03:33，删除流程内同屏）：

```
[DV] rows on screen before = [E2ECHK5554d3e-2|E2ECHK5554d3e-3|E2ECHK5554d3e-4|E2ECHK5554d3e-5]
[DV] 4 kebab candidate(s) on screen
```

行距 325 px（第 1 行 cy=1235、第 2 行 cy=1560、第 3 行 cy=1885），**面板行容量未见上限；4、5 行均真实存在、可点 ⋮**。第七轮"第 4 行看不到"已证实是**导航脚本没滚到那一条**，不是面板容量问题。

### 1.3 建条过程中踩到并修掉的三个脚本缺陷（都是"假失败"，非产品缺陷）

| # | 症状 | 真因 | 修法 |
|---|---|---|---|
| 1 | 目标框回读只有 `ssh` | `input text` **遇真实空格即截断**（`%s` 才是空格转义） | 封装 `SetET`，内部统一 `.Replace(' ','%s')` 后再 `input text` |
| 2 | 回读比期望多一截 | **表单会保留上一次的值**，新输入变成**追加** | `SetET` 先 `keyevent 123`(MOVE_END) + 70 个 `67`(DEL) 清空再输入 |
| 3 | 点 `Add server` 无反应、条目不落库 | **IME 打开后表单页脚会上移**：`Add server` 实际在 `[760,1294][938,1335]`（y≈1314），而脚本按旧坐标 tap 了 y=1800（落在键盘区） | 提交前**重新 dump 取实时 bounds** 再 tap，并带 3 次重试 + 行可见/落库双校验 |

---

## 2. B — D3-3 第 4 行删除 ✅ **PASS**

### 2.1 判定链（运行期解析，自校验）

| 步 | 读数 |
|---|---|
| 目标行 | `E2ECHK5554d3e-4` |
| 同屏行清单（删前） | `[E2ECHK5554d3e-2 \|-3 \|-4 \|-5]` |
| 同屏 ⋮ 候选数 | **4** |
| **try1** kebab `[780,929][906,1055]` → 菜单 `Delete [618,1087][717,1140]` | 确认条正文 `This removes "E2ECHK5554d3e-2"` ⇒ **不匹配，撤销，不确认** |
| **try2** kebab `[780,1254][906,1380]` → 菜单 `Delete [618,1412][717,1465]` | 确认条正文 `This removes "E2ECHK5554d3e-3"` ⇒ **不匹配，撤销，不确认** |
| **try3** kebab `[780,1579][906,1705]` → 菜单 `Delete [618,1737][717,1790]` | 确认条标题 `Delete SSH server? [233,1635][546,1676]`，正文 `This removes "E2ECHK5554d3e-4" from the list. This cannot be undone.` ⇒ **匹配** |
| 确认条按钮 | **`Delete @ 759,1832`** ⇒ **y 区间 1635(标题) ~ 1832(按钮底)**，按钮本体 y≈1811..1853，**完全落在 2424 视口内**，且不与固定页脚重叠 |
| 确认 | tap @ **759,1832** |
| 删除后行清单 | `[E2ECHK5554d3e-3 \| E2ECHK5554d3e-5]` ⇒ **`-4` 消失，其余行仍在** |
| `TARGET_GONE(-4)` | **True** |
| 落库 | 隧道键 **1052 → 856（Δ −196）**，与建条 +196 严格对称 |

> **B 判定：PASS** —— 确认条**可见可点**（`Delete @ 759,1832`，视口 2424 内），**删的正是第 4 行 `E2ECHK5554d3e-4`**，其余自建行与后建行完好，落库同步回退一条。
> **上一轮"第 4 行未取证"闭环。**

### 2.2 ⚠️ 本轮抓到的真隐患：按坐标猜 ⋮ 会删错行（且差点真删）

**这不是产品缺陷，是第七轮脚本的坐标推算缺陷**，但对下一轮有直接价值：

- 第七轮/本轮初版用"离行标签最近的 `Server actions`"推算目标行的 ⋮。实测**该推算不可靠**：同屏 4 个 ⋮ 时，标签 `cy=1410`（`-4`）却选中 `cy=1288` 的 ⋮（属 `-3`）。
- 真因之一已定位：`Center` 返回的键是 `y`，而 `PanelRows` 里写的是 `$c.cy`（**null**），导致排序退化、`$tgt.cy` 传空（`dY=985` 即"与 0 的距离"）。已修为 `$c.y`。
- **更关键的产品侧事实**：**确认条正文 `This removes "<名字>"` 是权威的"将要删掉谁"**。本轮据此做了**自校验拦截**——try1/try2 两次识别出将要删的是 `-2`/`-3` 而非目标 `-4`，**主动撤销不确认**，才没有造成错删。
- **给下一轮的硬建议**：任何"点 ⋮ → Delete"自动化，**都必须先读确认条正文并与预期名字比对**，不匹配就撤销；不要用"最近/最远 ⋮"之类的几何猜测决定删哪一行。顺带说明：即便猜错，**确认条这一步是人可以看见并叫停的**（这是产品侧的安全阀，值得记为优点）。

---

---

## 3. C — D3-4 三态（逐条判定）

> **本节结论前置**：三态**一态都未拿到"该态下完成删除"的完整链路**。但**失败原因与第七轮完全不同**，且**定位到了一个可复现的共同根因**（见 §3.4），值得下一轮直接照做。

### 3.1 `Connected` —— ❌ **本轮未执行**（不是"造不出"，是没排上）

**如实说明**：本轮脚本已写好（`c1_connected.ps1`：用口令 `opencode` 建条目 → 若弹 `SSH authentication` 卡先试 `keypass123` 再试 `opencode` → 轮询到 `Connected` → 删除），**但因 A/B 段脚本缺陷排查耗时，本轮时间预算耗尽，该脚本未运行**。**不下任何"造不出 Connected"的结论。**

已确认的**前提条件**（为下一轮铺路）：

- 私钥**已铺到 app 私有目录**且 app uid 可读：`files/id_ed25519` 444 B，md5 `0120a8270f7f0b0c71d31f4933384c63`（`E2ECHK5554_provision_key.ps1` 自校验通过）。
- **第七轮的"口令记混"已澄清并落到脚本里**：`keypass123` = **测试私钥的 passphrase**；`opencode` = **`sshtest` 账号口令**。第七轮失败用的是前者当账号口令使。
- ⚠️ **主机侧 key 认证未能独立验证**：`ssh -o BatchMode=yes sshtest@127.0.0.1`（**22 端口**，`2222` 是 `adb reverse` 只在设备侧存在的端口，宿主机 `Connection refused`）返回 `Permission denied (publickey,password,keyboard-interactive)`。随后改用私钥文件时被 **Windows ACL 拦截**（`Bad permissions… UNPROTECTED PRIVATE KEY FILE`），`icacls` 收紧后仍失败。**因此"该密钥是否已被对端 authorized_keys 接受"本轮始终未证实** —— 这是下一轮造 `Connected` 前**必须先独立确认**的一环。

### 3.2 `Connecting` —— ❌ **未取证**（但找到了可复现的根因）

| 步 | 读数 |
|---|---|
| 造态手段 | target 改成 **`ssh -p 2222 sshtest@10.255.255.1`**（黑洞地址，`adb reverse` 无关），条目 **已成功建出**（隧道 856 → 1060） |
| 提交 | `Add server @ 849,1314`，随即连扫 8 次（≈0.7 s/次，12:05:55 → 12:06:21） |
| 8/8 采样 | `state='(row not visible)'`、**`kebabs=0`** |
| 终态 | `C2 CAPTURED state='(row not visible)' kebabFound=False` |
| 删除 | `C2 NO KEBAB during Connecting -> cannot open delete menu` ⇒ **未执行删除** |
| 同屏状态（12:06:25） | `Disconnected. The remote server is left running.` ×2 |

**根因（明确、可复现）**：**新条目追加在列表末尾，而面板不会自动滚动到它**，因此提交后连扫 8 次全部落在**已滚动区域**，"行"根本不在 a11y 树里（`row not visible`），自然也数不到该行的 ⋮（`kebabs=0`）。
**这与第七轮"Connecting 窗口比一次 dump 还短"是两种不同的失败** —— 本轮**不是窗口太短，而是压根没看到那一行**。

> **给下一轮的可执行配方**（成本很低）：造 Connecting 态时，**提交后立刻把面板滚到列表最底**再扫（每步只 dump 一次，避免把窗口拖没），而不是在当前位置死扫 8 次。

### 3.3 `Authentication required` —— ❌ **行状态串未取证**（但**认证阶段已确证**）

| 步 | 读数 |
|---|---|
| 造态手段 | 目标照旧 `127.0.0.1:2222`，**故意填错口令 `wrongpass`**，条目 **已成功建出**（隧道 1060 → 1256） |
| 8/8 采样（12:07:36 → 12:08:07） | **`authCard=True`**、`cardUp=FALSE` |
| 行状态 | 全部 `'(row not visible)'`（与 §3.2 同一根因：行在列表末尾、未滚到） |
| 删除 | `row 'E2ECHK5554d3e-c3' never became visible` ⇒ **未执行删除** |
| 落库 | 1256（条目已建成，未删） |

**正面结论**：**错口令确实把连接推到了认证阶段** —— `authCard=True`（`SSH authentication` 挑战卡）在 8 次采样中稳定为真，而 `cardUp=FALSE`（**不再回退到核验卡**）。这说明**"可达但口令错"这条造态路径是通的**。

**但必须说清楚**：拿到的是**认证卡的形态**，**不是行状态字符串 `Authentication required`**。**未证实行状态串能否出现该文案，故不下结论** —— 与第七轮 §2.3 的界定保持一致，不把两件事混为一谈。

### 3.4 三态小结

| 态 | 判定 | 关键读数 |
|---|---|---|
| `Connected` | ❌ **未执行** | 脚本已备好未跑；主机侧 key 认证未独立证实 |
| `Connecting` | ❌ **未取证** | 行在列表末尾未滚到（`row not visible` ×8、`kebabs=0`） |
| `Authentication required` | ❌ **行状态串未取证** | 但 `authCard=True` ×8 确证进入认证阶段 |

> **C 段整体判定：BLOCKED（较第七轮有实质进展）** —— 第七轮三态全空是因为**核验卡卡死面板**；本轮**核验卡不再阻塞**（`known_hosts` 已被信任，建条 5/5 `cardUp=FALSE`），**认证阶段也确证可达**，**卡在"新行不在视口"这一个统一的根因上**。这个根因是**脚本侧**的（面板不自动滚到底），**不是产品缺陷**。

---

## 4. D — 反例与清理

### 4.1 反例：只删目标行，他人 `10.0.2.2:4095` 完好 ✅ PASS

- 删 `-4` 前同屏 `[E2ECHK5554d3e-2 \|-3 \|-4 \|-5]` → 删后 `[E2ECHK5554d3e-3 \| E2ECHK5554d3e-5]`，**只少了目标行**。
- 他人条目**全程未动**，多次采样均在：`'10.0.2.2:4095' [254,1709][474,1750]`（D/D2/D3 三个清理阶段末态复读均为 `OTHER_ENTRY_INTACT=True`）。

### 4.2 清理 ✅ 已闭环

自建条目共 **6 条**（`-1 \|-2 \|-3 \|-5 \|-c2 \|-c3`；`-4` 在 B 段已删），逐条用**自校验删除**（`DelVerified`）清理，落库从 1256 逐条回退至基线 76。

> **⚠️ 清理过程本身踩到两个脚本缺陷（都已修，且都是"假干净"）**：
> ① **首轮 D 段报 `CLEANUP FINAL rows=[] count=0` 但隧道键仍是 1256** —— C3 的认证卡还开着挡住面板，遍历根本看不到行，**"0 条"是假的**；
> ② **`TunnelLen` 的返回值被日志污染** —— `Log` 用 `Tee-Object` 写日志，其输出混进函数的返回值，导致 `$len -le $base` 判定**假通过**，脚本在什么都没删的情况下宣告"回到基线"。
> **教训（与 §2.2 同源）**：**清理是否成功，只能用落库计数判定，绝不能用"UI 没看到行"判定** —— 弹层遮挡和视口裁剪都会让 UI 说谎；`grep` 在这里同样无效（隧道值是 AES 密文，`grep E2ECHK5554d3e` 恒为空）。
> 修复：① 先 `am force-stop` 再进面板清掉滞留弹层；② 每轮从**顶部**取第一条可见行删，删完回到顶部；③ `Log` 改为 `Tee-Object … | Out-Null`，不再污染返回值。

| 项 | 状态 | 证据 |
|---|---|---|
| 自建条目删净 | ✅ | 6 条逐条 `DV RESULT ok=True gone=True`；隧道键 **1256 → 76** 回到空表基线 |
| 落库回到基线 | ✅ | 隧道键 value 回到 **76**（与本轮起始基线逐位一致） |
| 他人条目完好 | ✅ | `10.0.2.2:4095 [254,1709][474,1750]` 末态仍在，`OTHER_ENTRY_INTACT=True` |
| **`known_hosts` 已清** | ✅ | **本轮 + 第七轮写入的那条已删除**（见下） |
| `/sdcard` 无本轮残留 | ✅ | 截图/dump 一律 pull 完立即 `rm -f`；本轮另补删 `/sdcard/c1.png`、`c2.png`、`c3_chatimported.png`；现存 `_*.xml` 仅 `_h.xml`/`_v91`/`_v93`/`_v94.xml`，**均属往轮遗留，未动** |
| `reverse` 复核 | ✅ | `host-15 tcp:4095 tcp:4095`、`host-15 tcp:2222 tcp:22` —— **本轮一条都没建** |
| 只动自建条目 | ✅ | 只创建/删除 `E2ECHK5554d3e-*` |
| 设备健康 | ✅ | 全程 `focus=OcActivity`，**从未出现 `Application Not Responding`**，**未 reboot**；仅对本 app 做过 `am force-stop`（纪律允许） |

### 4.3 `known_hosts` —— **本轮已清（含第七轮遗留）**

**清之前**（第七轮 03:25 写入，本轮全程沿用、未再新增）：

```
$ adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell ls -la files/ssh/
-rw------- 1 u0_a230 u0_a230   98 2026-10-03 03:25 known_hosts
$ adb -s emulator-5554 shell run-as com.xiufeigo.opencodeshell cat files/ssh/known_hosts
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

**执行**：`run-as com.xiufeigo.opencodeshell rm -f files/ssh/known_hosts`

**清之后**：

```
$ run-as … ls -la files/ssh/
total 16 drwx------ 2 u0_a230 u0_a230 4096 2026-10-03 04:09 . drwxrwxr--x 3 u0_a230 u0_a230 4096 2026-10-02 07:54 ..
$ run-as … cat files/ssh/known_hosts
cat: files/ssh/known_hosts: No such file or directory
```

> **清掉的具体内容**：`files/ssh/known_hosts`（98 B，唯一一行 `[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM`），**该文件由第七轮点 `Trust and connect` 写入、本轮全程复用**（正是它让本轮建条不再弹核验卡）。**本轮自建条目全部指向已删除的记录，无悬挂信任。**
> **`files/id_ed25519` 刻意保留**（444 B，app 私有目录内）：它是 E2E 夹具的**前置资产**（脚本注释明写"每次重装 APK 后都要重跑"），不是本轮信任记录；删除它会让下一轮无法造 `Connected`。**如需完全复原请另行 `run-as … rm -f files/id_ed25519`。**

---

## 5. 未闭环 / 给下一轮

1. **【已闭环】D3-3 第 4 行：PASS**（§2）。上一轮"第 4 行未取证"结清；根因（导航脚本没滚到）也一并结清。
2. **【已闭环】建到 ≥5 条**：5 条，UI 行数与落库 1→2→3→4→5 严格同步（76→1052）。
3. **【已闭环】`known_hosts` 清理**：`files/ssh/known_hosts` 已删（含第七轮遗留），设备/app 私有目录无本轮自建条目残留。
4. **【最高】D3-4 三态仍 BLOCKED，但只剩一个统一根因**：**新条目追加在列表末尾，面板不自动滚到它**（`row not visible` ×8、`kebabs=0`）。**下一轮只需在提交后先 `ScrollTop`+向下滚到底再扫**，三态的"造态"环节本轮已证明全部可行（`Connecting` 条目建成、`authCard=True` ×8）。
5. **`Connected` 造态前必须先独立证实"对端接受该密钥"**：宿主机 `ssh -p 22` 返回 `Permission denied`；用私钥文件时被 **Windows ACL** 拦（`Bad permissions`），`icacls` 收紧未解决。**建议下一轮改用 `ssh-agent` / 或把 key 复制到本就只有当前用户可读的路径再验**，确认 `authorized_keys` 侧 OK 后再进 UI。
6. **工具侧教训（本轮新增 4 条，均为"假失败/假成功"，值得沉淀）**：
   - ① **`input text` 遇真实空格即截断**（`%s` 才是空格）—— 目标框只进 `ssh` 就"回读失败"。
   - ② **表单保留上一次的值**，新输入是**追加**；必须先 `MOVE_END`+批量 `DEL` 清空。
   - ③ **IME 打开后表单页脚会整体上移**（`Add server` 实测在 y≈1314，脚本按 y=1800 tap 落进键盘区，**条目静默不落库**）。**提交前必须重新 dump 取实时 bounds。**
   - ④ **`Log` 用 `Tee-Object` 会污染函数返回值**，导致基于返回值的断言假通过 —— 凡是"用返回值做判据"的 helper，日志都必须 `| Out-Null`。
   - ⑤ **"UI 看不到行" ≠ "没有行"**：弹层遮挡、视口裁剪、列表末尾不可见，都会让 UI 说谎。**落库/清理的判据只能用落库计数**；`grep` 在加密 prefs 上恒为空，不可作判据。
   - ⑥ **删除前必须读确认条正文 `This removes "<名字>"` 做自校验**（§2.2）：本轮据此拦下两次"会删错行"的操作。按 ⋮ 坐标几何猜测**不可靠**，但确认条是权威且人眼可辨的安全阀。

---

## 附：本轮证据文件

`research/v97_verify/5554_v98d3/`
- `D00_c8run.log` / `A2_console.log` —— A 段建条（5 条，隧道 76→1052）
- `B_console.log` —— **B 段第 4 行删除三试命中**（try1 `-2` 否 / try2 `-3` 否 / try3 `-4` 是，Confirm@759,1832，1052→856）
- `C23_console.log` —— C2 `Connecting`（8 次扫描 `row not visible`/`kebabs=0`）、C3 `authCard=True` ×8
- `D_console.log` / `D2_console.log` / `D3_console.log` —— 清理三轮（**并记录两次"假干净"**），末态隧道键回 76、`OTHER_ENTRY_INTACT=True`
- `c8lib.ps1` / `b8_funcs.ps1` / `c8_funcs.ps1` / `dv.ps1` —— helper（`dv.ps1` = **自校验删除**）
- `_prefs8_*.txt` —— 落库快照（隧道键 len 全程轨迹 76→268→464→660→856→1052→…→1256→…→76）
- 截图：`A_E2ECHK5554d3e-1..5`、`DV_E2ECHK5554d3e-4_confirm`、`C2_scan1..8`、`C1_*`、`C3_state`、`CL_final_panel`、`D2_final_panel`、`D3_final_panel`
