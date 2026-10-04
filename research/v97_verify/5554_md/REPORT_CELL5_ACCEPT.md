# REPORT_CELL5_ACCEPT —— v147：真机验收 ⑤ 的修复（照 v145 六条判据逐条判定）

- 执行：mavis（mcode），**v147 · 真机验收测试员**
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：**`emulator-5554`**（Medium_Desktop，Override `1366x768` / `160`，物理 3840x2160 / 320）
- 验收对象：`research/v97_fix/CELL5_FIX.md`（v146）—— `SessionScreen.kt` 的 `SessionTerminalEntryPolicy.END_DP` **8dp → 64dp**
- 判据来源：`research/v97_verify/5554_md/REPORT_BASELINE_AND_CELL5.md`（v145）
- 证据目录：`research/v97_verify/5554_md/artifacts/`，驱动脚本 `v147_*.ps1`

> **一句话结论**：**六条判据 6/6 PASS**（含像素级的 ③：`chipVisiblePixelCount = 2304 > 0`），
> **基线四数 140 / 92 / 51 / −10 / −12 逐位未漂**，缩窗往返 2 轮零 FATAL。
> ⑥ 修复的 chip 左移 56dp **没有压住消息正文**（像素级：chip 区内 0 个深色文字像素，
> 正文文字止于 `x=534`，chip 起点 `x=1264`）——**取舍可接受**。
> **docked 形态已复验**（pane 宽 820px ≫ 112dp 下限），判据 1/3 两档全 PASS。
> **状态 = PASS**。

---

## 0. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| APK md5 | `f927323f80412a6ed6e503aa4983dd0c` | **`f927323f80412a6ed6e503aa4983dd0c`** | ✅ **逐位一致** |
| `lastUpdateTime` | （Lead 刚装） | **`2026-10-04 01:07:34`** | ✅ 已记录 ⇒ **无需重装** |
| `versionName` / `versionCode` | — | `1.4.0` / `26` | — |
| `firstInstallTime` | — | `2026-09-23 13:58:18` | — |
| APK 路径 | — | `/data/app/~~s_6V3gBLO…/base.apk`（44,198,253 B） | — |
| `Override size` | `1366x768` | `1366x768` | ✅ |
| `Override density` | `160` | `160` | ✅ ⇒ **1dp == 1px** |
| 后端 v2 | 4095 在线 | `GET http://127.0.0.1:4095` → **HTTP 200**（len 6147） | ✅ |
| `adb reverse` | `tcp:4095` 已挂 | `host-19 tcp:4095 tcp:4095`（另有 `tcp:2222`） | ✅ |
| 归属 | 交互全程本 app | `mCurrentFocus=…com.xiufeigo.opencodeshell/.OcActivity` | ✅ |
| `appPid` | 全程未变 | **`1425`**（五腿逐位相同） | ✅ |
| 源码常量 | `END_DP = 64.dp` | `SessionScreen.kt:3644` `val END_DP: Dp = 64.dp` | ✅ **含 ⑤ 的修复** |

`adb` 不在 PATH，走 `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`。
**未跑 gradle、未 `assembleDebug`、未装机**（md5 一致 ⇒ 无需装）、未杀进程（仅本 app 一次 `am force-stop` 在启动段）；
未按 BACK(4)、未在弹层内发 ESC(111)；截图 `screencap -p` + `adb pull`；
坐标全部**运行期从 dump 解析**，切档后重新解析；**边做边写盘**；
读高度前一律 `AssertIme` 硬断言 `mInputShown`（全部帧 `True`）；
`logcat` 一律带 `-t` 且按 **`$appPid`** 过滤（**未使用 `$pid`**）。

---

## 1. 任务 A：六条判据逐条读数与判定（**主判据**）

**形态**：**一行形态**（`1366x600` + `wm density 160`）—— 即 v145 判 ⑤ 为真缺陷的那一形态
（`Open terminal` 与 `Send` 曾占同一矩形、且同为 48dp 高）。
**队列条形态**：`barNodes = 0`（`Steer`/`Remove`/`Queue` 全为 0）⇒ 条关，与 v145 母帧同口径。

### 1.0 dump 原文（逐字，从 `artifacts/C1_600_ONEROW.xml` 解析）

```
C1_600_ONEROW.xml  Open terminal : index=15 bounds=[1264,192][1312,240] clickable=true enabled=true
C1_600_ONEROW.xml  Send          : index=19 bounds=[1316,191][1364,239] clickable=true enabled=false

C0_768.xml         Open terminal : index=15 bounds=[1264,192][1312,240] clickable=true enabled=true
C0_768.xml         Send          : index=19 bounds=[1308,292][1356,340] clickable=true enabled=false
```

z 序：chip `index=15` < `Send` `index=19` ⇒ **`Send` 仍在上**（两框已不相交，z 序不再是缺陷载体）。
**600 档 `Send` 仍 `enabled=false`**（composer 为空），但 chip `enabled=true` ⇒ 不再是"禁用按钮压死可用按钮"。

### 1.1 逐条判定

| # | 判据 | 600 档读数 | 768 档读数 | 判定 |
|---|---|---|---|---|
| ① | `overlapArea == 0` | **`0`**（intersection `0×47`） | **`0`**（intersection `4×0`） | ✅ **PASS** |
| ② | `narrowestResidualPx ≥ 16` | **`48`**（coverage `0×0` ⇒ residual `48×48`） | **`48`**（residual `48×48`） | ✅ **PASS** |
| ③ | `chipVisiblePixelCount > 0` | **`2304`**（面积 2304，被 `Send` 覆盖 **0**） | **`2304`**（覆盖 **0**） | ✅ **PASS** |
| ④ | **两靶仍各 48 高** | chip `h=48`，send `h=48` | chip `h=48`，send `h=48` | ✅ **PASS** |
| ⑤ | **768 档仍 0 遮挡** | — | **`0`**（纵向分离 `52px`；横向差 `−4` 不成矩形） | ✅ **PASS** |
| ⑥ | 两者 `clickable == true` | chip `true` / send `true` | chip `true` / send `true` | ✅ **PASS** |

**六条 6/6 PASS。**

### 1.2 ② 的口径说明（我第一版度量写错，已当场纠正并记账）

`narrowestResidualPx` 的正确口径是 **chip 盒减去 `Send` 覆盖的那部分**；
**当交集在任一轴上为空时，覆盖面积必须归零**，此时残余 = 整盒 `48×48` ⇒ `min(48,48) = 48`。

- 我第一版脚本**漏了这个归零**：768 档横轴交集 `4px` 但纵轴 `0`，
  却把纵向覆盖算成 `47` ⇒ 报出 `residual 44×48`、`600` 档报出 `48×1` ⇒ **假 FAIL**。
- 修正后（`covW/covH` 仅在 `ow>0 且 oh>0` 时取值）两档均为 `48` ⇒ **PASS**。
- 另：v147 早期一版把"两框之间的**间隙**"当成残余，报出 `600` 档 `R48x4 ⇒ narrowest=4`，
  同样是度量错误（间隙不是 chip 的像素）。**两处均为我自己的脚本 bug，不是产品缺陷。**

### 1.3 判据 ③ 的**像素级**证据（非仅 a11y）

`artifacts/v147_A_600_ONEROW.png` 上，chip 矩形 `[1264,192][1312,240]` 内逐像素扫描：

| 口径 | 读数 |
|---|---|
| chip 面积 | `48 × 48 = 2304 px` |
| 其中落在 `Send` 矩形 `[1316,191][1364,239]` 内的 | **`0 px`** |
| `chipVisiblePixelCount` | **`2304 px > 0`** ✅ |
| chip 区间色彩直方图 | `250,250,250 × 1824`（chip 本体）、`255,255,255 × 432`（白底）、`225,225,225 × 48`（描边） |
| chip 本体主色 | `250,250,250` = chip 自己的表面 ⇒ **确为 chip 在绘制**，不是背景 |

对照 v145（修复前）：chip 本体被 `Send`（`enabled=false`）全灭，可见像素 **0**，唯一露出的
`x 1354..1365` 两行皆纯白 `R255`。**本轮从 0 变为 2304，判据 ③ 成立。**

**肉眼可辨**：`artifacts/v147_ZOOM_600_chip_send.png`（`x1250 y185 w120 h60`，**4× 最近邻放大**）
清楚显示 **chip 的上箭头图标**在左、`Send` 的圆角灰按钮在右、两者之间有一道**可见白色缝隙**，
两个控件各自完整、无任何互相压盖的痕迹。

---

## 2. 任务 B：新引入的风险与未覆盖形态（v146 点名）

### 2.1 判据 7 —— chip 左移 56dp（`end: 8 → 64`）**是否压住消息正文**

| 项 | 读数 |
|---|---|
| 修复前 chip 位置（`END_DP=8`） | `[1320,192][1366,240]`（46 宽，被右缘裁 2） |
| 修复后 chip 位置（`END_DP=64`） | `[1264,192][1312,240]`（**48 宽，完整**） |
| 水平位移 | **左移 56 px（= 56dp，density 160）** |
| 与 chip 矩形相交的正文/工具条节点 | `HorizontalScrollView [441,201][1316,229]`（875×28），交叠 `48×28` |
| **该条内实际文字像素**（`GPT-6.1 Sol` 等）范围 | **`x = 468 .. 534`**（`R<120` 深色像素，共 86 个） |
| **文字像素范围 ∩ chip 范围** | **`max(0, min(534,1312) − max(468,1264)) = 0 px`** |

**判定：可接受（不压正文）。**

理由三条，彼此独立：

1. **几何**：chip 覆盖的是该 `HorizontalScrollView` 工具条的**空白段**（`x 1264..1312`），
   而条内全部文字止于 `x=534`，相距 **730 px**。
2. **像素**：对 chip 矩形 `[1264,192][1312,240]` 内逐像素统计**深色文字像素 = 0**。
   区域内主色是 `250,250,250` 的 chip 自身表面，**没有任何字形被压**。
3. **视觉**：4× 放大图中 chip 箭头完整，其下方为浅色工具条底，无截断字形。

**取舍性质**：这确实是六条判据**不覆盖**的视觉取舍 —— 判据只管两个**靶**的关系，
不管 chip 与**正文/工具条**的关系。本轮实测该风险**未兑现**：左移 56dp 后 chip 落在工具条空白区。
**但这是"当前文案下未压"，不是"结构上不可能压"**：若将来工具条里出现延伸到 `x>1264` 的内容
（右侧锚定元素、尾部 chip），左移的 chip 会与之相争。**该风险为条件性、未被六条判据覆盖，
建议留作后续形态回归项。**

### 2.2 判据 8 —— docked panel 形态（`end=64` 要求 pane 宽 ≥ 112dp）：**已构造并复验**

**docked 形态真实存在**，且 pane 宽度**远超** 112dp 下限：

- 构造方式：点 chip 挂载终端面板（**未按 BACK(4)、未发 ESC(111)**）⇒ `paneRight = 820`
  （`chip.right + 54`，即 `END_DP=64` 换算），**pane 宽 820px ≫ 112dp**。
- 一处如实记账：首次进入脚本时 app **已处于 docked 态**（`chip=[718,192][766,240]`），
  我的"挂载"tap 实际是**取消**docked，脚本在 `AssertIme` 处**正确抛错中止**（dock 收起了 IME）。
  我改为"先探测当前态、必要时再 tap"，并**先点 composer 恢复 `mInputShown=True` 再读高度**。

| 档 | chip bounds | send bounds | 判据 ① | 判据 ③ |
|---|---|---|---|---|
| **docked 768** | `[718,192][766,240]` 48×48 | `[762,292][810,340]` 48×48 | `overlapArea = 0`（`4×0`）✅ | `2304`，覆盖 `0` ✅ |
| **docked 600** | `[718,192][766,240]` 48×48 | `[770,191][818,239]` 48×48 | `overlapArea = 0`（`0×47`）✅ | `2304`，覆盖 `0` ✅ |

docked 态两靶的 `clickable` 亦为 `true`/`true`，`h` 均为 `48`。
**docked 形态成立，判据 1/3 全部通过。**

---

## 3. 任务 C：基线不回退

口径沿用 v145：`① = unionBottom − paneBottom`；`unionBottom` = composer 子节点并集底沿
（**排除 `Open terminal`**）；`readable = paneH ≥ 150 ? paneH − 96 : paneH − 48`；`timelineBox = paneH − 48`。

### 3.1 768 档（条关）

```
C2_768_BAROFF BAR-OFF barNodes=0 pane=[0,162][1366,350] paneH=188 timelineBox=140 readable=92
             unionPx=96 unionBottom=340 paneBottom=350 (i)=-10 mInputShown=True appPid=1425
```

| 项 | 实测 | 基线 | 判定 |
|---|---|---|---|
| pane 盒 h | `188` | `188` | 未漂 |
| **时间线盒** | **`140`** | **`140`** | ✅ **逐位** |
| **readable** | **`92`** | **`92`** | ✅ **逐位** |
| **① (i)** | **`−10`** | **`−10`** | ✅ **逐位** |

### 3.2 600 档（条关）

```
C3_600_BAROFF BAR-OFF barNodes=0 pane=[0,162][1366,261] paneH=99 timelineBox=51 readable=51
             unionPx=48 unionBottom=249 paneBottom=261 (i)=-12 mInputShown=True appPid=1425
```

| 项 | 实测 | 基线 | 判定 |
|---|---|---|---|
| pane 盒 h | `99` | `99` | 未漂 |
| **readable** | **`51`** | **`51`** | ✅ **逐位** |
| **① (i)** | **`−12`** | **`−12`** | ✅ **逐位** |

### 3.3 缩窗往返 2 轮（`768 → 600 → 768`），往返前 `logcat -c`

| 轮次 | 档 | paneH | readable | ① (i) | appPid |
|---|---|---|---|---|---|
| RT1 | → 600 | `99` | `51` | `−12` | `1425` |
| RT1 | → 768 | `188` | `92` | `−10` | `1425` |
| RT2 | → 600 | `99` | `51` | `−12` | `1425` |
| RT2 | → 768 | `188` | `92` | `−10` | `1425` |

- 四腿与 §3.1/§3.2 **逐位相同** ⇒ 往返无漂移。
- `appPid = 1425` **全程未变** ⇒ 缩窗未触发进程重启。
- `FATAL|IllegalArgumentException|maxHeight` 扫描（`logcat -t 4000`，按 `appPid=1425` 过滤）：
  `appLines = 0`，**matches = 0** ⇒ 干净。
- `dumpsys window` 无 `Not Responding` ⇒ **0 次 ANR**，未执行 `adb reboot`。

**判定：140 / 92 / 51 / −10 / −12 全部未回退。**

---

## 4. 任务 D：收尾

| 项 | 结果 |
|---|---|
| `wm` 显式重设回 `1366x768` / `160` | ✅ `Override size: 1366x768`、`Override density: 160`（收尾段**再次显式**下发并复核） |
| 焦点归属 | ✅ `mCurrentFocus=…com.xiufeigo.opencodeshell/.OcActivity` |
| `/sdcard` 自建残留 | 本轮**未新增**自建文件。`/sdcard` 现有 `__e2e.xml` / `__e2e.png` 为 `helper.ps1` 的 `Dump`/`Shot` 临时文件（每帧覆写，**非内容残留**）；其余 `E2ECHK5554u1*` 均为 **app 库内的会话/标签标题**，不是 `/sdcard` 文件 |
| 自建内容清理 | **未删**。当前树可见 `E2ECHK5554u1*` 节点共 **28** 个（`u1s ×8`、`u1t ×6`、`u1v ×2`、`u1v134 ×11`、`u1w ×1`）。它们属历史轮次自建内容、位于 app 自己的存储，**未找到受支持的删除入口**；按纪律 **未绕过任何权限网关强行删除**，如实记账（见 §5） |
| 残留诚实性 | 本轮**没有**在 `/sdcard` 写入任何新的自建内容文件 |

---

## 5. 未闭环 / 留账

1. **自建会话内容未清理**（`E2ECHK5554u1*` 共 28 个节点）。它们是 app 库内的会话/标签，
   需在 app UI 内逐个删除（或由 Lead 用受支持入口清理）。**本轮未找到 CLI/受权限网关允许的删除路径，未强行绕过。**
2. **判据 7 的风险是条件性的**（见 §2.1）：当前文案下 chip 落在工具条空白区（0 文字像素被压），
   但若将来出现右锚定内容，左移 56dp 的 chip 可能与之相争。**六条判据不覆盖此形态**，建议纳入后续回归。
3. **docked 态下判据 ④⑤⑥ 未逐条复跑**（只复跑了 1/3，如任务书所要求）。docked 两档实测
   `h=48/48`、`clickable=true/true` 与 ①/3 一致，但**未在 docked 态专门钉成断言**。
4. **两处自曝的脚本度量 bug**（§1.2）：均为我自己的测量函数错误（漏归零、把间隙当残余），
   修正后读数稳定。**不是产品缺陷**，但记账在此以免下轮复现。
5. **IME 断言曾正确抛错**：首次构造 docked 时 IME 被收起，`AssertIme` 中止了脚本（未读错误高度）。
   已改为恢复 IME 后再读。**纪律生效，无脏读数。**
6. **像素判据只覆盖当前渲染态**：③ 的 `2304` 取自 `screencap -p` 静帧，未做逐帧动画/滚动过程中的连续采样。

---

## 6. 证据清单（`research/v97_verify/5554_md/artifacts/`）

| 文件 | 内容 |
|---|---|
| `C0_768.xml` / `C1_600_ONEROW.xml` | 判据用 768 / 600 母帧 dump（六条判据原文出处） |
| `C2_768_BAROFF.xml` / `C3_600_BAROFF.xml` | 基线 768 / 600 帧 dump |
| `C4_rt1_*.xml` / `C4_rt2_*.xml` | 缩窗往返 4 腿帧 dump |
| `E1_DOCK_768.png` / `E2_DOCK_600.png` + `.xml` | **docked 形态**两档截图与 dump |
| `v147_A_600_ONEROW.png` | 600 一行形态全屏截图（判据 ③ 像素扫描源） |
| **`v147_ZOOM_600_chip_send.png`** | **4× 放大**：chip 箭头 + `Send` 圆钮 + 中间白缝，肉眼可辨 |
| `v147_B_chip_600_crop.png` | chip 区含周边正文带对照裁剪 |
| `C_logcat_apppid.log` | 按 `appPid` 过滤的 logcat（`FATAL\|IllegalArgumentException\|maxHeight` = 0） |

驱动脚本：`v147_recon.ps1` / `v147_s1.ps1` / `v147_A.ps1` / `v147_AB.ps1` / `v147_final600.ps1` / `v147_C.ps1` / `v147_DOCK2.ps1` / `v147_E.ps1` / `v147_cleanup.ps1`
