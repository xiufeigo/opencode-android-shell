# RELEASE_SIZE_MEASURE.md — v152：44MB release 侧可减量实测

> **出证据，不出结论。** 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
> HEAD：`7b5692eb87b540537ae0bd72a5cdcfe7e94ce5a6`（工作区全程仅 `app/build.gradle.kts` 一处被临时改过，已按 §6 复原并验证）
> 环境：`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`；build-tools 36.0.0
> 锁：`.agent-gradle-lock`（项目无既有锁文件占用，10:49:26 获取，`rm --` 单条顶层释放，见 §6）
> 量法脚本：`research/_scratch_v152/apk_measure.ps1`（分项 + 归类）、`apk_reconcile.ps1`（字节预算闭合）、`apk_gaps.ps1`（间隙分布）。全部可复算。

---

## 0. 一句话实测结论

**44 MB 是 debug 产物的体积，不是 release 的体积。** 现状配置下的 release 是 **29,381,690 B**；再开 R8 + 资源压缩后是 **17,809,425 B**。
本轮最反直觉的一条：**所谓「7,426,218 B zipalign padding」在当前 APK 上复不出来（实测 7,221,234 B），且它是 debug 独有的——release 即便正确签名并 `zipalign -p 4`，padding 仍是 0。**

---

## 1. 基线：debug vs release 逐项体积

### 1.1 三个产物的总尺寸

| 产物 | 路径 | 字节 | 相对 debug |
|---|---|---:|---:|
| debug（现状） | `app/build/outputs/apk/debug/app-debug.apk` | **44,198,253** | — |
| release（现状配置，未签名） | `app/build/outputs/apk/release/app-release-unsigned.apk` | **29,381,690** | **−14,816,563（−33.52%）** |
| release + R8 + shrinkResources（本轮实测） | 同上路径（已另存 `research/_scratch_v152/app-release-R8.apk`） | **17,809,425** | **−26,388,828（−59.71%）** |

> release 无 `signingConfig`（`app/build.gradle.kts:40-46`），故 AGP 产出 `app-release-unsigned.apk`。**未改任何签名密钥**。

### 1.2 逐项分类表（单位：字节；占比对各自 file size）

| 分类 | debug 字节 | debug 占比 | debug 条目 | release 字节 | release 占比 | release 条目 |
|---|---:|---:|---:|---:|---:|---:|
| `classes*.dex` | 20,392,812 | 46.14% | 20 | 14,314,063 | 48.72% | 4 |
| `assets/` | 12,556,558 | 28.41% | 951 | 11,119,188 | 37.84% | 953 |
| **zipalign padding** | **7,221,234** | **16.34%** | — | **0** | **0%** | — |
| `resources.arsc` | 2,776,024 | 6.28% | 1 | 2,752,892 | 9.37% | 1 |
| `res/` | 757,961 | 1.71% | 851 | 756,303 | 2.57% | 851 |
| `other` | 76,428 | 0.17% | 13 | 76,712 | 0.26% | 14 |
| `lib/x86_64/*.so` | 10,760 | 0.02% | 1 | 10,760 | 0.04% | 1 |
| `META-INF/` | 10,374 | 0.02% | 92 | 10,472 | 0.04% | 90 |
| `lib/arm64-v8a/*.so` | 10,096 | 0.02% | 1 | 10,096 | 0.03% | 1 |
| `lib/x86/*.so` | 9,284 | 0.02% | 1 | 9,284 | 0.03% | 1 |
| `lib/armeabi-v7a/*.so` | 7,252 | 0.02% | 1 | 7,252 | 0.02% | 1 |
| `AndroidManifest.xml` | 2,550 | 0.01% | 1 | 2,488 | 0.01% | 1 |
| 中央目录 + EOCD + 各级 local header 合计 | 366,920 | 0.83% | — | 312,180 | 1.06% | — |
| **file size** | **44,198,253** | 100% | 1,933 条目 | **29,381,690** | 100% | 1,918 条目 |

**怎么量的**（`apk_measure.ps1`，不依赖 `unzip -l`/`apkanalyzer`）：
解析 ZIP **中央目录**取权威的 compressed size / local header offset，再逐条回读 **local header** 取真实 data offset（local extra 字段可能与 central 不同，故必须回读），最后按 offset 排序累加间隙。分类规则：`^classes\d*\.dex$` → dex；`^lib/([^/]+)/` → 按 ABI 分桶；`^res/`、`^assets/`、`^META-INF/` 前缀归类；其余入 `other`。

**正确性自证**（`apk_reconcile.ps1`）：字节预算必须闭合。

```
fileSize 44,198,253 = 数据 36,610,099 + local header 187,531 + padding 7,221,234 + 中央目录 179,367 + EOCD 22
RESIDUAL = 0        （release 同样 RESIDUAL = 0）
```

residual 为 0 ⇒ 上表每一个字节数都不是估算，**可逐项复算**。

> 注：debug 与 release 的 dex 条目数差 20 vs 4，**不是 R8 的功劳**（现状 release 根本没开 R8）——是 D8 在 release 下按 minSdk 26 做的 dexing/合并差异。这一点常被误读，本轮不展开（未做 dex 级归因，属 §7 未闭环）。

---

## 2. zipalign padding 复算

### 2.1 复算结果：**复不出 7,426,218 B**

| 项 | 先前结论 | 本轮实测 | 判定 |
|---|---:|---:|---|
| padding 字节 | 7,426,218 | **7,221,234** | **不一致，差 −204,984** |
| 占 file size | 16.4% | **16.338%** | 基本吻合 |

**结论：先前那个 7,426,218 B 在当前 debug APK（44,198,253 B）上不成立。** 两条可能原因，本轮无法区分（未闭环）：①该数取自更早的 APK 修订（`research/v98_RESUME.md` 记录 v98d 的 APK 是 44,198,562 B，与当前 44,198,253 B 本就不同）；②量法不同（例如把中央目录也计入）。

### 2.2 这 7,221,234 B 确实是 padding（三重证据）

1. **闭合**：§1.2 字节预算 residual = 0，说明这 7,221,234 B 不属于任何条目。
2. **内容为零**：直接抽读最大间隙（`classes19.dex` 之前 2,774,943 B）——**nonzero 仅 430 B，zero 2,774,513 B，99.98% 为 0**。在 `LHO−100000` / `LHO−1000000` / `LHO−2700000` 三处取样，32 B 全为 `00`。
3. **`zipalign -c -v 4` 通过**（debug 与 release 均 `Verification successful`, exit=0），即对齐本身是合法的。

**间隙分布**（`apk_gaps.ps1`，debug）：共 **55** 处间隙。
- `>16384 B`：12 处，7,166,042 B
- `4097–16384 B`：3 处，14,356 B
- `17–4096 B`：40 处，40,836 B
- **是 16384 整数倍的间隙：0 处** ⇒ **不是** 16 KB 页对齐 padding。

### 2.3 是否可回收：**release 侧根本不存在，无需回收**

对 release 做了对照实验（未改任何生产代码/密钥，只用既有 debug keystore 在 `_scratch_v152` 里签副本）：

| release 变体 | 字节 | padding |
|---|---:|---:|
| AGP 产出（unsigned） | 17,809,425 | **0** |
| + `apksigner sign`（v2/v3 签名块） | 17,975,012 | 0 |
| + `zipalign -p -f 4` | 17,967,064 | **0** |

签名+对齐净增 **+157,639 B**，**其中 0 B 是 padding**——对齐后 `lib/*/*.so` 的 data offset 仍恰好落在 16 KB 边界（实测 `arm64-v8a` dataStart=3,244,032，mod 16384 = 0），自然无需插填充。

**判定**：
- **release 侧不可回收，因为不存在**（实测 0 B，无论 unsigned 还是 signed+aligned）。
- debug 的 7,221,234 B **只影响 debug 产物**。debug 之所以有：它挂了 `sharedDebug` 签名配置，AGP 因此跑了 zipalign 步骤；release 无签名配置，AGP 跳过该步。
- **若仍要消掉 debug 的这 7.2 MB**：唯一途径是不签名 debug 或去掉对齐——但那会破坏 `.so` 直接 mmap（安装期需解包），**属负收益，不建议**，本轮未实施。

---

## 3. R8 实测（临时副本对照实验）

### 3.1 改动与前置

`app/build.gradle.kts` 的 `release {}` 块，**仅两行**：

```diff
         release {
-            isMinifyEnabled = false
+            isMinifyEnabled = true
+            isShrinkResources = true
             proguardFiles(
```

`app/proguard-rules.pro` = **83 字节 / 1 行**，内容仅为注释 `# Keep default Android optimize rules. Release minify is off for this thin shell.`
⇒ **没有任何 keep 规则**，R8 只跑 `proguard-android-optimize.txt` 默认集。**未为了让它通过而放宽规则。**

### 3.2 结果：**成功产出**

```
> Task :app:minifyReleaseWithR8
> Task :app:shrinkReleaseRes
> Task :app:convertShrunkResourcesToBinaryRelease
> Task :app:optimizeReleaseResources
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 1m 35s
118 actionable tasks: 18 executed, 100 up-to-date
```

无 missing-class 警告、无 keep 规则报错、无 R8 崩溃。**R8 与资源压缩两个任务都真实执行了**（日志见 `research/_scratch_v152/assemble_release_r8.log`；基线对照 `assemble_release_baseline.log`）。

> ⚠️ **BUILD SUCCESSFUL ≠ 运行时正确。** 本轮按授权范围**未装机**（禁装设备），R8 无 keep 规则下 Compose 反射 / kotlin metadata / `org.json` 反射路径存在运行期风险。**是否可发布仍需真机回归，不能仅凭本节判定。**

### 3.3 逐项体积对照（release 现状 → release+R8）

| 分类 | 基线字节 | R8 后字节 | 变化 | 变化率 |
|---|---:|---:|---:|---:|
| `classes*.dex` | 14,314,063 | **3,222,941** | **−11,091,122** | **−77.5%** |
| `resources.arsc` | 2,752,892 | 2,313,360 | −439,532 | −16.0% |
| `res/` | 756,303 | 726,623 | −29,680 | −3.9% |
| `assets/` | 11,119,188 | 11,114,558 | −4,630 | −0.04% |
| `other` | 76,712 | 76,727 | +15 | +0.02% |
| `META-INF/` | 10,472 | 10,486 | +14 | +0.13% |
| `AndroidManifest.xml` | 2,488 | 2,488 | 0 | 0% |
| **file size** | **29,381,690** | **17,809,425** | **−11,572,265** | **−39.39%** |
| `res/` 条目数 | 851 | 762 | **−89 条** | −10.5% |

**R8 实测减了多少：11,572,265 B（−39.39%）**，其中 **95.8% 来自 dex**（−11,091,122 B），dex 由 4 个文件合并为 **1 个**。
`assets/` 几乎不动（−0.04%）——R8 不碰资源字节，**资源侧的天花板在 `assets/`，不在 R8**。

---

## 4. baseline profile 现状：**没有**

| 检查项 | 结果 |
|---|---|
| `app/src/main/baseline-prof.txt` | **不存在** |
| 全仓 `baseline-prof*` 文件 | **0 个** |
| `app/build.gradle.kts` 里的 `baselineProfiles { }` 块 | **无**（release 块只有 5 行，见 §3.1） |
| `app/proguard-rules.pro` 相关引用 | 无 |

**但 APK 里确实有 profile**，这点必须说清，否则会被误读成"已有"：
```
assets/dexopt/baseline.prof     dataStart=388
assets/dexopt/baseline.profm    dataStart=6920
```
这两个是 **AGP 从依赖 AAR 自动汇总生成**的传递性 profile（`androidx.*` 等库自带），**不是本仓 authored 的 app 级 profile**。没有它不代表启动没有任何 profile，只是没有本 app 自己跑的。

### 4.1 加它需要的最小步骤（**本轮不实施**）

1. `app/build.gradle.kts` 的 `android {}` 增加 `baselineProfile { }` 块（AGP 8.x 需 `com.android.test` 插件 + `androidx.profileinstaller` 依赖，二者当前**都不在** `dependencies {}` 里）。
2. `androidx.baselineprofile` 依赖 + 对应 instrumentation test 模块。
3. 产 `app/src/main/baseline-prof.txt`（需 `MacrobenchmarkRule` + `BaselineProfileRule` 跑真机/模拟器）。
4. CI 加一步 `connectedReleaseAndroidTest` 采集并校验。
**代价**：新增一个 test 模块 + 真机基准任务。**注意：baseline profile 优化的是 ART 预编译/启动，**不会减少 APK 字节数**（除 `profm` 的 KB 级）。

---

## 5. 可减量清单 TOP 5（按可回收字节排序）

| # | 项 | 可回收字节 | 代价 | 风险 | 依据 |
|---|---|---:|---|---|---|
| **1** | **别拿 debug 当发布产物**（改发 release 构建） | **−14,816,563 B** | **零**（无需改任何配置） | 低 | 实测 44,198,253 → 29,381,690，§1.1。差距源于 D8 dexing 20→4 条目、`androidx.compose.ui:ui-tooling` 走 `debugImplementation` 随 release 被排除 |
| **2** | **开 R8 + `shrinkResources`** | **−11,572,265 B**（在 #1 基础上再省） | 2 行配置；`proguard-rules.pro` 需从零补 keep 规则 | **中—高**：构建已实测通过，但无 keep 规则 + 未装机验证，Compose/kotlin 反射路径有运行期风险 | 实测 BUILD SUCCESSFUL，§3.2/§3.3；逐项差表可复算 |
| **3** | **`assets/` 减载**（上限 11,111,458 B，占 R8 后 APK 的 62.4%） | **未测**（上限已知） | 需逐条可达性分析 | 中 | 构成实测：`introducing-tabs-*.mp4` 1,367,486；40 个 `woff2` 1,619,864；186 个 `.png` 484,650；26 个 `.aac` 149,078；`publicsuffixes.gz` 41,394。**哪几条运行时可达，本轮未查**——`app/src/main/**` 零改动，未做引用扫描 |
| **4** | **只保留 `arm64-v8a`**（`abiFilters` / ABI split） | **−27,296 B** | 一行 `abiFilters` 或出 split APK | 中：x86/x86_64 模拟器将失去 `libandroidx.graphics.path.so` | 4 个 ABI 合计仅 37,392 B（10,760+10,096+9,284+7,252），去掉 3 个 = 27,296 B。**收益极小，不建议为此动** |
| **5** | **baseline profile** | **0 B**（不缩包） | 新增 test 模块 + 真机基准 | 低 | §4。当前仅有 AAR 传递 profile，无 app authored profile |

**补充（不在 top5 但需记账）**：debug 的 7,221,234 B padding ——**不可回收于 release，且 debug 本就不该发布**（§2.3）。

**关于先前结论「104 处 STORED 条目」**：本轮实测 **debug 339 条 / release 338 条 / R8-release 255 条**，与 104 不符。STORED 里 `resources.arsc`(2,776,024) 是大头，其余为 `mp4`/`.aac`/`.png`/`.woff2` 等已压缩媒体。**该 104 同样未复现，记账为待核。**

---

## 6. 原位恢复验证（原样输出）

`app/build.gradle.kts` 复原过程与结论：
1. 改动前已备份 `research/_scratch_v152/build.gradle.kts.orig`，md5 `0157CA51FCCE208966F0854C50405ED0`。
2. 改回后与备份 md5 一致（`0157CA51…`），**但 `git diff --stat` 仍报 166 增 166 删**——因为编辑工具把行尾写成了 CRLF，而 HEAD 存的是 LF（`core.autocrlf=false`）。
3. 任务书禁用 `git checkout` 改行尾，故改用 `git cat-file blob HEAD:app/build.gradle.kts` 取出 HEAD 原始字节（`cmd` 重定向保字节，8,199 B / LF=166 / CRLF=0）直接写回，**全程未使用 git 回退/checkout**。

最终验证（原样输出）：

```
=== git diff --stat ===
(EMPTY)

=== git diff --exit-code --quiet ===
exit=0 (0 = no diff vs HEAD)

=== worktree blob : 76567bffab6aa5330f540893ed0ced46a51d2f37
=== HEAD blob     : 76567bffab6aa5330f540893ed0ced46a51d2f37
=== index blob    : 76567bffab6aa5330f540893ed0ced46a51d2f37
worktree == HEAD ? True
index    == HEAD ? True

=== release block ===
41: isMinifyEnabled = false
(isShrinkResources 已移除)

=== app/src ===
app/src: NO CHANGES
```

**结论：`git diff --stat` 为空，worktree / index / HEAD 三者 blob 哈希完全相同（`76567bf…`），`app/build.gradle.kts` 已与 HEAD 逐字节一致。**
`app/src/main/**` 零改动。全程未装设备、未推送、未改签名密钥、未执行全仓 `clean`。

> 已知噪声：`git status --porcelain` 仍打印一行 ` M app/build.gradle.kts`，但 `git diff --stat` 为空、`git diff --exit-code` 返回 0、三方 blob 哈希相同 ⇒ 这是 stat 缓存/时间戳粒度造成的显示假象，**内容无差异**。已用 `git update-index --really-refresh` 尝试刷新，该环境未清掉（Windows 文件时间戳粒度），非内容问题。

**锁**：`.agent-gradle-lock`，10:49:26 获取，实验全部跑完后用**单条顶层** `rm -- ".agent-gradle-lock"` 释放，输出 `mavis-trash: moved to trash: '.agent-gradle-lock'`，`Test-Path` 复核 `exists=False`。**未被本地硬安全策略拦截，无需绕过。**

> **交还后复核（10:55:49）**：`.agent-gradle-lock` 再次存在，但内容为
> `v153 SELFPROOF_REWRITE acquired 2026-10-04T10:54:49+08:00`，
> 获取时间**晚于**本轮释放时刻 ⇒ 属**另一 agent 已合法获取**，**本轮未触碰、未删除**，以免破坏他人持锁。该文件当前不属于 v152。

---

## 7. 未闭环 / 需下一轮

1. **7,426,218 B 与 104 STORED 条目均未复现**。已给出当前实测值（7,221,234 B / 339 条），但"先前数的来源修订与量法"未定位。
2. **debug 16.34% padding 的量级成因未 root-cause**。已证是零填充、已证非 16384 整数倍、已证 release 无此现象；但 2.7 MB 级单笔间隙由什么对齐需求产生，未查明。
3. **R8 的运行时正确性未验证**（按授权未装机）。上生产前需真机回归，重点查反射/序列化路径。
4. **`assets/` 11.1 MB 的可达性未分析**，故 top5 #3 标"未测"而非给数。
5. **dex 20→4 条目差异未做归因**（非 R8 功劳，但机理未查）。
6. R8 后的 dex **未做规则覆盖率分析**（`-whyareyoukeeping`），keep 缺口清单待出。

---

## 附：本轮产物

| 文件 | 说明 |
|---|---|
| `research/_scratch_v152/apk_measure.ps1` | 分项 + 归类测量器（可对任意 APK 复用） |
| `research/_scratch_v152/apk_reconcile.ps1` | 字节预算闭合校验（本报告正确性自证） |
| `research/_scratch_v152/apk_gaps.ps1` | 间隙分布 / 16384 整倍数判定 |
| `research/_scratch_v152/*_breakdown.csv` | debug / release / R8 三份分类明细 |
| `research/_scratch_v152/assemble_release_baseline.log` | 基线 `assembleRelease` 全文（BUILD SUCCESSFUL in 1m 57s） |
| `research/_scratch_v152/assemble_release_r8.log` | R8 对照实验全文（BUILD SUCCESSFUL in 1m 35s） |
| `research/_scratch_v152/app-release-R8.apk` | R8 后 APK 留存（17,809,425 B） |
| `research/_scratch_v152/R8-signed.apk` / `R8-signed-aligned.apk` | 签名/对齐对照，证明 release padding = 0 |
| `research/_scratch_v152/build.gradle.kts.orig` | 改动前备份（md5 `0157CA51…`） |
