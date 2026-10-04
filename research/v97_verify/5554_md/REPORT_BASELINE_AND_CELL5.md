# REPORT_BASELINE_AND_CELL5 —— v145：复核四个基线数 + 取 ⑤ 的当前真机态

- 执行：mavis（mcode），**v145**
- 日期：2026-10-04
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：**`emulator-5554`**（Medium_Desktop，Override `1366x768` / `160`，物理 3840x2160 / 320，SDK 34）
- 证据目录：`research/v97_verify/5554_md/artifacts/`
- 驱动脚本：`v145_A.ps1` / `v145_B.ps1` / `v145_B_overlay.ps1` / `v145_B_context.ps1` / `v145_C.ps1`
- 覆盖对象：`research/v97_fix/CELL5_FIX_AND_DECLARATIONS.md`（v144）

> **一句话结论**：**四个基线数逐位复现、无漂**（768 `140`/`92`/`−10`、600 `51`/`−12`），
> **缩窗往返 2 轮零 FATAL、pid 全程未变**。
> **⑤ 仍是真缺陷，且比 v144 记的更重**：不只"残余 2px 窄边"——
> **实测 `Open terminal` 在一行形态下贡献了 0 个可见像素**（被一个 `enabled=false` 的 `Send` 压住），
> 可见性维度比 2px 更致命，且该结论由 **a11y z 序 + 原始像素扫描两条独立证据同时坐实**。

---

## 0. 构建 / 后端 / 归属核对

| 项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| APK md5 | `cb5034aa5a888c30884a8fce4538b797` | `cb5034aa5a888c30884a8fce4538b797` | ✅ 逐位一致 |
| 设备 `lastUpdateTime` | `2026-10-03 23:33:06` | **`2026-10-03 23:33:06`** | ✅ **逐位一致 ⇒ 无需重装** |
| `versionName` / `versionCode` | — | `1.4.0` / `26` | — |
| `firstInstallTime` | — | `2026-09-23 13:58:18` | — |
| `Override size` | `1366x768` | `1366x768` | ✅ |
| `Override density` | `160` | `160` | ✅ |
| 后端 v2 | 4095 在线 | `GET http://127.0.0.1:4095` → **HTTP 200**（len 6147） | ✅ |
| `adb reverse` | `tcp:4095` 已挂 | `host-19 tcp:4095 tcp:4095`（另有 `tcp:2222`） | ✅ |
| 归属 | 交互前 `mCurrentFocus` 属本 app | 全程 `com.xiufeigo.opencodeshell/.OcActivity` | ✅ |
| `wm density 160` | **1dp == 1px** | 成立 ⇒ 本报告所有 px 读数可直接当 dp | ✅ |

**`adb` 不在 PATH**，走 `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`（与 `local.properties` 的 `sdk.dir` 同一 SDK）。全程只对 `-s emulator-5554` 发指令。

**启动**：本轮 app 已处于**已认证 + 已连接**态（v127 起持久化）。
`am force-stop` → `am start -n com.xiufeigo.opencodeshell/.OcActivity` 后直接落在会话
`Counting from 1 to 4000, one number per line`，**未重复填地址/口令、未触发 401**。

**纪律合规**：未跑 gradle、未 `assembleDebug`、未装机、未杀进程（仅本 app 的 `am force-stop` 一次，在启动段内）；
未按 BACK(4)、未在弹层内发 ESC(111)；`logcat` 一律带 `-t` 且按 **`$appPid`** 过滤（**未使用 `$pid`**）；
读高度前 `AssertIme` 硬断言 `mInputShown`（全部帧均为 `True`）；
坐标全部**运行期从 dump 解析**，切档后重新解析（`Geo` 每次重新 `Dump`）。

**⚠️ 自曝缺陷 1（已修，必须记账）**：驱动脚本里把测量函数命名为 `Measure`。
PowerShell 里 **`measure` 是 `Measure-Object` 的内置别名，而别名优先级高于函数** ⇒
该函数**一次都没被调用**，前两轮跑出的"A 段无输出、零 `A_*.xml` 产物"就是这个原因。
已改名 `Geo` 后复跑。**给下一轮**：本项目脚本里禁止起名 `Measure` / `R`（`r` = `Invoke-History`），
这两个都会静默吞掉函数体。

---

## 1. 主判据 A：四个基线数逐格对照（**本轮主判据**）

**口径**（沿用 Lead 裁定）：验收量 `① = unionBottom − paneBottom`；
`unionBottom` = **composer 子节点并集**底沿（**排除 `Open terminal`**）；
`readable` = `paneH ≥ 150 ? paneH − 96 : paneH − 48`；`timelineBox` = `paneH − 48`（`band = 48`）。
**队列条关闭形态** = a11y 树内 `Steer` / `Remove` / `Queue` 计数 **= 0**（A.1 实测 `bar nodes: 0`）。

### 1.1 768 档（条关）

```
mInputShown=True   appPid=31864
pane=[0,162][1366,350]  paneH=188
unionPx=96  unionBottom=340  paneBottom=350
```

| 项 | 本轮实测 | 基线 | **是否漂** |
|---|---|---|---|
| pane 盒 h | `188` | `188` | **否** |
| **时间线盒**（`188 − 48`） | **`140`** | **`140`** | **否（逐位）** ✅ |
| **readable**（`188 − 96`） | **`92`** | **`92`** | **否（逐位）** ✅ |
| **① (i)**（`340 − 350`） | **`−10`** | **`−10`** | **否（逐位）** ✅ |
| 形态 | 条关（`bar nodes=0`） | 条关 | 否 |
| `mInputShown` | `True` | `True` | 否 |

`Open terminal` 在 768 = `[1320,192][1366,240] 46x48`（与 600 档**同一像素位置**，但见 §2.5 对比）。

### 1.2 600 档（条关）

```
mInputShown=True   appPid=31864
pane=[0,162][1366,261]  paneH=99
unionPx=48  unionBottom=249  paneBottom=261
```

| 项 | 本轮实测 | 基线 | **是否漂** |
|---|---|---|---|
| pane 盒 h | `99` | `99` | **否** |
| **readable**（`99 − 48`） | **`51`** | **`51`** | **否（逐位）** ✅ |
| **① (i)**（`249 − 261`） | **`−12`** | **`−12`** | **否（逐位）** ✅ |
| `unionPx` | `48` | `48` | **否** |
| 形态 | 条关 | 条关 | 否 |
| `mInputShown` | `True` | `True` | 否 |

### 1.3 四格汇总

| 档 | 时间线盒 | `readable` | ① (i) | **是否漂** |
|---|---|---|---|---|
| **768** | **`140`** ✅ | **`92`** ✅ | **`−10`** ✅ | **逐位未漂** |
| **600** | （`51`，单行形态） | **`51`** ✅ | **`−12`** ✅ | **逐位未漂** |

> **判定：四个基线数全部逐位复现，任一格均未漂。** 不抄 bounds 判 FAIL 的条件未触发。

### 1.4 缩窗往返 2 轮（`768 → 600 → 768`）

`logcat` 在往返前 `-c` 清缓冲，保证扫到的就是本轮窗口内的。

| 轮次 | 档位 | paneH | `readable` | ① (i) | `appPid` | `mCurrentFocus` 归属 |
|---|---|---|---|---|---|---|
| RT1 | → 600 | `99` | `51` | `−12` | `31864` | 本 app |
| RT1 | → 768 | `188` | `92` | `−10` | `31864` | 本 app |
| RT2 | → 600 | `99` | `51` | `−12` | `31864` | 本 app |
| RT2 | → 768 | `188` | `92` | `−10` | `31864` | 本 app |

- **`appPid` = `31864` 全程未变**（四腿逐位相同 ⇒ 缩窗未触发进程重启）。
- `FATAL|IllegalArgumentException|maxHeight` 扫描：**matches = 0**（详见 §5 的 `logcat` 口径更正）。
- `dumpsys window` 无 `Not Responding` ⇒ **本轮 0 次 ANR**，未执行 `adb reboot`。

**四腿读数与 §1.1 / §1.2 逐位相同 ⇒ 缩窗往返后无漂移。**

---

## 2. B：⑤ 的当前真机态（可复现取证）

**形态**：**一行形态**（`1366x600` + `wm density 160`），即 v143/v144 判 ⑤ 为真缺陷的那种形态。
`Open terminal` 与 `Send` 在该形态**占同一个矩形、且同为 48dp 高**。

### 2.1 动作节点 bounds（运行期从 dump 解析，dump 原文见 §2.6）

| 节点 | dump `index` | 文档序 | **bounds** | w×h | `clickable` | `enabled` |
|---|---|---|---|---|---|---|
| `Open terminal` | `15` | **46** | **`[1320,192][1366,240]`** | **46×48** | `true` | `true` |
| `Send` | `19` | **55** | **`[1316,191][1364,239]`** | **48×48** | `true` | **`false`** |
| `EditText` | — | — | `[24,201][405,249]` | 381×48 | `true` | — |

**注**：`Open terminal` 宽 **46 而不是 48** —— `28.dp` 本体 + material3 `minimumInteractiveComponentSize(48)` = 48，
再被**屏幕右缘裁掉 2**。这与 v144 §1.3 的读数一致。

### 2.2 谁盖了谁：**z 序证据（两条互相独立的排序口径，同向）**

| 口径 | `Open terminal` | `Send` | 结论 |
|---|---|---|---|
| dump 自带 `index` 属性 | `15` | `19` | `19 > 15` ⇒ **`Send` 在上** |
| 文档序（本轮另算） | `46` | `55` | `55 > 46` ⇒ **`Send` 在上** |

**两种排序都把 `Send` 排在后面 ⇒ `Send` 绘制在上、压住 chip**，点击由 `Send` 收到。

**boundsInScreen 交叠（逐算）**：

```
intersection = [ max(1320,1316) , max(192,191) ] [ min(1366,1364) , min(240,239) ]
             = [1320,192][1364,239] = 44 x 47 = 2068 px^2
chip  area   = 46 x 48 = 2208 px^2
COVERED by Send = 2068 px^2      NOT covered (residual) = 2208 - 2068 = 140 px^2
```

**⇒ 重叠面积 2068 px²，非 0 ⇒ 遮挡成立。** v144 记的 `covered = 44×47 = 2068` **逐位复现**。

### 2.3 残余窄边（逐轴分解，v144 说 2px）

| 残余碎片 | 算式 | 值 | 窄边 |
|---|---|---|---|
| **右侧竖条** | `terminal.right − Send.right` = `1366 − 1364` | **2 × 47** | **2** |
| 下侧横条 | `terminal.bottom − Send.bottom` = `240 − 239` | 46 × 1 | 1 |
| 左侧间隙 | `Send.left − terminal.left` = `1316 − 1320` | 0 | — |
| 上侧余量 | `Send.top − terminal.top` = `191 − 192` | 0 | — |
| **合计** | 互不重叠 slabs 求和 | `94 + 46 = ` **140 px²** | — |

**校验**：`2208 − 2068 = 140` = `94 + 46` ✔ **自洽**。

> **实测窄边 = 2 px，与 v144 逐位一致。**

### 2.4 **像素级扫描：比 2px 更重的一条（本轮新增发现）**

v144 把 ⑤ 记成"残余 2px 窄边"。本轮对**原始截图**逐列扫描，发现**可见性维度上 ⑤ 更严重**：

```powershell
row y=215 (overlap 带中部), x=1314..1365
  x=1314..1325  WHITE          <- 左侧留白
  x=1326..1353  GREY(176/233)  <- Send 的 28dp 可见本体 + 向上箭头
  x=1354..1365  WHITE          <- 纯白 R255

row y=205 (overlap 带上部)
  x=1314..1325  WHITE
  x=1326..1352  GREY(176/233)  <- Send 本体
  x=1353..1365  WHITE          <- 纯白 R255
```

两点判读：

1. **可见的灰色圆角块 + ↑ 箭头就是 `Send`**。
   验证：其可见本体 `x 1326..1353`（28 px 宽）、中心 `(1339.5, 215)`；
   `Send` 的 a11y 矩形中心 = `((1316+1364)/2, (191+239)/2)` = `(1340, 215)`。**中心吻合到 0.5px** ⇒ 归属确定。
2. **chip 贡献了 0 个可见像素。**
   `x 1354..1365` 是**唯一**落在 chip 矩形内、却在 `Send` 矩形之外的区域
   （因为 `Send` 止于 `1364`、chip 到 `1366`）。该区域在 `y=205` 与 `y=215` 两行**都是纯白 `R255`** ——
   既不是 chip 的灰底，也不是 chip 的图标。
   ⇒ **一行形态下 `Open terminal` 在屏上没有任何一个可辨认像素。**

**成因补充（本轮读到的直接证据）**：帧内 `Send` 的 **`enabled="false"`**（composer 为空 ⇒ 发送键禁用）。
**⇒ 一个"禁用态、不可用"的按钮，依然占据了绘制上层并压死主入口。** 这是 v144 未记的一层。

**与 v144 的差异记账**：v144 写"残余 2px 窄边"，本轮**证实 2px 成立**（touch 几何口径），
但同时**补一条**：`Send` 的 **28dp 可见本体**与 chip 的 28dp 本体**几乎同位**（中心差约 3px），
所以**可见性**不是"只剩 2px"，而是**全灭**。两个口径**不冲突**（一个是 touch 靶几何、一个是绘制像素），
但验收时**必须分别断言**，否则修好 2px 仍可能保持"看不见"。

### 2.5 对照：768 堆叠形态**不遮挡**（证明这是**一行形态专属**缺陷）

| 档 | `Open terminal` | `Send` | 交叠 |
|---|---|---|---|
| **600 一行** | `[1320,192][1366,240]` idx 46 | `[1316,191][1364,239]` idx 55 | **44×47 = 2068 px²** |
| **768 堆叠** | `[1320,192][1366,240]` idx 64 | `[1308,292][1356,340]` idx 73 | **NONE（不相交）⇒ 0% 遮挡** |

768 档 `Send` 底沿 `340` 已在 chip 底沿 `240` 之下 ⇒ 纵向分离 ⇒ **无交叠**。
⇒ **⑤ 是"一行形态"专属**；任何修法都必须**在 600 档验收，且不得在 768 档引入新遮挡**。

### 2.6 dump 原文（逐字）

```xml
<node index="15" text="" resource-id="" class="android.widget.Button"
  package="com.xiufeigo.opencodeshell" content-desc="Open terminal" checkable="false"
  checked="false" clickable="true" enabled="true" focusable="true" focused="false"
  scrollable="false" long-clickable="false" password="false" selected="false"
  bounds="[1320,192][1366,240]" />

<node index="19" text="" resource-id="" class="android.view.View"
  package="com.xiufeigo.opencodeshell" content-desc="Send" checkable="false"
  checked="false" clickable="true" enabled="false" focusable="false" focused="false"
  scrollable="false" long-clickable="true" password="false" selected="false"
  bounds="[1316,191][1364,239]" />
```

### 2.7 证据文件（B 部分）

| 文件 | 内容 |
|---|---|
| `artifacts/B_CELL5_600_ONEROW.xml` | **600 一行形态原始 dump**（⑤ 取证母帧） |
| `artifacts/B_CELL5_600_ONEROW.png` | 同帧原始截图（1366×600，**未标注**） |
| `artifacts/B_CELL5_context_6x.png` | **6× 上下文放大** + a11y 框（青=`Open terminal` / 绿=`Send`）+ 10px 网格 |
| `artifacts/B_CELL5_zoom8x.png` | **8× 最近邻放大**（未标注，肉眼判"谁在上"） |
| `artifacts/B_CELL5_annotated_8x.png` | **8× 标注叠图**：青框 / 绿框 / 品红=重叠 2068 / **黄=2px 残余 + 1px 下沿** |
| `artifacts/B_CELL5_768_STACKED.xml` / `.png` | 768 堆叠形态对照帧（0 遮挡） |
| `artifacts/B_CELL5_crop.png` | 1:1 裁切 |

> `B_CELL5_annotated_8x.png` 的品红是**半透明**的，因此能透出被压在下面的 chip 本体与 ↑ 图标 ——
> 那是**示意图的透明叠加**，**不是用户实际看到的画面**。
> 用户实际看到的是 `B_CELL5_600_ONEROW.png` / `B_CELL5_zoom8x.png`：**灰色块只有一个，`Send` 的 chip 盖在另一个上面**。

---

## 3. B：修好之后应满足什么（**可判定判据，下一轮照此验收**）

把 v144 的 `occlusionCriterion(residualNarrowPx) = residualNarrowPx ≥ 16` **保留并加严**，
再加上本轮新增的可见性维度。以下 7 条**全部可机读**，任一不满足即 **FAIL**。

| # | 判据（可判定形式） | 当前实测 | 期望 |
|---|---|---|---|
| **C1** | `overlapArea(term, send) == 0`（两矩形**不相交**） | `2068` | **0** |
| **C2** | `narrowestResidualPx ≥ 16`（`TAPPABLE_PX`） | **`2`** | **≥ 16** |
| **C3** | `chipVisiblePixelCount > 0`（chip 本体在屏**有**可辨认像素） | **`0`** | **> 0** |
| **C4** | `covered == 0`（chip 面积不被 `Send` 吃掉） | `2068` | **`0`** |
| **C5** | 600 一行形态 `term.h == 48` 且 `send.h == 48`（**48dp 靶不许缩**） | `48` / `48` | `48` / `48` |
| **C6** | 768 堆叠形态 `overlapArea == 0`（**不许把缺陷搬上去**） | `0` | **`0`** |
| **C7** | `term.clickable == true` 且 `send.clickable == true`（两个动作**都活着**） | `true` / `true` | `true` / `true` |

**计算方式（下一轮直接照抄）**：

```
term   = 树内 content-desc="Open terminal" 的节点
send   = 树内 content-desc="Send"           的节点
overlapArea = 面积( term ∩ send )                     # C1 / C4 / C6
narrowestResidualPx = min( 所有互不重叠残余 slab 的两轴中较小者 )
                      # = min(2, 47, 46, 1, ...) = 2                      # C2
chipVisiblePixelCount = 在 (term \ send) 区域内
                        统计既非背景色、也非 Send 本体色的像素数      # C3
```

**三条强调**（避免下一轮走 v144 已证伪的弯路）：

1. **"只改 z 序"不构成修复。** v144 §1.4 已证：把 chip 置顶只会把缺陷转交给 `Send`，
   其残余窄边 `2 → 4px`（更糟）。⇒ **C1 必须靠分离几何达成，不能靠换绘制顺序作弊。**
2. **C3 不能被 C2 代替。** 把 2px 修到 16px 仍可能让 chip **完全不可见**（本轮 `visible = 0`）。
   两个判据**都要单列**。
3. **C5 是红线。** 任何靠"把靶缩小"来消除重叠的方案 = 删 48dp 靶语义 ⇒ 直接 FAIL，不得记 PASS。

**未闭环（本轮不判）**：⑤ 的**修法**本身仍未裁定（v144 §2 交 Lead 的 A/B/C/D 四选项未回复）。
本轮**零实施、零生产代码改动**，只交付**当前真机态**与**验收判据**。

---

## 4. 收尾

| 项 | 状态 | 证据 |
|---|---|---|
| `wm` **显式**重设回 `1366x768` / `160` | ✅ **已重设并复查** | `Override size: 1366x768` · `Override density: 160`（两条断言均 `True`） |
| 本轮自建内容清理 | ✅ **本轮未产生任何自建内容** | 全程只做**切档 / dump / 截图 / 读数**，**未新建会话、未发送消息**，故无可清理项 |
| 既有 `E2ECHK5554u1*` 遗留 | ⚠️ **留账，未动** | `u1s` / `u1t` / `u1v` / `u1v134` / `u1w` 是**前几轮**的自建内容，非本轮产生；删除会毁掉他轮证据 ⇒ **本轮不动，如实记账** |
| `/sdcard` **新增**残留 | ✅ **已清** | 本轮唯一新增是工具暂存 `__e2e.xml` / `__e2e.png` ⇒ 已 `rm -f` 删净，复查 `ls /sdcard/__e2e.*` = **无** |
| `/sdcard` 既有 43 个 png/xml | ⚠️ **留账，未动** | `esc.xml` / `q*.xml` / `s_parked*.png` / `v137_a.png` 等，**均为他轮产物**，非本轮新增；**未越权清理** |
| 回合 `Stop` | 不适用 | 本轮未启动任何回合 |
| 设备可交还 | ✅ | app 仍在前台连接态，`reverse tcp:4095` 在位，**未 force-stop**（无待停回合） |
| 设备纪律 | ✅ | 全程只对 `-s emulator-5554`；未碰 `emulator-5594` |

---

## 5. `logcat` 口径更正（**自曝缺陷 2**，必须记账）

A 段与 C 段的头几轮扫描都报 `app lines=0 / matches=0`。**这个"干净"是空的**：
我最初用 PowerShell 侧正则 `\s$appPid\s` 过滤 `logcat -v brief`，
而 brief 格式是 `D/EGL_emulation(31864): ...` —— pid 右侧是 `)` 而非空白 ⇒ **正则永不命中**。

已改为**设备侧**过滤 `logcat -d -t 3000 --pid=$appPid`，重跑：

```
appPid=31864
lines=210                       <- 真实取到 210 行 app 日志（此前恒为 0）
MATCHES(FATAL|IllegalArgumentException|maxHeight) = 0
```

⇒ **"零 FATAL"这个结论在修正过滤后依然成立，且这次是**基于真实样本**（210 行）而不是空集。
**给下一轮**：本设备上扫 logcat **必须**用 `adb logcat -d -t <N> --pid=<appPid>`；
纯 PowerShell 正则过滤 brief 输出会静默返回 0 行，**产生假的"干净"**。

---

## 6. 未闭环（如实记账）

1. **⑤ 的修法未裁定** —— v144 §2 的 A/B/C/D 四选项 Lead 未回复；本轮零实施。
2. **`Send` `enabled=false` 仍压死主入口** —— 本轮新读到的事实（§2.4），
   **未验证**修好几何后禁用态是否仍会遮挡（`enabled=false` 通常意味着不应接收点击，
   若真不接收点击，则 chip 被"幽灵"覆盖**比 v144 记的更不可用**）。**需下一轮带点击实验确认。**
3. **C3（`chipVisiblePixelCount > 0`）的背景色阈值尚未固化** —— 本轮用"非 `R≥250` 且非 `Send` 本体灰"人工划分，
   **未写成生产可复用的判据函数**。下一轮需把它钉成函数并做双向可判定性验证。
4. **`idx` 口径差异** —— 本轮 `Open terminal` 的 **dump 自带 `index=15`**、**文档序 `=46`**；
   v144 记的是 `46`（文档序）。两个口径**同向**（`Send` 都更大）故结论不受影响，
   但**引用时须写明是哪个口径**，否则跨轮比对会对不上。
5. **未测**：chip 被压住时的**实际点击归属**（本轮只做几何与像素取证，未做"点 chip 位置看谁响应"的交互实验）。
6. **`v145` 未新建会话** ⇒ 队列条/编辑态/拖拽等 **B 的其余分支本轮未触及**（本轮 B 只针对 ⑤）。
7. **未覆盖** 768 以外的中间尺寸与 `density` 变化（缩窗只跑了 600 ↔ 768 两档）。

---

## 7. 证据文件清单（`research/v97_verify/5554_md/`）

**驱动脚本**

| 文件 | 用途 |
|---|---|
| `v145_A.ps1` | A：两档条关读数 + 缩窗往返 2 轮 + `mInputShown` 断言 |
| `v145_B.ps1` | B：600 一行形态 ⑤ 取证（z 序 / 交叠 / 残余 / 像素扫描 / 8× 放大） |
| `v145_B_overlay.ps1` | B：8× 标注叠图（重叠 + 2px 残余着色） |
| `v145_B_context.ps1` | B：6× 上下文图 + 像素探针 |
| `v145_C.ps1` | C：显式重设 + `/sdcard` 残留核对 |

**A 段证据**

| 文件 | 内容 |
|---|---|
| `artifacts/A0_pre.xml` | 条关形态确认帧（`bar nodes = 0`） |
| `artifacts/A_768_BAROFF.xml` / `.png` | **768 条关**（`140` / `92` / `−10`） |
| `artifacts/A_600_BAROFF.xml` / `.png` | **600 条关**（`51` / `−12`） |
| `artifacts/A_rt1_600.*` / `A_rt1_768.*` | 往返第 1 轮两腿 |
| `artifacts/A_rt2_600.*` / `A_rt2_768.*` | 往返第 2 轮两腿 |
| `artifacts/A_logcat_apppid.log` | app-pid 过滤日志 |
| `artifacts/A_logcat_final.log` | 收尾扫描（**设备侧 `--pid`，修正后**） |

**B 段证据** —— 见 §2.7 表。

---

**本轮产出：0 生产代码改动、0 测试改动、0 装机、0 gradle。**
