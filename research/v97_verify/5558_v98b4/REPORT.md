# 5558 真机复验 —— B4 标题导航离开丢弃草稿（fix3）

- 轮次：v97 verify / **v98 B4**（fix3 复验，第三版）
- 设备：**emulator-5558**（`emu avd name` = `Pixel_10_Pro_XL` ✅）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 后端：http://127.0.0.1:4095 Basic `opencode:opencode`
- 被测包：`com.xiufeigo.opencodeshell/.OcActivity` / `1.4.0` (26)
- 自建会话：`ses_f03398291ffeEM1mB31qS0Io75`，基准标题 **`E2ECHK5558v98`**
- 纪律：未跑 gradle / 未装机 / 未杀模拟器进程（仅本 app `am force-stop`）；只操作 5558；未碰 127.0.0.1:5555；未按 BACK(4)；弹层内未用 ESC(111)；截图全部 `screencap -p` + `adb pull`；坐标运行期从 dump 解析。

---

## 0. 结论速览

| # | 用例 | 判定 |
|---|---|---|
| 1 | **B4 主判据**：点标题 → 输入 `E2ECHK5558v98_discard` → **直接回 Home** | ✅ **PASS** |
| 2 | **B5 正路径**：点标题 → 输入 → **回车** | ✅ **PASS** |
| 3 | **tab 切换变体**：输入 → 切到 Changes 再切回 | ✅ **PASS** |
| 4 | **点外部变体**：输入 → 点会话内其它区域 | ⚠️ **半 PASS —— 与任务书断言冲突，见 §5.4** |
| 5 | **强杀重启后**复核标题 | ✅ **PASS** |
| 6 | **清理**：标题改回 `E2ECHK5558v98` | ✅ **PASS** |

> **一句话**：**上一轮两版都复现的 B4 缺陷（导航离开仍提交）本轮不再复现。** 草稿在点 Home 时被丢弃，服务端 `time.updated` 逐字节未变。B5 未回归。栅栏能区分「导航离开（丢）」与「会话内点外（存）」两种失焦来源。

---

## 1. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| `adb devices -l` | 7 台在列；`emulator-5558 device product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k` | ✅ |
| `adb -s emulator-5558 emu avd name` | `Pixel_10_Pro_XL` | ✅ 与指定一致 |
| 设备时间 | `Fri Oct  2 13:20:12 GMT 2026` | — |
| `dumpsys package com.xiufeigo.opencodeshell` | `versionName=1.4.0` / `versionCode=26` / **`lastUpdateTime=2026-10-02 13:18:59`** / `firstInstallTime=2026-09-26 13:48:36` | ✅ 与 Lead 21:18 闸门构建一致（13:18:59 GMT = 21:18:59 CST） |
| 焦点窗口 | `mCurrentFocus=Window{...com.xiufeigo.opencodeshell/.OcActivity}` | ✅ |
| 宿主后端 `GET /api/session` | HTTP 200，响应体形如 `{"data":[...],"cursor":{...}}` | ✅ |

### ⚠️ 与任务书的两处偏差（均已处理，报告留痕）

1. **reverse 实际未挂。** 任务书称「reverse 4095/2222 已挂」，实测首条 `adb -s emulator-5558 reverse --list` **返回空**。已由本测试员补挂：

   ```powershell
   adb -s emulator-5558 reverse tcp:4095 tcp:4095   # -> 4095
   adb -s emulator-5558 reverse tcp:2222 tcp:2222   # -> 2222
   adb -s emulator-5558 reverse --list
   # host-15 tcp:4095 tcp:4095
   # host-15 tcp:2222 tcp:2222
   ```

   补挂在全部功能步骤之前完成，故不影响任何判定。

2. **设备无 `curl`。** `adb shell curl ...` → `/system/bin/sh: curl: inaccessible or not found`。设备侧连通性改由**宿主侧 API 读回** + **应用内 UI 读数**双重佐证（应用能列出该会话并渲染其标题，本身即证明隧道通）。

### 自建会话建立

```powershell
$h=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode'))}
Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/session' -Method Post -Headers $h -Body '{}'
```

响应原文：

```json
{"data":{"id":"ses_f03398291ffeEM1mB31qS0Io75","projectID":"ae54078c0e09bf8e7cbeefd35db19aaa6b064e46",
 "cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
 "time":{"created":1790947261866,"updated":1790947261866},
 "location":{"directory":"/mnt/c/sensorsdata/main/program/opencode-android-shell"}}}
```

> **踩坑留痕**：`POST` 响应被包在 `data` 键内。首次用 `$s.id` 直取属性得到空值，**看起来像创建失败**。改用 `Invoke-WebRequest` 看原文后确认会话已建。后续所有读回统一走 `.data`。

基准标题写入并读回：

```powershell
PATCH http://127.0.0.1:4095/api/session/ses_f03398291ffeEM1mB31qS0Io75   Body: {"title":"E2ECHK5558v98"}
# -> ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98   updated=1790947269633
```

**`updated=1790947269633` 即基线时间戳**，后续所有「是否发生写入」的判定都靠它是否变化。

---

## 2. 实测坐标（全部运行期从 `uiautomator dump` 解析，未凭印象）

| 元素 | 实测 bounds | 取点 | 出现于 |
|---|---|---|---|
| 首页列表行标题 `E2ECHK5558v98` | `[192,1514][508,1561]` | `(350,1537)` | `b4_01_home.xml` |
| 会话内 **Home 按钮**（`content-desc='Home'`） | **`[24,141][168,255]`** | `(96,198)` | `b4_02_session.xml` |
| 会话内 **标题按钮**（`android.widget.Button`） | `[252,256][604,394]` | `(428,325)` | `b4_02_session.xml`（标题 `E2ECHK5558v98`） |
| 同上，标题为 `…_enter` 时 | `[252,256][719,394]` | `(485,325)` | `t0.xml` / `b5_02` |
| 同上，标题为 `…_outside` 时 | `[252,256][758,394]` | `(505,325)` | `o2_after.xml` |
| **编辑态标题 EditText** | `[294,255][1050,394]`，`focused=true` | — | `b4_03_edit.xml` |
| **tab `Session`** | `[0,394][306,521]` | `(153,457)` | `t0.xml` |
| **tab `Changes`** | `[306,394][612,521]` | `(459,457)` | `t0.xml` |
| **tab `Files`** | `[612,394][918,521]` | `(765,457)` | `t0.xml` |
| **tab `Terminal`** | `[918,394][1212,521]` | `(1065,457)` | `t0.xml` |
| 会话内空白消息区（点外部用） | `android.view.View [0,521][1344,1984]`，`clickable=false` | `(672,900)` | `t2_afterswitch.xml` |

> **Home 按钮坐标与 fix3 文档 §4 R1 写的 `[8,30][56,78]` 不符。** 文档那个值应是 dp，而 `uiautomator dump` 出的是物理像素（1344×2992）。实测 `content-desc='Home'` 的 bounds 是 `[24,141][168,255]`。**若照搬 `[8,30][56,78]` 去点会落在屏幕外** —— 本轮一律用实测值。

---

## 3. B4 主判据 —— 导航离开必须丢弃草稿

### 步骤与命令

```powershell
# 进入自建会话
adb -s emulator-5558 shell input tap 350 1537          # 首页行
# 点标题进编辑态
adb -s emulator-5558 shell input tap 428 325
# 清空原标题
adb -s emulator-5558 shell input keyevent 123          # KEYCODE_MOVE_END
1..40 | % { adb -s emulator-5558 shell input keyevent 67 }   # 40× DEL
adb -s emulator-5558 shell input text "E2ECHK5558v98_discard"
# 不按回车、不点会话内部，直接回 Home
adb -s emulator-5558 shell input tap 96 198
```

### 编辑态证据（`b4_04_typed.xml`）

```
EDIT | text='E2ECHK5558v98_discard' bounds=[294,255][1050,394] focused=true
EDIT | text=''                    bounds=[84,1678][1260,1822] focused=false
[E2ECHK5558v98]        TextView  [204,190][478,237]      <- 顶部 tab 名（仍是旧名）
[E2ECHK5558v98_discard] EditText [294,255][1050,394]     <- 草稿在编辑器里
```

→ 草稿确实已输入、编辑器 `focused=true`、**未回车**。

### 点 Home 前后服务端读回

```
T+0 （点 Home 前）: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98 | updated=1790947269633
T+3 （点 Home 后）: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98 | updated=1790947269633
```

> **`updated` 逐位未变** —— 比「标题字符串没变」更强：说明**根本没有发生一次写请求**。若曾误提交，`time.updated` 必然被推后。

### Home 列表读回（`b4_05_after_home.xml`）

```
[E2ECHK5558v98] TextView [204,190][478,237]     <- 顶部 tab 名
[E2ECHK5558v98] TextView [192,1514][508,1561]   <- 首页列表行
EDIT | text='' bounds=[168,1162][1260,1306]     <- 仅剩 Search sessions 搜索框，无标题编辑器
```

截图 `b4_05_after_home.png` 佐证：已回到 Projects + 会话列表页，**列表行与 tab 名均仍为 `E2ECHK5558v98`**。

### 重新进入会话（断言 C，`b4_06_reenter.xml`）

```
[E2ECHK5558v98] TextView [204,190][478,237]
[E2ECHK5558v98] Button  [252,256][604,394]
EDIT | text='' bounds=[84,1678][1260,1822] focused=true    <- 只有 composer，标题编辑器已关闭
dump 中 'discard' 出现次数: 0
```

```
FINAL: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98 | updated=1790947269633
```

### **B4 判定：✅ PASS**

| 断言 | 结果 |
|---|---|
| A. Home 列表该行标题 = 原名 | ✅ `E2ECHK5558v98` |
| B. 服务端 `title` = 原名 | ✅ `E2ECHK5558v98` |
| C. 重新进入 → 标题 = 原名、tab = 原名、**编辑器未自动打开** | ✅ 三项全中 |
| D.（附加）`time.updated` 未被推后 | ✅ 证明无写请求 |

> **与上一轮对比**：上一轮两个构建各复现一次「点 Home → 标题已被改」。本轮同一路径**未复现**。

---

## 4. B5 正路径 —— 回车必须仍然提交

```powershell
adb -s emulator-5558 shell input tap 485 325          # 标题（标题较长，重新解析 bounds）
adb -s emulator-5558 shell input keyevent 123
1..40 | % { adb -s emulator-5558 shell input keyevent 67 }
adb -s emulator-5558 shell input text "E2ECHK5558v98_enter"
adb -s emulator-5558 shell input keyevent 66          # 回车
```

编辑态（`b5_01_typed.xml`）：`EDIT | text='E2ECHK5558v98_enter' focused=true`

回车后（`b5_02_after_enter.xml`）：

```
NODE text='E2ECHK5558v98_enter' TextView bounds=[204,190][593,237]     <- 顶部 tab 名已同步
NODE text='E2ECHK5558v98_enter' Button  bounds=[252,256][719,394]     <- 标题按钮
EDIT | text='' bounds=[84,2614][1260,2758]                            <- 仅 composer（键盘已收起），标题编辑器已关闭
```

```
B5: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98_enter | updated=1790947414981
```

截图 `b5_02_after_enter.png` 目视确认：**tab 名与 header 标题均为 `E2ECHK5558v98_enter`**，编辑框已关闭。

### **B5 判定：✅ PASS** —— 提交未被弄坏，tab 同步，`updated` 已推后。

---

## 5. 触发变体

### 5.1 tab 切换变体 —— ✅ PASS

```powershell
adb -s emulator-5558 shell input tap 485 325
adb -s emulator-5558 shell input keyevent 123
1..40 | % { adb -s emulator-5558 shell input keyevent 67 }
adb -s emulator-5558 shell input text "E2ECHK5558v98_tab"
adb -s emulator-5558 shell input tap 459 457     # tab: Changes
adb -s emulator-5558 shell input tap 153 457     # tab: Session（切回）
```

切换前（`t1_typed.xml`）：`EDIT | text='E2ECHK5558v98_tab' focused=true`

切回后（`t2_afterswitch.xml`）：

```
[E2ECHK5558v98_enter] TextView [204,190][593,237]
[E2ECHK5558v98_enter] Button  [252,256][719,394]
EDIT | text='' bounds=[84,1678][1260,1822] focused=true
TABVAR: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98_enter | updated=1790947414981
```

→ 标题未改，编辑器关闭，**`updated` 仍是 B5 那一次的 `1790947414981`，未推后**。

**判定：✅ PASS** —— 同一根因的另一触发路径一并拦住。

### 5.2 点外部变体 —— ⚠️ 半 PASS，实测与任务书断言冲突

```powershell
adb -s emulator-5558 shell input tap 485 325
adb -s emulator-5558 shell input keyevent 123
1..40 | % { adb -s emulator-5558 shell input keyevent 67 }
adb -s emulator-5558 shell input text "E2ECHK5558v98_outside"
adb -s emulator-5558 shell input tap 672 900     # 会话内空白消息区，输入框外
```

点前先验证落点安全 —— `(672,900)` 处命中的节点**全部 `clickable=false`**，最内层是 `android.view.View [0,521][1344,1984]`（消息区容器），不落在任何按钮/composer 上：

```
overlap cls=android.widget.FrameLayout clk=false bounds=[0,0][1344,2992]
overlap cls=androidx.compose.ui.platform.ComposeView clk=false bounds=[0,0][1344,2992]
overlap cls=android.view.View clk=false bounds=[0,521][1344,1984]
```

点后（`o2_after.xml`）：

```
[E2ECHK5558v98_outside] TextView [204,190][632,237]
[E2ECHK5558v98_outside] Button  [252,256][758,394]
EDIT | text='' bounds=[84,2614][1260,2758] focused=false    <- 标题编辑器已关闭
OUTVAR: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98_outside | updated=1790947507855
```

**实测两件事：**

| 任务书断言 | 实测 | 判定 |
|---|---|---|
| 编辑器失焦 | 标题 EditText 消失，composer `focused=false`，键盘收起 | ✅ 符合 |
| **标题未被改** | **标题被改成 `E2ECHK5558v98_outside`，服务端 `updated` 推后** | ❌ **不符合任务书** |

> #### ⚠️ 这条我判定为**任务书断言写错了，不是代码回归** —— 理由：
>
> 1. **`TITLE_DISCARD_FIX3.md` §4 R3 与 §6 R5 白纸黑字要求「会话内点外 = 必须保存」**：
>    > R3 ★ 会话内点外（**必存**，上游 `onBlur → save`）… `blurTitleEditorOnOutsideTap`：`arm()` → `focusManager.clearFocus()`（同步相邻）… `consumeBlurSave()` == `true` → 落库。**既有行为未破。**
>    > R5 … 断言：编辑框关闭且标题**被改成**该值（提交）。→ 这是上游 `onBlur → save`，**不是 bug**；**若此处变成不提交，说明栅栏收得过宽。**
> 2. **fix3 恰恰把「保存」与「丢弃」按授权闩锁区分开**：会话内点外 = `arm()`（授权）→ 存；导航离开 = 无人 `arm()` → 丢。本轮 B4（点 Home 丢弃）与本条（点外保存）**同时成立**，正好证明这道栅栏**确实在按来源分流**，而不是简单地「一律不存」。
> 3. 若按任务书字面把此处判 FAIL，等于要求**破坏上游 `onBlur → save` 契约**，反而会踩中 fix3 文档警告的「栅栏收得过宽」。
>
> **建议**：把任务书第 4 条的断言从「标题未被改」改为「**编辑器失焦且标题被改成该值（提交）；与 B4 的丢弃行为构成对照，以此证明授权闩锁按来源分流**」。**本报告不擅自改判，按实测原样记录并在此标注冲突，请 Lead 复核。**

**本条判定：编辑器失焦 ✅ PASS；「标题未改」这一半 ❌ 与任务书冲突（与 fix3 设计一致）。**

---

## 6. 强杀重启后复核

```powershell
adb -s emulator-5558 shell am force-stop com.xiufeigo.opencodeshell
adb -s emulator-5558 shell pidof com.xiufeigo.opencodeshell   # 空（进程已死，确认 force-stop 生效）
adb -s emulator-5558 shell am start -n com.xiufeigo.opencodeshell/.OcActivity
```

重启后（`r1_restart_home.xml`）：

```
[E2ECHK5558v98_outside] TextView [204,190][632,237]
[E2ECHK5558v98_outside] Button  [252,256][758,394]
RESTART: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98_outside | updated=1790947507855
```

> **判定：✅ PASS。** 重启后 UI 标题 = `E2ECHK5558v98_outside`，服务端 = `E2ECHK5558v98_outside`，`updated=1790947507855` 与点外部那次提交**完全一致**。即：**内存里没有「改了但没落库」，也没有「落库了但内存是旧的」**。
>
> 附带结论：B4 丢弃的那次（`E2ECHK5558v98_discard`）在经历一次强杀重启后**依然没有出现**在服务端或 UI 的任何位置 —— 丢弃是真丢弃，不是「延迟落库」。

---

## 7. 清理

```powershell
adb -s emulator-5558 shell input tap 505 325      # 标题（[252,256][758,394]）
adb -s emulator-5558 shell input keyevent 123
1..40 | % { adb -s emulator-5558 shell input keyevent 67 }
adb -s emulator-5558 shell input text "E2ECHK5558v98"
adb -s emulator-5558 shell input keyevent 66
```

```
[E2ECHK5558v98] TextView [204,190][478,237]      <- tab 名
[E2ECHK5558v98] Button  [252,256][604,394]      <- 标题
CLEAN:  ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98 | updated=1790947562502
CLEAN2: ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98 | updated=1790947562502   <- 2s 后复读，标题稳定
```

前缀会话清点：

```powershell
(Invoke-RestMethod -Uri 'http://127.0.0.1:4095/api/session' -Headers $h).data |
  Where-Object { $_.title -like 'E2ECHK5558v98*' } | ForEach-Object { $_.id + ' | ' + $_.title }
# ses_f03398291ffeEM1mB31qS0Io75 | E2ECHK5558v98
```

> **清理判定：✅ PASS。** 自建会话**保留**，标题已改回 **`E2ECHK5558v98`**。`E2ECHK5558v98*` 前缀下**仅此一条**，无残留（`_discard` / `_enter` / `_tab` / `_outside` 均未残留）。
> 两次读取 `updated` 一致 → 单次写入，无二次回写。
> 本次清理**走的是应用内 UI 回车**，顺带在**强杀重启之后**又复验了一次 B5 提交路径 —— 仍正常。

---

## 8. 证据文件清单（本目录）

| 文件 | 内容 |
|---|---|
| `b4_00_home.png` | 启动后首页 |
| `b4_01_home.xml` / `b4_00_home.png` | 首页 dump，定位会话行 `[192,1514][508,1561]` |
| `b4_02_session.png` / `b4_02_session.xml` | 进入会话；Home 按钮 `[24,141][168,255]` 实测 |
| `b4_03_edit.png` / `b4_03_edit.xml` | 点标题后编辑态，EditText `focused=true` |
| `b4_04_typed.png` / `b4_04_typed.xml` | 草稿 `E2ECHK5558v98_discard` 已输入 |
| `b4_05_after_home.png` / `b4_05_after_home.xml` | **点 Home 后**：列表与 tab 仍为原名 |
| `b4_06_reenter.png` / `b4_06_reenter.xml` | **重新进入**：标题原名、编辑器未开 |
| `b5_01_typed.xml` | B5 回车前 |
| `b5_02_after_enter.png` / `b5_02_after_enter.xml` | **回车后**：tab + 标题均已更新 |
| `t0.xml` | tab 条 bounds 实测 |
| `t1_typed.xml` | tab 变体切换前 |
| `t2_afterswitch.png` / `t2_afterswitch.xml` | **切换后**：标题未改 |
| `o1_typed.xml` | 点外部变体点前 |
| `o2_after.png` / `o2_after.xml` | **点外部后**：失焦 + 已提交 |
| `r1_restart_home.png` / `r1_restart_home.xml` | **强杀重启后**标题复核 |
| `c1_typed.xml` / `c2_done.png` / `c2_done.xml` | 清理：改回 `E2ECHK5558v98` |
| `TARGET_SESSION.txt` | 会话 id 留档 |

---

## 9. 未闭环 / 需 Lead 关注

1. **【需复核】任务书第 4 条断言与 fix3 设计冲突**（详见 §5.2）。实测「会话内点外 = 保存」，与 `TITLE_DISCARD_FIX3.md` R3/R5 一致，与任务书字面断言不一致。**建议改断言而非改代码**；若 Lead 坚持「点外也必须丢」，那是行为规格变更，需重新评估 fix2/fix3 整套 `TitleEditFence` 的授权语义。
2. **`reverse` 隧道并非预置**。本轮实测为空、由测试员补挂。建议把 `adb -s <serial> reverse tcp:4095 tcp:4095` 写进真机验证的前置脚本，否则换机器/重连后会静默失联。
3. **fix3 文档 §4 R1 的 Home 坐标 `[8,30][56,78]` 是 dp，`uiautomator dump` 出的是 px**（实测 `[24,141][168,255]`）。照搬会点到屏幕外，建议文档补注单位。
4. **本轮只验了 5558 一台设备、单个会话、每条用例各 1 次。** B4 属竞态类缺陷（fix3 修的正是跨节点焦点分发顺序），**单次通过不足以判定彻底闭环**，建议对 B4 主判据与 tab 变体**各重复 ≥5 次**再收口。
5. **B4 主判据尚未覆盖「先有消息再改名」的真实会话。** 本轮会话为空会话（`Build anything`），消息列表为空。部分分支（如「离开时有正在生成的消息」）未覆盖。
6. **未验 fix3 文档 §6 的 R4 / R6 / R7**（回车后立刻离开不回写、ESC 两段式、空白草稿）—— 不在本轮任务书 5 条内，未执行，非失败。
7. **服务端与设备的时钟基准**：`updated` 是 epoch ms（与设备时区无关），报告中所有时间戳可直接横向比较。
