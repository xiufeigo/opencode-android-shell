# REPORT_EDIT_LOAN_FIX — 编辑贷款（edit loan）真机复验 v132

> **结论：PASS。** 编辑后 **提交** 与 **取消** 两条路径上，composer 的 chip 都**没有**被钉死在 `Steer`；
> composer 一旦重新有内容，chip 一律恢复为 **`Queue`**，且点它**队列条可以再次打开**。
> v130 的缺陷签名（**恒显 `Steer`、入口消失**）在真机 **emulator-5554** 上**没有复现**。
> 3 轮幂等（提交 / 取消 / 提交）**全部通过**。`Steer`/`Remove` 的 `48 / 48 / clickable` 与 768 档的
> `140 / 92 / −10` **逐位未漂**。

- 设备：`emulator-5554`（Medium_Desktop，Override `1366x768` / `160`）
- 包名：`com.xiufeigo.opencodeshell`　`appPid` **13631**（全程未变）
- 证据目录：`research/v97_verify/5554_md/artifacts/`（`*.xml` dump + `*.png` 截图，逐标签编号）
- 覆盖：v131「编辑贷款出口」修复的真机复验（此前为 JVM 证据）

---

## 0. 构建 / 后端 / 归属核对

| 项 | 实测 | 要求 | 结论 |
|---|---|---|---|
| 本地 `app-debug.apk` MD5 | `60875A0424D8BE497218F9602B90905E` | `60875a0424d8be497218f9602b90905e` | ✅ **逐位一致** |
| **设备实装 `base.apk` MD5** | `60875a0424d8be497218f9602b90905e` | 同上 | ✅ **逐位一致**（两侧都比过，排除「本地产物对、装上去是别的包」） |
| `lastUpdateTime` | `2026-10-03 21:21:35` | Lead 刚装 | ✅ 核对时设备钟 `2026-10-03 21:22:17` → **42 秒前**装的 |
| `versionName` / `versionCode` | `1.4.0` / `26` | — | — |
| 归属窗口 | `Window{91dbf u0 com.xiufeigo.opencodeshell/.OcActivity}`，`mOwnerUid=10189` | 属本 app | ✅ 全程窗口唯一（无他 app 弹层混入） |
| `adb reverse` | `host-19 tcp:4095 tcp:4095` | 后端 v2 在 4095 | ✅ |
| 认证态 | 首屏即会话列表 + composer，**无登录页** | 允许已认证 | ✅ 全程已认证，**未重填**账号/密码 |

**本轮含 v131 修复**（编辑贷款出口）——MD5 在本地产物与设备实装包两侧都逐位对上。
**未**执行 gradle / 装机 / 杀进程；仅做过一次应用内 `Stop`。

---

## 1. 主判据：编辑后 `Queue` 入口是否恢复

### 1.1 取样姿势（真机确认的充要条件）

`ComposerDelivery.kt:54-58` `alternate()`：
`if (editingQueued) return STEER`；`if (!running || !hasContent) return null`。

⇒ `Queue` chip 出现需要**同时**满足：① 有回合 `running`　② composer `hasContent`　③ 后端 v2（`isQueueSupported`）。

**本轮踩到的两个姿势坑（写给下一轮）**：

1. **`Stop` 按钮 = running 的可靠标志**，`Working` 标签时有时无（本轮多次出现「回合明明在跑、`Working` 却抓不到」）。
2. **回合一跑完 chip 就消失**：`Counting from 1 to 6000` 那类长回合实测 **约 1–2 分钟就跑完**，
   抓屏稍慢一步 `Queue` 就没了。3 轮幂等必须用更长的回合（`…to 20000`）才够铺开。

### 1.2 提交路径（v130 场景复现）— ✅ 通过

| 步骤 | 动作 | 观测（chip 文案原文 / 节点 bounds） |
|---|---|---|
| `a05` | 回合 running + 填入 `E2ECHK5554u1t rowA first` | chip 文案 = **`Queue`**，bounds `[1271,309][1308,324]` |
| `b01` | 点 `Queue` | **队列条出现**：`Steer` `[1263,183][1311,231]` h=48、`Remove` `[1302,182][1350,230]` h=48，两者 `clickable=true`；行 `E2ECHK5554u1t rowA first` `[71,198][237,214]`；`Queued` 标签 `[22,199][63,213]` |
| `b02` | 点行**正文** → 进编辑 | chip 文案 = **`Steer`** `[1277,309][1308,324]`（编辑态，**符合预期**）；composer 读回该行文本 |
| **`b03`** | **提交**（点 `Steer` chip） | chip = **既无 `Steer` 也无 `Queue`**，只剩 `Stop` `[1308,292][1356,340]` → **没有恒显 `Steer`**；composer 被清空为 `''`；该行移出队列（时间线侧 `[1128,194][1300,214]`） |
| **`b04`** | 再填入 `E2ECHK5554u1t probe` | chip 文案 = **`Queue`** `[1271,309][1308,324]` ✅ **入口恢复** |
| **`b05`** | 再点 `Queue` | **队列条再次出现**：`Steer` `[1263,183][1311,231]` + `Remove` `[1302,182][1350,230]`；新行 `E2ECHK5554u1t probe` `[71,198][214,214]`；`Queued` `[22,199][63,213]` ✅ |

**判读（重要，别误读 b03）**：`b03` 提交后看不到 `Queue`，是因为提交把 composer 清空、`hasContent=false`
（chip 只在有内容时出现），**不是** v130 的「入口消失」——
`b04` 立刻证明入口回来了。**v130 的缺陷签名是「提交后 `Steer` 永驻」，本轮没有复现。**

### 1.3 取消路径 — ✅ 通过

取消的官方入口是 **`Esc`**（`Composer.kt:1644-1647`：
`if (event.key == Key.Escape && queueEdit != null && !popoverOpen) { cancelQueueEdit() }`）。
真机**必须先让 IME 收起**，否则按键被 IME 吃掉——v130 报告已记过这句
（`Composer.kt:632`：*「Esc could not save it either, because the IME swallows the key while the keyboard is up (v130, emulator-5554)」*）。

| 步骤 | `mInputShown` | 动作 | 观测 |
|---|---|---|---|
| `c01` | `true` | 点行正文 `E2ECHK5554u1t probe` → 进编辑（**未动内容**） | chip = **`Steer`** `[1277,309][1308,324]`；composer 读回 `'E2ECHK5554u1t probe'`，与行**逐字一致** |
| `c02` | `true` | **第 1 次 `Esc`** | **只收起 IME**；编辑**未取消**，chip 仍是 `Steer` `[1277,679][1308,694]`；`Remove` `[1302,552][1350,600]` |
| **`c03`** | **`false`** | **第 2 次 `Esc`** | **编辑被取消**；chip = **`Queue`** `[1271,679][1308,694]` ✅；行仍为 `Queued  E2ECHK5554u1t probe` `[71,568][214,584]`（**内容未变、未被投递**） |

**判读**：取消路径同样恢复到 `Queue`，且**没有副作用**（行还在队列里，文本没被动）。
关键细节：**`Esc` 在 IME 打开时是空操作的**——这一条在收尾清理时又独立复现了一次
（chip 由 `Steer` 变回 `Queue`），所以它稳定成立，不是偶然。

---

## 2. 3 轮幂等（提交 / 取消 / 提交）

脚本 `rounds2.ps1`；每轮都重新解析行坐标、逐轮断言。**入编辑**用「与 `Queued` 标签同行带配对」定位，
避免误点 composer 或时间线里已 steer 的行（第一版脚本栽在这上面，轮次作废后重写）。

| 轮 | 动作 | 成功进入编辑 | 编辑态 chip | **动作后 chip** | **队列条可再打开** |
|---|---|---|---|---|---|
| 1 | **提交** | `YES` | `Steer` | **`Queue`** ✅ | **`YES`** ✅ |
| 2 | **取消** | `YES` | `Steer` | **`Queue`** ✅ | **`YES`** ✅ |
| 3 | **提交** | `YES` | `Steer` | **`Queue`** ✅ | **`YES`** ✅ |

**3/3 全部通过。** 每轮的动作后 chip 都是 `Queue`（不是 `Steer`），且点 `Queue` 队列条都能再次出现。
（`artifacts/rounds.csv`，逐轮截图 `R{1,2,3}_edit` / `_after` / `_reopen`。）

---

## 3. 不回退复核

### 3.1 `Steer` / `Remove` 尺寸与可点性（v128 基线 **48 / 48 / 全 clickable**）

队列条在屏时的 8 个独立采样：

| 来源 | 节点 | bounds | h | clickable |
|---|---|---|---|---|
| `b01` | `Steer` | `[1263,183][1311,231]` | **48** | `true` ✅ |
| `b01` | `Remove` | `[1302,182][1350,230]` | **48** | `true` ✅ |
| `b05` | `Steer` | `[1263,183][1311,231]` | **48** | `true` ✅ |
| `b05` | `Remove` | `[1302,182][1350,230]` | **48** | `true` ✅ |
| `c03` | `Steer` | `[1263,553][1311,601]` | **48** | `true` ✅ |
| `c03` | `Remove` | `[1302,552][1350,600]` | **48** | `true` ✅ |
| `d6` | `Steer` | `[1263,183][1311,231]` | **48** | `true` ✅ |
| `d6` | `Remove` | `[1302,182][1350,230]` | **48** | `true` ✅ |

**⇒ 8/8 逐位复现 48 / 48 / `clickable=true`，未回退。**
`Remove` 的 a11y 标签确认为 **`Remove`**，从来不是 `×`（与 v128 记录一致）。

### 3.2 两档几何（口径沿用 `REPORT_BASELINE_RECHECK.md` §1）

口径：`unionBottom` = composer 子节点并集底沿（从 editor 顶沿起算，排除 `Open terminal`）；
`paneBottom` = pane 宿主盒 `y2`；`①(i) = unionBottom − paneBottom`；`② = unionPx ≤ 48`。
**两档读数前都硬断言 `mInputShown=true`**（脚本 `AssertIme`，不为 true 直接退出）。坐标全部运行期从 dump 解析。

#### 1366x768（`mInputShown=true`，队列条在屏，`Stop` 表示回合 running）

```
editor      [28,253][1338,301]  h=48
Add         [10,292][52,340]    h=48
Stop        [1308,292][1356,340] h=48
union       = [10,253][1356,340]   unionPx=87   unionBottom=340
pane host   = [0,162][1366,350]    paneH=188    paneBottom=350
```

| 项 | 本轮实测 | v128 基线 | 是否漂 |
|---|---|---|---|
| pane 盒 h | `188` | `188` | 否 |
| **时间线盒**（`188−48`） | **`140`** | **`140`** | **否（逐位）** ✅ |
| **readable**（`188−96`） | **`92`** | **`92`** | **否（逐位）** ✅ |
| **①(i)**（`340 − 350`） | **`−10`** | **`−10`** | **否（逐位）** ✅ |
| ② `unionPx(87) ≤ 48` | **假** | 假 | 否 |
| `mInputShown` | `true` | `true` | 否 |

#### 1366x600（`mInputShown=true`，队列条在屏）

```
editor      [24,214][405,262]  h=48
Add         [399,215][441,263] h=48
Stop        [1316,215][1364,263] h=48
union       = [24,214][1364,263]  unionPx=49   unionBottom=263
pane host   = [0,162][1366,261]   paneH=99     paneBottom=261
```

| 项 | 本轮实测 | v128 基线 | 是否漂 |
|---|---|---|---|
| pane 盒 h | `99` | `99` | 否 |
| **readable**（`99−48`） | **`51`** | **`51`** | **否（逐位）** ✅ |
| **①(i)**（`263 − 261`） | **`+2`** | `−12` | **⚠️ 见下，非回退** |
| ② `unionPx(49) ≤ 48` | **假** | 真 | ⚠️ 见下 |
| `mInputShown` | `true` | `true` | 否 |

**关于 600 档 ① 的 `+2` vs `−12`——这是取样状态差异，不是漂移，如实说明：**

- v128 的 600 档是**队列条关闭**时的「单行形态」：composer `[24,201][…][…,249]`，`unionBottom=249`、`unionPx=48`。
- 本轮 600 档**队列条在屏**，composer 被队列条整体下推 `13px`：`unionBottom=263`、`unionPx=49`（**恰好 +14**）。
- pane 宿主盒 `[0,162][1366,261] h=99` 与基线**完全一致**——队列条吃掉的就是时间线那 14px，
  这正是 `ComposerQueuePanel.kt:70-95` 记录的设计（队列条是 composer Column 的第一个子节点，先于卡片被测量）。
- 想复现 `−12` 需要**队列条关闭**；但本轮队列里有 6 行待清理（见 §4），
  行内 `Remove` 处在**裁剪行**里、**不进 a11y 树**（`ComposerQueuePanel.kt:92-95` 明确记录的行为），
  因而**无法在 UI 上把队列清空到关闭态**，本轮**没有**取到 600 档的「条关闭」读数。**此项记为未闭环。**
- 768 档的 `140 / 92 / −10` 是在**与基线同一形态**（队列条在屏、单行）下测得的，三项全部逐位吻合。

---

## 4. 收尾

| 项 | 状态 | 证据 |
|---|---|---|
| `wm` 显式重设回 `1366x768` / `160` | ✅ **已重设并复核** | `Override size: 1366x768`、`Override density: 160`（末次读数） |
| 窗口归属终检 | ✅ | `mCurrentFocus=Window{91dbf u0 com.xiufeigo.opencodeshell/.OcActivity}` |
| `appPid` | ✅ 全程 `13631`，未重启 | — |
| `/sdcard` **新增**残留 | ✅ **无** | 本轮自建临时文件只有 `/sdcard/__e2e.png`、`/sdcard/__e2e.xml`，已 `rm -f` 清掉；顶层条目数 `60 → 58`（净减 2，恰为本轮自建） |
| 已知 44 项历史残留 | **未触碰** | 按纪律**没有**去绕权限网关 |
| 回合 | ✅ 已 `Stop` | 收尾前点过 `Stop` |
| **自建排队行清理** | ❌ **未闭环** | 见下 |

### 自建排队行清理 — 未完成，写清原因

- 本轮自建（`E2ECHK5554u1t`）排队行在收尾时剩 **6 行**（`a05`/`b04`/`R1`/`R2`/`R3` 各轮产生；
  提交按设计是**「re-admit 替换 + 取消原行」**，所以行数不降反增 —— 见 `Composer.kt:1298-1305`）。
- 尝试过的合规手段（均未奏效）：
  1. 行内 **`Remove` 按钮**点按 —— 多数采样里 `Remove` **不在 a11y 树**（`RemoveButtons=0`），点不到；
  2. 由 dump 推导坐标直接点 `Remove` 位置（`x=1326`, 行带 `y`）—— 无效果；
  3. **长按 ≥1200ms** —— 未弹出任何菜单；`dumpsys window windows` 复核**窗口列表未变**（仍只有 `OcActivity`，无新弹层），故也未「验明弹层属本 app」后再删；
  4. 在队列条内**上滑到顶**让动作进入 a11y 树 —— 滑不动（IME 打开时手势被 IME 吃掉），连续 14 轮迭代无进展，已主动终止。
- 纪律**禁止 `BACK(4)`**，收尾阶段也没有其它合规的「清空队列」入口。
- **结论：残留 6 行 `E2ECHK5554u1t` 排队行，判定为未闭环**，需下一轮在**队列条关闭态**下用
  `/find/file` 或队列行动作清理，或由 Lead 提供可滚动队列条的队列管理入口后处理。
  这**不影响**本报告的主判据结论（主判据所需的队列条状态均已在清理前取证完毕）。

---

## 5. 源码侧交叉印证（为什么这次会过）

`Composer.kt:625-637`（v131 注释原文要点）：

- 此前贷款是**两个**状态：队列行 `queueEdit` **加上**一个只有 `editQueued` / `cancelQueueEdit` 会动的 `queueEditOpen` 闩锁。
  提交路径关掉了行、**却把闩锁留在 on**，于是 `ComposerDelivery.alternate` 第一行
  `if (editingQueued) return STEER` 会在**这个 composer 的余生**都答 `Steer`，`Queue` 入口再也回不来。
- v131 收敛为**一个**状态：`var queueEdit by remember { mutableStateOf<ComposerQueue.Row?>(null) }`，
  `val queueEditOpen = queueEdit != null`。**一个状态无法分叉**，闩锁不可能再单独卡住。
- 提交出口 `Composer.kt:1298-1305`：`queueEdit = ComposerQueue.endEditLoan(editing, CONFIRM)` —— **确认即出口**。
- 取消出口 `Composer.kt:1644-1647`：`Esc` → `cancelQueueEdit()` → `endEditLoan(..., CANCEL)`。
- `ComposerQueuePanel.kt:98`：`if (rows.isEmpty()) return` —— 队列条必须至少 1 条排队行才存在。

真机观测与该改动一致：**提交**（`b03`→`b04`）与**取消**（`c03`、收尾复现）两条出口上 chip 都不再被钉在 `Steer`。

---

## 6. 未闭环

1. **600 档「队列条关闭」形态的 `① = −12` 未取到读数**（队列条无法清空到关闭态，见 §3.2）。
   768 档三项（`140 / 92 / −10`）已逐位复现；600 档的 `paneH=99` / `readable=51` 亦逐位复现。
2. **自建 6 行 `E2ECHK5554u1t` 排队行未清掉**（UI 手段穷尽，见 §4）。
3. **`Esc` 必须「两次」的成因未定位到具体归属**：`keyevent 111` 在 IME 打开时被 IME 吞掉，
   已按 v130 的既有记录处理（收 IME + 再取消），但**没有**去查 IME 是否可通过合规方式预置为关闭态，
   所以取消路径在自动化上仍是「两次按键」。
4. `Working` 标签抓取不稳定，**running 判定改用 `Stop` 按钮**；若 `Stop` 也因瞬时布局抓空，会误判「回合已结束」。

---

## 7. 复现命令

```powershell
# 0) 核对设备与包
adb -s emulator-5554 shell wm size ; adb -s emulator-5554 shell wm density
adb -s emulator-5554 shell md5sum (adb -s emulator-5554 shell pm path com.xiufeigo.opencodeshell).Trim().Replace('package:','')

# 1) 主判据：提交 / 取消后 chip 是否恢复 Queue（见 §1.2 / §1.3 的 a05→b05、c01→c03）
# 2) 3 轮幂等
research\v97_verify\5554_md\rounds2.ps1
# 3) 两档几何（先切档，切完再跑；读数前会硬断言 mInputShown）
adb -s emulator-5554 shell wm size 1366x600 ; adb -s emulator-5554 shell wm density 160
research\v97_verify\5554_md\geom.ps1 -Tag g600
adb -s emulator-5554 shell wm size 1366x768 ; adb -s emulator-5554 shell wm density 160
research\v97_verify\5554_md\geom.ps1 -Tag g768
```

辅助脚本：`helper.ps1`（dump/截图/坐标/IME 断言/logcat）、`chips.ps1`（chip 文案取证）、`geom.ps1`（几何）、
`rounds2.ps1`（3 轮）、`cleanup2.ps1`（队列清理尝试）。
