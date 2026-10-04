# GAP_CLOSE_G3 —— 收尾轮：关掉最后两条还关得掉的缺口

- **执行日期**：2026-10-05（硬期限 2026-10-07 之前 ✅）
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`，分支 `main`
- **上一轮**：`research/web/GAP_CLOSE_G2.md`（Gap#4 = IME 重叠量、Gap#3 = debug 体积逐 entry 明细）
- **写权限**：`research/**`（本轮只写 `research/web/GAP_CLOSE_G3.md` + `research/env/e7_g3_phone.png` + `research/env/logs/e7_g3_phone.log`）
- **未碰**：`app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`
- **后端**：`127.0.0.1:4095` 全程**未起停**（Lead 托管），本轮只做 `adb reverse`

---

## 0. 一句话结论

| # | 缺口 | 状态 | 结论 |
|---|---|---|---|
| 1 | IME 重叠量（上一轮没测到） | ✅ **已闭环** | **`OVERLAP_PX = 0`**，`mInputShown=true` 断言 PASS，原始行 `InsetsSource id=3 type=ime frame=[0,1517][1080,2400]`，composer `[454,514]` vs IME `[577.9,914.3]`，余量 **+63.9 CSS px** |
| 2 | debug 8.64 → 5.15 MB 逐 entry 明细 | ⚠️ **派单前提被证伪 / 换证法闭环，未按原样闭环** | **旧 APK 二进制在本仓既不在 git 也不在磁盘**，逐 entry 对比**物理上不可能**。改交：① 唯一可取回的 git 历史 APK 的**真实逐 entry TOP15**；② 新 APK 体积构成与 **3.49 MB 的可落位上界证明**；③ **debug 构建配置在 b19fe52↔f989907 间逐行相同**的证明 |

> ⚠️ **纪律声明**：缺口 2 **没有**按派单字面要求闭环。派单的前提
> 「旧 APK 在 git 历史里，`git log --all -- research/deliver/opencode-shell-web.apk` 能找到 8,636,901 B 那一版」
> **经实测为假**（证据见 §2.1）。下文 §2.4 给出了三条独立证据链把这个缺口**换成可交付的结论**，
> 但**「8,636,901 B 那个 APK 的逐 entry TOP15」这一具体产物无法产出**，
> 原因只有一个：**那个二进制已经不存在于任何可及介质上**。**没有用旧数顶替、没有把"没测到"写成"通过"。**

---

## 1. Gap：IME 重叠量（上一轮卡在"进不去会话页"）

### 1.1 排障：逐条证据（先排障，再测量）

派单要求逐条给证据、不许急着断言。5 条全部**通过**，且**第一次跑就通**。

#### ① `adb reverse --list` 有 4095 映射吗？

```
$ adb -s emulator-5920 reverse tcp:4095 tcp:4095
4095
$ adb -s emulator-5920 reverse --list
host-16 tcp:4095 tcp:4095
```

✅ **有映射**。（脚本内也会重下一次，幂等。）

#### ② 设备侧能否取到后端？

```
$ adb -s emulator-5920 shell curl -s -o /dev/null -w 'HTTP=%{http_code}\n' --max-time 8 http://127.0.0.1:4095/
HTTP=200
```

✅ **设备侧 HTTP 200**。这是上一轮最可能缺的一环。

#### ③ 口令是否已播种进 `SecretStore`（`passwordPresent=true`）？

```
I OcWebHost: home=http://127.0.0.1:46087/ backend=http://127.0.0.1:4095 passwordPresent=true
```

✅ **`passwordPresent=true`**。口令已在 `SecretStore`（`EncryptedSharedPreferences`）里，
**本轮没有、也不需要**手工播种。

#### ④ 代理是否起来了？

```
I OcWebHost/Proxy: listening on http://127.0.0.1:46087 -> http://127.0.0.1:4095
I OcWebHost: home=http://127.0.0.1:46087/ backend=http://127.0.0.1:4095 passwordPresent=true
I OcWebHost/Proxy: GET http://127.0.0.1:4095/
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/rolldown-runtime-hePW80VL.js
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/index-Dadcbstg.js
I OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/desktop-native-vJFY6KWL.js
```

✅ 代理在 `127.0.0.1:46087` 上监听，**实测转发成功**（200 系列资源 GET）。

#### ⑤ 前置身份检查：**是官方 UI，不是配置框/兜底页**

CDP 读回（脚本 `=== NAV ===` 步）：

```
=== NAV: {"title":"OpenCode","n":16,
         "sample":"O\nCounting from 1 to 4000, one number pe",
         "hasTextarea":0}
```

- `document.title = "OpenCode"` ✅ 官方标题
- 会话列表命中 **16** 行，抽样首行文本是**真实业务内容**（`Counting from 1 to 4000, one number pe…`）✅
- 不是配置框、不是 fallback 页（fallback 页标题/文本是 `readable failure page` 那一套，见 commit `5b7a572`）

⇒ **前置身份检查 PASS**。这是上一轮"进不去会话页"的直接判据，本轮**一次通过**。

> **一处真实的环境故障（不是产品缺陷），顺手记下来**：
> 首次执行 `ime_repro_e7.ps1` 时报
> `ParserError / MissingEndParenthesisInFunctionParameterList`。
> 根因：该脚本是 **UTF-8 无 BOM**，而 Windows PowerShell 5.1（`powershell.exe`）按 **GBK** 读它，
> 中文注释被解成乱码、把引号吃掉了。**改用 `pwsh`（PowerShell 7，默认 UTF-8）即通过，脚本一行未改。**
> ⇒ **上一轮"进不去会话页"的性质是流程/环境问题，不是方法不可行** —— 与派单"本仓已知事实"的判断一致。

### 1.2 测量（脚本 `research/env/ime_repro_e7.ps1 -Tag g3 -Geom phone`）

复用**本仓已验证可行**的原子取证脚本（`IME_OCCLUSION_FIX.md` §2 记录的方法），纪律照旧：
**读任何 ime 数值之前**先断言 `mInputShown=true`。

#### 触碰前（IME 未起）

```
{"dpr":2.625,"innerW":412,"innerH":915,"visualViewportH":915,"vvOffsetTop":0,
 "imeVar":"","safeVar":"",
 "edTag":"div","edAria":"Prompt",
 "ed":{"x":21,"y":790,"w":370,"h":60,"top":790,"bottom":850}}
```

composer 找到（`form.group/composer` 高 `104`，可编辑区 `[790,850]`），未退化。

#### 真实手指 tap 聚焦

```
=== TAP device(541,2152) from CSS(21,790) dpr=2.625 ===
```

#### **[1] 断言 `mInputShown`（读任何 ime 数值前必做）**

```
  mInputShown=mInputShown=true
  mServedInputConnection=mServedInputConnection=RemoteInputConnectionImpl{connection=WV.QT@5d8af99
    mDeactivateRequested=false mServedView=android.webkit.WebView{2949c40 VFEDH.C.. .F...... 0,0-1080,2400 aid=1073741824}}
  ASSERT PASS
```

✅ **`mInputShown=true`**，且 IME 被服务给了 **WebView 本身** ⇒ 焦点确实落在 composer 上。

> 附带观察（无害）：`dumpsys` 行伴随 `Failed to write while dumping service …: Broken pipe`。
> 属 dumpsys 输出管道被 `grep -m1` 提前关闭所致，**读数本身完整可用**（后续 `type=ime frame` 行同样带这句，行内容完整）。

#### **[2] `type=ime frame=` 原始行（派单要求抄原始行）**

```
InsetsSource id=3 type=ime frame=[0,1517][1080,2400] visibleFrame=[0,1517][1080,2400] visible=true flags= sideHint=BOTTOM boundingRects=null
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 63) mInsetsHint=Insets{left=0, top=0, right=0, bottom=883}}
  ime frame top=1517 bottom=2400 height=883px  => CSS imeTop=577.9
```

#### IME 弹起后

```
{"dpr":2.625,...,"imeVar":"336.38px","safeVar":"24.00px",
 "ed":{"x":21,"y":454,"w":370,"h":60,"top":454,"bottom":514},
 chain:[... {"tag":"form","cls":"group/composer …","h":104→440,"top":454,"bottom":894} ...]}
```

抬升链路在线：`form` 的 `padding-bottom` 生效 ⇒ 高 `104 → 440`（**+336 = ime 高**），composer `[790,850] → [454,514]`。

#### **[3] 重叠像素量**

```
  composer(css) top=454 bottom=514
  ime(css)     top=577.9 bottom=914.3
  innerH before=915 after=915   visualViewportH=915 vvOffsetTop=0
  --oc-ime-bottom = 336.38px   (CSS px)
  OVERLAP_PX = 0     (目标 0)
  clearance (imeTop - composerBottom) = 63.9 CSS px
  composerFullyVisible = True
=== SUMMARY g3/phone OVERLAP_PX=0 ===
```

### 1.3 结论 + 自洽性交叉验证

- **IME 重叠量 = 0**（目标 0，达成）
- 余量 **+63.9 CSS px**，composer **整条**在键盘上方
- 交叉验证：`--oc-ime-bottom = 336.38px × dpr 2.625 = 883`，与 `type=ime frame` 高 `2400 − 1517 = 883` **逐像素相等**
  ⇒ 宿主给的 inset 与实测 frame 自洽，抬升量来自**同一个** inset 通道，没有第二真值来源
- 与 `IME_OCCLUSION_FIX.md` §5.1 记录的改后读数（`composer [454,514]` / `OVERLAP_PX=0` / 余量 `+63.9`）**逐项复现**

**是否闭环：✅ 是。** 本轮**独立实测**得到该值，不是引用旧数。

**截图**：`research/env/e7_g3_phone.png`（208,773 B）
**完整日志**：`research/env/logs/e7_g3_phone.log`

---

## 2. Gap：debug 8,636,901 → 5,145,604 B（−3,491,297 B）的逐 entry 明细

### 2.1 派单前提被证伪：旧 APK 不在 git 里（原始输出）

派单给的方法：`git log --all --oneline -- research/deliver/opencode-shell-web.apk`。
**实测返回空**：

```
$ git log --all --oneline -- research/deliver/opencode-shell-web.apk
（空）

$ git ls-files research/deliver
（空 —— 该目录整个未被跟踪）

$ git log --all --oneline -- '*.apk'
6a632b8 Embed official OpenCode V2 frontend (Route B)
1386d6f Add debug APK for sideload on Xiaomi Pad
```

`1386d6f` 的那版：

```
$ git ls-tree -r -l 1386d6f | Where-Object { $_ -match '\.apk' }
100644 blob fec532b3f8766e3c58b1486d61655f13da0351f1 7158134	dist/opencode_shell_debug.apk
```

⇒ **git 历史里唯一的 APK 是 7,158,134 B / `dist/opencode_shell_debug.apk`（2026-09-16），
在 `6a632b8` 被删。不是 8,636,901 B，也不是 `research/deliver/` 下的。**

根因（`.gitignore`）：

```
*.apk
```

⇒ `research/deliver/opencode-shell-web.apk` 一直被 `*.apk` 规则忽略，**从来没进过版本库**。

**全对象按体积搜（不限路径）**：

```
$ git cat-file --batch-all-objects --batch-check='%(objectname) %(objecttype) %(objectsize)' \
    | Where-Object { $_ -match ' blob (\d+)$' -and [int64]$Matches[1] -ge 8400000 -and [int64]$Matches[1] -le 8900000 }
（空）

$ ... | Where-Object { $_ -match ' blob 8636901$' }
（空）
```

**磁盘搜（仓库全树 + `~/.gradle`，按体积与 md5 双条件）**：

```
=== disk search for any 8636901-byte apk (repo + webapp build + gradle) ===
（无匹配）
```

md5 目标 `8dcffc572ad469f472f61594f12ea990` 亦无匹配。
该体积只以**文字**形式存在于报告里（`CLEANUP_C1.md:261`「`:webapp` `assembleDebug` → `webapp-debug.apk` **8,636,901 bytes**」、
`RELEASE_DEVICE_VERIFY.md:40`「`SIZE=8636901 MD5=8dcffc572ad469f472f61594f12ea990`」）。

⇒ **结论（旧 APK 侧）**：那个二进制**在本机与本仓历史里都不存在**。
`git cat-file` 取不回来、磁盘上没有。**逐 entry 对比在物理上不可能。**

### 2.2 换证法 A：新 debug APK 的逐 entry 构成（`5,145,604 B`，definitive）

被测物：`research/deliver/opencode-shell-web.apk`，`md5 b716cbeb4c66e55fcb0f497640ae5e0c` ✅ 与派单一致。
**402 个 entry**，`sumCompressed = 5,070,423`，`sumUncompressed = 12,472,315`（与文件差 75,181 B = zip 头/中央目录/对齐）。

**TOP 15 entry（按压缩字节降序）**：

| # | Entry | Uncompressed | Compressed | 占文件 |
|---|---|---|---|---|
| 1 | `classes.dex` | 10,745,744 | **4,112,610** | 79.9% |
| 2 | `classes5.dex` | 834,660 | **373,061** | 7.3% |
| 3 | `resources.arsc` | 262,024 | **262,024** | 5.1% |
| 4 | `classes2.dex` | 124,680 | 37,754 | 0.7% |
| 5 | `classes4.dex` | 105,356 | 50,065 | 1.0% |
| 6 | `classes3.dex` | 56,632 | 26,291 | 0.5% |
| 7 | `okhttp3/internal/publicsuffix/publicsuffixes.gz` | 41,394 | 41,394 | 0.8% |
| 8 | `kotlin/kotlin.kotlin_builtins` | 18,661 | 5,257 | 0.1% |
| 9 | `assets/www/index.html` | 16,000 | 6,683 | 0.13% |
| 10 | `assets/www/oc-host.js` | 13,804 | 5,372 | 0.10% |
| 11 | `AndroidManifest.xml` | 6,112 | 1,784 | — |
| 12 | `kotlin/collections/collections.kotlin_builtins` | 3,685 | 1,521 | — |
| 13 | `res/layout/abc_search_view.xml` | 3,472 | 1,101 | — |
| 14 | `kotlin/ranges/ranges.kotlin_builtins` | 3,399 | 1,292 | — |
| 15 | `assets/www/oc-perf.js` | 3,307 | 1,594 | — |

**按顶层分组**：

| 组 | N | Uncompressed | Compressed |
|---|---|---|---|
| **dex（5 个文件）** | 5 | **11,867,072** | **4,599,781（占 89.4%）** |
| `resources.arsc` | 1 | 262,024 | 262,024 |
| `okhttp3/internal` | 2 | 41,612 | 41,543 |
| `res/*`（drawable/layout/anim/color…） | 253 | ~161,000 | ~114,000 |
| **`assets/www/*`（4 个文件）** | 4 | **36,045** | **15,246（占 0.30%）** |
| `kotlin/*_builtins` | 3 | 25,745 | 8,075 |
| 其它（manifest 等） | 134 | — | — |

**两个决定性事实**：

1. **`assets/www/` 整棵子树只有 15,246 B 压缩（占 0.30%）。**
2. **APK 里没有 `lib/` 目录，一个 `.so` 都没有。**

⇒ **前端资产在 APK 里几乎不存在**。这与运行时观察一致：官方 UI 是**代理转发**的，
logcat 里所有资源都从后端取（`OcWebHost/Proxy: GET http://127.0.0.1:4095/_assets/index-Dadcbstg.js`），
APK 内只放注入层/兜底页/探针那 4 个小文件。

### 2.3 换证法 B：3.49 MB 的**可落位上界**证明

设旧 = 8,636,901 B，新 = 5,145,604 B，差 **Δ = 3,491,297 B**。

| 候选落位 | 新 APK 里该类别的全部体积 | 能否解释 3,491,297 B |
|---|---|---|
| `assets/www/**`（前端资产） | 15,246 B | ❌ **差 229 倍** |
| `lib/**`（原生 .so） | **0 B（不存在该目录）** | ❌ 无此来源 |
| `res/**` | ~114,000 B | ❌ 差 30 倍 |
| `resources.arsc` | 262,024 B | ❌ 差 13 倍 |
| **`classes*.dex`（字节码）** | **4,599,781 B** | ✅ **唯一可能的落位** |

⇒ **这 3.49 MB 只可能在 dex（Kotlin/Java 字节码 + 被打包进来的库）里，
不可能在前端资源、原生库或资源表里。** 这是一个**上界证明**，不依赖旧 APK 本身。

> 旁证（`RELEASE_DEVICE_VERIFY.md` §5.2 自己记的）：release 与 debug 的
> `res/`「条目数与体积**逐条相同**，341 entries / 226,753 B，**没有**资源压缩/shrinking」，
> `assets/www/*`「逐条完全相同」。⇒ 该报告当时就已经测到**体积差主要在 dex**，
> 与本轮独立算出的上界一致。

### 2.4 换证法 C：构建配置逐行对照 ⇒ **没有任何 `webapp/**` 改动能解释这 3.49 MB**

8,636,901 B 那次构建的基线是 **`b19fe52`**（`CLEANUP_C1.md` 抬头：「HEAD `b19fe52`」，
日期 2026-10-04 21:12）；现在的交付件对应 **`f989907`**（2026-10-05 01:28）。

- 体积自洽：`af59924` 的 commit message 自己写「**8.2 MB APK**」，
  而 8,636,901 B = 8.24 MiB ⇒ **`af59924` 那一版就是 8.64 MB 那个构建**。

`git diff af59924 f989907 -- webapp/build.gradle.kts` 的**全部**改动只有两块：

```
+ release {                       # ← 只动 release 变体
+     val r8 = providers.gradleProperty("webapp.r8").orNull == "true"
+     isMinifyEnabled = r8
+     isShrinkResources = r8
      signingConfig = signingConfigs.getByName("sharedDebug")
+     applicationIdSuffix = ".debug"
+ }
+ tasks.withType<...Test>().configureEach { listOf(...).forEach { inputs.file(...) } }
```

**关键：debug 变体一个字没动。** 逐项核对：

| 项 | `af59924`（≈8.64 MB） | `f989907`（5.15 MB） | 变了？ |
|---|---|---|---|
| `minSdk / targetSdk / compileSdk` | 26 / 35 / 35 | 26 / 35 / 35 | ❌ 否 |
| `dependencies` 全部 7 条 | core-ktx 1.15.0 / appcompat 1.7.0 / activity-ktx 1.9.3 / webkit 1.12.1 / security-crypto 1.1.0-alpha06 / okhttp 4.12.0 / coroutines-android 1.8.1 | **逐字相同** | ❌ 否 |
| debug `isMinifyEnabled` | `false` | `false` | ❌ 否 |
| R8 / `isShrinkResources`（debug） | 无 | 无（只在 release，且默认 `r8=false`） | ❌ 否 |
| signingConfig / applicationIdSuffix | 同 | 同 | ❌ 否 |

⇒ **`webapp/**` 里 8 次提交（`182130d`→`f989907`）没有任何一次动过 debug 产物的构建输入**。
这些提交做的是：注入层加 IME 抬升 JS（`767f85d`）、兜底页（`5b7a572`）、输入延迟取证（`8d0acc7`）、
release 变体 + R8 开关（`cd088b3`）、release 验证（`ed96503`）、通知跳转 + 断点（`f989907`）
—— 这些**没有一个**能改变 debug dex 的体积量级（JS 改动加起来 < 40 KB，且它们是 `assets/www/*`）。

⇒ **诚实结论：这 3.49 MB 不能归因于 `webapp/**` 的任何一次改动。**
最可能的真实成因是**构建时依赖解析结果不同**（本地 Gradle 缓存中同名 artifact 的实际内容/版本不同，
或旧那次构建的 multidex 分片落点不同 —— 注意 `RELEASE_DEVICE_VERIFY.md` 记录的那版 debug
`classes.dex` 也是 **10,745,744 B，与今天逐字节相同**，但当时只报 `classes2/3/4` 共 260,412 B、
**没有 `classes5.dex`**，而今天有 `classes5.dex` 834,660 B），
**属于构建环境差异，不是本仓源码改动。** 本轮**没有**证据能进一步定位到具体 artifact，
**不编造 commit**。

### 2.5 换证法 D：唯一可取回的历史 APK 的**真实逐 entry 对比**（附带，非 8.64 MB 那一版）

既然 git 里唯一能取回 APK 的 commit 是 `1386d6f`（7,158,134 B），就把它的
`dist/opencode_shell_debug.apk` 取回来，与今天的 5,145,604 B 做**真实**逐 entry diff。

```
$ git cat-file -p 1386d6f:dist/opencode_shell_debug.apk > $env:TEMP\g3_old.apk
extracted bytes = 7158134      md5 = FA52818B4DA9CDF1BA4DF20C09D422DE      zip magic = 80,75   # 合法 ZIP
```

- 旧：`entries = 867`，`sumCompressed = 6,989,410`，`sumUncompressed = 15,989,631`
- 新：`entries = 402`，`sumCompressed = 5,070,423`，`sumUncompressed = 12,472,315`
- 状态计数：**`REMOVED: 473` / `KEPT: 394` / `ADDED: 8`**

**逐 entry 差异 TOP 15（按 |Δ压缩字节| 降序）**：

| # | Entry | 状态 | 旧压缩 | 新压缩 | **Δ压缩** | Δ未压缩 |
|---|---|---|---|---|---|---|
| 1 | `classes5.dex` | KEPT | 2,177,061 | 373,061 | **−1,804,000** | −5,117,912 |
| 2 | `classes.dex` | KEPT | 3,333,908 | 4,112,610 | **+778,702** | +2,883,720 |
| 3 | `resources.arsc` | KEPT | 1,035,696 | 262,024 | **−773,672** | −773,672 |
| 4 | `classes2.dex` | KEPT | 124,427 | 37,754 | −86,673 | −360,448 |
| 5 | `classes4.dex` | KEPT | 2,349 | 50,065 | +47,716 | +100,860 |
| 6 | `okhttp3/internal/publicsuffix/publicsuffixes.gz` | **ADDED** | — | 41,394 | **+41,394** | +41,394 |
| 7 | `classes3.dex` | KEPT | 14,930 | 26,291 | +11,361 | +24,204 |
| 8 | `assets/www/index.html` | **ADDED** | — | 6,683 | +6,683 | +16,000 |
| 9 | `assets/www/oc-host.js` | **ADDED** | — | 5,372 | +5,372 | +13,804 |
| 10 | `META-INF/androidx/constraintlayout/constraintlayout-core/LICENSE.txt` | **REMOVED** | 3,563 | — | −3,563 | −10,175 |
| 11 | `assets/www/oc-preflight.js` | **ADDED** | — | 1,597 | +1,597 | +2,934 |
| 12 | `assets/www/oc-perf.js` | **ADDED** | — | 1,594 | +1,594 | +3,307 |
| 13 | `res/layout/mtrl_search_view.xml` | **REMOVED** | 1,125 | — | −1,125 | −3,424 |
| 14 | `res/layout/mtrl_picker_header_fullscreen.xml` | **REMOVED** | 1,002 | — | −1,002 | −2,800 |
| 15 | `res/layout/activity_main.xml` | **REMOVED** | 961 | — | −961 | −2,704 |

**这组 diff 独立印证了 §2.3 的结论**：体积差**绝大部分在 dex**
（TOP1+TOP2+TOP4+TOP5+TOP7 五项 dex 合计 Δ ≈ **−1,053,282 B**），
`resources.arsc` 次之（−773,672 B，**这一版是原生壳，带 Material/AppCompat 全套资源**），
`assets/www/*` 全部是 **ADDED**、合计仅 **+15,246 B**（web 壳引入的新东西，**不是减小项**）。
REMOVED 的 473 项绝大多数是原生壳的 `META-INF/**/LICENSE.txt` 与 Material `res/layout/*`（单项均 < 4 KB）。

> ⚠️ **必须说清的边界**：这一组是 **7,158,134 B（2026-09-16 原生壳 `dist/` APK）↔ 5,145,604 B（web 壳 debug）**，
> **不是**派单要的 **8,636,901 B ↔ 5,145,604 B**。两者模块不同（原生壳 vs web 壳）、
> 相隔近一个月，**不能**用它回答"3.49 MB 差在哪"。它只作为**独立佐证**存在。

### 2.6 结论 · 是否闭环

- **派单要的「8,636,901 B 那个 APK 的逐 entry TOP15」：❌ 无法产出** —— 二进制不在 git、不在磁盘（§2.1 三条证据链）。
  **不拿 7,158,134 B 那一版冒充，不拿旧数顶替。**
- **「3.49 MB 差在哪些文件/目录」：✅ 已答** —— **只可能在 dex**（`classes*.dex`），
  由 §2.3 的上界证明给出（`assets/www` 15,246 B / 无 `lib/` / `res` ~114 KB / `arsc` 262 KB 全部差 13–229 倍），
  并由 §2.5 的真实 diff 独立佐证。
- **「是哪一次改动造成的（给 commit）」：❌ 证否，不是「找不到」。**
  `af59924`（8.2 MB 那版）↔ `f989907`（今天）之间，`webapp/build.gradle.kts` 的
  **debug 构建输入逐行相同**（deps 7 条逐字一致、minSdk 26/35/35 一致、debug 未开 R8），
  中间 8 次 `webapp/**` 提交**没有一次能解释 3.49 MB**。
  ⇒ 归因指向**构建/依赖解析环境差异**（最强线索：`classes.dex` 未压缩体积 **10,745,744 B 逐字节未变**，
  但 `classes5.dex` 834,660 B 在今天存在、在 `RELEASE_DEVICE_VERIFY.md` 记录的那版里不存在 ⇒ **multidex 分片落点不同**）。
  **本轮无证据定位到具体 artifact，不编造 commit。**
- **本缺口状态：⚠️ 换证法闭环（结论已给出），但未按派单字面形式闭环。**

---

## 3. 收尾复核输出（`HOUSEKEEPING.md` 规矩一「谁起谁关」+ 抄复核输出）

### 3.1 我碰过的 serial

| serial | AVD | 端口 | 镜像 | 谁起的 | 处置 |
|---|---|---|---|---|---|
| `emulator-5920` | **`OC_Deliver_R2`**（`OC_*` ⇒ 我的） | 5920（≥5920 偶数，起前查过 5920–5938 全 FREE） | `android-35;google_apis_playstore_tablet` + `pixel_tablet` | **本轮 G3 自建** | ✅ **已关** |

**本轮只碰过 `emulator-5920` 这一个 serial。** 轮次开始时 `adb devices` 为空、
emulator/qemu 进程数 = 0 ⇒ **没有任何用户设备可被波及**。
未碰任何 `MCRemote_*` / `DSH_*` / `T7x_*` / `Medium_Desktop_B` / 来源不明实例。
未复用 `OC_Deliver_R2` 的 AVD 定义做别的用途，仅本轮 boot 一次。

### 3.2 杀前身份确认（判据 = AVD 名，`HOUSEKEEPING.md` 规矩 2）

```
=== PRE-KILL identity check (must be MINE: OC_* AVD) ===
pid=55476 avd=OC_Deliver_R2 port=5920 MINE=True
pid=14044 avd=OC_Deliver_R2 port=5920 MINE=True
```

### 3.3 几何复位（先 reset 再 kill）

```
$ adb -s emulator-5920 shell wm size reset
$ adb -s emulator-5920 shell wm density reset
Physical size: 2560x1600
Physical density: 320
```

✅ **已无 `Override` 行**，回到物理面板原值。

### 3.4 关闭 + 复核输出

```
=== KILL (by serial only) ===
$ adb -s emulator-5920 emu kill
OK: killing emulator, bye bye
OK

=== VERIFY 1: residual procs for OC_Deliver_R2 ===
OC_Deliver_R2 residual count = 0

=== VERIFY 2: all emulator/qemu procs on host ===
total emulator/qemu process count = 0

=== VERIFY 3: port 5920 LISTEN count ===
LISTEN count = 0
all states on 5920:
  TimeWait OwningProcess=0

=== VERIFY 4: adb devices (no kill-server) ===
List of devices attached
（空）

=== VERIFY 5: gradle lock ===
.agent-gradle-lock exists = False

=== VERIFY 6: backend 4095 untouched ===
  4095 LISTEN pid=3748 (Lead-owned, not started/stopped by me)
```

**逐条对照 `HOUSEKEEPING.md`**：

| 规矩 | 本轮执行情况 |
|---|---|
| 1 谁起谁关 + 抄复核输出 | ✅ 起 `emulator-5920`，已 `emu kill`，复核输出已抄（§3.4） |
| 1 复核①进程里不再有该 AVD | ✅ `OC_Deliver_R2 residual count = 0`；全机 emulator/qemu 进程数 = **0** |
| 1 复核②`Get-NetTCPConnection -LocalPort 5920 -State Listen` 应为空 | ✅ `LISTEN count = 0`（仅剩 `TimeWait OwningProcess=0` 残根，与历史轮次同模式） |
| 2 只关自己起的（按 AVD 名） | ✅ 杀前 `MINE=True` 确认 AVD 名匹配 `OC_*`；**未碰**任何 `MCRemote_*`/`DSH_*`/`T7x_*`/`Medium_Desktop_B` |
| 3 不许 `adb kill-server` | ✅ **未执行**；收尾 `adb devices` 正常返回 |
| 4 不许 `-a` / 全量 kill / 通配符 | ✅ 只对 **`emulator-5920`** 单个 serial 发过 kill；进程筛选用 `-avd\s+OC_Deliver_R2` 精确匹配 |
| 5 故意保留需声明 | ✅ **无故意保留**。AVD **定义**（`OC_Deliver_R2`）仍留在磁盘，与 `HOUSEKEEPING.md` §四 待产品负责人决定的那批一致，本轮**不删**（删 AVD 定义不在派单范围，且 §四 已列为待决） |

**后端**：`127.0.0.1:4095` 收尾仍在 `LISTEN pid=3748`，**本轮 0 次启停**，只做过 `adb reverse`。

---

## 4. 未闭环清单（如实列出）

1. **缺口 2 的字面产物**：「8,636,901 B 旧 debug APK 的逐 entry TOP15」**无法产出** ——
   该二进制不在 git 历史（`research/deliver/` 未跟踪 + `*.apk` 被 gitignore）、
   不在磁盘（全树 + `~/.gradle` 按体积与 md5 双条件均无匹配）。
   **闭环需要什么**：一份 8,636,901 B / `md5 8dcffc572ad469f472f61594f12ea990` 的 `webapp-debug.apk` 副本
   （旧 CI 产物、构建机、或重新在能复现旧依赖解析的环境里构建）。
2. **3.49 MB 的具体 artifact 归因**未定位到根因级别。已知：不在前端资产/原生库/资源表（§2.3 上界证明）、
   debug 构建输入未变（§2.4）。**未知**：是哪个依赖解析结果不同导致的 dex 变化。
   **闭环需要什么**：在能复现 8.64 MB 依赖解析的环境重建一次 `assembleDebug`，或旧构建的
   `build/` 中间产物（`dex` 分片清单、依赖树快照）。
3. **旧 APK 侧的一处未解释现象**：`RELEASE_DEVICE_VERIFY.md` 记录的 debug 侧
   `classes.dex` = **10,745,744 B**（与今天**逐字节相同**），但当时只报 `classes2/3/2` 共 260,412 B、
   **无 `classes5.dex`**；今天 `classes5.dex` = 834,660 B 存在。
   ⇒ 那一版与今天**不是同一个 APK**，但其体积（当时记为 8,636,901 B）被当成了同一个。
   **本轮如实记录这个矛盾，不代其它轮次下结论。**
4. **平板几何（1366×768@160）侧本轮未复测**。派单只要求手机几何 1080×2400@420，本轮按此执行。
   平板侧 0 值的历史证据在 `IME_OCCLUSION_FIX.md` §5.2（`composer [217,277]` / `OVERLAP_PX=0`），
   **本轮未复现、未独立验证**。
5. 遗留：**AVD 定义**仍在磁盘（`OC_Deliver_R2` 等，见 `HOUSEKEEPING.md` §四，待产品负责人决定）。

---

## 5. 产物清单

| 文件 | 说明 |
|---|---|
| `research/web/GAP_CLOSE_G3.md` | 本报告 |
| `research/env/e7_g3_phone.png` | IME 弹起时的像素佐证（208,773 B） |
| `research/env/logs/e7_g3_phone.log` | 脚本完整原始日志 |

**本轮未修改任何产品代码**（`app/**`、`webapp/**`、`_upstream/**`、`.github/**` 全部未碰）。
