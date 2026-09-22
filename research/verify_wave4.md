# W4-E 独立验证报告（Route C / Wave 4）

- 角色：独立验证（**不实现、不修产品代码**）。唯一写作用域：本文件 + `research/verify_wave4_shots/`（仅截图证据，非代码）。
- 验证对象：W4-A/B/C/D 四张卡的实现与验收声明；`task-5`（被 task-1..4 阻塞）。
- 当前状态：**2026-09-20 21:2x 基线阶段（task-1..4 仍在 in_progress，尚未 claim task-5）**；已收到 Lead 对 R1 的裁决并写入 §4.4.1 / §5（门控=上游 768dp；移动分支用 5556 临时缩宽验证后必须复原）。
- 方法：只读复核（`read`/`grep`/`git show`/`adb`/`curl`）。发现的任何问题只报告，不修改。

---

## 1. 环境快照（基线时刻）

| 项 | 值 |
|---|---|
| 仓库 HEAD | `51f8e70`（2026-09-20 14:19:07 +0800）— 注意：工作区有大量**未提交**改动（M1–M6 产物），HEAD ≠ 工作区 |
| 工作区 | 大量 `M`/`??`（含四张卡正在进行的改动）；审计的行号是相对**工作区**而非 HEAD |
| 我核实时的工作区基准 | 对未被子卡改动的文件，用文件 mtime 判定「未被 Wave 4 触碰」（见 §3 备注） |
| JAVA_HOME | `C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1` |
| server | `http://127.0.0.1:4096`（opencode，v1 无前缀面），无鉴权 |
| emulator-5554 | Pixel_Tablet，`cur=2560x1600 @320dpi` → **1280 × 800 dp**（宽屏/桌面档） |
| emulator-5556 | AVD `Pixel_10_Pro_Fold`，`sdk_gphone16k`；**display 0（内屏，state ON）**：`cur=2076x2152 @390dpi` → **851.7 × 882.9 dp**（竖屏，宽度 **851 dp**）；**display 3（外屏，physicalPort 1，state OFF）**：1080x2364 @390dpi → **443.1 × 969.8 dp**（Lead 记作 "display 1"，实测 displayId = **3**） |
| 两台设备已装 | `com.xiufeigo.opencodeshell` versionName=1.4.0（lastUpdate 2026-09-20 12:39），当前前台均为 `OcActivity` |

**服务端实测（基线）**

- `GET /global/event` 首帧：`data: {"payload":{"id":"evt_…","type":"server.connected","properties":{}}}` → 无 `sync` 包装、无 `directory`；与 `audit_1to1_verdict.md` §1.3、§5.1 一致（v1 接入成立）。
- `GET /provider`：顶层键 `all,default,connected`；`connected` = **10 个 provider id 字符串**（`zai-coding-plan, minimax-cn-coding-plan, stepfun, xiaomi, …`）；`all` = 225 个 provider 对象（`id,name,source,env,options,models`）；全部模型 **7737** 个，其中带 `limit.context` 的 **7609** 个（例：`zai-coding-plan/glm-5.3-flash` → `limit={"context":1000000,"output":131072}`）。
  → 「设置 Providers/Models 应显示 10 个已连接 provider」「用量环应有真实分母」两条验收**在本机可实测**（数据源真实存在）。
- `GET /config`：顶层键 `$schema,shell,command,plugin,username,mode,agent,provider,compaction` —— **没有 `model`**（`config.model` = null）。凡依赖 `GET /config .model` 的默认模型回退，在本 server 上必然拿到 null，需在第二步单独核实其回退链（见 §5 风险 R2）。

---

## 2. 基线测试数字（作为第二步对比基准）

最近一次 `:app:testDebugUnitTest`（2026-09-20 21:11:02，Wave 4 卡开始前）：

```
files(suites)=63  tests=537  failures=0  errors=0  skipped=0
```

解析口径（**重要**）：`app/build/test-results/testDebugUnitTest/*.xml` 的**根元素名是测试类名**（不是 `<testsuites>` 也不是 `<testsuite>`），所以不能用 `$d.testsuites.tests` 取数；必须读 `$d.DocumentElement.GetAttribute(...)`，并按「1 个 XML = 1 个 suite」计数。

```powershell
$xmls = Get-ChildItem "app/build/test-results/testDebugUnitTest" -Filter *.xml
$t=0;$f=0;$e=0;$n=0
foreach ($x in $xmls) {
  [xml]$d = Get-Content $x.FullName; $r = $d.DocumentElement
  $t += [int]$r.GetAttribute("tests"); $f += [int]$r.GetAttribute("failures"); $e += [int]$r.GetAttribute("errors"); $n++
}
"suites=$n tests=$t failures=$f errors=$e"
```

判据：`clean :app:testDebugUnitTest :app:assembleDebug` 退出码 0；`failures=0 errors=0`；`tests ≥ 537`、`suites ≥ 63`（四张卡都要求新增测试）。**tests 少于 537 或 suites 少于 63 即视为「有测试被删/没跑到」**，需查 `--tests` 过滤或构建配置是否被改。

---

## 3. 规格对照抽查：`audit_session_ui.md` Top 12 中抽 6 条（审计说法 vs 我的核实）

抽查原则：**不改产品代码**，只打开 `_upstream` 原始 CSS/组件与 Android 源码逐行对照；被 Wave 4 触碰过的文件（mtime 晚于 2026-09-20 21:00）不作为「审计时状态」的证据，本次抽到的 Android 文件 mtime 均 ≤ 19:55（未被四张卡改动），故可作为审计快照。

| # | Top 12 条目 | 审计说法（原文要点） | 我的核实：上游侧 | 我的核实：Android 侧 | 结论 |
|---|---|---|---|---|---|
| 1 | 工具卡「有面卡片」 | 上游 `collapsible.css:1-14` + `basic-tool.css:314-318`：透明无边框，内容左缩进 12px 导轨；Android `PartRenderer.kt:198-203` 每卡 `layer01` + 0.5dp 描边 + radius 6 | `_upstream/packages/ui/src/components/collapsible.css:5-8`：`background-color: transparent; border: none; … border-radius: var(--radius-md)`；`_upstream/packages/session-ui/src/components/basic-tool.css:314-318`：`.tool-collapsible:not([data-rail="false"]) > [data-slot="collapsible-content"] { padding-inline-start: 12px; }` ✅ 与审计逐字一致 | `PartRenderer.kt:198-203`（工作区，mtime 09-20 19:13:37）：`Column(modifier.fillMaxWidth().clip(shapes.md).background(c.layer01).border(0.5.dp, c.borderBase, shapes.md))` ✅ 与审计描述逐字一致 | **审计成立** |
| 2 | `⋮` 图标方向 | 上游 `icon.tsx:139-142` 三点 `x=2.5/7.5/12.5, y=7.5–8.5`（横向 ⋯）；Android `ic_oc_dots.xml:7-11` `M12,5.5 / M12,12 / M12,18.5`（纵向 ⋮） | `_upstream/packages/ui/src/icons/icon/icon.tsx:139-141`：`outline-dots`，viewBox `0 0 16 16`，三条 path 分别为 `M2.5 7.5H3.5V8.5H2.5V7.5Z`、`M7.5…`、`M12.5…` ✅ 横向 | `app/src/main/res/drawable/ic_oc_dots.xml:7-11`：`android:pathData="M12,5.5v0.01M12,12v0.01M12,18.5v0.01"`（mtime 09-19 22:04，未改）✅ 纵向 | **审计成立** |
| 3 | 上下文用量环是死的、永远空 | 上游 `session-context-usage.tsx:68-82` 用 `model.limit.context` 算百分比、`:93-108` 点击开 context 面板、`:131-140` tooltip Cost/%/Tokens；Android `SessionScreen.kt:272` `limit = null`，`SessionHeader.kt:156-164` Box 无 `clickable`/tooltip | `_upstream/packages/app/src/session/timeline/session-context-usage.tsx:68-81`：`usage: model?.limit.context ? Math.round((total / model.limit.context) * 100) : null`；`:93-108` `openContext()`（close/open 语义）；`:131-139` tooltip 三行 cost/usage%/tokens ✅ 全部成立 | `SessionScreen.kt:272`（mtime 09-20 19:54）：`contextProgress = ContextUsage.progressOf(state.messages, limit = null)` ✅；`SessionHeader.kt:156-164`（mtime 09-20 12:21，HEAD==工作区）：`Box(Modifier.size(ICON_BUTTON_SIZE).semantics { contentDescription = … })` 内 `ContextRing`，**无 `clickable`** ✅ | **审计成立**；附带证实审计 §13 的另一条：上游环本体用的是 `appearance="indicator" size={16} strokeWidth={2}`（同文件 `:110-124`），Android `ContextRing` 是 14dp/1.5dp（compact 规格）——**修复时若只接分母而未改几何，仍与上游差一档** |
| 5 | 分组行文案完全不同 | 上游 `Used {count} {names}`（`tool-renderer.tsx:535-558`）；Android「Exploring/Explored + 3 reads, 2 searches, 1 list」（`ContextToolGroupCard.kt:101-105,182-221`） | `_upstream/packages/session-ui/src/tools/tool-renderer.tsx:535-558`：`label()` 组装 `i18n.plural("ui.messagePart.tools.used", count, { tools: title })`（`:543`），`title = names()`（`:533` 以 `", "` join 工具名）；字符串本体 `_upstream/packages/ui/src/i18n/en.ts:127-128`：`"ui.messagePart.tools.used.one/other": "Used {{count}} {{tools}}"` ✅ | `ContextToolGroupCard.kt:101-105`：`ToolStatusTitleSwap(busy)` + `ContextSummary(summary)`；`ContextSummary` `:182-219` 把 read/search/list 三个桶拼成 `N reads, N searches, N lists` ✅ 与审计一致 | **审计成立**；**引用瑕疵（Lead 已要求登记）**：审计把 key 位置写成 `en.ts:93-94`，实际为 `en.ts:89-90`（`:93-94` 是 `makingEdits`/`runningCommands`） |
| 7 | 移动端会话抽屉应是底部 sheet | 上游 `mobile-drawer.css:1-11,13-31,46-60`（底部 sheet：16px 上圆角 + 28px 拖拽柄 + scrim）；Android `SessionScreen.kt:194-212` 左侧 Material 抽屉 | `_upstream/packages/app/src/shell/mobile-drawer.css:1-11`（fixed inset:0 scrim + 240/200ms）；`:13-31`（`position: fixed; inset-inline: 0; bottom: 0; max-height: min(75dvh, …); border-radius: 16px 16px 0 0; background: var(--v2-background-bg-deep); box-shadow: var(--v2-elevation-overlay)`）；`:46-60`（handle 高 28px，条 32×4 radius 999）✅ 全部成立 | `SessionScreen.kt:201-213`：`ModalNavigationDrawer` + `ModalDrawerSheet(width = drawerWidth, drawerShape = RectangleShape, drawerContainerColor = c.bgBase)`；宽度常量 `:194-196,448-452`（78%、上限 320dp）。**左侧抽屉 + 方形端边 + bgBase** ✅ 与审计一致 | **审计成立**；**引用瑕疵（Lead 已要求登记）**：审计引 `SessionScreen.kt:194-212`，实际抽屉挂载点 `:201-213`（`:194-196` 是宽度计算），结论不变 |
| 8 | Review 手机端是四个整宽 tab，不是 340dp 侧栏 | 上游 `screen.tsx:191-242,303-317`；`session-review-v2.tsx:17-19,135-146`（240/200/480 可拖）；Android `SessionScreen.kt:443` 硬编码 340dp | `_upstream/packages/app/src/session/review/view.tsx:23-117`：`SessionMobileViewTabs`，`For each={["session","changes","files","terminal"]}`，`class="min-w-0 flex-1"` + `button: "w-full justify-center"` → **四个等宽 tab** ✅；`screen.tsx:303-317` 四个 `Match`（terminal/usage/files/changes）✅；宽度常量 `session-review-v2.tsx:17-19` = 240/200/480 ✅；拖拽+双击复位 `:135-146` ✅ | `SessionScreen.kt:442-443`：`/** Review panel width, upstream SESSION_REVIEW_V2_SIDEBAR_WIDTH range (~320–360 px). */ private val REVIEW_PANEL_WIDTH = 340.dp` ✅（注释里的 320–360 与上游 240/200/480 不符，审计已指出） | **审计成立** |

**抽查小结**：6/6 条的审计说法与上游源码/Android 源码**逐条成立**，未发现编造引用。可继续信任该审计作为第二步的对照规格；但注意下列已发现的引用瑕疵。

### 3.1 抽查中发现的审计引用瑕疵（不影响结论，但影响复现）

1. `audit_session_ui.md` §10 表第 5 行与 §13 附录写 `Exploring/Explored` 的 i18n key 在 `U/ui/src/i18n/en.ts:93-94`；实际在 **`:89-90`**（`:93-94` 是 `makingEdits`/`runningCommands`）。行号漂移 4 行。
2. `audit_session_ui.md` 用 `A/ui/session/SessionScreen.kt:194-212` 指「左侧 ModalNavigationDrawer」；实际抽屉挂在 `:201-213`，`:194-196` 是宽度计算。**父级 `ModalNavigationDrawer(` 在 201 行**，结论不变。
3. 审计把工作区行号当作稳定引用，但仓库 HEAD（`51f8e70`）**不含** M1–M6 未提交改动——用 `git show HEAD:…` 复现审计会得到完全不同的行（例：`HEAD` 的 `PartRenderer.kt:198` 是 markdown 的 `lineSpacingMultiplier`）。复现审计必须用**工作区**，不能用 HEAD。
4. 抽查确认「死 key」判定成立：`gatheringContext`/`gatheredContext` 在整个 `_upstream/packages` 里的命中**只有各语言 i18n 定义文件**（en/de/zh/…），无任何 `.tsx` 消费者。

---

## 4. 第二步将使用的命令与判据（预先登记）

### 4.1 构建 + 全量测试（唯一权威命令）

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
cd C:\sensorsdata\main\program\opencode-android-shell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug 2>&1 |
  Tee-Object "$env:TEMP\verify_w4e_build.log"
"exit=$LASTEXITCODE"
```

判据：`exit=0`；日志中无 `FAILED`/`error:`；`app/build/outputs/apk/debug/app-debug.apk` 存在且 mtime 为本次构建。
（日志写 `$env:TEMP`，避免越界写产品目录。）

### 4.2 测试聚合

用 §2 的 PowerShell 片段输出 `suites/tests/failures/errors`，并额外列出失败明细：

```powershell
Get-ChildItem "app/build/test-results/testDebugUnitTest" -Filter *.xml |
  ForEach-Object { [xml]$d = Get-Content $_.FullName
    if ([int]$d.DocumentElement.GetAttribute("failures") -gt 0 -or [int]$d.DocumentElement.GetAttribute("errors") -gt 0) { $_.Name } }
```

### 4.3 定向单测（每条声明先跑对口 suite，再看代码/设备）

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.ChatStoreTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.OpenCodeClient*"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SettingsStructureTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.GlobalEventTest"
```

（`app/src/test/**` 现有 63 个 suite；与四卡最相关的：`ChatStoreTest`、`GlobalEventTest`、`OpenCodeClient*Test`、`ToolCardPolicyTest`、`ToolPresentationTest`、`TimelineFidelityTest`、`TimelineGroupingTest`、`ContextUsageTest`、`SessionContextRingTest`、`SettingsStructureTest`、`SettingsPaletteModelTest`、`ModelPickerTest`、`PermissionFlowTest`、`QuestionFlowTest`、`EventStreamTest`。）

### 4.4 设备实测（两台都跑；截图存 `research/verify_wave4_shots/`）

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
foreach ($s in @("emulator-5554","emulator-5556")) {
  & $adb -s $s install -r app/build/outputs/apk/debug/app-debug.apk
  & $adb -s $s reverse tcp:4096 tcp:4096          # 每次装完必须重加
  & $adb -s $s shell am force-stop com.xiufeigo.opencodeshell
  & $adb -s $s shell monkey -p com.xiufeigo.opencodeshell 1
  Start-Sleep -Seconds 6
  & $adb -s $s shell screencap -p /sdcard/w4e_$s.png
  & $adb -s $s pull /sdcard/w4e_$s.png "research/verify_wave4_shots/w4e_$s.png"
}
```

辅助（排错用）：`adb -s <serial> logcat -d -t 300 *:E`、`adb -s <serial> shell input tap/swipe`、`adb -s <serial> shell dumpsys window displays`。

### 4.4.1 移动分支的验证方式（Lead 2026-09-20 裁决；R1 已定案）

- **门控 = 上游 768dp**（`_upstream/packages/app/src/session/model.ts:35`），**不是 600dp**（Lead 已同步 mobile-form）。
- 因此 **5554（1280dp）与 5556 内屏（851.7dp）都应显示桌面形态**（侧栏 / 桌面 review 面板），**不应**出现底部 sheet 或四整宽 tab。两台各截 1 张图，并把「没有 sheet」记为**符合上游语义**，不是缺陷。
- 移动分支只能用 5556 **临时缩宽**触发（唯一 <768dp 的手段）：

```powershell
& $adb -s emulator-5556 shell wm size 1080x2152      # 1080/2.4375 ≈ 443.1dp < 768 → 应出现 sheet + 四 tab
# 截图 w4e_fold_narrow_*.png（sheet 打开态 + 四 tab 态）
& $adb -s emulator-5556 shell wm size reset          # 必须复原
& $adb -s emulator-5556 shell wm size                # 复验应回到 Physical size: 2076x2152
# 复原后补 1 张 w4e_fold_reset.png
```

- 折叠屏外屏（**displayId 3**，1080x2364 @390dpi = 443.1dp）可作替代/补充证据；但实测其 `state OFF`（`dumpsys display`），激活需切屏，若不能在不动他人设备状态的前提下完成，则以 `wm size` 缩宽为主证据并在报告注明。
- **回滚纪律**：缩宽窗口内不做其他 teammate 的验证；结束立即 `wm size reset`，把 reset 后的 `wm size` 原始输出与复验截图一并存档。

### 4.5 场景覆盖与判据（逐卡）

| 卡 | 必验场景 | 判据（可证伪） |
|---|---|---|
| W4-A data-layer | `session.error` 解析、`message.part.removed`、`rawJson` 白名单、`contextLimit`、分页、认证头 | 单测存在且绿；`grep` 证明：`PartRemoved` 有 `parseEvent` 分支 + `reduce` 删除逻辑；`rawJson` 赋值被 `RAW_JSON_TYPES` 门控；`OpenCodeClient` 中不再出现 `"$username:$password"` 形态的字面 `null`；`message.part.removed` 全仓不再零命中 |
| W4-B session-fidelity | 工具卡透明、分组文案 `Used N`、横三点、submit 配色、7 种 part、消息元信息、Working/Jump、环分母+可点 | 读 `PartRenderer.kt`/`ToolCardPolicy`/`ContextToolGroupCard` 新行号并对照上游；设备截图：时间线工具区无实心卡面、分组行含 `Used`、⋮ 为横向、环非空且点击有反应 |
| W4-C mobile-form | ①两侧宽设备应显示**桌面形态**；②缩宽后底部 sheet、四 tab；③行长按菜单、question 分页、diff 13sp/24sp 只染背景 | ① 5554/5556 截图：无 sheet、有侧栏 = **符合 768dp 上游语义**（不是缺陷）；② 5556 `wm size 1080x2152` 后截图：底部升起的面板 + 16dp 上圆角 + 28dp 拖拽柄、四个等宽 tab；随后 `wm size reset` 复原并复验截图；③ 长按出现菜单；`ReviewChangesTab` 行高/字号改值 |
| W4-D shell-wiring | 设置 Providers/Models 真数据、死设置接线/隐藏、命令面板 5 条+`file.open`、快捷键 | 5554/5556 截图：Providers 列出 10 个 provider（对照 §1 实测 10）、Models 有模型；逐条点 5 条命令看是否有可见副作用；`grep` 确认无「有 UI 无消费方」的新增项 |
| 回归 | v1 接入未坏、无新增死设置 | `grep EventStream` 仍构造 `/global/event`；设备上发一轮提示词能流式回复；设置页逐项 grep 消费方 |

---

## 5. 基线阶段已识别、第二步必须正面回答的风险

- **R1（已定案，Lead 2026-09-20 裁决）上游 `isDesktop()` 门槛 = 768px，本机两台设备都属桌面档。**
  事实：上游分档是 `createMediaQuery("(min-width: 768px)")`（`_upstream/packages/app/src/session/model.ts:35`、`session/header/session-header.tsx:5`、`session/files/session-side-panel.tsx:87`、`session/timeline/session-context-usage.tsx:48` 等同一常量）；`emulator-5554` = 1280dp、`emulator-5556` 内屏 = 851.7dp，**都 ≥ 768dp**。
  裁决（Lead）：门控按**上游 768dp**实现（不用 600dp）。因此两台设备出现**桌面形态**是正确行为 —— 第二步要把「折叠屏没有底部 sheet」记录为**符合上游语义**，并各留截图作为证据；移动分支（sheet + 四整宽 tab）用 5556 临时 `wm size 1080x2152`（≈443dp）验证，结束必须 `wm size reset` 并复验（§4.4.1）。
  验证要点：先读实现门控常量确实是 768（或等价的 768dp 判定），再看设备行为是否与该常量自洽；若实现写 768 但 5556 上仍出现 sheet（或反之），即为缺陷并附截图 + `dumpsys window displays` 数值。
- **R2（中）`GET /config` 无 `model` 字段**（实测 null）。凡验收声明涉及「默认模型回退」「用量环分母来自当前选中模型」的，注意区分 `GET /provider` 的 `default` 映射（10 个已连接 provider 各有默认模型）与 `GET /config.model`。需要核实 `ChatViewModel` 在本 server 上最终拿到的 `contextLimit` 是否非 null（这是「用量环有真实分母」的直接判据）。
- **R3（中）`connected` 是字符串数组**（provider id），不是对象数组；若实现按 `connected[].id/name` 解析，会得到空名列表。第二步需确认 `Providers.fromJson` 对 `/provider` 两种形状（`all` 对象数组 + `connected` id 数组）都成立。
- **R4（低）审计行号只对工作区成立**（见 §3.1-3）；第二步复核请一律以工作区新行号为准，并在本报告里写新行号。

---

## 6. 第二步执行清单（task-5 就绪后逐条打勾）

- [ ] `team_task_get task-5` → 确认 `ready=true` 后 `claim`（带上当前 revision）
- [ ] 4.1 构建 + 4.2 聚合；记录实际 `tests/failures/errors/suites` 并与 §2 基线对比
- [ ] 读四份卡报告：`research/wave4_data_layer.md`、`wave4_session_fidelity.md`、`wave4_mobile_form.md`、`wave4_shell_wiring.md`（若不存在或缺失声明，直接记「无法复核」）
- [ ] W4-A 逐条复核（§4.5 表第一行），每条至少 1 项亲自验证
- [ ] W4-B 逐条复核（重点：工具卡透明 / `Used N` / 横三点 / 环分母+可点 / 7 种 part / 元信息 / Working+Jump）
- [ ] W4-C 逐条复核（重点：sheet、四 tab、长按菜单、question 分页、diff 规格）
- [ ] W4-D 逐条复核（重点：Providers/Models 真数据截图、5 条命令真执行、快捷键、死设置清单）
- [ ] 设备 E2E：发提示词→流式→回复、工具卡、Review/文件树、命令面板、设置页、折叠屏形态（两张设备，截图入 `research/verify_wave4_shots/`）
- [ ] **桌面形态取证**：5554 / 5556 内屏各 1 张截图，结论写「无 sheet、有侧栏 = 符合 768dp 上游语义」
- [ ] **移动分支取证（§4.4.1）**：5556 `wm size 1080x2152` → sheet + 四 tab 截图 → `wm size reset` → reset 复验截图与 `wm size` 输出存档（报告须写明已复原）
- [ ] 回归：`/global/event` 仍在用；无新增死设置项；无产品代码越界（对照四张卡的 writeScopes）
- [ ] 写结论（通过/不通过/部分通过）+ 每卡复核表 + 问题清单（谁的文件 + file:line + 现象 + 复现）+ **无法验证项与原因**
- [ ] `team_task_update action=complete` + `send_message` 给 `lead`（发现问题不自己修，不 git commit）

---

## 7. 无法验证 / 待确认项（动态维护）

| # | 项 | 原因 | 状态 |
|---|---|---|---|
| V-1 | W4-A~D 的验收声明本身 | 四张卡尚未完成（`task-1..4` = in_progress），报告文件未出现 | 待第二步 |
| V-2 | 折叠屏「移动端形态」是否真能触发 | 已定案（Lead 裁决）：门控 = 768dp，5556 内屏 851.7dp 回到桌面形态是**正确**行为；移动形态改由 `wm size 1080x2152` 缩宽验证并复原（§4.4.1） | 方案已定，待第二步执行 |
| V-3 | 「设置 Providers 显示 10 个 provider」的**设备**结果 | 只能等实现接线后截图；服务端数据本身已实测为 10（§1） | 待第二步 |
| V-4 | 命令面板 5 条命令的**真实副作用** | 需设备点击 + 观察路由/面板变化；单测无法证明端到端 | 待第二步 |
| V-5 | 上游 v1 路由的权威定义 | `_upstream` 是 sparse checkout，缺 `packages/{protocol,server,core,client}`（四份审计已声明此边界） | 结构性不可验证（已知边界） |
| V-6 | 逐像素/视觉一致性 | 本验证只做截图肉眼比对与规格值对照，不做像素 diff | 方法边界 |

---

# 第二步：四卡复核结果（2026-09-20 22:0x）

## 8. 结论

**部分通过（PARTIAL PASS）**：

- **通过**：`clean :app:testDebugUnitTest :app:assembleDebug` 全绿（70 suites / 628 tests / 0 failures / 0 errors）；四张卡的全部**主要**交付项都在代码或设备上被独立复现；Top 12 抽查的 6 条修复后达到上游形态；v1 接入（`/global/event`）未坏；Lead 的 3 处产品代码修复（终端崩溃 / BasicAuth / OcTheme alpha）均经设备复验。
- **未通过 / 必须由实现方处理**：1 个 **P1**（全局快捷键在第一次弹层关闭后**全部失效**，见 F-1）+ 1 个 **P2**（`Ctrl+P` 未触发文件面板，F-2）。
- **未能端到端验证**（不是判定失败，只说明证据边界）：`workspace.new` 点选流程、`review.toggle` 命令行点选、`question` 多题分页真机、`file.open` 的“打开文件+定位”语义、`session.previous`（与 `session.next` 同一实现路径）。详见 §14。

## 9. 构建与测试（独立复跑，非引用他人数字）

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug   # BUILD SUCCESSFUL in 1m 22s
```

| 项 | 结果 | 备注 |
|---|---|---|
| 退出码 | **0** | `84 actionable tasks` |
| 构建日志 `FAILED`/`error:` | **0 命中** | `$env:TEMP\verify_w4e_build.log` |
| suites | **70** | 基线 63 → +7 |
| tests | **628** | 基线 537 → **+91** |
| failures / errors / skipped | **0 / 0 / 0** | 无失败 suite |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，21:46:41，30,469,224 B | 两台设备均装此包 |

解析口径复述：XML 根元素名是测试类名，必须 `DocumentElement.GetAttribute(...)`（§2 脚本）。测试数**多于**基线，未发现被删测试。

## 10. 逐卡复核（每条声明至少 1 项亲自验证）

### W4-A 数据层（`research/wave4_data_layer.md`）

| 声明 | 我的独立证据 | 判定 |
|---|---|---|
| ① `session.error` 对象解析，不再整段 JSON | `ChatStore.kt:506-516` `errorMessageOf`＝`data.message`→`name`→顶层 `message`→字符串；`:524` `errorRawJsonOf` 保留原 JSON；`:626-632` 分支已不用 `optString("error")`；单测 `ChatStoreWave4EventTest` 覆盖 4 种形状 | ✅ 成立 |
| ② retry 细节 | `ChatStore.kt:105,140` `RetryInfo(+Action)`；`:610-617` `session.status{retry}` → `RunStatusChanged(RUNNING, retry=…)`；`:320` `retry` 只在 RUNNING 保留 | ✅ 成立 |
| ③ Providers 形状容错 | `OpenCodeClient.kt:447,469,543,574` `all` 同时接受 `JSONArray` 与对象；`limitContext`＝`:501` `limit.optInt("context,0").takeIf{>0}` | ✅ 成立 |
| ④ `limit` 参数 | `listSessions/messages(..., limit=null)`；`ChatViewModel.kt:855,1089` `SNAPSHOT_MESSAGE_LIMIT=200` | ✅ 成立 |
| ⑤ 认证修复 | `BasicAuth.kt:26-31`（空密码→`null`，用户名缺省 `opencode`）；`OpenCodeClient.kt:54,75`、`EventStream.kt:92` 共用（Lead 第 2 处改动一并成立） | ✅ 成立 |
| ⑥ `message.part.removed` | `ChatStore.kt:206` `Event.PartRemoved`；`:594-599` 解析（双字段缺失→null）；`:301-311` reduce 删 part、消息变空则整条移除 | ✅ 成立 |
| `rawJson` 只对 7 类生效 | `ChatStore.kt:55-63` 白名单＝subtask/step-start/step-finish/snapshot/patch/agent/retry；`:547` 与 `ChatViewModel.kt:1051` 两处都按白名单取值 | ✅ 成立 |
| `contextLimit` 真实分母 | `ChatViewModel.kt:1097-1103` `contextLimitOf` 取 `model.limitContext`；设备端 `SessionScreen.kt:303,347-353` 接进头部（§12） | ✅ 成立 |

### W4-B 会话界面保真（`research/wave4_session_fidelity.md`）

| 声明 | 我的独立证据 | 判定 |
|---|---|---|
| 工具卡透明 + 12dp 导轨 | `PartRenderer.kt:233` 卡片 `Column(modifier.fillMaxWidth())`，**无 background/border**；`layer01` 只剩注释（`:192`）；输出盒另在 `:322-326` 用 `bgBase`+0.5dp `borderBase`（对齐上游 `bash-output`）；设备截图工具区为轻量折叠行 | ✅ 成立 |
| 默认分组 + `Used {count} {names}` | `TimelineGrouping.kt:104-105` `englishText = "Used $count $englishTitle"`（`isUpdatesOnly`→"Updates"）；`ContextToolGroupCard.kt:162` `stringResource(R.string.oc_meta_used, count, title)`；`strings.xml:393` `"Used %1$d %2$s"` 与上游 `en.ts:127-128` 同形；设备显示 `使用了 1 Shell` / `使用了 1 思考`；Exploring/Explored 已删（仅注释） | ✅ 成立 |
| `⋮` 横向 | `ic_oc_dots.xml:12` `M4.5,12h0.01M12,12h0.01M19.5,12h0.01`（横向三点） | ✅ 成立 |
| 发送按钮 submit 规格 | `Composer.kt:392-412` `LightSurface #2E2E2E`/`DarkSurface #DBDBDB` + 渐变 + ring + 禁用 `0.4f`（`:370`）；设备浅色主题按钮为深灰底白箭头 | ✅ 成立 |
| 7 种 part 渲染 | `PartRenderer.kt:147-156` `patch/subtask/agent/retry` 有专属行，`step-start/step-finish/snapshot -> Unit`；`RawParts.kt` 解析 payload；`when` 覆盖 12 型 | ✅ 成立（设备无这 7 类样本，见 §14） |
| 消息元信息 + 复制 | 设备 dump：`复制回复`/`复制消息` 节点存在；截图文案 `Build · gpt-5.6-sol-fast · 32 秒`（助手）/`… · 12:35`（用户） | ✅ 成立 |
| Working 行 + Jump to latest | `MessageTimeline.kt:117,173,409,434,491-497`；设备：发提示词后出现 `工作中`（`v_fold_stream_working.png`），上滚后 `跳到最新` 节点 `[1264,1284][1296,1316]` 出现并可点回底 | ✅ 成立 |
| 用量环真实分母 + 可点 | 独立 dump：`上下文用量` `[2408,72][2464,168]` **clickable=true**；点按后评审面板打开（`v_5554_ring_tap_review.png`）；Models 面板显示 `上下文 1000000` 等真实分母 | ✅ 成立 |

### W4-C 移动端形态（`research/wave4_mobile_form.md`）

| 声明 | 我的独立证据 | 判定 |
|---|---|---|
| 门控 = 上游 768dp | `MobileForm.kt:37,40,43` `DESKTOP_MIN_WIDTH_DP=768`；设备 `mCurrentConfig`：5556 内屏 `sw852dp`→桌面、`wm size 1080x2152` 后 `sw443dp`→移动 | ✅ 成立 |
| 底部 sheet | 443dp 点 ☰ → `v_fold_narrow_sheet.png`：底部对齐、16dp 上圆角、32×4 拖拽柄、scrim 变暗、行带相对时间；下滑关闭生效（`v_fold_narrow_sheet_closed.png`）；代码 `SessionBottomSheet.kt:57-59,127,147` 与上游 `mobile-drawer.css:1-60` 对齐 | ✅ 成立 |
| 四个整宽 tab | 443dp dump：`会话视图 [0,244][1080,332]`，`会话/变更/文件/终端` 四等距（中心 123/368/613/859）+ `⋯`；代码 `MobileSessionTabs.kt:83-89` 均 `weight(1f)`；桌面形态下无 tab 条 | ✅ 成立 |
| 行长按菜单 500ms | 443dp 下 900ms 长按行 → dump `重命名`+`删除`（均 enabled）；`MobileForm.kt:53` 常量 500 | ✅ 成立 |
| diff 规格 13sp/24sp 只染背景 | `ReviewChangesTab.kt:233-234` `textBase to stateSuccessBg / stateDangerBg`，`:244-246` `fontSize 13.sp`, `lineHeight 24.sp` | ✅ 成立（设备无 diff 可截，见 §14） |
| question 分页 | 单测 + 代码；**无真机样本**（与 W4-C §4.9 自述一致） | ⚠️ 未端到端 |

### W4-D 外壳接线（`research/wave4_shell_wiring.md`）

| 声明 | 我的独立证据 | 判定 |
|---|---|---|
| Providers/Models 真数据 | `v_5554_providers.png`：`已连接的提供商：10 / 全部提供商：225`，行内模型数（Antigravity 2 / Command Code 5 / MiniMax 2 / OpenAI 7）；`v_5554_models.png`：`可用模型：36` + 搜索框 + 分组 + `上下文 1000000 / 1M / 200k`。与我的 server 探针（connected=10、all=225）一致 | ✅ 成立 |
| 死设置接线/隐藏 | `App.kt:187` provide `LocalTimelineDisplayPrefs`、`:502` `terminalFont`、`:697` `showFileTree=…`；`SettingsModel.kt:261` `HIDDEN_SETTINGS`；设备设置页只剩活设置项 + 7 个「未提供」禁用入口（`v_5554_settings_nav.png`） | ✅ 成立 |
| 5 条空命令补全 | `AppCommands.kt` 无 `onSelect = {}`：`:66-80` `session.new/tab.new`、`:85-87` `workspace.new`、`:92-101` `session.previous/next`、`:105-108` `terminal.toggle`、`:112-115` `review.toggle`、`:120-123` `file.open`、`:128-131` `fileTree.toggle` 全部绑真实回调 | ✅ 成立（设备端见下） |
| 命令真能执行 | **`session.new`**：palette 点「新建会话」→ 草稿页（`构建任何内容`+composer，`v_5554_session_new_cmd.png`）✅；**`session.next`**：点「下一个会话」→ 标题切为 `Count from 1 to 999…` ✅；**`terminal.toggle`**：终端面板打开并渲染 PowerShell ✅；`file.open`/`review.toggle`/`workspace.new` 未端到端（§14） | ⚠️ 部分 |
| 快捷键接入 | `App.kt:639-646` 五路 chord；`Ctrl+K`、`Ctrl+,`（KEYCODE_COMMA=55）设备实测可触发；`Ctrl+P` 未触发（F-2）；**首次弹层关闭后所有 chord 失效（F-1）** | ❌ 有缺陷 |
| 宿主策略（Home 无 Files） | Home 上 `Ctrl+K` 占位符＝`搜索命令和会话`、只有 5 条命令行（`v_5554_palette.png`）；会话宿主为 `搜索文件、命令和会话`（`v_5554_fresh_ctrln.png`） | ✅ 成立 |
| 终端 chip 宽度门控（§8） | 独立 dump（5554，未看其 dump 文件）：`更多选项 [2496,104][2528,136]`、`打开终端 [2500,188][2532,220]` —— **矩形不相交**；443dp 侧 `打开终端` 消失（`v_dump_fold_narrow.xml` 无该节点） | ✅ 成立 |
| Esc 关设置（§9.5 的“不生效”） | 反例：设置打开（dump 有 `提供商`）→ `ESC×2` → `提供商` 消失。单次 ESC 只收起 IME，故**“Esc 完全不关设置”不成立**，准确说法是“首个 Esc 常被输入法吃掉” | ⚠️ 原声明不精确 |

### Lead 的 4 处改动

| 改动 | 我的独立证据 | 判定 |
|---|---|---|
| 1. `TerminalPanel` 崩溃修复（先 `setTextSize` 再 `setTypeface`） | 5554 点「打开终端」→ 终端渲染 `Windows PowerShell … PS C:\Windows\System32>`（`v_5554_terminal.png`），清理 logcat 后复跑**无 FATAL/AndroidRuntime** | ✅ 成立 |
| 2. `BasicAuth.kt` + REST/SSE 共用 | `BasicAuth.kt:26-31`；调用点 `OpenCodeClient.kt:54,75`、`EventStream.kt:92`；无密码不发头、用户名缺省 `opencode` | ✅ 成立 |
| 3. `OcTheme` RRGGBBAA→AARRGGBB | `OcTheme.kt:107-111` 交换 RGB/AA；行为证据：443dp sheet 的**拖拽柄可见**、scrim 真实变暗（`v_fold_narrow_sheet.png`） | ✅ 成立 |
| 4. `research/e2e_harness.ps1` | 未运行、未审阅（工具脚本，无产品影响） | ⚠️ 未验证 |

## 11. 规格对照抽查（修复后，与 §3 同一批 6 条）

| # | 修复后 Android 值 | 上游值 | 判定 |
|---|---|---|---|
| 1 工具卡 | `PartRenderer.kt:233` 无填充/无描边；展开体 12dp 导轨 | `collapsible.css:5-8` + `basic-tool.css:314-318` | ✅ 一致 |
| 2 `⋮` | `ic_oc_dots.xml:12` 横向三点（24 viewBox 的 4.5/12/19.5） | `icon.tsx:139-141` | ✅ 一致 |
| 3 用量环 | `SessionScreen.kt:303,343-353` 接 `contextPercent/tokens/cost/onOpenContext`；环可点并打开评审 | `session-context-usage.tsx:68-81,93-108,131-139` | ✅ 一致（几何仍 compact 14/1.5，非 indicator 16/2 → F-6） |
| 5 分组文案 | `oc_meta_used`＝`Used %1$d %2$s`；`count = 工具数 ?: 思考数`；`title = 工具名 || Thought(s)` | `tool-renderer.tsx:535-558`＋`en.ts:127-128` | ✅ 一致 |
| 7 抽屉 | <768dp 底部 sheet（16dp 圆角 / 28dp 手柄 / 32×4 条 / scrim / 240-200ms）；≥768dp 左抽屉 | `mobile-drawer.css:1-11,13-31,46-60` | ✅ 一致 |
| 8 Review | <768dp 四整宽 tab（复用 Changes/Files + 内嵌终端）+ `⋯` 用量；≥768dp 保留 340dp 侧栏 | `review/view.tsx:23-117`、`screen.tsx:303-317` | ✅ 移动端一致；桌面 340dp 仍是自定值（上游 240/200/480，既有偏差，不在本轮范围） |

## 12. 设备 E2E 证据（截图均在 `research/verify_wave4_shots/`）

| 场景 | 证据 | 结论 |
|---|---|---|
| 两台设备冷启动 | `v_emulator-5554_home.png`、`v_emulator-5556_home.png` | 均恢复会话页；工具区为折叠行（无卡面）；消息元信息行+复制图标在 |
| 发提示词→流式→回复 | `v_fold_stream_working.png`（`工作中` 行 + 停止方块）、`v_fold_stream_done.png`（新助手气泡+meta） | ✅ 通过；5556 发的消息在 5554 上同步出现（v1 全局 SSE 工作） |
| 工具卡/分组 | 两机截图 + dump `使用了 1 Shell` / `使用了 1 思考` | ✅ 通过 |
| Review/文件树 | `v_5554_ring_tap_review.png`（评审：变更/文件 tab，空态「还没有变更」） | ✅ 通过 |
| 命令面板 | `v_5554_palette.png`（Home 宿主）、`v_5554_session_new_cmd.png`（session.new→草稿）、`v_5554_fresh_ctrln.png`（会话宿主 5 行） | ✅ 通过（命令点选见 §10） |
| 设置页数据 | `v_5554_providers.png`（10/225）、`v_5554_models.png`（36 模型+上下文） | ✅ 通过 |
| 终端（崩溃修复） | `v_5554_terminal.png`、`v_dump_5554_terminal.xml`（`Terminal 48OC`）+ logcat 无 FATAL | ✅ 通过 |
| 折叠屏桌面形态 | `v_fold_desktop_drawer.png`（852dp 左抽屉）、`v_dump_fold_desktop.xml`（有 `打开会话`/`切换审查面板`，无四 tab 条） | ✅ 符合 768dp 上游语义（不是缺陷） |
| 折叠屏移动形态（临时缩宽） | `wm size 1080x2152` → `sw443dp`；`v_fold_narrow_initial.png`（四 tab）、`v_fold_narrow_sheet.png`（底部 sheet）、`v_fold_narrow_longpress.png`（长按菜单）、`v_fold_narrow_sheet_closed.png`（下滑关闭）、`v_dump_fold_narrow.xml` | ✅ 通过 |
| **复原** | `wm size reset` → `Physical size: 2076x2152`，`mCurrentConfig` 回到 `sw852dp`；`v_fold_reset.png` | ✅ 已复原 |
| 跳到最新 | `v_5554_jump_button.png`、`v_5554_after_jump.png` | ✅ 通过 |

## 13. 问题清单（按严重度；只报告不修）

**F-1（P1，产品缺陷，必须处理）全局快捷键在第一次弹层关闭后全部失效，直到 App 重启。**
- 现象：一次 App 运行内第一条 chord 生效；**一旦弹层（命令面板/设置）被打开并关闭，之后 `Ctrl+K`/`Ctrl+,`/`Ctrl+N` 全部无反应**。
- 复现 A：`am force-stop` + `monkey` → `Ctrl+K`（palette=**True**）→ `ESC×2`（palette=**False**）→ `Ctrl+K`（palette=**False**）。复现 B：fresh → `Ctrl+,` 开设置（dump 有 `提供商`）→ `ESC×2` 关 → `Ctrl+,` 再按（不再出现）。
- 证据：dump 布尔序列 T0=False/T1=True/T2=False/T3=False；A1=开/A2=关/A4=再按不开；截图 `v_5554_fresh_ctrln.png`、`v_5554_ctrlk_retest.png`。
- 影响：本轮「快捷键真正接入键盘」在**第二次使用后即失效**（用户感知“时灵时不灵”）。
- 可疑机制（**假设，需实现方确认**）：`App.kt:560-568` 的 `GlobalKeybindFocus` 只在 `LaunchedEffect(Unit)` 里 `requestFocus()` 一次；弹层（palette 搜索框自动聚焦）夺走焦点后，关闭时未把焦点还给该 Box，而 chord 依赖 `onPreviewKeyEvent`。`DialogHost.kt:164` `LocalDialogActive provides stack.isNotEmpty()` 与 `:67` 的 ~100ms 退出窗口不足以解释持续失效。
- 关系：`research/wave4_shell_wiring.md` §9.7 怀疑的“软键盘吞掉一次 chord”只解释单次；我的复现表明**关闭弹层后持续失效**，范围更大。

**F-2（P2）`Ctrl+P`（`file.open` 快捷键）未触发。**
- 复现：冷启动后作为**第一条 chord** 按 `Ctrl+P`（`input keycombination 113 44`）→ 仍是会话页（dump 全文本为消息内容，无 palette）；同条件 `Ctrl+K`/`Ctrl+,` 可触发。
- 代码路径存在：`App.kt:641` `mod && key == Key.P && !shift -> PALETTE_FILES`，`:575-578` 打开 FILES_ONLY 面板。
- 影响：键盘用户无法直达文件面板（面板本身见 F-3）。

**F-3（P2，端到端不确定）`file.open` 的“打开文件”语义未复现。**
- palette 输入 `file` 后出现的是文件搜索结果行 `File not found: C:\Windows\System32\Users\xiufe\AppData\Local\Temp\oc-e2e-demo.txt`（`v_5554_file_open.png`）；点该行后回到会话页，未见文件预览面板被打开/定位。
- `research/wave4_shell_wiring.md` §3.4 声称“确保打开 review 面板 + 切文件 tab + 定位该路径”；我的证据不足以否证（该行可能是 `/find/file` 的报错伪行），需要实现方给一个“存在真实文件”的复现步骤。
- 另一可疑点：返回路径把相对路径拼到了 `C:\Windows\System32` 下，路径基准可能与会话目录不一致。

**F-4（P3）头部 ⋮ 菜单「删除」仍永久禁用，抽屉长按菜单「删除」可用。** 证据：5554 ⋮ → `重命名`(可用)+`删除`(灰)；5556 窄屏长按 → 两项均 enabled。同功能两入口不一致（非本轮声明项）。

**F-5（P3）设置页底部仍有 Android 独有 footer「OpenCode 桌面版 v1.4.0」**（上游设置导航无 footer；`audit_settings_palette.md` A-1 已记）。

**F-6（P3）用量环几何仍是 compact 档（14dp/1.5dp）**，上游 `SessionContextUsage` 用 indicator（16dp/2）；审计 §13 已指出，本轮未改。

**F-7（P3，观察项）折叠屏 852dp 下模型 chip 回退为「模型」**，同会话在 5554 显示 `GPT-5.6 Sol Fast`。可能与会话级 model override / 服务器默认解析有关（本机 `GET /config` 无 `model`），证据不足，登记观察。

## 14. 无法验证 / 未端到端验证的项与原因

| # | 项 | 原因 | 状态 |
|---|---|---|---|
| V-1 | `workspace.new` 点选流程 | 会弹出 SAF 目录选择器（跨 App），未在本轮点击 | 未端到端 |
| V-2 | `review.toggle` 命令行点选 | 面板按中文本地化标题匹配，`adb input text` 无法输入中文；`review`/`file` 关键词只命中文件行 | 未端到端（代码 `AppCommands.kt:112-115` + UI 等效入口已验） |
| V-3 | `session.previous` | 与 `session.next` 同一 `steppedSessionId` 实现，只验证了 next | 单侧验证 |
| V-4 | `question` dock 多题分页真机 | 两个测试会话都无 pending question，构造需驱动 agent 提问（同 W4-C §4.9） | 未端到端 |
| V-5 | 7 种 part 的真机渲染 | 现有会话 part 集合＝text/reasoning/tool/step-start/step-finish；无 subtask/agent/retry/patch/snapshot 样本 | 代码+单测级 |
| V-6 | diff 13sp/24sp 真机 | 测试会话无 diff（评审面板空态） | 代码级 |
| V-7 | `research/e2e_harness.ps1` | 未运行（Lead 声称的工具脚本） | 未验证 |
| V-8 | 逐像素一致性 | 方法边界：只做截图肉眼+规格值对照 | 方法边界 |
| V-9 | 折叠屏外屏（displayId 3）作为移动形态备选 | 该 display `state OFF`，未激活；已用 `wm size` 缩宽替代并复原 | 替代方案已用 |

## 15. 复核方法学备注

- 所有 `file:line` 均为**当前工作区**行号；抽查的改动行与四份卡报告行号一致。
- 设备操作全部经 `adb`（`install -r` / `reverse` / `force-stop` / `monkey` / `input` / `screencap` / `uiautomator dump`）；`wm size` 变更已 `reset` 复原并留存 `wm size`/`mCurrentConfig` 输出。
- 独立复现了他人的关键 dump 结论（⋮ vs 终端 chip 矩形不相交），未直接引用其 dump 文件作为证据。
- 发现并登记了两处他人报告不精确点：`Esc` 关设置（§10-W4-D 末行）；⋮ 被遮挡问题在当前 APK 已不复现。
- 第二步（纯验证阶段）未修改任何产品代码；只写 `research/verify_wave4.md` 与 `research/verify_wave4_shots/`。**第三步（用户直接指令的修复轮）修改了产品代码，见 §16。**

---

# 第三步：修复轮 + 与 anomalyco/opencode v2 一致度复核（2026-09-20 22:1x）

> 触发：用户直接指令「修复你找出的两个问题，然后全盘复核是否与 v2 一致，还有不一致与 bug 就修复」。
> 因此本轮起我**接管产品代码修复**（原 task-5 的“只读”约束来自任务卡，被直接指令覆盖）。Lead 已确认让路，并把
> `ui/App.kt`、`ui/palette/**`、`ui/overlay/**`、`ui/session/header/**`、`ui/settings/**`、`ui/session/SessionScreen.kt` 交给我。

## 16. 修复项与验证（全部已落盘、构建绿、设备复验）

最终构建：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → **BUILD SUCCESSFUL**；
**70 suites / 628 tests / 0 failures / 0 errors**；APK 22:17 装 5554+5556。

| # | 修复 | 文件:行 | 验证证据 |
|---|---|---|---|
| **F-1** | 全局快捷键在首次弹层关闭后失效 | `ui/App.kt:560-569`（Lead 交付的 `LaunchedEffect(active)` 重新抢焦点；我复核后接管维护） | **多轮精确判定**（marker = 面板的 `命令` 分组头 / 设置的 `提供商` 项）：T1 `Ctrl+K`→True、T2 ESC×2→False、**T3 `Ctrl+K`→True**、T4→False、**T5 第三次→True**、**T6 三轮弹层后 `Ctrl+,`→True**、T8 再 `Ctrl+K`→True。修复前同一序列在 T3 为 False。 |
| **F-3** | 冷启动 Home 上 file.open 请求被丢弃（`openSessions` 为空时无落点） | `ui/App.kt:332-350`：无 in-run 会话时回落 `chatViewModel.listSessions` 取最近更新会话再导航（回调在 `Dispatchers.Main.immediate`，`ChatViewModel.kt:45`） | 冷启动落在 **Home**（无会话）→ `Ctrl+P` → 输入 `cmdl32` → 点文件行 → 自动进会话并打开评审面板：dump 命中 `评审`/`变更`/`文件`/`cmdl32.exe`/`二进制文件——无法预览`（`v_5554_cold_home_file_open.png`）。会话内路径同样通过（`v_5554_file_open_v6.png`）。 |
| **F-4** | 头部 ⋮ 的「删除」永久禁用 | `ui/session/header/SessionHeader.kt:353-362`（`enabled = true`）+ `ui/session/SessionScreen.kt`（`onMenuAction` → `headerDeleteOpen`，复用 `SessionDeleteDialog`，成功后 `onBack()`） | 5554：⋮ → 菜单两项均可点 → 「删除」打开危险确认框 `删除会话? / 将删除「android-e2e」及其全部消息，且无法撤销。/ 取消 / 删除`（`v_5554_header_delete_dialog.png`）→ 点「取消」未删任何数据（`v_5554_after_cancel.png`）。 |
| **F-5** | 设置页 Android 独有 footer（上游无） | `ui/settings/SettingsScreen.kt`：删除 `SettingsNavFooter()` 调用与实现 + 无用 `BuildConfig` import | 5554：设置打开后 dump 里 `OpenCode 桌面版` / `v1.4.0` **0 命中**（`v_5554_settings_v2.png`）。 |
| **F-8** | Esc 关不掉设置层（上游是 document 级捕获关栈顶） | `ui/App.kt:576-590`：`active` 时若 `Key.Escape` → `LocalDialogController.close()`（有焦点的层如命令面板仍由自身处理） | 5554：设置打开（dump 有 `提供商`）→ **单次 ESC** → dump `提供商` 消失（修复前 ESC×2 仍开）；随后 `Ctrl+K` 正常开面板。 |

**设备复原**：5556 全程 `wm size 1080x2152` → 验证 → **`wm size reset`**，实测回到 `Physical size: 2076x2152` / `mCurrentConfig sw852dp`（`v_fold_reset_v2.png`）。移动形态在新构建下复验：四整宽 tab + 底部 sheet + 拖拽柄 + scrim（`v_fold_narrow_v2.png` / `v_fold_narrow_sheet_v2.png`）。

> 方法学教训：`uiautomator dump` 会滞后于界面（F-4 生效后 dump 仍显示旧菜单，截图才是真值）。本轮起“菜单/弹层是否打开”一律以**截图**为准，dump 只用于结构/文本取证。

## 17. 撤回与更正（保留记录，不删）

| 原条目 | 原结论 | 复查后 | 证据 |
|---|---|---|---|
| **F-2** | 「`Ctrl+P` 未触发」 | **撤回：误报**。当时用 dump 的「前 12 条文本」判断面板是否存在，漏掉对话框节点。复查同一场景截图（`v_5554_ctrlp.png` / `v_5554_ctrlp_v2.png`）：文件面板**确实打开**（placeholder「搜索文件」+ 空态「没有找到结果」）。`globalChord` 映射（`App.kt:648-650`）本身正确 | 两张截图 |
| **F-6** | 「用量环应为 indicator 16/2」 | **撤回：方向错，未改代码**。上游会话头是 `<SessionContextUsage placement="bottom" />`（`message-timeline.tsx:548`，**无 `variant`** → 默认 `"button"` → `IconButton` 内 **compact 14/1.5** 环）；`variant="indicator"`（16/2）只在**侧栏** `session-side-panel.tsx:370`。Android 头部 14/1.5 **正确**，真正偏差只有触控位 40dp vs 28px（触屏适配） | `session-context-usage.tsx:50,110-124,142-159`、`message-timeline.tsx:548`、`session-side-panel.tsx:370` |
| F-3 的一半 | 「file 行只回会话页、路径疑似拼到 `C:\Windows\System32\…`」 | **路径拼接怀疑撤回**：那行文本是**会话标题**（某会话首条消息就是该报错的回显），不是 `/find/file` 结果；真实 file 行在会话内搜 `cmd` 得到 `cmdl32.exe` 等并可预览。**但“冷启动 Home 上请求被丢弃”成立且已修**（见 F-3） | `v_5554_file_search.png`、`v_5554_file_open_v6.png` |

## 18. 与 https://github.com/anomalyco/opencode/tree/v2 的一致度复核

**结论：前端（UI 规格）已对齐到“高保真移动端分支”，功能面覆盖本轮范围的全部 P0/P1；但“完全一致”做不到，瓶颈不在本仓库。**

### 18.1 接入轴：**结构上无法与 v2 一致**（本机实测，非推测）

- 上游 v2 前端走 `/api/*`（`_upstream/packages/app/src/runtime/server/client.tsx:1-2` 导入 `@opencode/client`；端点清单 `packages/app/e2e/utils/mock-api.ts:36-281`）。
- 本机 server（opencode 1.18.31，`http://127.0.0.1:4096`）逐路径实测（22:2x）：

| v2 路径 | 实测 | v2 路径 | 实测 |
|---|---|---|---|
| `/api/session` | ✅ JSON `{"data":[…]}` | `/api/config` | ❌ SPA HTML |
| `/api/provider` | ✅ JSON `{"location":…,"data":[…]}` | `/api/vcs` | ❌ SPA HTML |
| `/api/agent` | ✅ JSON | `/api/file`、`/api/find/file` | ❌ SPA HTML |
| `/api/location` | ✅ JSON | `/api/permission`、`/api/question` | ❌ SPA HTML |
| `/api/event` | ✅ SSE（信封 `{"id","type","data"}`） | `/api/info`、`/api/global/health`、`/api/project`、`/api/lsp`、`/api/mcp` | ❌ SPA HTML |

- 本 App 走 v1 无前缀面（22 端点全可用），SSE `/global/event` 信封 `{"payload":{"id","type","properties"}}`（实测首帧 `server.connected`）。
- **推论**：切 v2 会立刻丢掉 config/vcs/file/find/permission/question 等 11 条能力面，且事件信封不同（需重写 `ChatStore` 解包与全部解析器）→ **当前 server 上不可达**。保持 v1 是正确决策，v2 迁移应作为“server 升级后”的独立路线（`research/04_data_api.md:103` 的原决策仍成立）。

### 18.2 前端轴：本轮已闭合的 P0/P1（附我的独立证据）

| 审计缺口（`audit_1to1_verdict.md` §3） | 现状 |
|---|---|
| P0-1 设置 Providers/Models 空壳 | ✅ 真实数据（10/225、36 模型、真实 `limit.context`） |
| P0-2 12 种 part 只渲染 5 种 | ✅ `rawJson`（仅 7 类）+ `patch/subtask/agent/retry` 渲染行 + step-*/snapshot 显式忽略；`when` 覆盖 12 型 |
| P0-3 `message.part.removed` 未实现 | ✅ 解析 + reduce（删 part、空消息整条移除） |
| P0-4 命令面板 5 条空实现 | ✅ 全部绑真实回调；`session.new`/`session.next`/`terminal.toggle` 实测有可见副作用 |
| P0-5 分组/折叠心智 | ✅ `Used {count} {names}` + compact 默认折叠（`DEFAULT_EDIT_EXPANDED=false`） |
| P1-6 工具卡有面卡片 | ✅ 透明 + 12dp 导轨 |
| P1-7 `⋮` 纵向 | ✅ 横向三点 |
| P1-8 用量环死控件 | ✅ 真实分母 + 可点（开评审） |
| P1-9 移动端照搬桌面 | ✅ <768dp 底部 sheet + 四整宽 tab（≥768dp 桌面形态，符合上游 `isDesktop()`） |
| P1-10 发送按钮配色 | ✅ light `#2E2E2E` / dark `#DBDBDB` + 渐变 + 0.4 禁用 |
| P1-11 消息无元信息 | ✅ Agent·Model·时长/时间 + 复制 |
| P1-12 无 Working/Jump | ✅ `工作中` + `跳到最新`（实测出现/回底） |
| P1-13 diff 11sp 整行染 | ✅ 13sp/24sp + 只染背景（评审路径） |
| P1-14 `session.error` 显示整段 JSON | ✅ `data.message` → `name` → 字符串回退，原 JSON 进 `errorDetail` |
| P1-15 权限 `always[]` 不展示 | ✅ **已实现**（我先前记错）：`SessionPermissionDock.kt:156-184` `permissionAlwaysHint(request)` 会把「Allow always」持久化的 patterns 单独列出（仅当与 `patterns[]` 不同） |
| P1-16 首启无连接页 | ❌ 仍未做（只有内联提示） |

### 18.3 仍然存在的差距（**非 bug，属功能面未做**；按优先级）

1. **结构件（P1/P2）**：首启连接页、会话摘要面板、时间线搜索栏、待发送队列面板、Providers 连接/OAuth/API key 对话框、设置嵌套页（Projects/Workspaces/Extensions/Experimental/About）、会话行 Export/Archive。
2. **交互（P2）**：composer 斜杠命令、`@` 候选的 agent/skill/reference 类别、附件上传进度与取消、粘贴大文本阈值、消息导航轨、`session-turn-diffs` 汇总、逐词进入动画、表格/图片/mermaid 渲染。
3. **细节（P3）**：`always[]` 展示、question dock 文案与 Dismiss 对齐、运行指示器波形/缓动、桌面 Review 侧栏 340dp≠上游 240/200/480（可拖）、面板 filter/行号/词级 diff/行评论。

以上均源自 `audit_session_ui.md`（349 行）与 `audit_settings_palette.md`/`audit_routes.md` 的既有清单，本轮未新增实现；继续收敛建议按“用户可感知 × 投入”另开任务。

## 19. 复现命令（本轮全部可复跑）

```powershell
$env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug          # 70 suites / 628 tests / 0 failures
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 reverse tcp:4096 tcp:4096
& $adb -s emulator-5554 shell am force-stop com.xiufeigo.opencodeshell
& $adb -s emulator-5554 shell monkey -p com.xiufeigo.opencodeshell 1
# F-1 多轮：Ctrl+K(113 39) / Ctrl+,(113 55) / Ctrl+P(113 44) / Esc(111)
& $adb -s emulator-5556 shell wm size 1080x2152   # 移动形态；结束必须 wm size reset
```

**设备状态：两台已复原、无占用**（5556 = 2076x2152 桌面分支；5554 = 2560x1600；均装 22:17 的 APK）。

---

# 第四步：未实现清单（2026-09-20 22:3x，两机实测 + 代码清点）

> 判据：`app/src/main/java/**` 中功能符号**零命中** + 设备实测；已实现的项不再列入（例如 `always[]` 提示、首启提示、设置搜索、命令面板、用量环、四 tab 等均已有）。

## 20. 仍然未实现的功能（按用户可感知度排序）

### A. 会话主界面（timeline）
| # | 功能 | 上游锚点 | 现状/证据 |
|---|---|---|---|
| A1 | **会话摘要面板**（项目/服务器/后台任务；`session.summary.*`） | `session/summary/panel.tsx`、`popover.tsx` | 零命中；5556 窄屏 tab 栏 `⋯` 里「会话详情」**灰置**（截图 `v_fold_narrow_tabmenu_v2.png`） |
| A2 | **会话内搜索栏**（timeline search, `mod+f`） | `session/timeline/search-bar.tsx` | 零命中；头部无搜索图标 |
| A3 | **时间线分隔条**（模型切换 / 压缩起止 / 中断） | `components/timeline-separator.tsx:1-33` | 零命中；只有 compaction 一行静态文案 |
| A4 | **待发送队列面板**（Steer/Queue、拖拽排序） | `session/composer/queue-panel.tsx` | 零命中；运行中发送无 delivery 语义 |
| A5 | **消息导航轨**（刻度条 + hover 卡片） | `components/message-nav.tsx` | 零命中 |
| A6 | **本轮变更汇总** `N Changed files / Show all` | `components/session-turn.css:137-206` | 零命中 |
| A7 | **消息级 fork / revert** | `ui_message_forkMessage`、`session_revertDock_*`（资源已在 `strings_oc_i18n.xml`） | 零命中（有资源无实现） |
| A8 | **会话 Export… / Archive** | `command_session_export/archive`（资源已存在） | 零命中；会话 ⋮ 只有重命名/删除（本轮已把删除接上） |
| A9 | 加载更早消息（`before`/cursor 分页） | v1 `before?`、v2 `cursor` | 只有 `SNAPSHOT_MESSAGE_LIMIT=200`，无反向翻页 |
| A10 | `session-turn` 的 `pt-2/pt-4` 两档间距、逐词进入动画 | `session-turn.css`、`markdown.css:19-25` | 未实现（观感细节） |

### B. Composer（输入区）
| # | 功能 | 上游锚点 | 现状/证据 |
|---|---|---|---|
| B1 | **斜杠命令**（`/model`、`/agent`、`/` 菜单） | `suggestions/machine.ts:107-114`、`composer/commands.tsx:49-82` | 零命中；**5554 实测输入 `/` 无任何菜单**（截图 `v_5554_slash.png`），而占位符写着「/ 查看命令」 |
| B2 | **「+」四项菜单**（附件 / 命令 / 上下文 / Shell） | `editor/editor.tsx:653-665` | 5554 实测直接弹 **Android 文件选择器**（Recent/Images/…，`v_5554_plus_button.png`） |
| B3 | Shell 模式（`!` 前缀、monospace 占位） | `composer/placeholder.ts:6` | 零命中 |
| B4 | **agent 选择器 / model variant（思考强度）** | `commands.tsx:65-81`、`models/variant.ts` | 零命中 |
| B5 | 附件上传进度/取消、图片点击预览、附件行两端渐隐 | `editor.tsx:577-598`、`attachments.css:99-123` | 零命中 |
| B6 | 粘贴上传 / 大文本阈值（≥8000 字符或 ≥120 换行） | `attachments.ts:127-161` | 零命中 |
| B7 | 输入区自动聚焦、`↑↓` 历史、Tab 补全、`mod+Enter` | `composer/region.tsx:145-149`、`interaction.ts` | 零命中 |
| B8 | 按会话持久化草稿 / 发送失败回填 | `persistence.tsx:47-59`、`submit.ts:239-294` | 单实例 `rememberSaveable`，失败不回填 |
| B9 | `@` 候选的 agent / skill / command / reference 类别 | `editor.tsx:905-914` | 只有 file 一类 |

### C. Review / 文件
| # | 功能 | 上游锚点 | 现状 |
|---|---|---|---|
| C1 | 桌面侧栏 **240/200/480 可拖 + 双击复位 + 持久化** | `session-review-v2.tsx:17-19,135-146` | 固定 340dp |
| C2 | **filter files**、unified/split、expand/collapse 模式 | `session-review-v2.tsx:88-123,266-309` | 零命中 |
| C3 | **行号 / 词级 diff / 行级评论 / 文件间导航 / 大 diff 虚拟化** | `pierre/index.ts:205-231`、`session-review-file-preview-v2.tsx:120-162` | 零命中 |
| C4 | Git 三口径（git / branch / last turn） | `review/view.tsx:228-247` | 单口径 |
| C5 | Files 树的 A/M/D 徽标、no-git 空态 + 建仓按钮、媒体预览 | `file-tree-v2.tsx:114-122`、`session-review-empty-no-git-v2.tsx` | 零命中 |

### D. 设置 / Providers / 服务器
| # | 功能 | 现状 |
|---|---|---|
| D1 | 7 个根 tab 仍是**禁用入口**：外观 / 通知 / 项目 / 工作区 / 扩展 / 实验性功能 / 关于 | 设备已验（`v_5554_settings_v2.png`） |
| D2 | Providers 的 Connect/Disconnect、Popular 列表、API key / OAuth / 自定义表单、模型可见性、Manage models | 零命中（面板只读） |
| D3 | Servers 健康检查（恒 UNKNOWN）、保存前校验、SSH/WSL、扫码配对 | 零命中 |
| D4 | 快捷键录制/重绑定/冲突检测/Reset all | 只读 6 行 |
| D5 | Timeline detail preset 选择器（everything/detailed/compact/quiet/text-only） | 只有 3 个布尔开关 |
| D6 | 终端位置、follow-up 行为、workspace 默认目标、mobileDiffWrap 等上游设置项 | 零命中 |

### E. 接入 / 事件（部分受 server 限制）
| # | 项 | 现状 |
|---|---|---|
| E1 | 首启**全屏连接页 + 连通性校验** | 只有内联提示（`oc_setup_hint`） |
| E2 | 事件未消费：`session.created/deleted`、`file.edited`、`vcs.branch.updated`、`project.updated`、`provider/model/agent/config.updated`、`pty.*` | 面板不自动刷新（多为体验损失） |
| E3 | `message.part.delta` 仅支持 `field=="text"`；`permission.replied` 的 `reply`、`question.replied` 的 `answers` 丢弃 | 解析层缺口 |
| E4 | `/pty/shells` 列表、`POST /pty` 的 `args`/`env` | 零命中 |
| E5 | 通知（agent/permissions/errors）与音效播放器 | 设置项已隐藏（无消费方） |
| E6 | v2 `/api/*` 面 11 条路径 | **server 未实现**，不是客户端能补的（§18.1） |

### F. 明确不做（上游桌面专属 / 平台不适用）
拖放 dropzone、hover 显现控件、多窗口/标签页模型、内嵌浏览器、桌面菜单与 `Open in …`、WSL、web 扫码配对、终端面板拖拽。

## 21. 本轮两机实测记录（2026-09-20 22:3x）

| 设备 | 形态 | 测了什么 | 结果 |
|---|---|---|---|
| **emulator-5554（Pixel Tablet）** | 桌面 1280dp | `+` 按钮、`/` 输入、`⋮` 菜单、会话/评审/设置（前几轮） | `+`→系统文件选择器；`/`→无菜单；`⋮`→重命名/删除 |
| **emulator-5556（Pixel 10 Pro Fold）** | 内屏桌面 852dp / 缩宽 443dp | 桌面抽屉、四 tab、tab 栏 `⋯`、sheet、长按菜单 | 四 tab = 会话/变更/文件/终端；`⋯` = 用量（可用）/ 会话详情（灰置）；已 `wm size reset`（2076x2152） |

两台均装 22:17 的 APK（含 F-1/F-3/F-4/F-5/F-8 修复），无崩溃、无 FATAL。
