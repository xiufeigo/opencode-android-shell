# 真机复验报告 — v97 fix 三组修复（emulator-5556 / v98）

- 轮次：**v98 真机复验**（只读复验；未跑 gradle、未装机、未杀进程、未碰 127.0.0.1:5555）
- 设备：**emulator-5556** = `Medium_Desktop`
- 缺陷单：`research/v97_fix/REVIEW_FIX.md` / `REVIEW_HOME_SEARCH_DIAG.md` / `TITLE_EDITOR_DISCARD_FIX.md` / `PROJECT_PARITY_FIX.md`
- 目录：本文件所在目录即证据根；所有路径为相对本报告的路径

---

## 0. 终判

| 组 | 编号 | 判定 | 跑在哪个构建上 |
|---|---|---|---|
| A Review | A1 Files 过滤器递归 | **PASS** | `12:26:07` |
| A Review | A2 窄窗超时后 Refresh 恢复 | **PASS** | `12:26:07` |
| A Home | A3 搜索浮层遮挡 | **PASS**（附 1 条未闭环观察） | `12:26:07` |
| B 标题 | B4 离开 → 丢弃草稿 | **FAIL** ★ | **两个构建各失败一次** |
| B 标题 | B5 回车 → 提交 | **PASS** | `12:26:07` |
| B 标题 | B6 会话内点外 → 提交（反向护栏） | **PASS** | `12:26:07` |
| C 项目 | C7 未注册项目隐藏「刷新工作区」 | **PASS** | `12:39:01` |
| C 项目 | C8 点击已上传图标即清除 | **PASS** | `12:39:01` |
| C 项目 | C9 启动命令 `$VAR` 提示 | **PASS** | `12:39:01` |

**8 PASS / 1 FAIL。** 唯一红灯是 **B4**，且它是本批修复里唯一声称"离开即丢弃"的那条。

### ⚠️ 0.1 复验期间构建被换掉（影响判读，必须先说）

开场读到 `lastUpdateTime = 2026-10-02 12:26:07`（与 Lead 闸门一致）。**跑到 C 组时**（设备时钟
约 12:39）它变成了 `12:39:01` —— **本轮复验过程中，这台共享设备上的应用被换成了更新的构建**
（不是本轮装的：本轮未执行任何 `adb install` / `pm install`，见 §5 纪律自查）。

| 设备时钟 | 构建 | 跑完的项 |
|---|---|---|
| 12:27–12:36 | `lastUpdateTime=12:26:07` | A1 / A2 / A3 / B4(首次) / B5 / B6 |
| 12:39 之后 | `lastUpdateTime=12:39:01` | C7 / C8 / C9 / **B4(复测)** |

因此：
- **B4 红灯在两个构建上各失败一次**（`12:26:07` 与 `12:39:01`），结论**不是**构建时序巧合。
- A 组三条只跑过 `12:26:07`；若 Lead 认为 `12:39:01` 里 A 组也动过，**需要在最终构建上重跑 A 组**。
  本轮未重跑（时间预算耗尽），这是**未闭环项**，不是 PASS 的保证。

---

## 1. 环境读数（开场复核）

```
adb devices                     -> emulator-5554/5556/5558/5560/5562/5564  (只操作 5556)
adb -s emulator-5556 emu avd name -> Medium_Desktop                ✅
adb shell wm size               -> Physical 3840x2160 / Override 1366x768
adb shell wm density            -> Physical 320    / Override 160   (1 px = 1 dp)
dumpsys package com.xiufeigo.opencodeshell
    versionName    = 1.4.0
    lastUpdateTime = 2026-10-02 12:26:07                          ✅ 与 Lead 闸门一致
adb reverse --list              -> host-19 tcp:2222 tcp:22 / host-19 tcp:4095 tcp:4095  ✅
settings get secure show_ime_with_hard_keyboard = 1              ✅
GET http://127.0.0.1:4095/api/project  (Basic opencode:opencode) -> 200
```

---

## 2. A 组 — Review 面板 + Home 搜索

### A1 — Files 过滤器能搜到未展开目录里的文件：**PASS**

**关键方法学修正（重要）**：任务书举例的 `build.gradle.kts` **不能**作为判据 —— 仓库根目录
也有一个 `build.gradle.kts`（`Test-Path .\build.gradle.kts = True`），命中可能来自根层那条
一直可见的行，**无法区分修复前后**。故改用两个只存在于子目录的文件：

| 过滤词 | 文件实际位置 | 根目录是否有同名 | 结果 |
|---|---|---|---|
| `HANDOFF` | `research/HANDOFF.md` | **否** | 未命中（`research/` 从未 fetch 过） |
| `proguard` | `app/proguard-rules.pro` | **否** | **命中** |

流程（证据 `15_root_rows.xml` → `16_app_expanded.xml` → `17_app_collapsed.xml` → `18_a1_proguard.*`）：

1. `15_root_rows.xml`：根层 15 行全部为**折叠**态（`.dsh-acl-recovery` / `.git` / … / `research` / `v36projB`），未展开任何目录。
2. 展开 `app/`（`16_app_expanded.xml`）→ 其子项 `build.gradle.kts` / `proguard-rules.pro` / `src` / `build` 出现，**children 被加载**。
3. **再次折叠** `app/`（`17_app_collapsed.xml`）→ `app` 回到 15 行根列表，其下不再有子行。
4. 过滤 `proguard`（`18_a1_proguard.xml`）：

```
T=[proguard]            B=[1174,247][1316,280]   (输入框)
T=[app]                 B=[1184,297][1205,308]   (祖先行，chevron 仍为 › = 折叠)
T=[proguard-rules.pro]  B=[1200,325][1308,340]   (depth 1)
```

截图 `18_a1_proguard.png` 可见 `app` 行左侧箭头为 **›（未展开）**，其下缩进一级显示
`proguard-rules.pro`。**在"已折叠但已加载"的目录里搜到了文件 —— 正是本轮修复的契约。**

对照 `HANDOFF`（`14_a1_handoff2.xml`）：`research/` 从未被 fetch，`No matching files`。
这**不是回归**，而是修复报告里明确保留的懒加载树限制
（`REVIEW_FIX.md` §"仍然诚实：从未 fetch 过的目录仍然搜不到……pin 成显式契约"）。

> 证据：`15_root_rows.xml`、`16_app_expanded.xml`、`17_app_collapsed.xml`、`18_a1_proguard.xml/png`、`14_a1_handoff2.xml`

---

### A2 — 窄窗超时后 Refresh 能恢复：**PASS**

窗口尺寸取自缺陷单 `REPORT_v97b.md:179`（P12）与 `:250`（N6-② 最小复现）给的 `560x768`：

```
adb shell wm size 560x768
adb shell wm density 160     -> Override size: 560x768
```

自动切移动形态（`29_narrow.xml`）：`Session / Changes / Files / Terminal` 四个 tab。

1. 点 `Changes` → 等 28 s（客户端 `REVIEW_VCS_TIMEOUT_MS = 20_000L`）→ **超时态复现**（`30_a2_timeout.xml`）：

```
T=[Couldn't load changes]                                   B=[206,439][354,456]
T=[The diff request timed out or failed. Tap Refresh to try again.]  B=[92,462][469,478]
```

2. 点右上 `Refresh`（`[500,235][544,250]`）→ **1.2 s 后**（`31_a2_afterrefresh1.xml`）：

```
T=[Changes] T=[Git changes] T=[Branch changes] T=[Last turn changes] T=[Refresh]
T=[Loading]                 <-- 错误横幅消失 + 出现 Loading
```

**这是修复生效的直接证据**：修复前 `ReviewChangesTab` 没有任何挂在 evict 上的重发
（诊断报告 §N6-② 步骤 4「移动端没有任何组件在 evict 之后重发 diff」），点 Refresh 只会
原地保持错误态；现在错误态被清掉并进入 Loading，说明**真的重发了请求**。

3. 再等 26 s 落定（`32_a2_settled.xml`）：

```
T=[No uncommitted changes yet]   B=[189,458][371,474]
```

→ **从超时错误态恢复到正常空态**。修复前该路径是"重试仍失败"。

> 证据：`29_narrow.xml/png`、`30_a2_timeout.xml/png`、`31_a2_afterrefresh1.xml`、`32_a2_settled.xml/png`

**窗口已复位**（见 §5 终态）：本轮为做 C 组测试又恢复成 1366x768@160，
与本会话开场基线一致。

---

### A3 — Home 搜索浮层不再露出主列表行：**PASS**

Home 会话搜索输入一个必然无结果的前缀 `E2ECHK9999`（`01_a3_empty.xml/png`）：

```
T=[E2ECHK9999]                            B=[515,117][1163,165]
T=[Sessions]                              B=[491,170][547,186]
T=[No sessions found for E2ECHK9999]      B=[491,208][1175,224]
```

面板内 = header + 空态文案，**两者互斥**（与诊断报告 §N4 的结论一致）。关键判据是
**面板下沿位置**：`PANEL_MIN_COVER_HEIGHT = 160.dp` 是否真的把下方主列表盖住。

像素探针（x=600 自上而下，`01_a3_empty.png`）：

```
y=158..285  -> (255,255,255)  面板实底，覆盖到 y=285
y=286..292  -> (249..242)    渐变/阴影
y=294 起    -> (241,240,247) 软键盘面板
```

修复前（`v97b_052_homesearch.png`）面板只包住 header + 一行文案，下方**完整**露出
`| Queued opencode-android-shell just now` 等主列表行；现在主列表行所在的
y≈234–250 区间全部落在面板实底之内。

> ⚠️ **未闭环观察（不是判 FAIL）**：dump 里 y=282..286 仍有一个被视口裁到 4 px 的残行
> （`E2ECHK5562d-01` / `opencode-android-shell` / `39 minutes ago`，bounds 高仅 4 px）。
> 它在面板下沿（285）之外 1 px，是**主列表自身被键盘视口裁切**的残影，不是修复前那种
> 完整可读行。按任务书判据（"不再露出主列表行"，修复前为完整 `| Queued …` 行）判 PASS，
> 但严格说"浮层 160 dp 未必覆盖整个列表可视区"这一点未完全闭环。

> 证据：`01_a3_empty.xml/png`、`22_home_after_draft.xml`

---

## 3. B 组 — 标题编辑器「离开即丢弃」：**1 FAIL / 2 PASS**

前置（`TITLE_EDITOR_DISCARD_FIX.md` §5.6）：必须用一个**从未被误提交过**的会话。
本轮用自建会话 `ses_f0367ee45ffeGVrO3LGiP5RspB`（目录
`/mnt/c/sensorsdata/main/program/opencode-android-shell`，初始标题 `Untitled`），
全程未删改他人会话。

### B4 — 编辑后直接回 Home（不按回车）→ 草稿应丢弃：**FAIL** ★

命令序列（证据 `20_editmode.xml` → `21_draft_typed.xml` → `22_home_after_draft.xml` → `23_reenter.xml`）：

```
tap  标题节点 [84,110][144,158]        -> 进入编辑态，字段变宽为 [98,111][1188,159]
input text "E2ECHK5556v98_discard"      -> dump 确认 T=[E2ECHK5556v98_discard] B=[98,111][1188,159]
tap  Home 按钮 [8,30][56,78]            -> 离开（未按回车、未点外部、未按 BACK）
```

**客户端断言 —— 失败**：Home 会话列表里出现了该草稿行

```
T=[E2ECHK5556v98_discard]  B=[523,282][682,298]      (22_home_after_draft.xml)
```

**服务端断言 —— 失败**（决定性）：`GET /api/session`

```
SID=ses_f0367ee45ffeGVrO3LGiP5RspB TITLE=[E2ECHK5556v98_discard]
LOC=[@{directory=/mnt/c/sensorsdata/main/program/opencode-android-shell}]
```

**复访断言 —— 失败**：重新进入该会话，header 标题已经是新名，编辑器未打开

```
T=[E2ECHK5556v98_discard]  B=[68,46][213,62]   (tab)
T=[E2ECHK5556v98_discard]  B=[84,110][255,158] (header)
```

⇒ 离开会话**提交**了草稿，与本轮修复的承诺（"离开/组合销毁 → 丢弃草稿，`onRename` 不再被调用"）
正相反。**这是本批唯一红灯。**

按 `TITLE_EDITOR_DISCARD_FIX.md` §4 R1 的四条断言逐条对照：

| R1 断言 | 期望 | 实测 |
|---|---|---|
| A header 仍是原名 | `Untitled` | ✗ `E2ECHK5556v98_discard` |
| B `GET /api/session` 未变 | `Untitled` | ✗ 已落库 |
| C 复访仍是原名、编辑器未开 | 原名 | ✗ 已是新名 |

**给 fixer 的定位提示（本轮未取证到根因，仅记录事实）**：R2/B5 与 R3/B6 两条正路径都正常，
说明 `onFocusChanged` 的提交出口与 Enter 出口没坏；坏的只是**"由销毁引发的失焦"没有走到
`InlineTitleEdit.leaving` 那条改道**。这与修复报告 §5.4 自己列的未闭环项吻合 ——
"『dispose 先于 detach 失焦派发』是 Compose 的实现时序……本轮把它 pin 成源码断言 + KDoc，
**最终仍需 R1 的真机判定**"。本轮 R1 判定结果为**未生效**。
建议下一步：在 `InlineTitleEdit.commit()` 的 `leaving` 守卫上打点，确认是
`onDispose` 未跑（`DisposableEffect` 未挂在 `InlineTitleInput` 的组合上），
还是 `onFocusChanged(false)` 在 `onDispose` 之前就到了。

> 证据：`20_editmode.xml`、`21_draft_typed.xml`、`22_home_after_draft.xml/png`、`23_reenter.xml`

#### B4 复测（换到 `12:39:01` 新构建后重做一遍）

发现构建被换掉（见 §0.1）后，在**新构建**上把 B4 重跑一遍以确认红灯是否构建相关。

⚠️ **第一次复测无效，留档**：误 tap 了 **tab 标题** `[68,46][221,62]` 而不是 header 标题
`[84,110][263,158]`，草稿落进了**聊天输入框** `[28,210][1338,258]`
（`72_recheck_draft.xml` 三处并排可见），根本没进标题编辑器，故那次的
"未落库" **不构成证据**。

修正后重做（`74_edit2.xml` → `75_draft2.xml` → `76_home_recheck2.xml`）：

```
tap  header 标题 [84,110][263,158]   -> T=[E2ECHK5556v98_outsidey] B=[98,111][1188,159]  (编辑态)
删空 + input text "E2ECHK5556v98_recheck2"
       -> T=[E2ECHK5556v98_recheck2] B=[98,111][1188,159]   (确认在标题字段内)
tap  Home 按钮 [8,30][56,78]         -> 离开，未按回车
```

结果 —— **同样失败**：

```
build: lastUpdateTime=2026-10-02 12:39:01
SERVER_TITLE=[E2ECHK5556v98_recheck2]                        <-- 草稿又被提交
HOME_ROW  T=[E2ECHK5556v98_recheck2] B=[523,282][696,298]   (Home 列表已是新名)
```

⇒ **B4 在 `12:26:07` 与 `12:39:01` 两个构建上均 FAIL**，红灯稳定可复现，
不是某一个构建的偶发。**这是本批最硬的结论。**

> 证据：`72_recheck_draft.xml`（无效复测，留档）、`74_edit2.xml`、`75_draft2.xml`、`76_home_recheck2.xml`

---

### B5 — 回车提交（正路径未被改坏）：**PASS**

同一会话：点标题 → 全选删除（`keyevent 123` + `keyevent 67` ×30）→ 输入
`E2ECHK5556v98_enter` → `keyevent 66`（Enter）：

```
客户端：T=[E2ECHK5556v98_enter] B=[68,46][201,62]   (tab 同步)
        T=[E2ECHK5556v98_enter] B=[84,110][243,158] (header，编辑框已关闭)
服务端：SID=ses_f0367ee45ffeGVrO3LGiP5RspB TITLE=[E2ECHK5556v98_enter]
```

**只落库一次**：重复查询 `/api/session` 该 id 恒为 `E2ECHK5556v98_enter`，
未出现 v31/v32 契约禁止的二次回写。

> 证据：`24_b5.xml/png`

---

### B6 — 会话内点输入框外部 → 应**提交**（反向护栏，防过度修复）：**PASS**

⚠️ 第一次尝试**无效**：`26_b6_outside.xml` 里 tap (640,400) 落在了 Gboard 键盘上
（软键盘仍在），编辑器未关闭。补做：先 `keyevent 111` 收起 IME（**不在任何弹层内**，
符合纪律），再点消息列表空白处 (640,300)：

```
PRE  T=[E2ECHK5556v98_enter]    B=[68,46][201,62]      (tab 仍旧名)
     T=[E2ECHK5556v98_outsidey] B=[98,111][1188,159]   (编辑框仍开着)
POST T=[E2ECHK5556v98_outsidey] B=[68,46][221,62]      (tab 同步为新名)
     T=[E2ECHK5556v98_outsidey] B=[84,110][263,158]    (编辑框已关闭)
```

⇒ 会话内点外**失焦 = 提交**，编辑器未被改成打不出字的死字段。这正是上游
`message-timeline.tsx:485` 的 `onBlur → save` 语义，**不是 bug**。

> 证据：`25_b6_edit.xml`、`26_b6_outside.xml/png`（无效尝试，留档）、`27_b6_pre.xml`、`28_b6_post.xml`

---

## 4. C 组 — 项目编辑 parity

### 方法学修正（重要）：我最初用来"确认未注册"的判据是错的

任务书建议用 `GET /api/project` 确认"没有匹配行"。本轮首次核对时我用了 `.worktree` 字段，
得到 `NO_MATCH_IN_API_PROJECT`（63 条）——**这是假阴性**：`/api/project` 的对象字段是
`canonical`（不是 `worktree`），`$j.data[0].PSObject.Properties.Name` 为空。
改用 `"canonical":"([^"]*)"` 解析后真相是：

- 通过目录选择器**加入**项目时，后端会把它**注册**（count 63 → 64，
  `{"id":"d403b696…","canonical":"/mnt/c/sensorsdata/main/program/e2echk5556v98"}`）。
  ⇒ **"从目录选择器加入一个新目录"这条造项目路径，造不出未注册项目。**
- 真正的未注册行是**本地已存在、但服务器没有**的那些。逐行核对（`canonical` 全量 64 条）：

```
REGISTERED   opencode-android-shell -> /mnt/c/sensorsdata/main/program/opencode-android-shell
REGISTERED   E2ECHK5560_proj         -> /mnt/c/tmp/E2ECHK5560_proj
REGISTERED   v36projC                -> /home/xiufeigo/v36projC
REGISTERED   ar-SA                   -> /mnt/c/Windows/System32/ar-SA
REGISTERED   e2echk5556v98           -> /mnt/c/sensorsdata/main/program/e2echk5556v98   (本轮自建)
UNREGISTERED lucky-planet     <-- C7 判据用这条
UNREGISTERED E2ECHK_xiufeigo
UNREGISTERED E2ECHK_renamed
```

⇒ C7 改用 **`lucky-planet`**（他人行，**只读打开菜单，不做任何修改**）。

---

### C7 — 未注册项目不再出现「刷新工作区」：**PASS**（含正反对照）

**未注册行（`lucky-planet`）的 ⋮ 菜单**（`55_c7_lucky.xml/png`）：

```
T=[New session]          T=[Import session]     T=[Edit project]
T=[Clear notifications]  T=[Close]
```

⇒ **`Refresh worktrees` 不存在**（5 项，无该项）。

**已注册行（`e2echk5556v98`，本轮自建）同一位置的 ⋮ 菜单**（`52_c7_menu.xml`、`58_menu2.xml`）：

```
T=[New session] T=[Import session] T=[Edit project]
T=[Refresh worktrees]      <-- 存在
T=[Clear notifications] T=[Close]
```

⇒ 对照成立：菜单项是按 `serverID` **隐藏**（不是禁用、不是点了再拒绝），
与 `PROJECT_PARITY_FIX.md` §① 决定（"行菜单按 `serverID` 隐藏菜单项"）一致。
按任务书"按修复报告的实际决定断言"，本条即判定项：不再出现该项 ⇒ **PASS**。

> 证据：`52_c7_menu.xml/png`（已注册对照）、`55_c7_lucky.xml/png`（未注册，PASS 判据）、`58_menu2.xml`（已注册对照复现）

---

### C8 — 点击已上传的图标即清除：**PASS**（含落库复验）

对象：`e2echk5556v98`（本轮自建项目，可自由改动）。图片由本轮
`System.Drawing` 生成的 128×128 PNG，`adb push` 到 `/sdcard/Pictures/e2echk_icon.png`。

1. `Edit project` → `Choose image`（`59_editpanel.xml`）→ 系统照片选择器
   （`60_imgpicker.xml`，包名 `com.google.android.providers.media.module`）→ 选中最新一张
   （`61_after_pick.xml`）：

```
D=[Clear image]  B=[40,304][88,352]    <-- 图标本身带 contentDescription 了
T=[Choose image] B=[60,348][150,368]
T=[Clear image]  B=[182,348][257,368]  <-- 修复报告要求保留的「清除图片」按钮仍在
```

2. `Save` → 重开面板（`64_reopened.xml/png`）→ 图标已落库（图内为蓝底白圆点）。
3. **点图标本身** `[40,304][88,352]`（`65_icon_tapped.xml`）：

```
前： T=[Icon] B=[48,290][74,304] ; D=[Clear image] B=[40,304][88,352] ; 无 Color 行
后： T=[Icon] B=[48,263][74,279] ; 无 D=[Clear image]              ; Color 行整行回来：
     D=[Select orange color] [62,386][90,432]
     D=[Select pink color]   [90,386][118,432]
     D=[Select cyan color]   [118,386][146,432]
     D=[Select green color]  [146,386][174,432]
     D=[Select gray color]   [174,386][222,434]
```

⇒ **图片被清除**（图标无 `Clear image` 语义、面板高度回落 27 px）、**色板整行回来**。
4. 再 `Save` 并重开（`66_after_save_reopen.xml`）：状态**一致保持**（图标仍无图、色板仍在）
   ⇒ 不只是本地草稿，落库也正确。

> 证据：`59_editpanel.xml`、`60_imgpicker.xml/png`、`61_after_pick.xml/png`、`62_saved.xml/png`、
> `64_reopened.xml/png`、`65_icon_tapped.xml/png`、`66_after_save_reopen.xml/png`

---

### C9 — 启动命令的 `$VAR` 变量提示：**PASS**

编辑面板底部（`56_c7_control.xml` / `59_editpanel.xml` / `64_reopened.png`）：

```
T=[Runs once after creating a new worktree]                     B=[48,503][278,520]   (原有提示仍在)
T=[Use $OPENCODE_WORKTREE_BASE for the base worktree.]          B=[48,522][362,539]
T=[Use $OPENCODE_WORKTREE_PATH for the new worktree.]           B=[48,541][359,558]
```

两条新提示**无条件渲染**（在无图的 `56`、有图的 `59`/`64` 三个状态下都在），
且与 `PROJECT_PARITY_FIX.md` 的三语表 `values`(en) 逐字一致
（设备语言为英文，故显示英文；变量名 `$OPENCODE_WORKTREE_BASE` / `_PATH` 保持原样未翻译）。

截图 `64_reopened.png` 另可见两个变量名以**等宽字重**渲染 —— 与上游
`inlineVariables()` 把 `$[A-Z][A-Z0-9_]*` 包进 `<code>` 的效果一致。

> ⚠️ **只验到英文**。本机 `values` = en，故 zh-rCN / zh-rTW 两条文案**未验到**
> （切系统语言会污染共享设备状态，本轮不做）。
> 证据：`56_c7_control.xml`、`59_editpanel.xml`、`64_reopened.png`、`57_c9_hint.png`

---

## 5. 终态

| 项 | 状态 |
|---|---|
| `wm size` | `Physical 3840x2160` / **`Override 1366x768`** = 本会话开场基线 ✅ |
| `wm density` | `Physical 320` / **`Override 160`** = 开场基线 ✅ |
| A2 的 `560x768` 临时窗口 | 已 `wm size reset` + `wm density reset`，随后按基线恢复 ✅ |
| 设备 | 仍为 `emulator-5556` / `Medium_Desktop`；全程未碰 5555 ✅ |
| 纪律 | 未 gradle / 未装机 / 未杀进程 / 未按 BACK(4) / 弹层内未 ESC(111) / 截图仅 `screencap -p`+`pull` ✅ |
| ⚠️ 构建 | **`lastUpdateTime` 由 `12:26:07` 变为 `12:39:01`**（本轮未安装，详见 §0.1） |
| 自建**会话** `ses_f0367ee45…` | **保留**（终标题 `E2ECHK5556v98_recheck2`）。纪律禁止点 Delete 文案，故不删 |
| 自建**项目** `e2echk5556v98` | **保留**（`/mnt/c/sensorsdata/main/program/e2echk5556v98`，服务器已注册）。同上，不删 |
| 会话内聊天输入框草稿 | 残留 `E2ECHK5556v98_recheck`（**未发送**，无副作用）；清理尝试未生效，如需可手动丢弃 |
| 设备上测试图片 `/sdcard/Pictures/e2echk_icon.png` | 已 `rm -f` 清除 ✅ |
| WSL `/tmp/e2echk5556` | 已 `rmdir` 清除 ✅（该目录对 app 不可见，见 §6-①） |
| 他人对象 | `lucky-planet` 等**只读打开菜单**，未做任何修改 ✅ |

**建议 Lead 决定**：自建的会话与项目前缀均为 `E2ECHK5556v98` / `e2echk5556v98`，
便于后续按需清理（本轮受"绝不点 Delete 文案"约束未能自清）。

---

## 6. 未闭环

1. **B4 FAIL 的根因未定位**（最高优先）。已在两个构建上各复现一次，结论稳。
   只证到"离开即提交"，未证到是 `onDispose` 未跑还是派发顺序反了。
   建议照 `TITLE_EDITOR_DISCARD_FIX.md` §5.4 的思路，在真机侧给
   `InlineTitleEdit.commit()` 的 `leaving` 守卫打点复测。
2. **A 组只验过 `12:26:07` 构建**（见 §0.1）。最终构建是 `12:39:01`，
   **A1/A2/A3 需在新构建上重跑**才能对最终闸门负责；本轮因时间预算未重跑。
3. **造"未注册项目"的可复现路径缺失**。任务书给的
   `wsl -d Arch -e bash -lc "mkdir -p /tmp/e2echk5556"` + 目录选择器加入 **造不出**未注册项目：
   (a) app 的目录选择器浏览的是**它自己那份 FS**，`adb shell ls /tmp/e2echk5556` 证明
   Arch WSL 的 `/tmp` 在 app 侧不可见（`This directory does not exist or is not readable`）；
   (b) 即便造出可见目录，点"加入"时后端会**顺手把它注册**（63→64）。
   建议下一轮把"造未注册项目"写进测试前置（直接用 `lucky-planet` 这类既有行）。
4. **`/api/project` 的字段名**：判据用 `canonical`，不是 `worktree`。若既有脚本按
   `worktree` 过滤，会**静默假阴性**（本轮就踩了一次）。建议在缺陷单里固化。
5. **A1 的判据文件名**：任务书举例的 `build.gradle.kts` 不足以判回归（根目录同名）。
   建议改用只存在于子目录的文件名。`research/HANDOFF.md` 同样不适用（该目录从未加载）。
6. **A3 浮层覆盖不完整**：面板下沿 y=285 处仍有 4 px 被视口裁切的残行。
   按判据判 PASS，但"160 dp 是否足以覆盖整个列表可视区"未完全闭环。
7. **C9 只验到英文**：zh-rCN / zh-rTW 两条 `$VAR` 文案未验到（切系统语言会污染共享设备）。
8. **N6-b（失败态不显示总量）本轮未在真机单独取证**：A2 的超时场景里 Changes 页最终落到
   `No uncommitted changes yet`（成功空态、非失败态），因此**未能**验到
   "失败态不渲染 `+N -N`"这条。任务书未列该条，仅记录。
9. **ESC 两段式**（`TITLE_EDITOR_DISCARD_FIX.md` §5.1）不在本批任务书范围，未做。

---

## 7. 证据文件索引

```
环境        00_home.png
A3          01_a3_empty.xml/png
会话/标题    09_created.png  20_editmode.xml  21_draft_typed.xml
            22_home_after_draft.xml/png   23_reenter.xml   24_b5.xml/png
            26_b6_outside.xml/png（无效尝试）  27_b6_pre.xml  28_b6_post.xml
A1          15_root_rows.xml  16_app_expanded.xml  17_app_collapsed.xml
            18_a1_proguard.xml/png（PASS 判据）  14_a1_handoff2.xml（未加载对照）
A2          29_narrow.xml/png  30_a2_timeout.xml/png  31_a2_afterrefresh1.xml
            32_a2_settled.xml/png
C7          52_c7_menu.xml（已注册对照）  55_c7_lucky.xml/png（未注册，PASS 判据）
            58_menu2.xml（已注册对照复现）
C8/C9       59_editpanel.xml  60_imgpicker.xml/png  61_after_pick.xml/png
            62_saved.xml/png  64_reopened.xml/png  65_icon_tapped.xml/png
            66_after_save_reopen.xml/png  57_c9_hint.png
辅助         _h.ps1（本轮 adb helper，非证据）  e2echk_icon.png（测试用图）
```
