# M-support 交付：i18n 三语资源 + Inter 可变字体接入

> 范围：横向支撑（非某个 milestone 专属）。权威：`PLAN-android-native-refactor.md` §5（i18n 与文案）、§1.3 / §9-g,h（字体）、`research/07_motion_copy_p2.md` §2.4（字体栈最终答案表）。
> 结论：**两门在真实工作区均已绿** —— `.\gradlew.bat --offline :app:processDebugResources :app:compileDebugKotlin` → **BUILD SUCCESSFUL，0 error**（含 1082+1080+1073 条新字符串、`res/font/inter_variable.ttf`、`OcFonts.kt`/`OcTheme.kt` 改动）。协同说明：执行期内工作区曾因并行 agent 的半成品 `ui/App.kt`、`ui/settings/SettingsModel.kt`、`ui/palette/PaletteModel.kt`、`ui/home/HomeSessionsList.kt` 多次红；其收敛后（观测到 App.kt 修完）本次两门复跑转绿，故无需隔离副本即可交付（隔离副本仅作**归因证据**，复现命令见 §6，副本目录已删除）。

---

## 1. 交付物

| 类型 | 路径 | 说明 |
|---|---|---|
| 生成脚本 | `scripts/gen_i18n.ps1` | PowerShell 7，幂等，可重复执行（含 `-Mode`/`-Prefixes` 参数） |
| 英文（默认） | `app/src/main/res/values/strings_oc_i18n.xml` | 1082 条，87 167 B |
| 简体中文 | `app/src/main/res/values-zh-rCN/strings_oc_i18n.xml` | 1080 条，85 495 B（新目录） |
| 繁体中文 | `app/src/main/res/values-zh-rTW/strings_oc_i18n.xml` | 1073 条，85 026 B（新目录） |
| 字体资源 | `app/src/main/res/font/inter_variable.ttf` | 官方 `assets/opencode-app/assets/Inter.ttf` 原样复制（874 708 B，sha256 `0be2399e…` 与源一致） |
| 字体接入 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/theme/OcFonts.kt` | 新增：`InterFontFamily` / `OcFontWeight` / `OcFonts` |
| 主题接线 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/theme/OcTheme.kt` | 仅新增（`OcType` 尾部默认参数、2 个 CompositionLocal、`LocalTextStyle` 提供） |
| 证据日志 | `research/_i18n_gen_log.txt`、`research/_i18n_font_process_resources.log`、`research/_i18n_font_compile.log`、`research/_i18n_missing_keys.txt` | 生成器输出 / 资源门 / 编译门 / 缺词清单 |
| 探针记录 | 见 §3.3 | aapt2 转义规则实测（探针文件已删除，不随交付） |

**未改动** `app/src/main/res/values/strings.xml`（现有 `oc_*` 等 221 个键一字未动，见 §3.4 理由）。

---

## 2. 生成器用法

```powershell
pwsh -File scripts/gen_i18n.ps1                    # 默认：各 locale 独立文件（当前采用）
pwsh -File scripts/gen_i18n.ps1 -Mode merge        # 把 en 块拼进 values/strings.xml（带 delimit 标记，幂等）
pwsh -File scripts/gen_i18n.ps1 -Prefixes '*'      # 展开全部 1399 个键（含 desktop/wsl/sound/… 尾部）
pwsh -File scripts/gen_i18n.ps1 -ResDir <dir>      # 指向副本做自检，不动真实 res
```

幂等性：脚本每次**重新读取**既有资源名、重建整个生成文件；重复执行字节级一致（`-Mode merge` 亦实测两次结果同为 1473 行、单块标记）。
`-ResDir` 自检路径已在副本中验证过 `separate` / `merge` 两种模式（副本已删除）。

---

## 3. i18n 展开裁决与实现

### 3.1 结构事实（与任务描述的差异，先纠偏）
`research/_i18n/*.json` **不是嵌套 JSON**，而是**扁平点号 key**：en 1399 / zh 1311 / zht 1309 个键，无一个嵌套对象。因此"嵌套 key 展开"退化为 `key.replace('.','_')`，未做任何递归：
`ui.promptInput.placeholder.normal` → `ui_promptInput_placeholder_normal`（大小写保留）。
aapt2 接受 values 内的大写资源名（探针实测 `<string name="zz_probe_camelCaseName">` 通过，`R.txt` 生成 `zz_probe_camelCaseName`），故未强制小写化。

### 3.2 占位符与转义（`ConvertTo-AndroidValue`）
1. `{{slash}}` → `/`、`{{at}}` → `@`（PLAN §5 明文：这两个是**字面字符**，不是参数）。
2. 其余 `{{var}}` → 位置化 `%1$s`/`%2$s`…，按**首次出现顺序**编号；同一变量重复出现复用同一序号（`Formatter` 允许）。**统一用 `%s` 不用 `%d`**，避免 Int/Long 类型不匹配崩溃。
   统计：125 个键带占位符，去重 30 种变量（`{{count}}` 75 次、`{{distro}}` 48、`{{provider}}` 45…）。
3. 转义顺序：`\`→`\\` → `&`→`&amp;`/`<`→`&lt;`/`>`→`&gt;` → `'`→`\'`、`"`→`\"` → 换行→`\n`；行首 `@`/`?` 加 `\`。
4. **首尾空格**：aapt2 会 trim，故这 10 个前缀/后缀键用双引号包裹值（实测有效）：`ui.lineComment.label.prefix`、`ui.lineComment.editorLabel.prefix`、`provider.connect.{opencodeZen,oauth.code,oauth.auto}.visit.{prefix,suffix}`、`provider.custom.description.prefix`、`common.moreCountSuffix`。生成结果例：`<string name="common_moreCountSuffix">" (+%1$s more)"</string>`。
5. 游离 `%`（非 `%N$s`）会触发 aapt2 "non-positional format" 警告，脚本自动改写成 `%%` 并记入 warnings —— **当前三语 dict 里 `%` 出现 0 次**，此分支仅为安全网。
6. 空值：en 3 个 / zh、zht 各 2 个 → 产出自闭合 `<string name="…" />`。

### 3.3 aapt2 转义探针（已删除）
临时 `res/values/zz_escape_probe.xml`（17 条）实跑 `processDebugResources`：`\'`、双引号包裹保空格、`\u0020`、`\@`、`\?`、`\\`、`%1$s`（含重复 `%1$s`）、`100% off`、`#1 file`、`中文·繁體·emoji 🚀`、camelCase 资源名 —— **全部通过**；唯一警告来自 `%1$s -- 50% of %2$s`（非位置化混合），即 §3.2-5 的处置依据。探针与 selftest 副本均已删除。

### 3.4 不写 `values/strings.xml` 的裁决（与任务建议的偏差，需 Lead 知悉）
任务建议把 en 合并进 `values/strings.xml`。**实际改为独立文件 `values/strings_oc_i18n.xml`**，原因：本次执行期间 `values/strings.xml` 正被**其他 agent 高频改写**（观测到 10:49:07 / 10:51:36 / 10:54:52 三次落盘，期间还曾因 `oc_setting_*` 缺失导致编译红）。脚本若回写该文件，读-改-写窗口会造成**他人工时丢失**。独立文件在 Android 语义上等价（同 config 目录合并），且天生不与他人工时冲突。
- "现有优先"仍然成立：脚本扫描 `res/values*/` 全部 `string|plurals|string-array` 名（当前 221 个），**重名一律跳过并记入 conflicts**（本次 conflicts = **0**，因为生成名都以命名空间前缀开头，与 `oc_*`/`app_name` 等 0 交集）。
- 若 Lead 坚持单文件：`pwsh -File scripts/gen_i18n.ps1 -Mode merge` 即可，块用 `<!-- BEGIN GENERATED i18n … -->` 标记、可反复执行；**建议在无其他 agent 编辑时执行**。

### 3.5 范围（防资源爆炸）
默认展开 **30 个 UI 命名空间 / 1082 键**（app, command, common, context, dialog, directory, drag, error, help, home, language, lsp, mcp, mode, model, notification, palette, prompt, provider, server, session, settings, sidebar, status, terminal, theme, titlebar, toast, ui, workspace）。
未展开 **69 个命名空间 / 317 键**（清单见 `research/_i18n_gen_log.txt`）：`desktop(88)`、`wsl(67)`、`sound(46)`、`debugBar(29)` 属桌面端/调试浮层；`spec(4)/test(4)/component(2)/variable(2)/editor(2)/gitDecoration(3)` 与 `CMakeLists.txt`、`d.ts`、`build.gradle`、`astro/jest/vite/…`（各 1–2 键）是 **Web 文件树/图标识别用的文件名与工程配置标签**，非 UI 文案。
补全只需一条命令：`pwsh -File scripts/gen_i18n.ps1 -Prefixes '*'`（脚本已支持，未执行）。

### 3.6 已知数据异常（**需要人写 en 文案**，非本脚本能修）
en dict 有 14 个值本身就是"点号 key"（抽取器把值写成了兄弟 key），zh/zht **无此问题**：
- **12 个可解析 alias**（en 值指向存在键）：`ui.sessionReview.diffStyle.unified`→`…split`、`ui.messagePart.compaction`→`ui.message.interrupted`、`ui.fileMedia.kind.image`→`ui.fileMedia.kind.audio`、`ui.common.file.other`→`…one`、`ui.common.question.other`→`…one`、`session.review.change.one`→`…other`、`session.question.restore`→`session.question.minimize`、`dialog.provider.opencode.tagline`→`…opencodeGo.tagline`、`prompt.dropzone.file.label`→`prompt.dropzone.label`、`error.page.description.localServerStartup`→`error.page.description`、`error.page.action.reported`→`error.page.action.report`、`debugBar.focus.on`→`…off`（最后 1 个不在展开范围内）。
- **2 个悬空引用**：`drag.enter`→`drag.leave`、`mode.shell`→`mode.normal`（目标键不存在）。
- 处置：**按源数据原样保留**（PLAN §5「保留所有 key」），并在 en 文件对应行上方生成 `<!-- upstream alias: en value is the key '…' (untranslated) -->` 注释（共 11 条，`debugBar.focus.on` 未展开）。
- zh-rCN 缺 2 键 / zh-rTW 缺 9 键（`research/_i18n_missing_keys.txt`）：`drag.enter`、`mode.shell` 两语都缺，zht 另缺 `dialog.server.add.{errorCors,errorAuth,corsHint}`、`dialog.server.status.current`、`dialog.server.apply`、`settings.general.row.tabPlusCreatesSession.{title,description}`。**未向 locale 目录硬塞英文**——这些键在 locale 文件里缺席，运行时按 Android 规则回落默认（en），语义正确。

### 3.7 捷径（明确记录）
- **plural 未生成**：dict 里的 `.one`/`.other`（如 `ui.common.file.one|other`、`session.review.change.one|other`、`{{count}}` 系列）当前一律产出 `<string>`，未转成 `<plurals>`+`getQuantityString`。PLAN §5 要求 plural 语义，属**后续**（现有 `oc_time_*`/`oc_context_*` 手写 plurals 仍在用）。代价：中文无影响，英文单复数由调用方决定（如 `common_moreCountSuffix` 是后缀，天然无碍）。
- **`values-b+zh+Hant`** 未建：繁体用 `values-zh-rTW`（按 §5）；若将来要覆盖 `zh-Hant-HK` 等，可加 `values-b+zh+Hant` 兜底。

---

## 4. 字体接入

### 4.1 字体内核证据（补齐 research/07 §存疑项）
直接解析 `Inter.ttf` 的 `fvar`：**是可变字体**，两轴 `wght min=100 default=400 max=900`、`opsz min=14 default=14 max=32`，9 个命名实例（100…900），含 `avar`/`STAT`/`gvar`，`glyf` 轮廓（非 CFF2）。→ research/07 §2.4 "字体二进制内部 axis 未解析验证"**已证**：`FontWeight(440/530/610)` 是真实轴映射，不是合成加粗。

### 4.2 接线方式
- `app/src/main/res/font/inter_variable.ttf`（res/font；minSdk=26 已满足 font 资源要求）。
- `OcFonts.kt`：
  - `InterFontFamily = FontFamily(Font(R.font.inter_variable, 100/200/300/400/**440**/500/**530**/600/**610**/700/900))` —— **单文件多 `Font` 条目**覆盖全字重，**零额外汇编体积**（未生成 6 份静态子集，那会 +≈2.5 MB）。web 字重结构（正文 440 ×38 / 强调 530 ×40 / 组件 500 ×6 / h1-h2 600 / 一处 610）全部可达。
  - `OcFontWeight.Body=440 / Emphasis=530 / Component=500 / Heading=600 / HeadingStrong=610 / Regular=400`。
  - `OcMonoFontFamily = FontFamily.Monospace`（本构建**无 IBM Plex Mono**，通用 mono = 系统 mono 栈，符合 §9-h）；`OcTerminalFontFamily` 暂同为系统 mono，**TODO(M5)**：vendored 只有 `JetBrainsMonoNerdFontMono-Regular.woff2`，Android 不认 woff2，需补 TTF 后改指向它。
  - `OcFonts.materialTypography()`：**可选** M3 `Typography`（所有 slot 走 Inter + OC2 字号/字重），供 M4 Settings 等用 M3 组件的屏幕 `MaterialTheme(typography = OcFonts.materialTypography()) {…}` 显式采用；`OcTheme` 本体仍不引入 M3 配色。
  - `OcFonts.body()/emphasis()` 供自建 TextStyle 的调用方复用。
- `OcTheme.kt`（**纯新增，API 向后兼容**）：
  - `OcType` 尾部追加 `fontFamily: FontFamily = OcFonts.default`、`monoFontFamily: FontFamily = OcFonts.mono`（带默认值 → 现有位置参数构造不受影响）。
  - 新增 `LocalOcFontFamily`/`LocalOcMonoFontFamily`（staticCompositionLocalOf），`OcTheme.font` / `OcTheme.monoFont` 暴露；M4 字体选择器可直接覆盖 local。
  - `OcTheme(isDark) { }` 内额外把 `androidx.compose.material3.LocalTextStyle` 设为 `当前值.merge(TextStyle(fontFamily=Inter, fontWeight=440))` —— **裸 `Text("…")` 默认即 Inter/440**（M3 `Text` 读的就是这个 local）。**只给族+字重、不给 fontSize/lineHeight**：避免"接入字体"顺带重排既有屏幕版式（字号仍由 `OcType`/调用方掌控）。签名未改、无调用点需修改。
  - 注：`LocalTextStyle` 实体在 `androidx.compose.material3`（`ui-text` 包内**不存在**该符号，实测 `ui-text-1.7.6` classes.jar 无 `LocalTextStyle`）；首版误用 `androidx.compose.ui.text.LocalTextStyle` 已被编译门捕获并修正。

### 4.3 可变字重的 API 分层（记录取舍）
Compose 的 `Font(resId, weight)` 在 **API 28+** 走 `Typeface.create(typeface, weight, style)`，变量轴真实生效（440/530/610 有区别）；**API 26/27** 平台无法变化可变字体，落到默认 400 实例 → 440/530/610 视觉同 400。minSdk=26，故这是"API 26–27 略偏细"的已知降级，选它优于 +2.5 MB 静态子集。另：`opsz` 轴保持默认 14（≈13–15sp 正文区间），Compose 无逐 Text 光学尺寸接口，未做。
体积：+874 708 B APK（未子集化）；子集化（只留 UI 用到的字形）列为后续优化。

---

## 5. 生成资源实测
- aapt2 产出符号确认（隔离副本 `runtime_symbol_list/debug/processDebugResources/R.txt`）：`int string ui_promptInput_placeholder_normal`、`int string common_moreCountSuffix`、`int string ui_common_file_one`、`int string status_popover_tab_servers`、`int string provider_connect_opencodeZen_visit_prefix`、`int string ui_sessionReview_diffStyle_unified`、`int font inter_variable 0x7f080000`。
- 三语抽样（人工核对，确认 zh/zht **不是英文**）：en `ui_sessionReview_diffStyle_split`=Split / zh `并排视图` / zht `工作階段變更`（`ui_sessionReview_title`）；占位符 `限制：%1$s 行变更。当前：%2$s 行变更。`；保留空格 `"访问 "`。
- 编码：UTF-8 **无 BOM**（首字节 `3C 3F 78`），换行 CRLF。

---

## 6. 编译结果（必须项）

| 门 | 工作区 | 命令 | 结果 |
|---|---|---|---|
| **资源门 + Kotlin 门（最终）** | **真实工作区** | `.\gradlew.bat --offline :app:processDebugResources :app:compileDebugKotlin` | ✅ **BUILD SUCCESSFUL，0 error**（日志 `research/_i18n_font_compile.log`；定稿时序 attempt1 45 err → attempt2 21 err → attempt3 **0 err**，见下"复验提示 1"） |
| 资源门（单独复验） | 真实工作区 | `.\gradlew.bat --offline :app:processDebugResources` | ✅ **BUILD SUCCESSFUL**（日志 `research/_i18n_font_process_resources.log`） |
| Kotlin 门（归因用） | 隔离副本（`%TEMP%\oc_isolate`：全 res/font/i18n + `ui/theme/*` + 稳定根包，剔除并行 agent 在写的 `ui/*`） | `.\gradlew.bat --offline :app:processDebugResources :app:compileDebugKotlin` | ✅ **BUILD SUCCESSFUL**（17/17 executed，两次），用于证明"当时真实工作区的红线全在他人 `ui/*`，与本改动无关" |

真实工作区 R 符号（`app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt`，共 **1540** 条 string、**1** 条 font）：
```
int font inter_variable 0x7f080000
int string common_moreCountSuffix 0x7f1100b4
int string status_popover_tab_servers 0x7f1104f6
int string ui_common_file_one 0x7f11053b
int string ui_promptInput_placeholder_normal 0x7f110589
```

独立校验（`python` 扫 XML，三语互不冲突）：无同名重复、与既有 221 个手写名**零交集**、zh/zht 无"默认配置缺失"的孤儿 id、全部名匹配 `[A-Za-z][A-Za-z0-9_]*`、无游离 `%`。

> ⚠️ 复验提示 1（并行编辑窗口）：本报告定稿前后，协同 agent 仍在多轮编辑 `ui/App.kt`（新引入 `ui/util/*`、`ui/nav/NavRestore|NavMemory`、`SessionTabRef` 等尚未落盘的文件），工作区会随其编辑窗口在"红 45 错 → 红 21 错 → 绿"之间摆动，**与本次改动无关**。定稿这一次复跑时序：attempt1 45 err → attempt2 21 err → attempt3 **0 err / BUILD SUCCESSFUL**（日志已落盘 `research/_i18n_font_compile.log`）。若复验时又见 `ui/App.kt` 红线，请等其当轮收敛后重跑，而不要归因到 i18n/字体。
> ⚠️ 复验提示 2（不要 `--rerun-tasks`）：实测强制重跑 `mergeDebugResources` 会踩到增量合并残留（`merged.dir\values-az\values-az.xml` 不存在，来自依赖 AAR），报 "Resource compilation failed" —— 与源码无关；用普通增量命令重跑即恢复 `BUILD SUCCESSFUL`（本次已复现并恢复）。若确实要全量验证，建议 `clean` + 常规两门，且避开其他 agent 正在构建的窗口。
> 未跑 `assembleDebug`（任务口径只要上述两门；`processDebugResources` 已覆盖字体与全部字符串的打包）。

隔离副本复现命令（临时脚本已删除）：
```powershell
$src='<repo>'; $dst="$env:TEMP\oc_isolate"      # 复制 gradle 壳 + app/src（-Exclude assets）+ gradle wrapper
# 保留 根包 (去掉 OcActivity.kt，它 import ui.*) 与 ui/theme/* ，剔除 ui/ 其余目录
cd $dst; $env:JAVA_HOME='C:\Users\xiufe\AppData\Local\Temp\jdk21\unzip\jdk-21.0.12.1+1'
.\gradlew.bat --offline :app:processDebugResources :app:compileDebugKotlin   # BUILD SUCCESSFUL
```

---

## 7. 未做 / 后续清单
1. plural：`.one/.other` 与 `{{count}}` 未转 `<plurals>`（§3.7）。
2. 未展开的 317 键（§3.5）：`desktop/wsl/sound/debugBar` + Web 文件名标签 → `-Prefixes '*'` 一条命令补。
3. en 12 个 alias 值 + `drag.enter`/`mode.shell` 悬空值需人工写英文文案（§3.6）；zht 缺 7 个 server/settings 键。
4. 终端字体：需 JetBrains Nerd Font **TTF**（现只有 woff2）→ `OcTerminalFontFamily` 才能落地（M5）。
5. 字体子集化（-874 KB 优化）、`opsz` 轴、API 26–27 的 440/530 静态子集（若实机观感不可接受）。
6. `values-b+zh+Hant` 兜底（如需覆盖 HK/其他繁体区域）。
7. M4 Settings 字体/配色选择器可覆盖 `LocalOcFontFamily`（已留钩子）。

## 8. 遵循的约束
- 资源只**新增**：`values/strings.xml` 及既有 `oc_*`/plurals 一字未改，conflicts=0。
- Kotlin 只动 `ui/theme/OcTheme.kt`（纯新增）与新增 `ui/theme/OcFonts.kt`；未触碰任何业务逻辑/其他 agent 文件。
- 交付前已删除全部临时物：`res/values/zz_escape_probe.xml`、`research/_i18n_selftest/`、`scripts/_isolate_build.ps1`、`%TEMP%\oc_isolate`。
