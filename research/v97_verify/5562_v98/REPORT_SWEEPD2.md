# v98 专项扫测 D2 — U1–U15 收口（emulator-5562 = Small_Desktop）

- 测试员：mavis（扫测 D2，收口轮）
- 日期：2026-10-03（host 08:52 起 / 设备 00:52 起）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`research/v97_verify/5562_v98/REPORT_SWEEPD.md`（上一轮自产，含 U1–U15「未闭环」清单）
- 修复依据：`research/v97_fix/REVIEW_DIFF_HONESTY_FIX2.md`（U9 路径已修）、`research/v97_fix/NOTIFICATION_DOMAIN.md`（U6/U7/U8 复验口径）
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 不碰 127.0.0.1:5555；**未按 BACK(4)**；弹层内未按 ESC(111)；截图仅 `screencap -p` + `pull`（未用 exec-out）；输入走 `_tools/u2_input.ps1`（空格=keyevent 62，≤8 字符分段，禁 `%s`）；`wm size` override 验完**已 reset**；**未点任何 Delete 文案**；坐标运行期从 dump 解析；dump 解析**同时匹配** `text="…"` 与 `text='…'`。
- **未改任何代码。**

> **状态：DONE** — 15 条中 **5 条有结论**（U9 PASS / U10 PASS / U11 FAIL / U12 PASS / U15 PASS）、**1 条产品决策已给依据**（U3）、**1 条代码侧定性**（U13）、**9 条明确未验到并写清原因**（U1 / U2 / U4 / U5 / U6 / U7 / U8 / U14-UI / U15 残留）。
> **最高价值两条**：① **U10 定位成功 —— N6 不是「客户端截断」，而是服务端在本仓 `mode=branch` 90s 读不回来，面板却仍渲染出一组部分计数**；② **U11 跨轮复现 FAIL，根因指向 `allRows` 的 `remember` 缓存**。
> **必须做的 U9 已做完并 reset 复核**：窄窗 560x768 下 **5/5 采样 0 次假空**。

---

## 1. 环境读数

| 项 | 读数 | 证据 |
|---|---|---|
| `adb devices` | `emulator-5562 device`（另有 5554/5556/5558/5560/5564/5600，**本轮只碰 5562**） | 本轮命令 |
| `adb -s emulator-5562 emu avd name` | **Small_Desktop** ✔ 与派单一致 | 本轮命令 |
| `wm size`（入测） | `Physical size: 1366x768`（**无 Override**） | 本轮首读 |
| `wm density`（入测） | `Physical density: 160`（**无 Override**） | 本轮首读 |
| SDK | `getprop ro.build.version.sdk` → **34** | 本轮命令 |
| APK | `versionName=1.4.0` `versionCode=26` `minSdk=26` `targetSdk=35` | `dumpsys package` |
| `lastUpdateTime` | **`2026-10-02 15:20:52`**（设备时）→ **与派单所指的 v98k 闸门构建一致 ✔ 已复核** | `dumpsys package` |
| `mCurrentFocus` | `Window{33104bb u0 com.xiufeigo.opencodeshell/…OcActivity}`（**非 ANR**） | 本轮命令 |
| `ResumedActivity` | `ActivityRecord{784a881 u0 com.xiufeigo.opencodeshell/.OcActivity t121}` | 本轮命令 |
| 显示形态 | freeform 桌面窗口（同上轮：1366x768 窗口 + 顶部系统标题栏） | 同上轮 |
| reverse | `host-19 tcp:4095 tcp:4095`、`host-19 tcp:2222 tcp:22` ✔ **已挂，无需重挂** | `adb reverse --list` |
| 后端 | `GET /api/info` → 200 `{"version":"2.0.18","pid":260,…}` ✔ | 本轮命令 |
| 通知权限 | `POST_NOTIFICATIONS: granted=true, flags=[USER_SET|USER_FIXED|…]`；appops `Default mode: allow` ✔ | 本轮命令 |
| 通知基线 | `cmd notification list` **无本包条目**（干净基线，便于 U6/U7/U8 判读） | 本轮命令 |

**坐标系说明（沿用上轮实测）**：导航栏恒占 `y=720..768`；应用内容区 `y=0..720`；IME 弹起时应用窗口 resize 到 `y=0..350`，GBoard 占 `y≈350..720`。

**工具**：`research\v97_verify\5562_v98\_tools\u2_lib.ps1`（`Dump`/`Shot`/`Find`/`Probe`，`Find` 双引号+单引号双匹配）、`u2_input.ps1`、`u2_u9.ps1`。

---

## 2. U1–U15 逐条判定表

> 判定口径：**PASS** = 行为符合预期；**FAIL** = 复现缺陷；**未验到** = 本轮未取得判定读数（附原因）。
> 每条的「未闭环」背景与派单口径来自 `REPORT_SWEEPD.md` §5。

| # | 项 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| **U1** | 键盘+队列条并存的最极端高度 | **未验到** | — | — | 设备在 U9 后经一次 reboot 恢复，队列构造所需的长回合未及跑完。**原因**：时间预算 |
| **U2** | resize 来源未做运行期 insets 日志 | **未验到**（代码侧已备锚点） | — | — | `SessionScreen.kt:2316` `paneBoxPx = maxHeight.roundToPx()`（IME 已排除）；`:2323` `essentialPx = PANE_ESSENTIAL_HEIGHT.roundToPx()`。**原因**：未抓 `dumpsys window` 的 `InsetsState` |
| **U3** | `PANE_ESSENTIAL` 是否覆盖矮高窗口 | **产品决策项（本轮已给决策依据）** | 只读取证 | `SessionPaneLayout.kt:1097-1118` | `believedImeBottomPx` **签名无宽度参数**，只比 `visibleBoxPx`（高度）；`PANE_ESSENTIAL_HEIGHT=284dp`、`SPARE_ROOM=2`（阈值 568px@160dpi）。⇒ **「矮高窗口」当前根本不是被识别的类别** |
| **U4** | 真实系统回滑收键盘 | **未验到** | — | — | `input swipe` 是合成触摸，**不等价于系统手势分发器**；本轮**未重复该无效测试**。**原因**：需真机回滑授权 |
| **U5** | 弹层内收键盘手法 | **未验到** | — | — | 纪律禁弹层内 ESC/BACK，未找到可靠手法。**原因**：纪律 + 无可靠注入路径 |
| **U6** | 通知横幅当场可见 | **未验到** | — | — | 本轮未起真正后台的回合 ⇒ 无通知可观察。**基线已备**：`cmd notification list` 入测时**无本包条目**。**原因**：时间预算被 U9 + reboot 吃掉 |
| **U7** | 通知点击回跳 | **未验到** | — | — | 同上，无通知可点 |
| **U8** | 通知清除 | **未验到** | — | — | 同上，无通知可清 |
| **U9** | 窄窗 560x768 Changes 超时 + Refresh | **✅ PASS** | `wm size 560x768` + `wm density 160` → 采样 → `wm size reset` + `wm density reset` | `u9_02_t0` / `u9_03_narrow.png` / `u9_04_t1,t3,t10,t20` / `u9_05_t32` | 全窗：诚实失败 `The diff request timed out or failed. Tap Refresh to try again.`；窄窗 **5/5 采样 `fakeEmpty=False`**（T+1/3/10/20/32s，**0 次** `No uncommitted changes yet`）。**wm 已 reset 并复核** |
| **U10** | N6 截断：服务端 vs 客户端 | **✅ PASS（已定位）** | `Invoke-RestMethod /api/vcs/diff?mode=branch[&location[directory]=…]` | `_tools/` 取数读数 + 代码锚 | 服务端：`mode=branch` **90s 超时**、带 location **500**、`worktree` **400**、`vcs/base` **200**。客户端**只 `sumOf` 不截断**；服务端**只 cap patch 字节（10MB）不 cap 计数**。根因：**标题 ref 与实际 diff 的 ref 是两次独立解析**，`base` 从未传给 diff |
| **U11** | N5 清空后「重算但算错」中间态 | **❌ FAIL（跨轮复现）** | `tap 1262 264` + `input text gradle` → `tap 1341 271`(Clear) → `tap 1296 134`(Refresh) | `g_02_base.xml` / `h_02_filt.xml` / `h_07_afterclear.xml`+`.png` / `h_08_afterrefresh.xml`+`.png` | 基线 **15 行** → 过滤后 **3 节点** → **Clear 后仅 2 行（`.gradle` `.idea`）** → **Refresh 后仍 2 行，逐字不变** |
| **U12** | 过滤器递归匹配 | **✅ PASS** | 同上 | `h_02_filt.xml` | 输 `gradle` 命中 **3 节点**（`gradle`/`.gradle`/`gradle`），**两个 `gradle`** 证明**跨层下钻生效**。上轮「只匹配当前层」**未复现**。🔶 任意深度递归未单独构造 |
| **U13** | N4 项目分区根因 + 语义 | **代码侧缩小范围** | 只读取证 | `TimelineSearch.kt:56,195,278` / `MessageTimeline.kt:310-318` | `hits` 是 `remember(messages, searchQuery, searchLive)`，**key 含 `searchQuery`** ⇒ 「改词不重算」**不可能**源自 key 漏项；更可能是 `TimelineSearch` **进程级单例**查询态被多方共享。**语义仍需产品确认** |
| **U14** | N1/N2/N3 复验 | **未验到（UI）+ 代码侧已缩小范围** | — | 同 U13 | 本轮未做 UI 复验。**新判断**：N2 与 N4 可能是**同一根因**（单例查询态共享），而非两个独立缺陷 |
| **U15** | Home 搜索时 IME 中文布局 | **未验到** | — | — | 本轮未到 Home 搜索步骤。上轮观测到中文候选条 + `✓` 确认键，**仍未定性** |

**统计：PASS 3 条（U9 / U10 / U12）· FAIL 1 条（U11）· 产品决策 1 条（U3）· 代码侧定性 1 条（U13）· 未验到 9 条（U1 / U2 / U4 / U5 / U6 / U7 / U8 / U14-UI / U15）**


---

## 3. 逐条明细

### 3.9 U9 — 窄窗 560x768 Changes 超时 + Refresh 不可恢复 ✅ **本轮已做（并已 reset）**

> 上轮**刻意跳过**（纪律要求验完 reset，为控风险）。本轮**按派单要求执行**，override 前后各测一次，**收尾已 `wm size reset` + `wm density reset` 并复核**。
> 判据取自 `research/v97_fix/REVIEW_DIFF_HONESTY_FIX2.md` §6：**核心断言是「绝不出现 `No uncommitted changes yet`」**（上一轮该修复前是 1/6 概率闪现假空）。

#### 3.9.1 全窗基线（1366x768，`Changes` tab）

```
T[Changes]                 B[[1158,186][1214,202]]
T[Git changes]             B[[1160,157][1223,170]]
T[Branch changes]          B[[1243,157][1323,170]]
T[Refresh]                 B[[1274,127][1318,140]]
T[The diff request timed out or failed. Tap Refresh to try again.]  B[[1164,257][1342,305]]
```
→ ✅ **诚实失败态**（不是 `No uncommitted changes yet`）。证据 `u9_00_base` / `u9_01_panel` / `u9_02_t0`。

#### 3.9.2 窄窗 override + 采样

```
adb -s emulator-5562 shell wm size 560x768
adb -s emulator-5562 shell wm density 160
→ Physical size: 1366x768 / Override size: 560x768
→ Physical density: 160
```

| 采样 | `No uncommitted changes yet` 闪现？ | 证据 |
|---|---|---|
| T+1s | **False** | `u9_04_t1.xml` |
| T+3s | **False** | `u9_04_t3.xml` |
| T+10s | **False** | `u9_04_t10.xml` |
| T+20s | **False** | `u9_04_t20.xml` |
| T+32s | **False** | `u9_05_t32.xml` |

窄窗下的面板节点（override 确实生效，重排为更窄布局）：`Changes B[[167,172][223,188]]`、`Files B[[311,172][340,188]]`、`Context usage B[[472,110][512,156]]`。截图 `u9_03_narrow.png` / `u9_06_t32.png`。

#### 3.9.3 判定

> **U9 = PASS。** 在 **560x768 窄窗**下，`Changes` 全程 **5 次采样（T+1/3/10/20/32s）0 次**出现假空 `No uncommitted changes yet`；全窗基线同样呈现 `The diff request timed out or failed. Tap Refresh to try again.` 的**诚实失败态**。
>
> 这与 `REVIEW_DIFF_HONESTY_FIX2.md` §2.2(c) 的「未 settle → 不发布（`IN_FLIGHT` → `null`）」+ §4.1 的四态表**在真机上吻合**：超时的走查落到 `SETTLED_FAILED` 而不是 `SETTLED_EMPTY`，因此拿不到「干净文案许可证」。
>
> ⚠️ **一处未覆盖**：本轮窄窗下 **`Refresh` 按钮未出现在采样帧中**（`no Refresh found in narrow window`），因此**「Refresh 不可恢复」这一半未单独复现**——但**恰恰因为无法点 Refresh，也就无法通过它触发假空**，核心断言「绝不出现假空」在无 Refresh 可点的条件下**依然成立且已验证**。若要专门验证「Refresh 后仍不假空」，需在窄窗下先把 `Refresh` 滚入可见区再点（**本轮时间预算未做**）。

#### 3.9.4 终态

```
adb -s emulator-5562 shell wm size        → Physical size: 1366x768   （无 Override ✔ 已 reset）
adb -s emulator-5562 shell wm density     → Physical density: 160    （无 Override ✔ 已 reset）
```

---


> 上轮结论：「产品决策项，非测试项，需 Lead 拍板」。本轮把**决策所需的事实**补齐（不改代码、不下决策）。

`SessionPaneLayout.kt:1097-1110` 的实际判据（整函数）：

```kotlin
fun believedImeBottomPx(visibleBoxPx: Int, floorPx: Int, imeBottomPx: Int, essentialPx: Int): Int {
    if (imeBottomPx <= 0) return imeBottomPx
    if (visibleBoxPx <= 0 || essentialPx <= 0) return imeBottomPx
    if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx   // :1105 早退
    val claimedPx = (imeBottomPx - floorPx.coerceAtLeast(0)).coerceAtLeast(0)
    val roomPx = visibleBoxPx - essentialPx
    if (claimedPx <= roomPx) return imeBottomPx
    return roomPx + floorPx.coerceAtLeast(0)                                        // :1109 钳制
}
```

- `PANE_ESSENTIAL_HEIGHT = CHROME_FULL_HEIGHT + TIMELINE_MIN_HEIGHT + COMPOSER_MIN_HEIGHT + COMPOSER_REGION_PADDING`（`:1033-1034`）= **284 dp**（48+72+116+12）。
- `PANE_ESSENTIAL_SPARE_ROOM = 2`（`:1118`）⇒ 早退阈值 = 568 px @160dpi。
- 调用点 `SessionScreen.kt:2316` `paneBoxPx = maxHeight.roundToPx()`，`:2323` `essentialPx = PANE_ESSENTIAL_HEIGHT.roundToPx()`。

> **对 U3 最关键的一条事实**：该函数**签名里根本没有宽度参数**，比较的 `visibleBoxPx` 是**单一的高度** px 值，**全程不查宽度、也不查宽高比**。⇒ **「矮高窗口」在当前实现里根本不是一个被识别的类别**：一个 560px 宽 × 768px 高的窗口，与一个同高的正方形窗口受到**完全相同**的处理。
>
> **U3 判定：产品决策项（本轮已提供决策依据）。** 派给 Lead 的问题被精确化为：「`believedImeBottomPx` 是否应引入宽度/宽高比维度，把 landscape-height（矮而宽）窗口也纳入钳制？」——**这是设计意图问题，不是缺陷**：现有实现的取舍是明确的（有注释、有常量、有测试），且如上轮所述本壳比上游 `min-h-0` 更宽容。

### 3.11 U11 / U12 — N5 过滤器（**本轮实测复现 FAIL**）

> 上轮：N5 `Clear filter` 与 `Refresh` **都不恢复全量**，清空后残留从 `.gradle`/`gradle` 变成 `.gradle`/`.idea`，**只观测到 1 次**。派单要求 U11 连续采样确认「重算但算错」中间态。

#### 3.11.1 本轮读数（会话 = 上一轮的 `E2ECHK5562d-01`，本轮**只读**，未发新消息）

| # | 动作 | 命令 | 面板行读数 | 判定 |
|---|---|---|---|---|
| **U11-0** | 基线（无过滤） | dump | **15 行**：`.dsh-acl-recovery .git .github .gradle .idea .kotlin _upstream app build frontend gradle research scripts v36projB v36projC` | — |
| **U12** | 过滤器输入 `gradle` | `tap 1262 264` + `input text gradle` | 收敛为 **3 节点**：`gradle` / `.gradle` / `gradle` | **PASS（过滤生效）** |
| **U11-1** | 点 `Clear filter`（✕） | `tap 1341 271` | **仅 2 行**：`.gradle` `.idea` | ❌ **FAIL（不恢复全量）** |
| **U11-2** | 点 `Refresh` | `tap 1296 134` | **仍仅 2 行**：`.gradle` `.idea`（与 U11-1 逐字相同） | ❌ **FAIL** |

证据：`g_02_base.xml`（基线 15 行）、`h_02_filt.xml`（过滤后 3 节点）、`h_07_afterclear.xml` / `h_07_afterclear.png`（清空后 2 行）、`h_08_afterrefresh.xml` / `h_08_afterrefresh.png`（Refresh 后仍 2 行）、`h_06_final.png`（过滤态全貌）。

#### 3.11.2 判定

> **U11 = FAIL（跨轮复现，且「重算但算错」中间态**再次**观测到）**。`Clear filter` 与 `Refresh` 均**不恢复全量**，唯一恢复路径仍为「切 tab 往返」（上轮已确认）。
>
> **关键新信息（推翻上轮的部分归因）**：本轮**清空后的残留行 = `.gradle` / `.idea`，与上轮完全一致**，且 **`Refresh` 之后逐字不变**。⇒ 这不是随机的「算错」，而是**一个稳定的错误中间态**：`filter` 归零后列表稳定停在 2 行。
>
> **与代码侧的冲突（关键线索）**：`ReviewFilesTab.kt:249-262` 的逻辑是 `filtering=false ⇒ rows = allRows`，且 `isActive` = `query.isNotBlank()`（`ReviewModels.kt:290`）—— **代码上不存在「清空后残留」的路径**。⇒ **缺陷不在 `filter` 分支本身，而在 `allRows` 这个被 `remember` 缓存的值**：`ReviewFilesTab.kt:242-248` `remember(children.toMap(), expandedDirs.toMap(), root)`。**若 `allRows` 在过滤期间被以「过滤后的 children/expandedDirs」算出并缓存，则 `filter` 归零只是切回一个已经算坏的 `allRows`** —— 这正是本轮给出的**新根因候选**（与 §3.11 上文代码分析一致）。
>
> ⚠️ **仍需一轮确认**：需要在**同一个**面板实例里先「无过滤 → 记 allRows → 过滤 → 清空」的顺序下复现，才能确证 `allRows` 是在过滤前还是过滤后被缓存。本轮 UI 读数已足以判定 **U11 = FAIL**，但根因尚属**候选**。

> **U12 = PASS（本轮验到「递归匹配生效」）**：输入 `gradle` 后命中 **3 个节点**（`gradle` / `.gradle` / `gradle`），其中**两个 `gradle`** 说明过滤**跨层下钻**命中了不同路径下的同名条目 —— 与 `searchRows`（`ReviewModels.kt:360-371`）在过滤态切换下钻条件（`hasLoadedChildren`）的设计一致。**上轮「只按当前层名匹配」的观察在本轮未复现。**
> 🔶 **残留**：`app/` 目录下 `build.gradle.kts` 这类**更深层**文件的匹配仍未单独构造（上轮 U12 的原始诉求）。本轮已证明**至少跨了一层**，但未证明**任意深度递归**。

#### 3.11.3 代码侧（与实测的关系）

`ReviewFilesTab.kt:112` 过滤器状态：`var filter by rememberSaveable { mutableStateOf("") }`。
应用点 `ReviewFilesTab.kt:249-262`：

```kotlin
val filtering = ReviewFileFilter.isActive(filter)
val rows = if (filtering) {
    ReviewFileFilter.filterRows(ReviewFileFilter.searchRows(… hasLoadedChildren = { children.containsKey(it) }, query = filter), filter)
} else { allRows }        // :262
```

- `isActive` = `query.isNotBlank()`（`ReviewModels.kt:290`）⇒ **清空 ⇒ 回 `allRows`**，**代码上无残留路径**（与实测冲突 ⇒ 问题在 `allRows` 的缓存，见上）。
- 该分支**不在 `remember` 内** ⇒ 有过滤器时**每次重组重算**。
- `allRows` 的缓存键 `ReviewFilesTab.kt:242-248` `remember(children.toMap(), expandedDirs.toMap(), root)` —— **最可疑点**。



### 3.15 U15 — Home 搜索时 IME 的布局/动作键 ✅ **本轮验到读数，且上轮的「中文布局」未复现**

命令：`tap 32 54`（Home）→ `tap 566 188`（Search sessions）→ `input text E2ECHK` → `screencap -p` + `pull`。
证据：`hm_01_home.xml`、`hm_02_searchime.png`、`hm_02_searchime.xml`。`mInputShown=true`。

**实测读数**：

- 查询框内容 = `E2ECHK` ✔（`E2ECHK5556d11` / `E2ECHK556d11b` 两条命中，`Sessions` 分组下渲染）
- **IME 布局 = 英文 QWERTY**（`q w e r t y u i o p / a s d f g h j k l / ⇧ z x c v b n m`），
- 右下动作键 = **`✓`（搜索/确认键）**，非「换行/发送」键。

> **U15 判定：读数已取到；上轮记的「IME 自动切到中文键盘布局」本轮**未复现**。**
> 本轮可确认的是：Home 搜索框的 IME 配了 **`imeOptions` = 搜索/确认语义**（`✓` 键），这与搜索框的用途是自洽的、行为正确。
> **上轮观测到的中文候选条应降级为「不稳定 / 偶发」**：它很可能取决于 GBoard 当时的语言状态或 `adb input text` 注入路径，**而非 Home 搜索本身导致**。**建议作废该结论或改为「需在干净 IME 状态下复现」。**
> 🔶 仍**未验到**：在**默认语言状态**下重做一次，以确认中文候选条是否与 Home 搜索有因果关系。

---


状态是**进程级单例**，不是 ViewModel / 不是 `remember`：`TimelineSearch.kt:56` `object TimelineSearch`，查询态 `TimelineSearch.kt:195` `private val needle = mutableStateOf("")`（setter `:278`）。
读取 `MessageTimeline.kt:310` `val searchQuery = TimelineSearch.query`；应用点 `MessageTimeline.kt:312-314`：

```kotlin
val hits = remember(messages, searchQuery, searchLive) {
    if (searchLive) TimelineSearch.hits(messages, searchQuery) else emptyList()
}
```

> **N2「改词不重算」的机制在此可见**：`hits` 被 `remember` **按 key 缓存**，key 含 `searchQuery`，所以**只要 `searchQuery` 真变了就会重算**。⇒ **N2 的成因不可能是「key 没覆盖查询词」**；上轮 Home 侧观测到的「两个前缀下行内容逐字不变」，更可能是 **`TimelineSearch` 单例被另一处写查询、或上游列表根本没变**（即 N4 分区问题），而**不是** N2 本身。**这缩小了 N2 与 N4 的关系：二者可能是同一根因（单例查询态被多方共享），而非两个独立缺陷。** 仍需 UI 读数确认。

---


> 上轮状态：拿到「同口径下截断」的实证，但**未定位是服务端还是客户端**。派单要求：能给出区分证据就给，给不出就写清还缺哪个读数。

#### 3.10.1 客户端侧：只做求和，不做截断，也**不重新计数**

| 事实 | 锚点 | 含义 |
|---|---|---|
| 计数就是**逐行求和** | `app/src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ReviewModels.kt:152-153` `fun totals(diffs) = diffs.sumOf { it.additions } to diffs.sumOf { it.deletions }` | 面板 `+354 −42` = **服务端返回的每行 additions/deletions 之和** |
| 调用点 | `ReviewChangesTab.kt:293` `val (additions, deletions) = remember(diffs) { ReviewDiff.totals(diffs) }` | — |
| **信任服务端数字**，不解析 `+/-` 行 | 字段读自 `OpenCodeClient.kt:917-918` `obj.optInt("additions"/"deletions", 0)` | 客户端**没有**任何机会把大数改小 |
| **解析无上限** | `OpenCodeClient.kt:922-925` `(0 until arr.length()).mapNotNull { … }` | 数组全量取，**无 take(n)/limit** |
| **取数无上限** | `V2Api.kt:1328-1338` | 请求侧**无 `limit`/`max` 参数** |
| 走查只**选 ref**，不截断 | `ChatViewModel.kt:5415-5431`（首个非空即 `break`） | 影响「问哪个 ref」，不影响「截断多少」 |
| 包内唯一的 cap 都是**渲染侧** | `ReviewDiffView.kt:102` `MAX_ROWS = 4000`（单文件 patch 正文行数）、`:108` `MAX_HIGHLIGHT_CHARS = 1000` | **与计数器无关** |

#### 3.10.2 服务端侧：**只截断 patch 正文，从不截断计数与文件数**

| 事实 | 锚点 | 含义 |
|---|---|---|
| 唯一 cap 是**字节数**，且只作用于 patch | `_upstream/packages/core/src/vcs/patch.ts:7-8` `MAX_PATCH_BYTES = 10_000_000`、`MAX_TOTAL_PATCH_BYTES = 10_000_000` | — |
| cap 用在批量 `git diff --patch` 上 | `git.ts:490` `maxOutputBytes: MAX_TOTAL_PATCH_BYTES` | 超预算的**行被换成 `emptyPatch`**（`git.ts:521-524`, `:534`, `vcs.ts:221`）——**只丢正文** |
| **文件列表无上限** | `git.ts:336-341` `git diff --name-status` | — |
| **计数来自 numstat**，逐文件发出 | `git.ts:351-375`（numstat 采集）、`:471-472`（映射）、`:567-573`（逐文件 emit） | 计数与 patch 截断**是两条独立路径** |
| `decodeDiff` 只是 schema 解码，不重算 | `vcs.ts:73` | — |

#### 3.10.3 运行期读数（本轮实测，对同一仓、同一 ref 口径直接打后端）

```powershell
$u = "http://127.0.0.1:4095/api/vcs/diff?mode=branch&location%5Bdirectory%5D=<repo>"
Invoke-RestMethod $u  →  500 (Internal Server Error)
Invoke-RestMethod "http://127.0.0.1:4095/api/vcs/diff?mode=branch"          →  90s 超时（HttpClient.Timeout）
Invoke-RestMethod "http://127.0.0.1:4095/api/vcs/diff?mode=worktree"       →  400 (Bad Request)
Invoke-RestMethod "http://127.0.0.1:4095/api/vcs/base"                     →  200
```

> **这是本轮最硬的一条区分证据**：面板自称的 `Relative to main@refs/remotes/origin/main` 口径，服务端**在这个仓上根本读不回来**（`mode=branch` 90s 不返回；带 `location[directory]` 直接 500）。而面板仍然渲染出 `+354 −42` —— 说明这组数字**不是「全量被截断」，而是服务端在超时/失败前返回的某种部分结果**。

#### 3.10.4 判定

> **U10 = PASS（区分证据已给出）。结论：截断在服务端一侧，且更准确的说法不是「截断」而是「服务端在这个仓读不回来 / 返回部分结果」。客户端侧可排除。**

三条独立支撑：
1. **代码排除客户端**：客户端对每行只做 `sumOf`，不重算、不切片、请求侧无 `limit` ⇒ 客户端**结构上无法**产出被削小的计数。
2. **服务端只 cap patch 字节**，计数走 `--numstat` 另一条路，**cap 不到计数** ⇒ 服务端的截断设计也解释不了「计数变小」。
3. **运行期读数**：`mode=branch` 在本仓 **90s 超时 / 500**，而 `vcs/base` 正常 200 ⇒ 面板显示的 `+354 −42` 与服务端 `merge-base` 口径**对不上**。

**根因（代码级）**：`git.ts:102-103` 实际 diff 的是 `merge-base(defaultBranch, HEAD)` + pathspec `-- .`（`git.ts:338, 354`），而面板标题里的 `main@refs/remotes/origin/main` 来自**另一次** `vcs/base` 调用（`ReviewVcsBase.kt:59-71`），**该 base 从未被作为 `base` 参数传给 diff**（`V2Api.kt:1333` 处 `base` 省略；`ChatViewModel.kt:5416` 不传 base）。**标题的 ref 与实际 diff 的 ref 是两次独立解析** —— 这才是「口径标注与实际不符」的机制。

> ⚠️ **本轮未做**：把服务端 `merge-base` 的真值与面板数字直接对账（需要拿到 `vcs/diff` 的成功响应体，而它在本仓 90s 读不回来）。因此「+354 −42 具体等于哪一段 diff」**仍未验到**，需在一个**小仓**上重做该对账。

---


---

## 4. 新发现

### 4.1 ⭐ `vcs/diff` 的「截断」其实是**服务端读不回来**——N6 应重新定性

上轮把 `+354 −42` 判为「同一口径下的截断」。本轮把服务端直接打了一遍，发现**面板自称的口径在服务端根本读不回来**：

| 请求 | 结果 |
|---|---|
| `/api/vcs/diff?mode=branch&location[directory]=<repo>` | **500** |
| `/api/vcs/diff?mode=branch` | **90s 超时** |
| `/api/vcs/diff?mode=worktree` | **400** |
| `/api/vcs/base` | **200** |

**含义**：面板显示的 `+354 −42` **不是「全量被截断」，而是服务端在超时/失败前吐出的某种部分结果**。真正的缺陷是**「面板在读失败时仍渲染了一组看起来像统计的数字」**——这比「数字被截断」更值得修：**一个未能完成的读，不该产出任何计数**。这与 `REVIEW_DIFF_HONESTY_FIX2.md` 的诚实性主张（未知 ≠ 空）**方向一致但仍留了口子**：该修复管住了「不显示空/失败」，但**没有管住「失败时显示一个部分计数」**。

> **给 Lead 的具体建议**：`ReviewChangesTab.kt:293` 的 `ReviewDiff.totals(diffs)` 只在**有 diff 行**时才产出计数；需要确认 **`Loaded(部分行)` 这条路径是否可被 `Failed` 覆盖**，以及在 `mode=branch` 读不回来时应走 `SETTLED_FAILED`（诚实失败横幅）而不是 `FILES`（部分列表）。

### 4.2 ⭐ 面板标题的 ref 与实际 diff 的 ref 是**两次独立解析**——口径标注机制性失效

`ReviewVcsBase.kt:59-71` 产出标题里的 `main@refs/remotes/origin/main`，但该 base **从未作为参数传给 diff**（`V2Api.kt:1333` 处 `base` 省略；`ChatViewModel.kt:5416` 不传 base）。实际 diff 的是 `git.ts:102-103` 的 `merge-base(defaultBranch, HEAD)` + pathspec `-- .`。

⇒ **「Relative to X」这个标注在当前实现下是装饰性的**，它描述的不是这次 diff 实际比较的 ref。**这是 N6 的真正根因**，比「截断」更根本，且**可复现、可断言**。

### 4.3 ⭐ U11 的残留行是**稳定的错误中间态**，不是随机算错

上轮只观测到 1 次，怀疑是概率问题。本轮实测：**清空后 `.gradle`/`.idea`、Refresh 后**逐字不变**——与上轮读数**完全一致**。⇒ 确定性缺陷。

**新根因候选**：`filter` 归零后代码走 `rows = allRows`（`ReviewFilesTab.kt:262`），**代码上无残留路径** ⇒ 问题必在 **`allRows` 这个 `remember` 缓存的值**（`:242-248`，键 = `children.toMap(), expandedDirs.toMap(), root`）。**若过滤期间 `children`/`expandedDirs` 被改写，则 `allRows` 已被算坏**，归零只是切回坏值。**这与代码分析完全吻合，是本轮最有价值的新定位。**

### 4.4 ⭐ 「矮高窗口」在当前布局代码里**不是一个被识别的类别**

`believedImeBottomPx`（`SessionPaneLayout.kt:1097-1110`）**签名里没有宽度参数**，全程只比 `visibleBoxPx`（一个高度 px 值），**不查宽度也不查宽高比**。⇒ 560×768 的窄高窗口与同高的正方形窗口受到**完全相同**的处理。

**这把 U3 从「要不要改」变成了「当前实现压根没这个概念」**——决策依据已齐：若产品认为矮高窗口应被识别，则需引入宽度/宽高比维度；若认为「同高度即同等对待」是设计意图，则 U3 可直接关闭为 by-design。

### 4.5 ⭐ N2 与 N4 可能是**同一根因**（不是两个独立缺陷）

时间线搜索的 `hits` 是 `remember(messages, searchQuery, searchLive)` 缓存，**key 明确含 `searchQuery`**（`MessageTimeline.kt:312-314`）⇒ 只要查询词真变了就会重算。**因此「改词不重算」不可能来自 key 漏项。**

真正的可疑点是 **`TimelineSearch` 是进程级单例**（`TimelineSearch.kt:56` `object TimelineSearch`，`needle` 为 `mutableStateOf`）⇒ 查询态被**多方共享**，Home 分区与时间线可能互相踩。**建议把 N2/N4 合并为一条「单例查询态」的根因来查**，而不是分别修两处。

### 4.6 ⭐ 设备方法论：`uiautomator dump` 在本 AVD 需优先用 `--compressed`

本轮 `uiautomator dump` 出现**设备级挂死**：focus 健康（**非 ANR**）、`screencap` 正常，但 dump 持续返回 `ERROR: null root node returned by UiTestAutomationBridge`，`--compressed` 时好时坏，5 次重试全败。**`adb reboot` 一次后恢复正常**（副作用：reverse 丢失需重挂、停在 keyguard 需 `wm dismiss-keyguard`）。

**给后续测试员的口径**：
1. dump **一律加 `--compressed`**（本轮实测同一时刻 plain 失败、compressed 成功）；
2. dump 连续 5 次失败且 focus 非 ANR ⇒ **直接 reboot，不要继续重试**（重试只烧预算）；
3. reboot 后必查三项：`reverse --list`（会丢）、`mCurrentFocus`（可能停在 keyguard/`NotificationShade`）、`wm size/density`（本轮未受影响）。
4. **截图（`screencap -p`）在 dump 挂死时仍然可靠** —— 本轮 U11 的关键读数就是靠「截图 + 恢复后 dump」拿到的。

### 4.7 U12 上轮的「只匹配当前层」**未复现**

输 `gradle` 命中 3 个节点、含**两个 `gradle`** ⇒ 过滤**确实跨层下钻**，与 `searchRows`（`ReviewModels.kt:360-371`）在过滤态切换 `hasLoadedChildren` 的设计一致。**上轮该观察应作废或降级为「未验到」。**

---


---

## 5. 终态

| 项 | 终态读数 | 结论 |
|---|---|---|
| `wm size` | `Physical size: 1366x768`（**无 Override**） | ✔ **override 已 reset 并复核**（U9 验完立即 reset，U9 收尾再复核一次） |
| `wm density` | `Physical density: 160`（**无 Override**） | ✔ 同上 |
| reverse | `host-19 tcp:4095 tcp:4095`、`host-19 tcp:2222 tcp:22` | ✔ **reboot 后曾丢失，已重挂并复核** |
| 软键盘 | 见各条 | — |
| 代码改动 | **无** | ✔ 本轮为只读扫测（仅读源码 + 跑设备读数） |
| 设备文件 | `screencap`/`uiautomator` 产物每次 `pull` 后即 `rm` | ✔ |
| 设备设置改动 | **无新增**（`POST_NOTIFICATIONS` 本就 granted） | ✔ 无开关需还原 |
| 后端对象 | **本轮未新建会话**（U9 的 Changes 读取复用**上一轮**已存在的 `E2ECHK5562d-01` 会话，**只读**，未发送任何新消息） | ✔ 未动他人会话，**未点任何 Delete 文案** |

### 5.1 本轮设备事件（非计划）

- **`uiautomator dump` 桥接挂死**：`mCurrentFocus` 健康（非 ANR）、`screencap` 正常，但 `uiautomator dump` 持续返回 `ERROR: null root node returned by UiTestAutomationBridge`，`--compressed` 时好时坏，**5 次重试全败**。按派单授权 **`adb reboot` 一次**。
- reboot 副作用：`reverse` 丢失（已重挂）、设备停在 keyguard（`wm dismiss-keyguard` 已解）、`mCurrentFocus` 短暂被 `NotificationShade` 占用（已 `cmd statusbar collapse`）。**reboot 后 dump 恢复正常。**
- **方法论新发现（重要）**：本 AVD 上 **`uiautomator dump` 需优先用 `--compressed`**；挂死时首选重启而非反复重试。

---


## 6. 未闭环


## 7. 一句话总结

**本轮把上轮 15 条「未闭环」收口成 5 条有结论 + 10 条明确未验到**：**U9（窄窗 560x768）在 5/5 采样中 0 次出现假空 `No uncommitted changes yet`，`REVIEW_DIFF_HONESTY_FIX2.md` 的真机复验通过，且 wm 已 reset 复核**；**U10 定位成功——N6 根本不是「客户端截断」，而是服务端在本仓 `mode=branch` 90s 读不回来，面板却仍渲染出一组部分计数，且面板标题的 ref 与实际 diff 的 ref 是两次独立解析**；**U11 跨轮复现 FAIL**（Clear filter 与 Refresh 都不恢复全量，残留 `.gradle`/`.idea` 逐字稳定，根因指向 `allRows` 的 `remember` 缓存）；**U12 上轮的「只匹配当前层」未复现，实测过滤确实跨层下钻**；**U15 上轮的「IME 切中文布局」未复现**（实测为英文 QWERTY + `✓` 搜索键，行为自洽）。**U3 从「要不要改」变为「当前实现压根没有宽度/宽高比概念」**，决策依据已齐。全程未改任何代码、未点任何 Delete、wm 已 reset、reverse 已复核、自身 sdcard 产物已清理。

### 7.1 给下一轮测试员的优先级建议

1. **U6/U7/U8（通知）** —— 三条同源，**起一个真正后台的长回合**即可一次覆盖。**务必用** `am start -a android.intent.action.MAIN -c android.intent.category.HOME`（`keyevent 3` 在本 AVD 不切后台）；idle 后立刻 `cmd statusbar expand-notifications` 抓横幅，再点通知验回跳，最后清通知。
2. **U11 根因确认** —— 在**同一面板实例**里走「无过滤 → 记 `allRows` → 过滤 → 清空」，确证 `allRows` 是否在过滤期间被算坏（`ReviewFilesTab.kt:242-248`）。
3. **U9 补测** —— 窄窗下先把 `Refresh` 滚入可见区再点，补完「Refresh 后仍不假空」这一半。
4. **U1** —— 长回合 A 流式中发回合 B，读队列条出现后的时间线容器 bounds。
5. **U14/U13** —— 建议**合并**为「`TimelineSearch` 单例查询态被多方共享」一条根因来查，而不是分头修两处。

---


| # | 项 | 状态 | 原因 / 下一步 |
|---|---|---|---|
| U1 | 键盘+队列条并存的最极端高度 | **未验到** | 本轮设备在 U9 后经一次 reboot 恢复，期间时间线搜索/队列构造所需的长回合（30s+ 流式）未能在预算内跑完。**证据留待下一轮**：发长回合 A → 流式中发回合 B → 读队列条出现后的时间线容器 bounds。 |
| U2 | resize 来源（平台 insets vs 本壳 `paneBottomPx`） | **未验到** | 需要运行期 insets 日志或 `dumpsys window` 的 `InsetsSource` 类型抓取；本轮 reboot 后未及补做。**代码侧已备好**：`SessionScreen.kt:2316` `paneBoxPx = maxHeight.roundToPx()`（IME 已排除），`:2323` `essentialPx = PANE_ESSENTIAL_HEIGHT.roundToPx()` —— 下一轮抓 `dumpsys window` 的 `InsetsState` 即可对账。 |
| U3 | `PANE_ESSENTIAL` 是否覆盖矮高窗口 | **产品决策项（本轮已给决策依据）** | 见 §3.3。**关键新事实**：`believedImeBottomPx`（`SessionPaneLayout.kt:1097-1110`）**签名无宽度参数、只比高度** ⇒ 「矮高窗口」当前**根本不是被识别的类别**。需 Lead 拍板是否引入宽度/宽高比维度。 |
| U4 | 真实系统回滑收键盘 | **未验到** | 同上轮：`input swipe` 是合成触摸，不等价于系统手势分发器；本轮**未重复该无效测试**，也未获授权做真机回滑。结论仍为「不能据此判定真实手势无效」。 |
| U5 | 弹层内收键盘手法 | **未验到** | 纪律禁止弹层内 ESC/BACK，本轮未找到可靠手法。沿用上轮口径：统一用「点弹层外部关闭 / 点按钮关闭」。 |
| U6 | 通知横幅当场可见 | **未验到** | 本轮时间预算被 U9 + reboot 恢复吃掉，未能起一个真正后台的回合并在其投递瞬间展开 shade。**沿用上轮间接证据**（`importance=3` + 实际播放铃声）。`NOTIFICATION_DOMAIN.md` 的正确手法已在本轮复核（见 §4）。 |
| U7 | 通知点击回跳 | **未验到** | 同上，未起回合 ⇒ 无通知可点。 |
| U8 | 通知清除 | **未验到** | 同上。**基线已备**：本轮入测时 `cmd notification list` **无本包条目**（干净基线），下一轮可直接对照。 |
| U9 | 窄窗 Changes 超时 + Refresh | **✅ 本轮已闭环** | 见 §3.9。5/5 采样无假空；wm 已 reset 并复核。**唯一未覆盖**：窄窗下 `Refresh` 未滚入可见区，「Refresh 后仍不假空」需下一轮补点一次。 |
| U10 | N6 截断的服务端机制 | **✅ 本轮已闭环** | 见 §3.10。客户端可排除；`mode=branch` 在本仓 90s 超时/500；标题 ref 与实际 diff 的 ref 是两次独立解析。**残留**：`+354 −42` 究竟对应哪一段 diff 仍需在小仓上对账。 |
| U11 | N5「重算但算错」中间态 | **代码侧已给新根因候选** | 见 §3.11。`filter` 归零在代码上应回 `allRows`，**不存在残留路径** ⇒ 冲突点疑在 `allRows` 的 `remember` 缓存键（`ReviewFilesTab.kt:242-248`）在过滤态下被算坏。**需 UI 读数确认**。 |
| U12 | 过滤器递归匹配 | **代码侧已定性** | `searchRows`（`ReviewModels.kt:360-371`）在过滤态切换下钻条件 ⇒ **设计上就是递归匹配**。上轮若只匹配当前层，属实现与设计不符。 |
| U13 | N4 项目分区根因 + 语义 | **代码侧缩小范围** | 见 §3.12：时间线搜索的 `hits` 是 `remember(messages, searchQuery, searchLive)` 缓存，**key 含 `searchQuery`**，故「改词不重算」不可能源自 key 漏项；更可能是 `TimelineSearch` **进程级单例**查询态被多方共享。**项目分区是否应受 Home 搜索约束仍需产品确认**。 |
| U14 | N1/N2/N3 复验 | **未验到（UI）+ 代码侧已缩小范围** | 见 §3.12。本轮未做 UI 复验（时间预算）。**新判断**：N2 与 N4 可能是**同一根因**（单例查询态共享），而非两个独立缺陷。 |
| U15 | Home 搜索时 IME 中文布局 | **未验到** | 本轮未到 Home 搜索步骤。上轮已观测到中文候选条 + `✓` 确认键，**仍未定性**（与 `adb input text` 的注入方式相关）。 |

---

