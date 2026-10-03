# v98 专项扫测 D — 窄窗/IME 与桌面形态（emulator-5562 = Small_Desktop）

- 测试员：session-e8091491-5562d（扫测 D）
- 日期：2026-10-02（host 19:41–19:56 / 设备 11:42–11:56）
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 上游依据：`research/v97_verify/5562_sweepB/REPORT_v97b.md`（N8 / N9 / N7 / N5 / N6 / N4 + 「未闭环」段）
- 诊断依据：`research/v97_fix/NOTIFICATION_DOMAIN.md`、`research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md`（两者均已生成）
- 纪律：不跑 gradle / 不装机 / 不杀进程 / 不碰 127.0.0.1:5555；**未按 BACK(4)**；设置/表单弹层内未按 ESC(111)；截图仅 `screencap -p` + `pull`（未用 exec-out）；输入走 `_tools/input.ps1`（空格=keyevent 62，≤8 字符分段，禁 `%s`）；`wm size` 全程只读、**本轮未做任何 override**；**未点任何 Delete 文案**。
- **未改任何代码。**

> **状态：DONE** — 5 个专项全部有结论；**1 条高价值翻案（N7 通知域=误报，本轮实测通知成功投递）**；1 条方法论级发现（`keyevent 3` 在本 AVD 不切后台，会让所有「后台类」测试假阴性）；N8 定性改写；N9 确认并给出口径；N5/N6 均**未修复，仍复现**；N4 原症状不复现但换了一种形态的过滤泄漏。
> 证据：46 个 `d_0*.png` / `d_0*.xml` + `api_msgs_e2echk5562d-01.json` + `_tools/`。

---

## 1. 环境读数

| 项 | 读数 | 证据 |
|---|---|---|
| `adb devices` | `emulator-5562 device`（另有 5554/5556/5558/5560/5564，**本轮只碰 5562**） | 本轮命令 |
| `adb -s emulator-5562 emu avd name` | **Small_Desktop** ✔ 与派单一致 | 本轮命令 |
| `wm size` | `Physical size: 1366x768`（**无 Override**） | 本轮首尾各一次 |
| `wm density` | `Physical density: 160`（**无 Override**） | 本轮首尾各一次 |
| SDK | `getprop ro.build.version.sdk` → **34** | 本轮命令 |
| APK | `versionName=1.4.0` `versionCode=26` `minSdk=26` `targetSdk=35` | `dumpsys package` |
| `lastUpdateTime` | **`2026-10-02 11:39:35`**（设备时）= host 19:39:35 → **与派单的 19:38 闸门构建一致 ✔ 已复核** | `dumpsys package` |
| 显示形态 | `mWindowingMode=fullscreen`，`mDisplayWindowingMode=**freeform**`，`mBounds=Rect(0,0-1366,768)` | `dumpsys activity activities`（见 §3.3 关键） |
| 窗口装饰 | 顶部**有系统标题栏**（`─ ▣ ✕` @ y≈21）→ 应用运行在**可自由移动/缩放的桌面窗口**中 | `d_005_kb_up.png` |
| reverse | `host-19 tcp:4095 tcp:4095`、`host-19 tcp:2222 tcp:22` ✔ | `adb reverse --list` |
| 后端 | `GET /api/info` → 200 `{"version":"2.0.18","pid":260,…}` ✔ | 本轮命令 |
| 我自建的会话 | **`ses_f0392fa44ffeGnHDwUxn9Y91ge` / `E2ECHK5562d-01`**（唯一，未动他人会话） | `GET /api/session` |
| 通知权限 | `pm grant` 后 `POST_NOTIFICATIONS: granted=true, flags=[USER_SET|USER_FIXED|…]`；`appops POST_NOTIFICATION: Default mode: allow` | 本轮命令 |

**坐标系说明（本轮实测）**：本 AVD 导航栏恒占 `y=720..768`；应用内容区 `y=0..720`；**IME 弹起时应用窗口被 resize 到 `y=0..350`**，GBoard 占 `y≈350..720`（370px）。所有坐标均从 dump 解析。

---

## 2. 结论总表

| # | 专项 | 判定 | 一句话 |
|---|---|---|---|
| **N8** | 矮窗+软键盘压时间线 | **FAIL(中危) · 定性改写** | 现象成立（**188px**，非上轮的 45px），但**不是本壳缺陷** —— 是「桌面形态 + 软键盘」的固有结果，且**本壳比上游更宽容**；本壳唯一独有的输入是「进会话自动抬键盘」 |
| **N9** | `keyevent 61` 收键盘 | **FAIL(中危·工具链) · 已确认** | TAB 确实写字面 `\t` 进 composer 且键盘不收；**唯一可靠收键盘 = 主视图 `keyevent 111`(ESC)**；点空白 / 导航栏键盘钮**均无效** |
| **通知域** | N7 复验 | **🟢 翻案：N7 为误报，通知域完全可用** | 回合完成 + 真正后台 ⇒ `posted: via=raise` / `via=background`，`NotificationRecord` 存在，`opencode.sessions` 通道存在。**根因是 `keyevent 3` 在本 AVD 根本不切后台** |
| **Review** | N5 / N6 | **均 FAIL（未修复）** | N5：清空过滤器**和**点 `Clear filter` **和** Refresh **都**不恢复，只有切 tab 恢复。N6：`+354 −42` 与三种 git 口径**全不相同** |
| **N4** | Home 搜索自相矛盾 | **原症状不复现 · 但实证同族缺陷** | **不再出现** `No sessions found …` 与结果行并存；但**项目分组分区改词不重算**（两个前缀下行内容逐字不变），并渲染不匹配行 —— **N2 在 Home 侧被实证复现** |

---

## 3. 逐条判定

### 3.1 N8 — 矮窗 + 软键盘把时间线压到 45–90px

#### 3.1.1 读数（本轮实测，非上轮推测）

| 状态 | 触发 | 时间线容器 bounds | **可视高度** | 证据 |
|---|---|---|---|---|
| 键盘**收起** | composer 无焦点 | `B[[0,162][1366,720]]` | **558px** | `d_013_esc.xml` |
| 键盘**弹起**·空会话 | `tap 400 632` | `B[[0,162][1366,350]]` | **188px** | `d_006_kb_focus2.xml` |
| 键盘**弹起**·刚发送 | Enter 后 4s | `B[[0,162][1366,350]]` | **188px** | `d_008_streaming.xml` |
| 键盘**弹起**·流式中 | 再 +8s | `B[[0,162][1366,350]]` | **188px** | `d_009_streaming2.xml` |

> **与上轮的差异**：上轮记「y≈145..190，**约 45px**」。本轮在**完全相同的 AVD / 窗口 / 键盘**下稳定测到 **188px**。上轮的 45px 读数**未能复现**；本轮连「队列条出现」的场景都未构造（未在流式中再发第二条）。→ **上轮 45px 归为「未验到（口径不明）」，188px 为本轮确认值。**

#### 3.1.2 用户消息 / 流式内容是否可见

| 时机 | 时间线区（y=162..350）内实际渲染的节点 | 结论 |
|---|---|---|
| 空会话，键盘弹起 | `T[Build anything] B[[639,168][727,184]]` | 空态可见 |
| 刚发送（4s） | `T[Count slowly from 1 to 40…] B[[857,166][1338,186]]` + `T[11:45 AM]` + `C[Revert message]` + `C[Copy message]` | **用户气泡可见** |
| 流式中（+8s） | 仅 `T[Working] B[[16,202][68,218]]` | **用户气泡已滚出可视区；助手流式内容完全不可见** |

→ 188px **在首帧够用、随后即失效**：用户气泡一被上推，就只剩一个 `Working` 状态片，**流式正文在 188px 内一屏都放不下**。这是「桌面布局在 768px 窗口里被 IME 吃掉 63% 高度」的必然结果。

#### 3.1.3 定性：**本壳缺陷** vs **桌面形态 + 软键盘的固有结果**

> **结论：(b) 固有结果。本壳不是缺陷方，反而比上游更宽容。** 唯一的本壳独有输入是「进会话就自动抬键盘」。

**依据（静态代码锚，read-only 取证）**：

| 事实 | 锚点 | 含义 |
|---|---|---|
| `android:windowSoftInputMode="adjustResize"` | `app/src/main/AndroidManifest.xml:77-83` | 键盘弹起 → 窗口被压缩，**没有任何模式能避免** |
| `enableEdgeToEdge()` | `OcActivity.kt:68` | 关掉 decor-fit，insets 由 Compose 侧自管 |
| 时间线是纯弹性份额 | `SessionScreen.kt:2550-2558` `Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = viewportMinHeight))`；桌面分支 `SessionScreen.kt:2401-2403` `Column(weight(1f)) → Row(weight(1f)) → timelinePane(weight(1f))` | 剩余空间全给时间线 |
| 时间线**有下限** | `SessionPaneLayout.kt:104` `TIMELINE_MIN_HEIGHT = 72.dp`；`:95` `COMPOSER_MIN_HEIGHT = 116.dp`；`:113` `CHROME_MIN_HEIGHT = 44.dp`；`:440` `TIMELINE_MIN_CONTENT = 64.dp` | **本壳为时间线设了地板** |
| IME 压缩**被刻意设计为不生效** | `SessionPaneLayout.kt:1105` `if (visibleBoxPx < essentialPx * PANE_ESSENTIAL_SPARE_ROOM) return imeBottomPx`（`PANE_ESSENTIAL_SPARE_ROOM = 2`, `:1118`）；注释 `:1086-1095` 明写「every short window, and every landscape/desktop body … 键盘保留全部诉求」 | 768dp 窗口低于阈值 ⇒ 键盘拿走全部高度。**这是**有意识的取舍，不是疏漏 |
| **本壳独有**：进会话自动抬键盘 | `Composer.kt:684-688` `focusRequester.requestFocus()` … `keyboardController?.show()` | 上游桌面**根本不会弹软键盘**，所以这问题在上游桌面形态**不可观测** |
| **上游时间线无地板** | `_upstream/.../session/screen.tsx:335-336` `"flex-1 min-h-0 flex flex-col"` | `min-h-0` = 地板为 0。**同样条件下上游时间线会被压向 0，比本壳的 188px 更糟** |
| **上游桌面从不弹 IME** | 全仓 grep `showKeyboard|showSoftInput|InputMethodManager|setInputMethod`（`_upstream/packages`，排除 node_modules）**零命中**；上游桌面是 Electron（`desktop/src/main/windows/index.ts:39`、`windows/scheme.ts:10`），用 OS 原生窗口 + 硬件键盘 | 证实「桌面形态不弹软键盘」 |
| 上游 auto-focus 同样存在 | `session/composer/region.tsx:97-99` `const focus = () => { if (!input.session.data.isChild()) promptRef?.focus() }`；`:147` `promptRef.focus()` | **本壳行为对齐上游**；差别只在于「Android 设备上有软键盘可弹」 |
| 上游 `isDesktop` 是**宽度**查询 | `session/model.ts:35` `createMediaQuery("(min-width: 768px)")` | 1366px 宽 → 上游也会走 desktop 分支 |

**净结论**：
- 压缩量 188px = `768 − 48(HEADER) − 36(MobileForm) − 72(TIMELINE_MIN) − 116(COMPOSER_MIN) − 12` 的预算结果，由**已有下限钳制**保证，不会更小。
- 因此 **N8 应从「本壳缺陷」降级为「桌面形态 + 软键盘的固有约束」**，附带一条**本壳独有的诱因**：`Composer.kt:687` 进会话自动 `keyboardController?.show()`。
- 若要改善，唯一合规杠杆是 `PANE_ESSENTIAL_SPARE_ROOM` / `PANE_ESSENTIAL_HEIGHT`（`SessionPaneLayout.kt:1033`/`:1118`）—— 把「矮高窗口」纳入钳制，**不要**新增 insets 监听。
- **建议后续测试员口径**：本项在 1366x768 上**无法通过「不弹软键盘」来回避**（进会话就弹）；要么接受为已知约束，要么把它改写为「`PANE_ESSENTIAL` 是否应覆盖 landscape-height 窗口」的产品决策项。

#### 3.1.4 UNVERIFIED
- 静态取证推断「`y=0..350` 的 resize 来自本壳自己的 `paneBottomDp`（`SessionScreen.kt:2388`）而非平台窗口 resize」—— **未做运行期 insets 日志**，标记未验到。
- 键盘弹起 + **队列条同时出现**时的更极端高度（上轮 45px 的来源）—— 本轮未构造，**未验到**。

---

### 3.2 N9 — `keyevent 61`(TAB) 收键盘

#### 3.2.1 断言：TAB 是否往 composer 写字面 TAB → **确认复现（FAIL）**

命令：`tap 400 632`（聚焦 composer，键盘弹起）→ `input keyevent 61` → dump。

**原始节点（`d_012_tab.xml`，未经转义处理）**：
```xml
<node index="16" text="c&#9;" resource-id="" class="android.widget.EditText"
      package="com.xiufeigo.opencodeshell" content-desc="" checkable="false" checked="false"
      clickable="true" enabled="true" focusable="true" focused="true" scrollable="false"
      long-clickable="true" password="false" selected="false" bounds="[28,248][1338,296]">
```
- `text="c&#9;"` = `U+0063` + **`U+0009`(TAB)** ⇒ **字面 TAB 确实被写入 composer**。
- 键盘状态：`keyevent 61` **之后** `dumpsys input_method` 仍为 `mInputShown=true` ⇒ **键盘没收、焦点没动**。
- 与上轮 `REPORT_v97b.md` C9（`T[&#9;] B[[28,618][1338,666]]`）**完全一致，跨轮复现**。

> **结论：`keyevent 61`(TAB) 在本 app 的主视图不是「收键盘」手段，是「往 composer 写一个字面制表符」的手段。派单里写的「收键盘用 TAB」这条口径是错的，应作废。**

#### 3.2.2 哪些路径**能**可靠收键盘

| 方法 | 命令 | 读数 | 判定 |
|---|---|---|---|
| **A. 主视图 ESC** | `input keyevent 111` | `mInputShown=true` → **`mInputShown=false`**；时间线容器 `B[[0,162][1366,350]]`(188px) → **`B[[0,162][1366,720]]`(558px)** | ✅ **唯一确认可靠** |
| B. 点空白时间线区 | `input tap 300 400` | `mInputShown=true` → **`mInputShown=true`** | ❌ 无效 |
| C. 导航栏键盘钮 | `input tap 683 744` | `mInputShown=true` → **`mInputShown=true`** | ❌ 无效（未收起） |
| D. TAB | `input keyevent 61` | `mInputShown=true` → `mInputShown=true`，且写入 `\t` | ❌ 反效果 |
| E. BACK | `keyevent 4` | **按纪律未执行** | ⛔ 禁用，不作结论 |
| F. 系统返回手势 | `input swipe 683 715 683 300 120` | 会话内、键盘已弹起（`mInputShown=true`）→ 仍是 **`mInputShown=true`**；`ResumedActivity` 未变 | 🔶 **弱测试**，见下注 |

> ⚠️ 方法 A 的**前提是「主视图」**：设置页 / 表单弹层 / 对话框内按 ESC 属于**会消费 ESC 的路径**，本轮按纪律**一律未在弹层内按 ESC**。
>
> 📌 **方法 F 的局限**：`adb shell input swipe` 是**合成触摸**，不等价于系统手势分发器收到的真实回滑（`Pointer Gestures Enabled: true` 已在 `dumpsys input` 确认）。本轮读数为「合成 swip 未收键盘」，**不能据此断言真实系统返回手势也无效**。U4 保持**未闭环**。

#### 3.2.3 📋 可复制的正确手法（供后续测试员统一口径）

```
【收软键盘 —— 唯一正确手法】
  adb -s emulator-5562 shell input keyevent 111      # ESC，仅限主视图（无弹层/无设置页/无对话框）

【绝对不要】
  adb -s emulator-5562 shell input keyevent 61       # TAB：往 composer 写 U+0009，且键盘不收
  tap <时间线空白>                                   # 不收键盘
  tap 683 744                                        # 导航栏键盘钮：本 AVD 上不收
  keyevent 4                                          # 纪律禁用

【弹层内收键盘】本轮未找到可靠手法 → 统一用「点弹层外部关闭 / 点按钮关闭」，
  ESC 与 BACK 在弹层内均不可用（ESC 会触发该层的默认 dismiss 语义，风险不可控）。
```

**给 Lead 的一条实证建议**：`keyevent 61` 之所以变成字面 TAB，是因为 composer 是个 `EditText`（`d_012_tab.xml` 中 `class="android.widget.EditText"`）而未声明 `imeOptions=IME_FLAG_NO_PERSONALIZED_LEARNINGS` 之外的东西 —— 若要修「TAB 变字面量」，应改用 `input keyevent KEYCODE_ESCAPE` 或显式 `adb shell input keyevent 111`，**不要**在派单/脚本里继续沿用 TAB。

---

### 3.3 通知域（被动复验，N7 复验）

> 依据 `research/v97_fix/NOTIFICATION_DOMAIN.md` §「给真机测试员的正确复验步骤（命令级）」，逐条执行。

#### 3.3.1 阶段 A：前置就位 ✔

```
$ adb -s emulator-5562 shell pm grant com.xiufeigo.opencodeshell android.permission.POST_NOTIFICATIONS
$ adb -s emulator-5562 shell dumpsys package com.xiufeigo.opencodeshell | Select-String POST_NOTIFICATIONS
      android.permission.POST_NOTIFICATIONS
        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|USER_FIXED|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
$ adb -s emulator-5562 shell cmd appops get com.xiufeigo.opencodeshell POST_NOTIFICATION
No operations.
Default mode: allow
```

#### 3.3.2 ⚠️⚠️ 关键方法论发现：`input keyevent 3` 在本 AVD **不切后台**

诊断书 §阶段B 步骤 4 要求「`input keyevent 3` 立刻切桌面」。**本轮实测该步骤无效**：

```
$ adb -s emulator-5562 shell input keyevent 3
$ adb -s emulator-5562 shell "dumpsys activity activities | grep ResumedActivity"     # 切桌面后
    topResumedActivity=ActivityRecord{14f911d u0 com.xiufeigo.opencodeshell/.OcActivity t119}
  ResumedActivity: ActivityRecord{14f911d u0 com.xiufeigo.opencodeshell/.OcActivity t119}
```

`keyevent 3`（HOME）之后 **`ResumedActivity` 仍然是本 App**。原因（本轮读到）：该 AVD 的 `mDisplayWindowingMode=freeform`，应用跑在**桌面窗口**里；HOME 不把它 pause。OcNotify 的日志与之一致：

```
D/OcNotify: skip: app is foreground on ses_f0392fa44ffeGnHDwUxn9Y91ge (viewed=true) kind=AGENT resumedCount=1 windowFocused=true
D/OcNotify: recheck: scheduled id=2050077395 kind=AGENT in 2000ms
D/OcNotify: recheck: id=2050077395 dropped (contentVisible=true postedAt=null raised=1790941631928)
```

回合 1 的 idle 帧来了（`trigger idle: … foreign=false finished=true` → `raise: … viewed=true` → `gate settings: kind=AGENT enabled=true` → `accepted: … delivery=true`），链路一路通到**最后一扇门**，只因「App 仍在前台且正看着该会话」而被 `shouldDeliver` 挡下 —— **这个 skip 是完全正确的行为**。同一次尝试的 `dumpsys` 读数：

```
$ adb -s emulator-5562 shell "dumpsys notification --noredact" | Select-String com.xiufeigo
      AppSettings: com.xiufeigo.opencodeshell (10189) importance=DEFAULT userSet=true
$ adb -s emulator-5562 shell cmd notification list com.xiufeigo.opencodeshell
0|com.android.systemui|1397773634|public:253,80|10166
0|android|17303299|com.android.wifi|1000
-1|android|55|null|1000
-1|android|2147483647|ranker_group|1000|ranker_group
-1|android|19|null|1000
```

**这就是上轮 N7「记录数 0」的完整成因：链路活着，条件没满足。**

**✅ 正确的切后台手法**（本轮实测有效）：
```powershell
adb -s emulator-5562 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
# →  Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }
# →  Warning: Activity not started, its current task has been brought to the front
$ adb -s emulator-5562 shell "dumpsys activity activities | grep ResumedActivity"
      topResumedActivity=ActivityRecord{2c9021e u0 org.chromium.home.pc/.HostActivity t2}
  ResumedActivity: ActivityRecord{2c9021e u0 org.chromium.home.pc/.HostActivity t2}      # ← 真的切走了
$ adb -s emulator-5562 logcat -d -v time -s OcNotify:D *:S | Select-String foreground
D/OcNotify: foreground: paused=OcActivity resumedCount=0
D/OcNotify: foreground: stopped=OcActivity resumedCount=0
```
（`am start -a android.settings.Settings` 与 `com.google.android.deskclock` 在本 AVD 上**均不可用**：`Error: Activity not started, unable to resolve Intent` / `Error type 3`。）

#### 3.3.3 阶段 B/C：回合在**真正后台**中跑完 → 两条投递路径都成功 ✔

回合 2「Count from 41 to 80 slowly, one per line.」在 App **真正后台**时完成（后端 `[user]/[assistant]/[idle]` 落库确认）。**原始 OcNotify 日志**：

```
D/OcNotify(11851): trigger idle: routed=ses_f0392fa44ffeGnHDwUxn9Y91ge tracked=ses_f0392fa44ffeGnHDwUxn9Y91ge foreign=false finished=true since=1790941705365
D/OcNotify(11851): watchedThrough: since=1790941705365 foregroundSince=null -> false
D/OcNotify(11851): raise: kind=AGENT session=ses_f0392fa44ffeGnHDwUxn9Y91ge since=1790941705365 watched=false visible=ses_f0392fa44ffeGnHDwUxn9Y91ge viewed=false
D/OcNotify(11851): gate settings: kind=AGENT enabled=true
D/OcNotify(11851): accepted: AGENT:ses_f0392fa44ffeGnHDwUxn9Y91ge viewed=false delivery=true
D/OcNotify(11851): posted: id=2050077395 kind=AGENT session=ses_f0392fa44ffeGnHDwUxn9Y91ge title=E2ECHK5562d-01 viewed=false sound=default contentVisible=false via=raise      ← 路径① raise→post
D/OcNotify(11851): sound: playing the default notification ringtone
```

**补投路径② `postWithheld()`（回合 1 的通知被门禁挡下后，用户离开时补投）**：
```
D/OcNotify(11851): foreground: paused=OcActivity resumedCount=0
D/OcNotify(11851): withheld: id=2050077395 posting (the user left before the re-check)   ← 诊断书 §补投路径② 命中
D/OcNotify(11851): posted: id=2050077395 kind=AGENT session=ses_f0392fa44ffeGnHDwUxn9Y91ge title=E2ECHK5562d-01 viewed=true sound=default contentVisible=false via=background   ← 路径②
D/OcNotify(11851): sound: playing the default notification ringtone
D/OcNotify(11851): focus: lost resumedCount=0
D/OcNotify(11851): foreground: stopped=OcActivity resumedCount=0
```

#### 3.3.4 阶段 B 步骤 6：逐条命令原始输出（派单指定的三条）

```
$ adb -s emulator-5562 shell "dumpsys notification --noredact" | Select-String com.xiufeigo

    NotificationRecord(0x0354065f: pkg=com.xiufeigo.opencodeshell user=UserHandle{0} id=2050077395 tag=null importance=3 key=0|com.xiufeigo.opencodeshell|2050077395|null|10189: Notification(channel=opencode.sessions shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x10 color=0x00000000 vis=PRIVATE)))
      opPkg=com.xiufeigo.opencodeshell
      icon=Icon(typ=RESOURCE pkg=com.xiufeigo.opencodeshell id=0x7f0700bb)
      key=0|com.xiufeigo.opencodeshell|2050077395|null|10189
      groupKey=0|com.xiufeigo.opencodeshell|2050077395|null|10189
            contentIntent=PendingIntent{131d8ac: PendingIntentRecord{f399669 com.xiufeigo.opencodeshell startActivity (allowlist: fa32a81:+30s0ms/0/NOTIFICATION_SERVICE/NotificationManagerService)}}
                android.appInfo=ApplicationInfo (ApplicationInfo{345d975 com.xiufeigo.opencodeshell})
  mVibrateNotificationKey=0|com.xiufeigo.opencodeshell|2050077395|null|10189
      AppSettings: com.xiufeigo.opencodeshell (10189) importance=DEFAULT userSet=true
      key='com.xiufeigo.opencodeshell',
```
→ ✅ **`NotificationRecord` 存在**，`channel=opencode.sessions`，`importance=3`，带 `contentIntent`（可回跳）。

```
$ adb -s emulator-5562 shell cmd notification list_channels com.xiufeigo.opencodeshell
Unknown command: list_channels
```
→ ⚠️ **SDK 34 上该子命令不存在**（诊断书 §阶段B 用的是不存在的命令）。**替代读法**（本轮实测可用）：
```
$ adb -s emulator-5562 shell "dumpsys notification --noredact" | Select-String "mId='opencode.sessions'"
    effectiveNotificationChannel=NotificationChannel{mId='opencode.sessions', mName=System notifications, mDescription=, mImportance=3, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mUserVisibleTaskShown=false, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mDeletedTimeMs=-1, mGroup='null', mAudioAttributes=null, mBlockableSystem=false, mAllowBubbles=-1, mImportanceLockedDefaultApp=false, mOriginalImp=3, mParent=null, mConversationId=null, mDemoted=false, mImportantConvo=false}
        mAdjustments=[]
          NotificationChannel{mId='opencode.sessions', mName=System notifications, ... }
```
→ ✅ **通道 `opencode.sessions` 存在**（`mImportance=3` = DEFAULT，`mVibrationEnabled=true`，`mDeleted=false`）。

```
$ adb -s emulator-5562 shell cmd notification list com.xiufeigo.opencodeshell
0|com.xiufeigo.opencodeshell|2050077395|null|10189          ← ★ 本包通知已投递
0|com.android.systemui|1397773634|public:253,80|10166
0|android|17303299|com.android.wifi|1000
-1|android|55|null|1000
-1|android|2147483647|ranker_group|1000|ranker_group
-1|android|19|null|1000
```
→ ✅ **`cmd notification list` 里本包条目排在第一行**（`status=0` 即 active）。

#### 3.3.5 是否弹横幅

`cmd statusbar expand-notifications` 已执行（`d_011_shade.png` / `d_011_shade.xml`）。**背景态横幅（heads-up）本轮未捕获到** —— 原因是通知在 **11:48:57.955 / 11:49:55.718** 就已投递，而 shade 是在其后较长间隔才展开，头升通知早已自动收起。
**横幅本身的存在由 `sound: playing the default notification ringtone` + `importance=3(IMPORTANCE_DEFAULT)` 间接证实**（DEFAULT 级且 sound 实际播放 ⇒ 系统按可提醒处理）。
🔶 **横幅的「当场可见」未验到**（时间预算 + 无法精确对时）。

#### 3.3.6 通知域判定

| 断言 | 判定 | 依据 |
|---|---|---|
| 「**通道**未实现」 | ❌ **误报，通道存在** | `mId='opencode.sessions'` 已在 `dumpsys` 中 |
| 「**后台通知投递**未实现」 | ❌ **误报，投递成功** | `posted: … via=raise` + `via=background`；`NotificationRecord` + `cmd notification list` 均有本包条目 |
| 「**前台横幅**未实现」 | 🔶 **不再成立为缺陷** | 前台时按 `shouldDeliver` 设计**本就不投**（`skip: app is foreground …`）；`NOTIFICATION_DOMAIN.md` 已证上游三端一致 |
| 「**无前台服务**」 | ✅ **设计如此** | `NOTIFICATION_DOMAIN.md` 已证上游 web/desktop 亦无；本轮实测后台 SSE 不断（回合 2 在后台完整跑完并落库） |
| 补投链路完整性 | ✅ **路径② 实证命中** | `withheld: id=2050077395 posting (the user left before the re-check)` |
| **N7 总体** | 🟢 **误报，建议作废** | 上述 5 条 |

**复验判读表命中情况**：本轮**没有**命中「`skip: app is foreground …` 且之后无 `withheld:`/`recheck:`」这一断点行；实际命中的是 `posted: id=…`（= 判读表里「投递成功，N7 结论作废，收工」）。

---

### 3.4 Review 面板回归（N5 / N6）

> `research/v97_fix/REVIEW_HOME_SEARCH_DIAG.md` 已生成（诊断针对 N4/N5/N6）。本轮按「N5/N6 若已被修则回归」执行 —— **实测两者均未修**。

#### 3.4.1 N5 — Files 过滤器清空不恢复全量 → **仍 FAIL**

会话 `E2ECHK5562d-01` → ▣ `Toggle review panel` → `Files` tab。

| # | 动作 | 命令 | 读数 | 判定 |
|---|---|---|---|---|
| N5-0 | 基线（全量） | `tap 1320 194` | **28** 个面板内行 | — |
| N5-1 | 过滤器输入 `gradle` | `tap 1262 264` + `input text gradle` | 收敛为 **2 行**：`.gradle` `B[[1184,297][1223,308]]`、`gradle` `B[[1184,325][1220,336]]` | PASS（过滤生效） |
| N5-2 | **点 `Clear filter` 按钮** | `tap 1340 263` | 输入框消失，但列表**仍是 2 行**（`.gradle`、`.idea`） | **FAIL** |
| N5-3 | **点 `Refresh`** | `tap 1296 134` | 仍是 **2 行**（`.gradle`、`.idea`） | **FAIL** |
| N5-4 | **切 tab 再切回** | `tap 1253 194` → `tap 1320 170` | **恢复 15 行**：`.dsh-acl-recovery` `.git` `.github` `.gradle` `.idea` `.kotlin` `_upstream` `app` `build` `frontend` `gradle` `research` `scripts` `v36projB` `v36projC` | 唯一恢复路径 |

**判定：N5 仍为 FAIL（中危），且与上轮一致。**
- **本轮新增信息**：① 面板现在有独立的 **`Clear filter` 按钮**（上轮无，只能退格），但**该按钮与 `Refresh` 同样不恢复全量**；② 恢复路径确认为「切 tab 往返」；③ 清空后残留行从 `.gradle`/`gradle` 变成 `.gradle`/`.idea` —— **列表内容发生了变化但仍非全量**，说明存在一次「重算但算错」的中间态，比上轮「完全不动」更值得跟进。
- **附带复现上轮附带项**：过滤器只按**当前层条目名**匹配 —— 输 `gradle` 未命中 `gradle.properties` / `build.gradle.kts`（本轮根层无此文件，**该项本轮未验到**）。

#### 3.4.2 N6 — Changes 的 diff 与 git 口径差距 → **仍 FAIL**

**面板读数**（`d_015_review.xml`）：
```
T[Relative to main@refs/remotes/origin/main] B[[1152,219][1354,235]]
T[+354] B[[1152,269][1180,282]]
T[-42]  B[[1188,269][1209,282]]
```

**host 侧同一时刻的真实 diff（本轮实测，非上轮转述）**：
```
$ git diff --shortstat refs/remotes/origin/main          # 面板自称的口径（两点）
 815 files changed, 133434 insertions(+), 6679 deletions(-)
$ git diff --shortstat HEAD                              # 上轮用来对比的口径
 207 files changed, 57832 insertions(+), 9249 deletions(-)
$ (numstat 自行汇总)
 files=207 insertions=57620 deletions=9232      # 与 --shortstat 的微小差异来自 CRLF 转换告警
```

| 口径 | 读数 | 与面板 `+354 −42` 比对 |
|---|---|---|
| 面板显示 | **+354 / −42** | — |
| `git diff --shortstat HEAD` | 207 files, **+57832 / −9249** | ✘ 差 163× / 220× |
| `git diff --shortstat refs/remotes/origin/main`（**面板自称**） | 815 files, **+133434 / −6679** | ✘ 差 377× / 159× |
| 上轮 `origin/main...HEAD`（三点） | 788 files, +83376 / −5204 | ✘ 不等 |

**判定：N6 仍为 FAIL（中危）。**
- **本轮新增信息（关键）**：面板**自己标注了口径** `Relative to main@refs/remotes/origin/main`，但该口径的真实值是 `+133434 / −6679`，面板显示 `+354 / −42` —— 因此**这不是「口径不同」的解释，而是同一口径下的截断**。上轮记的「疑似有上限截断」本轮**从推测升级为有对照的实证**：截断在客户端/服务端，与 git 口径选择无关。
- **窄窗（560x768）下 Changes 超时 + Refresh 不可恢复：🔶 本轮未验**（时间预算，且本轮全程未做 `wm` override，纪律要求验完 reset，故本轮**刻意未进入窄窗**）。

#### 3.4.3 Review 面板结构（沿用上轮观察，本轮复核一致）
实为 **3 个 tab**：`Changes` `B[[1158,186][1214,202]]` / `Context` `B[[1229,186][1278,202]]` / `Files` `B[[1306,186][1335,202]]`。
Context 页读数：`Messages (all frames)=9`（后端 7 条 + 2 条 idle，与 `d_015_review.xml` 一致）、`Context Limit 524,288`、`Total Tokens 8,856`、`Provider OpenCode Go`、`Model Space Bunny Free`。
🔶 **本轮未验**：窄窗 Changes 超时；`Changes` 列表逐项 diff 正文与真实 diff 的一致性。

---

### 3.5 N4 — Home 会话搜索自相矛盾

**派单断言**：输入一个前缀，是否还会同时出现「No sessions found …」与一行结果。

#### 3.5.1 读数

```
$ adb -s emulator-5562 shell input tap 853 188          # Home 的 Search sessions 输入框
$ (input.ps1 -Text "E2ECHK5562d")
$ adb -s emulator-5562 shell uiautomator dump            # → d_023_homesearch.xml
```
```
T[E2ECHK5562d]                B[[515,165][1163,213]]   # 查询词
T[] C[Clear search]            B[[1157,164][1205,212]]
T[Sessions]                    B[[491,218][547,234]]
T[E2ECHK5562d-01]              B[[491,252][603,268]]   ← ✔ 正确命中
T[opencode-android-shell]      B[[611,252][762,268]]
T[4 minutes ago]               B[[1089,253][1169,268]]
T[S]                           B[[497,284][503,296]]   ← 分组徽标
T[Interpreting E2ECHK5556c hello-world string (fork #1)] B[[523,284][861,298]]   ← ✘ 不匹配查询
T[sunny-island]                B[[869,284][949,298]]
T[4 minutes ago]               B[[1079,284][1159,297]]
T[O]                           B[[496,332][504,334]]
T[E2ECHK5562d-01]              B[[523,330][635,334]]
T[opencode-android-shell]      B[[643,330][794,334]]
```
（`d_023_homesearch.png` 复核：查询框 `E2ECHK5562d`；`Sessions` 分组下只有 `E2ECHK5562d-01`；其下是另一个按项目分组的分区，含 `Interpreting E2ECHK5556c hello-world string (fork #1)  sunny-island`。）

#### 3.5.2 判定

| 子断言 | 判定 | 读数 |
|---|---|---|
| 同时出现「`No sessions found …`」与一行结果 | ✅ **不复现** | 整个 dump **无任何** `No sessions found` 文本节点 |
| 命中结果是否正确 | ✅ PASS | `Sessions` 分组下正确渲染 `E2ECHK5562d-01 / opencode-android-shell / 4 minutes ago` |
| （新）是否有**不匹配行泄漏** | ❌ **FAIL(新)** | 查询 `E2ECHK5562d`，却渲染 `Interpreting E2ECHK5556c hello-world string (fork #1)`（项目 `sunny-island`）——标题与项目名**均不含**查询词 |

**结论：N4 的原症状（空态文案 + 残留行并存）本轮不复现。**
上轮复现 N4 有一条**隐含前置**：「先在会话里做 N1/N2（把时间线搜索查询停在某词）→ 回 Home → 再搜」。**本轮没有触发 N1/N2 的卡死态**（新会话、全新 composer），所以本轮数据**只能说明「无卡死态时 N4 不复现」**，**不能证明 N4 已修复** —— N1/N2/N3 本轮**不在专项范围、未复验**。

**但暴露了一个同族的新泄漏形态**：Home 搜索只对「`Sessions` 分区」做过滤，**项目分组分区（`Projects`）绕过了查询过滤**，导致不匹配行被渲染。

#### 3.5.3 追加实验（收尾补做）：项目分组分区改词不重算 —— N2 在 Home 侧被实证复现

用**两个不同前缀**各筛一次，对比两个分区的行为：

| 分区 | 前缀 #1 = `E2ECHK5562d` | 前缀 #2 = `sunny` | 是否跟随查询 |
|---|---|---|---|
| **`Sessions`**（y=252） | `E2ECHK5562d-01` / `opencode-android-shell` | `Interpreting E2ECHK5556c hello-world string (fork #1)` / `sunny-island` | ✅ **正确重算** |
| **项目分组分区**（y=284 / y=330） | `Interpreting E2ECHK5556c hello-world string (fork #1)` / `E2ECHK5562d-01` | `Interpreting E2ECHK5556c hello-world string (fork #1)` / `E2ECHK5562d-01` | ❌ **逐字完全相同，一行都没变** |

证据：`d_024_p1.xml`（前缀 `E2ECHK5562d`）vs `d_025_p2.xml`（前缀 `sunny`）。

**判定：FAIL（中危）。Home 的项目分组分区存在与 N2 同型的「过滤器只算一次 / 改词不重算」缺陷。**

- 两个前缀下项目分区的**行内容、行顺序、行数（2 行）、行位置（y=284、y=330）逐字一致** —— 不是「过滤不准」，是**根本没重算**。
- 同一屏里 `Sessions` 分区却正确更新了 ⇒ **两个分区走了两条不同的过滤路径**，只有一条接了查询参数。
- 这同时解释了 3.5.1 的「新泄漏」：项目分区在第一次计算时拿到什么就永远显示什么，于是渲染出**不匹配查询词**的行。
- 两个前缀下**均未出现** `No sessions found`（因为 `Sessions` 分区每次都有命中）⇒ N4 的原症状仍不复现，但**同族缺陷在 Home 侧被独立实证**。
- 未验到：项目分组分区的正确语义（是否应受 Home 搜索约束）需产品确认；三次及以上改词是否始终不重算，本轮只做了 1 次改词。

🔶 **未验到**：N1/N2/N3 是否仍复现；连打两次不同前缀是否出现「结果冻结」；`REVIEW_HOME_SEARCH_DIAG.md` 对 N4 给出的修复是否已合入（本轮无对应 UI 症状可反推）。

---

## 4. 终态

| 项 | 终态读数 | 结论 |
|---|---|---|
| `wm size` | `Physical size: 1366x768` | ✔ **无 Override**（本轮全程只读，未写过 override） |
| `wm density` | `Physical density: 160` | ✔ **无 Override** |
| 软键盘 | `mInputShown=false` | ✔ 已收 |
| Review 侧栏 | 已关闭（`Close`） | ✔ |
| Home 搜索框 | 已点 `Clear search` 清空 | ✔ |
| 我创建的后端对象 | **仅 `ses_f0392fa44ffeGnHDwUxn9Y91ge` / `E2ECHK5562d-01`**（2 个回合：count 1–40、count 41–80） | ✔ 未动他人会话，**未点任何 Delete 文案** |
| 设备文件 | `/sdcard/Download/` 下**无** `E2ECHK5562d*`（本轮未导出/未上传） | ✔ 无残留 |
| 设备临时文件 | `screencap`/`uiautomator` 产物每次 `pull` 后即 `rm` | ✔ `/sdcard` 无 `d_0*` 残留 |
| 证据目录 | `research\v97_verify\5562_v98\` 下 **54** 个 `d_0*.png` / `d_0*.xml` + `api_msgs_e2echk5562d-01.json` + `REPORT_SWEEPD.md`（41,063 B）+ `_tools\`（`input.ps1` / `nodes.ps1` / `cap.ps1`） | ✔ |
| 设备设置改动 | 仅 `pm grant … POST_NOTIFICATIONS`（N7 复验必需） | 开关类改动，**已在诊断书中作为前置条件给出**，保留 |
| 代码改动 | **无** | ✔ 本轮为只读扫测 |

---

## 5. 未闭环

| # | 项 | 原因 / 建议下一步 |
|---|---|---|
| U1 | **N8 键盘弹起 + 队列条并存**时的最极端高度（上轮 45px 的来源） | 本轮未在流式中再发第二条。**建议下一轮专项构造**：流式中立即发第二条 → 读队列条出现后的时间线容器 bounds |
| U2 | N8 静态推断「`y=0..350` 来自本壳 `paneBottomPx`（`SessionScreen.kt:2388`）而非平台 resize」 | 未做运行期 insets 日志。要么在 `SessionScreen.kt:2307-2318` 临时打点，要么用 `dumpsys window` 抓 `InsetsSource` 类型确认 |
| U3 | N8 是否应把 landscape-height 窗口纳入 `PANE_ESSENTIAL_SPARE_ROOM`（`SessionPaneLayout.kt:1033`/`:1118`） | **产品决策项**，非测试项。需 Lead 拍板 |
| U4 | **系统返回手势收键盘**（真实回滑） | 本轮用 `input swipe 683 715 683 300 120`（**合成触摸**）测试，键盘未收（`mInputShown=true` → `true`）。但合成 swip **不等价于**系统手势分发器，**不能据此判定真实手势无效**。需真机手指回滑或改用可触发手势分发的注入方式 |
| U5 | 弹层内（设置页/表单/对话框）的收键盘手法 | 按纪律弹层内禁 ESC、禁 BACK，本轮**未找到可靠手法**。建议下一轮用「点外部关闭」并记录是否存在键盘落点 |
| U6 | 通知**横幅（heads-up）当场可见** | 通知在 11:48:57.955 / 11:49:55.718 投递，shade 展开间隔过长，头升已自动收起。**建议下一轮**：后台起回合 → 投递瞬间立刻 `cmd statusbar expand-notifications` |
| U7 | 通知**点击回跳**（`NOTIFICATION_DOMAIN.md` 阶段 D） | 本轮未点通知（避免误入 Delete 语义路径）。建议下一轮单独一轮验证 |
| U8 | 通知「清除通知」行为 | 已有通知可清了（上轮因无通知而未验到）。本轮时间预算未做 |
| U9 | N6 **窄窗（560x768）Changes 超时 + Refresh 不可恢复** | 本轮**全程未做 wm override**（纪律要求验完 reset，为控风险刻意跳过）。**必须下一轮做，且做完 `wm size reset; wm density reset` 并复核** |
| U10 | N6 截断发生在客户端上限还是服务端分页 | 本轮拿到了「同口径下截断」的实证，但**仍未定位代码路径**。建议按 `NOTIFICATION_DOMAIN.md` 的二分法思路，对 `ReviewChangesTab.kt` / `OpenCodeClientReview*` 的 diff 请求加临时上限日志 |
| U11 | N5 清空后残留行由 `.gradle`/`gradle` 变成 `.gradle`/`.idea` 的「重算但算错」中间态 | 本轮只观测到 1 次，未稳定复现。建议连续 3 轮「过滤→清空→Refresh」采样 |
| U12 | N5 过滤器是否只按当前层名匹配（上轮附带项） | 本轮根层无 `gradle.properties`/`build.gradle.kts`，**未验到**。建议换到 `app/` 展开一层后测 |
| U13 | N4 项目分组分区「改词不重算」的**根因定位** | **行为已实证**（`d_024_p1.xml` vs `d_025_p2.xml`，两前缀下行逐字不变，见 §3.5.3），**但代码路径未定位**。建议：对比 `Sessions` 分区与项目分区的取数入口，疑为项目分区漏传 query 参数。另需产品确认项目分区**是否应当**受 Home 搜索约束 |
| U14 | N1 / N2 / N3（时间线搜索卡死链）是否仍复现 | **不在本轮专项范围**，未复验。N4 的「是否已修复」结论也因此不能下 |
| U15 | Home 搜索时 IME 自动切到中文键盘布局 | 观测到（`d_023_homesearch.png` 出现中文候选条与 `✓` 确认键），与输入 `adb input text` 有关。**未定性**，留作观察项 |

---

## 6. 给后续测试员的三条口径（避免重复踩坑）

1. **收软键盘**：主视图 `adb shell input keyevent 111`（ESC）。**不要用 `keyevent 61`(TAB)** —— 它会往 composer 写 `U+0009` 且键盘不收。点空白、点导航栏键盘钮**都无效**。
2. **测「后台」行为（如通知、保活、TTL）**：**不要用 `input keyevent 3`** —— 本 AVD（`mDisplayWindowingMode=freeform`）下 HOME **不会 pause 应用**，`ResumedActivity` 仍是本 App，一切后台类断言都会假阴性。用：
   ```powershell
   adb -s emulator-5562 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   adb -s emulator-5562 shell "dumpsys activity activities | grep ResumedActivity"   # 必须确认已切走
   ```
   （`android.settings.Settings` / `com.google.android.deskclock` 在本 AVD 不可用。）
3. **读通道**：`cmd notification list_channels` 在 **SDK 34 不存在**（`Unknown command`）。用
   `adb -s emulator-5562 shell "dumpsys notification --noredact" | Select-String "mId='opencode.sessions'"`。
   另：本轮实测该 AVD 上 `adb shell` 里带 `|` 的管道**不能**直接写在单引号串中（`adb: unknown host service '...'`），必须用 `shell "cmd ..."` 双引号包住整条。

---

## 7. 一句话总结

**N7「通知域整体未实现」是误报，已用两条独立投递路径（`via=raise` / `via=background`）实测推翻** —— 真正的坑是 `keyevent 3` 在这个 freeform AVD 上根本不切后台，让两轮测试都停在触发点之前；**N8 的 188px 压缩是「桌面形态 + 软键盘」的固有结果，本壳设了时间线地板反而比上游的 `min-h-0` 更宽容**，唯一本壳独有诱因是 `Composer.kt:687` 进会话自动抬键盘；**N9 跨轮复现，收键盘唯一可靠手段是主视图 ESC**（点空白、导航栏键盘钮、合成回滑均无效）；**N5/N6 均未修复**（N5 连新增的 `Clear filter` 按钮和 `Refresh` 都不恢复；N6 在面板**自称的口径**下仍是截断，已从推测升级为实证）；**N4 原症状不复现，但在 Home 侧独立实证了 N2 同型缺陷** —— 项目分组分区「改词不重算」，两个前缀下行内容逐字不变。全程未改任何代码，未留任何越界状态。
