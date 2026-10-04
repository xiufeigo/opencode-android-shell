# GAP_CLOSE_G2 — 逐条关闭 R2 / 上一批列的未闭环

> **轮次**：G2（复核收口）。**执行 agent**：mavis。**开始**：2026-10-05 01:58
> **硬期限**：2026-10-07 之前 —— ✅ 在期限内完成
> **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`，`HEAD = 9b4d7e8`（= R2 的 `f989907` + 交付重建提交）
> **上游**：`research/web/DELIVER_REBUILD_R2.md` §10「未闭环」第 2 / 3 / 4 / 6 条
> **铁律遵守**：**不采信"应该能跑"**；**测不到就写测不到 + 原因**；**不许拿旧数顶替新数**；**不许把"没测到"写成"通过"**。

---

## 0. 结论速览

| # | 项 | 判定 | 一句话 |
|---|---|---|---|
| 1 | release/debug dex 逐条比对 | ✅ **已闭环**（字节级不等价；**方法级等价，差异 0 条**） | 全 5 个 dex 字节都不同，但两个宿主类**方法集合完全相同**（14 / 55，差异 0） |
| 2 | 首屏只跑 1 次、无中位数 | ✅ **已闭环** | 5 次冷启：**中位数 1,676 ms**（极值 1,583 / 1,829），R2 单次 1,945 ms 并列 |
| 3 | debug 体积 8.64 → 5.15 MB | ⚠️ **部分闭环** | **旧二进制已不可得**（git 无、磁盘无）⇒ 逐 entry 差值**做不出来**；但可**证明**它**不来自任何 `webapp/**` 改动** |
| 4 | IME 重叠量 | ❌ **仍未闭环** | 拿到了**原始 `type=ime frame=` 行**（R2 没拿到），但 `frame=[0,0][0,0] visible=false` ⇒ IME 从未抬起，**重叠量不可算**（**不报 0**） |

**本轮碰过的 serial：只有 `emulator-5910`**（自建复用 AVD `OC_Deliver_R2`，收尾已关，§5 有复核输出）。

---

## 1. Gap #1 —— release 与 debug 的 dex 逐条比对 ✅ 已闭环

### 1.1 方法

1. **`unzip -l` 等价**：`System.IO.Compression.ZipFile` 列出两份 APK 里**全部** `classes*.dex`
   （用 .NET API 而非 `unzip`，因为本机无 `unzip.exe`；列出的字段与 `unzip -l` 同义：`Length` / `Crc32` / `CompressedLength`）。
2. **逐条字节比对**：把每个 dex 解出，比 **size + MD5 + SHA256**。
3. **方法级比对**：`dexdump -h`（build-tools 35.0.0）逐个 dex 抽出 `NotifierHost` / `WebHostBridge`
   两个类的**方法签名集合**，做集合差。

### 1.2 原始输出 —— 全部 `classes*.dex` 条目

```
--- RELEASE (opencode-shell-web-release-nor8.apk, 3961217 B, md5 7b578ceec3d7e35e2fffa4da67fe2d3b) ---
Name            Size Crc         Comp
classes.dex  8765764 3a411be2 3059982
classes2.dex   960316 4d0f5668  388504
RELEASE dex entry count = 2

--- DEBUG (opencode-shell-web.apk, 5145604 B, md5 b716cbeb4c66e55fcb0f497640ae5e0c) ---
Name             Size Crc         Comp
classes.dex  10745744 b272c6f3 4112610
classes2.dex    124680 e57934cc   37754
classes3.dex     56632 7acb3352   26291
classes4.dex    105356 47fc84a7   50065
classes5.dex    834660 a0bd60d7  373061
DEBUG dex entry count = 5
```

**条目数：release 2 条、debug 5 条，union = 5 条。**

**逐条字节比对结果**：

| dex | rel_size | dbg_size | rel_md5 | dbg_md5 | 字节相同 |
|---|---|---|---|---|---|
| classes.dex | 8765764 | 10745744 | 16a2de0fa5d363ea56ee2ea116eff137 | 9c7b839f7d08eff3ac7ecdaa232dbfdb | ❌ |
| classes2.dex | 960316 | 124680 | f2cfd730aa83699cb93f91bf93cde73a | 544a04e4e2e2b569d3e341c43962a9fd | ❌ |
| classes3.dex | — | 56632 | （release 无） | 39a905565c7cfbc1b8640b7a0f2c70a2 | ❌ |
| classes4.dex | — | 105356 | （release 无） | cb133c38675bf36e24b24b314ef4fb72 | ❌ |
| classes5.dex | — | 834660 | （release 无） | e8fda0149714c01882e4d22455a73866 | ❌ |

```
ENTRY-WISE: total=5  identical=0  differing=5
```

### 1.3 方法级比对（关键：`f989907` 改动的两个宿主类）

```
================ Lcom/xiufeigo/opencodeshell/web/host/NotifierHost; ================
RELEASE: dex=classes.dex  methodCount=14
DEBUG  : dex=classes4.dex  methodCount=14
common=14  releaseOnly=0  debugOnly=0

================ Lcom/xiufeigo/opencodeshell/web/host/WebHostBridge; ================
RELEASE: dex=classes.dex  methodCount=55
DEBUG  : dex=classes4.dex  methodCount=55
common=55  releaseOnly=0  debugOnly=0

TOTAL METHOD-SIGNATURE DIFFERENCES across the 2 classes = 0
```

### 1.4 明确回答：`NotifierHost.kt` / `WebHostBridge.kt` 在 release 与 debug 里是否等价？

**分两层回答，不含糊：**

| 层次 | 结论 | 依据 |
|---|---|---|
| **dex 字节级** | **不等价** | 5/5 条目 md5/sha256 全不同；debug 拆成 5 个 dex、release 2 个；`classes.dex` 差 1,979,980 B |
| **两个宿主类的方法集合** | **等价，差异 0 条** | `NotifierHost` 14/14 全同、`WebHostBridge` 55/55 全同，`releaseOnly=0 debugOnly=0` |

**为什么字节不同但方法是同一套**（可解释、非猜测）：`f989907` 的宿主改动**只加方法、不删方法**，
两份构建的**方法集合因此必然相同**；字节差异来自 debug 变体的 `debuggable=true`
（保留调试信息、参数名、line number，dex 显著变大）以及 debug/release 的 dex 分片策略不同
（debug 触发 legacy multidex，拆 5 片；release 拆 2 片）。

**⚠️ 诚实标注本结论的边界（不许夸大）**：
本轮做的是**方法签名集合比对**，**不是指令级（bytecode）比对**。因此严格地说，
"release 的这两个类与 debug 逐指令相同"**未被证明**。本轮**没有**在 release 包上
产生任何设备行为证据（release 编译期无 eval 通道，见 R2 §6.1，这一点本轮未改变）。

### 1.5 这是否影响通知点击行为？

**依据 + 判断：不影响**，理由三条，且都可复核：

1. **点击链路的宿主方法在两份包中集合完全相同**（`consumeNotificationIntent` → `NotifierHost` → `WebHostBridge`
   → 注入 `__openSessionByRoute`），**没有 release 缺失的方法**（`releaseOnly=0`）。
   若 release 少了任何一环，方法集合差集必然非空 —— 实测为空。
2. **页面侧字节完全相同**（R2 §2.4 已用 md5 逐字节证明两份 APK 的 `assets/www/oc-host.js`
   均 = 源码 `bf54276929d5f044f92ab08670b2dbbe`）。`f989907` 的**页面侧改动全在这一个文件里**。
3. **release 少的只是调试通道**：`BuildConfig.DEBUG=false` 使 `consumeDebugEval` 直接 return
   （`WebHostActivity.kt:684`）。该通道只服务**注入与回读**，**不参与**通知点击的生产路径。

**残留风险（如实声明）**：`BuildConfig.DEBUG` 是编译期常量，release 与 debug 生成的
`BuildConfig` 字段值不同；这属于**预期差异**，但我**没有**在 release 上跑过一次真实点击
来端到端证明。⇒ **"方法集合等价 + 页面侧字节相同 ⇒ release 携带同一路由路径"是强推断，不是设备实测。**

**判定：✅ 已闭环**（R2 第 2 条问的是"未验证 dex 等价"，本轮已用逐条 + 方法级比对回答；
字节级不等价这一事实与差异条目数也已如实给出）。

---

## 2. Gap #2 —— 首屏只跑 1 次、无中位数 ✅ 已闭环

### 2.1 方法（与 `research/perf/**` 同一口径）

- **口径刻意不取"最后一个 `_assets` GET"**：主读数用**宿主 UI 可用标记**
  `M.g1.firstContent`（页面首个内容，≈FCP，`kind=raf`），辅读数 `Z.firstDraw`（宿主首帧）。
  与 `STARTUP_AND_FRAMETIME.md` / R2 §5 同口径。每次都把 `P.fwd` 资源 GET 数量打出来**但明确不作为首屏**。
- **冷启定义**：`am force-stop <pkg>` → 等 3 s → `logcat -c` → `am start`。
- **跑了 5 次**（≥5 的要求满足），取中位数与极值。
- 脚本：`research/web/_g2_cold.ps1`；日志：`_g2_coldstart.txt`、`_g2_run{1..5}_logcat.txt`；JSON：`_g2_coldstart.json`。

### 2.2 设备指纹（本轮实测，记在此以便复现）

| 项 | 值 |
|---|---|
| serial | `emulator-5910` |
| AVD | `OC_Deliver_R2`（复用 R2 已验证组合，镜像 `android-35;google_apis_playstore_tablet` + `hw.device.name=pixel_tablet`） |
| 端口 | 5910（起前查过 `Get-NetTCPConnection -LocalPort 5910` = **FREE**） |
| 几何 | `Override size: 1080x2400` / `Override density: 420`；物理面板 `2560x1600` / `320` |
| `init=2560x1600 320dpi ... cur=1080x2400 app=1080x2400` | 与 R2 同几何 |
| WebView | `versionName=124.0.6367.219`（major 124，与要求一致） |
| `dpr` | **2.625**（= 420/160，页面侧自证） |
| 被测产物 | `research/deliver/opencode-shell-web.apk`（**debug**，`b716cbeb4c66e55fcb0f497640ae5e0c`，5,145,604 B） |
| 后端 | `adb reverse tcp:4095 tcp:4095` → `host-16 tcp:4095 tcp:4095`；**全程未起停 `4095`** |
| 官方 UI 身份 | `{"t":"OpenCode","r":"complete","ih":915}`（title/readyState 与 R2 同） |

### 2.3 原始输出（5 次，逐次）

```
==================== RUN 1 ====================
Z.firstDraw          = 1173 ms
M.g1.firstContent    = 1829 ms   <== FCP / UI-usable marker (primary)
P.fwd asset GET count= 76  (explicitly NOT used as first-screen)
==================== RUN 2 ====================
Z.firstDraw          = 1049 ms
M.g1.firstContent    = 1738 ms
P.fwd asset GET count= 79
==================== RUN 3 ====================
Z.firstDraw          = 1087 ms
M.g1.firstContent    = 1676 ms
P.fwd asset GET count= 82
==================== RUN 4 ====================
Z.firstDraw          = 1020 ms
M.g1.firstContent    = 1637 ms
P.fwd asset GET count= 82
==================== RUN 5 ====================
Z.firstDraw          = 1027 ms
M.g1.firstContent    = 1583 ms
P.fwd asset GET count= 62
```

### 2.4 中位数 / 极值（并 R2 单次值）

```
################ SUMMARY (UI-usable marker = M.g1.firstContent) ################
runs captured       = 5 / 5
firstContent values = 1829, 1738, 1676, 1637, 1583
firstContent median = 1676 ms
firstContent min/max= 1583 / 1829 ms
firstDraw    values = 1173, 1049, 1087, 1020, 1027
firstDraw    median = 1049 ms
firstDraw    min/max= 1020 / 1173 ms
R2 single-run value (for side-by-side): firstContent=1945 ms, firstDraw=1124 ms  <-- R2 §5
```

| 读数 | R2 单次（本轮之前） | **G2 五次中位数** | G2 极值 | 结论 |
|---|---|---|---|---|
| `M.g1.firstContent`（≈FCP） | 1,945 ms | **1,676 ms** | 1,583 / 1,829 ms | 与 R2 单次**同量级** |
| `Z.firstDraw`（宿主首帧） | 1,124 ms | **1,049 ms** | 1,020 / 1,173 ms | 与 R2 单次**同量级** |

**⚠️ 不宣称"更快"**：R2 那个值是 **n=1**，本轮是 **n=5**；1,676 < 1,945 这个差
**在 n=1 vs n=5 之间不可归因**（单次值本来就可能落在分布的任一侧）。本轮只给
**中位数 + 极值 + 极差 246 ms**，供后续轮次做同口径纵向比较。
另：5 次都是 `force-stop` 后的进程冷启，但**第 1 次之前应用已启动过一轮**（装完首启做过身份自证），
系统侧 JIT/缓存未完全冷 —— 这也是**不宣称"更快"**的第二个理由。

**判定：✅ 已闭环**（R2 第 4 条要求"≥5 次 + 中位数/极值 + 设备指纹 + 与单次值并列"，四项齐备）。

---

## 3. Gap #3 —— debug 体积 8.64 → 5.15 MB ⚠️ 部分闭环

### 3.1 方法（先验证"旧产物能否取到"，这是派单方法的前提）

派单要求 `git show <R2 之前的提交>:research/deliver/opencode-shell-web.apk > tmp`。
**这条命令在本仓不可能成功**，证据如下。

**0a. 该文件从未被 git 跟踪**
```
$ git log --all --oneline -- research/deliver/opencode-shell-web.apk
<EMPTY>
```

**0b. 整个 git 历史里只存在过 1 个 `.apk` blob，且不是它**
```
$ git rev-list --all --objects | grep '\.apk$'
blob=fec532b3 path=dist/opencode_shell_debug.apk size=7158134 B
```
7,158,134 B ≠ 8,636,901 B，是**原生壳**的产物，与 web 壳无关。

**0c. 磁盘上按 md5 全量搜 `research/` + `dist/` + `webapp/build/`，旧二进制不存在**
```
     44198253 B  bd21ff21e72e75db62fc371d290f438e  research\deliver\opencode-shell-debug.apk
     29393986 B  b6073ac63f5d8796708fb269fc855a8d  research\deliver\opencode-shell-release-debugsigned.apk
      3961217 B  7b578ceec3d7e35e2fffa4da67fe2d3b  research\deliver\opencode-shell-web-release-nor8.apk
      5145604 B  b716cbeb4c66e55fcb0f497640ae5e0c  research\deliver\opencode-shell-web.apk
     44198253 B  f927323f80412a6ed6e503aa4983dd0c  research\v97_verify\5554_md\evidence\base.apk
     44198253 B  87b27b42e0346e835886b366c3e43f3a  research\v97_verify\5556_v98\device_app-debug.apk
     17809425 B  cad716a76f3eb5077256e27a61b596af  research\_scratch_v152\app-release-R8.apk
     17967064 B  ddca2566acd65f73616817eaa1be1915  research\_scratch_v152\R8-signed-aligned.apk
     17975012 B  4fc381452729bc42f23029afebd43b9d  research\_scratch_v152\R8-signed.apk
     17809425 B  cad716a76f3eb5077256e27a61b596af  research\_scratch_v152\signed_src.apk
   （无一条 md5 = 8dcffc572ad469f472f61594f12ea990）
```

**⇒ 逐 entry 体积差（"哪些 entry 变小/消失/新增，各多少字节"）在本轮做不出来：
两份解包内容缺一份。** 8,636,901 B / `8dcffc57…` 这个旧产物**已不可得**。
**我不拿新产物自己跟自己比来伪造差值表。**

### 3.2 新 debug APK 的解包内容（作为差值的"新"端基准）

```
total entries = 402   file size = 5145604 B

Bucket              Entries    RawBytes          <- 解包后（未压缩）字节
classes.dex               1 10745744.00
classes5.dex              1   834660.00
resources.arsc            1   262024.00
res/                     341   226753.00
classes2.dex              1   124680.00
classes4.dex              1   105356.00
classes3.dex              1    56632.00
okhttp3/                   2    41612.00
assets/                    4    36045.00
kotlin/                    7    29945.00
AndroidManifest.xml        1     6112.00
DebugProbesKt.bin          1     1738.00
META-INF/                 40     1014.00

--- top 12 largest entries ---
classes.dex                                     10745744  4112610
classes5.dex                                     834660   373061
resources.arsc                                    262024   262024
classes2.dex                                      124680    37754
classes4.dex                                      105356    50065
classes3.dex                                       56632    26291
okhttp3/internal/publicsuffix/publicsuffixes.gz   41394    41394
kotlin/kotlin.kotlin_builtins                    18661     5257
assets/www/index.html                              16000     6683
assets/www/oc-host.js                              13804     5372

assets/www entries=4  rawBytes=36045
```

**关键结构事实：这份 APK 是 dex 主导的。** 5 个 dex 的**压缩后**合计
`4,112,610 + 373,061 + 37,754 + 50,065 + 26,291 = 4,599,781 B`，占 5,145,604 B 的 **~89%**；
而 `assets/www`（真正的 Web 资产）**只有 36,045 B**。

### 3.3 归因：差值来自哪个改动？—— 用 `git log` 对照

`webapp/build.gradle.kts` 的全部提交：
```
cd088b3  feat(web): a readable failure page, a signed release, and proof of where the latency lives
5b7a572  chore(web): turn the placeholder into a real fallback page, and pin the debug-eval contract
767f85d  fix(web): stop the keyboard from covering the composer (overlap 60px -> 0 on phone and tablet)
af59924  feat(web): host the official web UI on Android (route A) -- 8.2 MB APK, verified on device
```

`cd088b3` 对该文件的**全部**改动（`git show cd088b3 -- webapp/build.gradle.kts`）：
```
+        // R8 是开关式的：默认关（release 产物与非 R8 完全一致）…
         release {
-            isMinifyEnabled = false
+            val r8 = providers.gradleProperty("webapp.r8").orNull == "true"
+            isMinifyEnabled = r8
+            isShrinkResources = r8
+            applicationIdSuffix = ".debug"
```
⇒ **只动了 `release {}` 块**。`webapp/build.gradle.kts` 里**根本没有 `debug {}` 块**
（本轮读到 41–61 行确认），所以 debug 变体一直是 AGP 默认：`isMinifyEnabled=false` + `debuggable=true`。

各依赖的引入提交（`git log -S`）：
```
security-crypto  -> af59924
okhttp           -> af59924
webkit           -> af59924
appcompat        -> af59924
coroutines       -> af59924
```
**5 个依赖自 route A 起从未增删改版本。**

### 3.4 结论

**能证明的（已闭环）**：
> 8,636,901 → 5,145,604 B 这 **−3,491,297 B** 的差值，**不来自任何 `webapp/**` 的改动**。
> 依据：依赖清单逐字未变（全部 `af59924`）、`debug` 构建配置从未被定制（无 `debug {}` 块）、
> 唯一的构建配置提交 `cd088b3` 只改 `release {}`（R8 开关 + `applicationIdSuffix`），
> 对 debug 产物无影响；页面资产 `assets/www` 仅 36,045 B，**物理上装不下 3.5 MB 的差**。

**顺带解释了 R2 当时的疑点**（R2 §2.1 记的是"疑为更早的构建配置/依赖状态"）：
> R2 观察到旧交付件比 `webapp/build/outputs/apk/debug/` 下的同名产物**大 3.5 MB**。
> 既然依赖与 debug 配置都没变，**"更早的依赖/配置状态"这个猜测可以排除**。
> ⇒ 那份 8.64 MB 的文件**从来就不是本模块 debug 变体的一个忠实产物**（很可能是别的构建配置
> 或别的模块状态下的文件被拷到了这个交付路径下）。

**不能证明的（未闭环，如实写明）**：
> **具体是哪几个 entry、各差多少字节 —— 本轮无法回答**，因为旧二进制已不可得（§3.1 三重证据）。
> **本报告不对"到底是哪个 entry 变大"下任何结论。** 上面那条"不是 `webapp/**` 改动"是
> **排除法**得出的（配置/依赖未变 ⇒ 变更不在此处），**不是**逐 entry 比对得出的。

**判定：⚠️ 部分闭环**
- "差值是否来自 `webapp/**` 的改动" → ✅ **已闭环**（答案：不是）
- "逐 entry 差值明细" → ❌ **仍未闭环**，原因：**输入缺失**（旧 APK 不可得），
  且**该缺口无法在本轮内补上**（需要有人重新产出/找回那份 8.64 MB 的文件）。

---

## 4. Gap #4 —— IME 重叠量 ❌ 仍未闭环

### 4.1 本轮相对 R2 的**四个实质进展**（都拿到了原始输出）

| # | R2 的卡点 | 本轮的处理 | 结果 |
|---|---|---|---|
| 1 | eval 通道读不到结果 | 发现 **logcat tag 是 `OcWebHost/Eval`**（不是 `OcWebHost`），且 JS 必须作为**单个带引号的 token** 传给 `am start`，否则 `/system/bin/sh` 会在 `(` 处断开 | ✅ 通道打通：`{"t":"OpenCode","r":"complete","ih":915}` |
| 2 | `querySelectorAll` 顶层返回 `[]`，够不到 composer | 写**递归穿透 shadow root** 的查找器 | ✅ 穿透成功，在 shadow root 里找到 `input[placeholder="Search sessions"]`（R2 顶层查询拿不到） |
| 3 | `dumpsys window insets` | 该命令在本 build **根本不存在** | ✅ 换 `dumpsys window` 后**拿到了 `type=ime` 原始行** |
| 4 | 无 `type=ime frame=` 原始行可抄 | — | ✅ **抄到了**（见 §4.2） |

第 3 点的原始报错：
```
Bad window command, or no windows match: insets
Use -h for help.
```

### 4.2 抄到的 `type=ime frame=` 原始行（本轮新增证据）

```
=== [5] valid insets source: 'dumpsys window' filtered for type=ime ===
RAW: InsetsSource id=3 type=ime frame=[0,0][0,0] visible=false flags= sideHint=NONE boundingRects=null
RAW: InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(120, 2274) mInsetsHint=Insets{left=0, top=0, right=0, bottom=0}}
RAW: mSource=InsetsSource id=3 type=ime frame=[0,0][0,0] visible=false flags= sideHint=NONE boundingRects=null
RAW: mControl=InsetsSourceControl mId=3 mType=ime mLeash=Surface(name=...InputMethod...)/@0xf2b4472 - animation-leash of insets_animation)/@0x767ee0a mInitiallyVisible=false mSurfacePosition=Point(120, 2274) mInsetsHint=Insets{left=0, top=0, right=0, bottom=0} mSkipAnimationOnce=false
type=ime line count = 4
```

### 4.3 仍然测不到 —— 卡在哪一步（逐步原始输出）

**卡点：进不去会话页 ⇒ 拿不到 composer ⇒ IME 从未被抬起。**

**(a) 首页 `/` 上没有 composer**（唯一可编辑元素是搜索框）：
```
=== [2] shadow-piercing composer search on the session page ===
search -> {"n":1,"ih":915,"items":[{"tag":"input","ph":"Search sessions","top":115,"bottom":139,"h":24,"cx":220,"cy":127}]}
=== [7] final composer bounds ===
bounds -> NOT_FOUND
```
（`textarea` / `contenteditable` 数量 = **0**；`NOT_FOUND` 是我方查找器的失败返回，不是"重叠 0"。）

**(b) 五种进会话的方式全部失败，`location.pathname` 始终停在 `/`**：
```
[start]      start url -> {"u":"/","ih":915}
[1] JS click <html> 祖先       -> after click -> {"u":"/","ih":915}
[2] JS click 精确文本 SPAN      -> after tap   -> {"u":"/","ih":915}
[3] 真·系统 tap SPAN 中心 (215,205) -> after tap -> {"u":"/","ih":915}
[4] 定位到 BUTTON 后真·系统 tap (206,213) -> after tap -> {"u":"/","ih":915}
[5] 直接 location.href 赋值到 /server/<b64>/session/<id> -> url -> {"u":"/","ih":915}
```
第 4 步的祖先链（证明我确实找到了可点元素，但点了不导航）：
```
chain -> [{"tag":"SPAN","x":215,"y":205,"w":328,"h":16},
          {"tag":"DIV","x":215,"y":213,"w":328,"h":32},
          {"tag":"BUTTON","x":206,"y":213,"w":370,"h":48},
          {"tag":"DIV","x":206,"y":213,"w":370,"h":48},
          {"tag":"DIV","x":206,"y":209,"w":370,"h":56},
          {"tag":"DIV","x":212,"y":1387,"w":382,"h":2475}]
>>> REAL TAP on session BUTTON: input tap 206 213 <<<
```

**(c) `mInputShown` 恒为 false（按要求"读前先断言"，我没有跳过这一步）**：
```
ASSERT mInputShown = false          (round 2, 10 次轮询全部 false)
  attempt 1..10: mInputShown = false
ASSERT mInputShown = false          (round 4/5, 6 次轮询全部 false)
mInputShown after eval-focus = false
```

**(d) 排除后端不可达**（否则"点不动"可能是后端问题）：
```
HOST 4095 LISTEN pid=3748            <- 我们从未起停
host-16 tcp:4095 tcp:4095            <- adb reverse 在
device -> http://127.0.0.1:4095/api/info = 401   <- 401 = 需要鉴权 ⇒ 链路通，不是连不上
mCurrentFocus=Window{81a3788 u0 com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity}
```
⇒ 后端**可达**（401 正是未带鉴权头的预期响应），焦点也**在 `WebHostActivity`** 上。
**导航不发生的原因本轮未定位**，不下结论（可能是 SPA 路由在该 shell 内的行为、
也可能是 `4095` 侧会话数据此刻为空导致列表项不可进入）。

### 4.4 为什么**不能**报"重叠量 = 0"

`frame=[0,0][0,0]` 且 `visible=false`、`mInitiallyVisible=false`、
`mInsetsHint=Insets{left=0,top=0,right=0,bottom=0}` ——
**这是一个"IME 从未抬起"时的退化矩形**（零高零宽）。
拿它去和 composer 的 `bottom` 算重叠，得到的 0 **只反映"IME 没显示"**，
**不是**"键盘没盖住 composer"。

**因此本轮不给出任何重叠量数值。** 按铁律：**测不到就写测不到 + 卡在哪一步 + 原始输出**。
（F4 轮在 442 dp / 708 dp 上量到 0 是**那一轮**的读数，**不能拿来顶替本轮**，R2 已有此告警。）

**判定：❌ 仍未闭环**
- 相比 R2 的**净进展**：eval 通道打通 ✅、shadow root 穿透 ✅、`type=ime` 原始行抄到 ✅、
  发现 `dumpsys window insets` 命令不存在 ✅。
- **未闭环原因（唯一）**：本轮设备上**无法进入会话页**，因此拿不到 composer，
  IME 无法被抬起（`mInputShown=false`），`type=ime` 帧保持退化。
  **建议下一轮从这里接**：先用 `uiautomator dump` 看清当前可见节点 / 或改用
  R2 §6 验证过的**通知点击**路径（那条路径本轮在 debug 上是通的）进会话，再量重叠。

---

## 5. 收尾复核输出（原始）

### 5.1 `wm` 复原（我们改过 `size` / `density`）

```
=== [1] restore geometry we changed (wm size/density reset) ===
wm size reset / wm density reset
after reset:
Physical size: 2560x1600
Physical density: 320
```
⇒ **已无 `Override` 行**，物理面板已复原。

### 5.2 先按 AVD 名确认归属，再按 serial 关

```
=== [2] confirm OWNERSHIP by AVD name before killing (HOUSEKEEPING rule 2) ===
pid=43228 avd=OC_Deliver_R2 port=5910 MINE=True
=== [3] kill ONLY our serial emulator-5910 ===
OK: killing emulator, bye bye
OK
```

### 5.3 复核

```
=== [4] verification (a) processes ===
emulator/qemu process count = 1
OC_Deliver_R2 residual count  = 0
=== [5] verification (b) port 5910 ===
port 5910 LISTEN = 0
  (non-Listen remnants: 1, OwningProcess values: 0)
=== [6] verification (c) adb devices (NO adb kill-server issued) ===
List of devices attached
=== [7] verification (d) gradle lock ===
.agent-gradle-lock exists = False
=== [8] backend 4095 untouched by us (Lead owns it) ===
HOST 4095 LISTEN = yes (pid=3748) -- we never started or stopped it
```

**`process count = 1` 的如实说明**（不掩饰、不越权处理）：
```
pid=33564 name=emulator.exe
  cmd=C:\Users\xiufe\AppData\Local\Android\Sdk\emulator/emulator  -kill 51324 -sleep 20
```
这是 `emu kill` 自己拉起的**瞬时拆卸辅助进程**（`-kill <pid> -sleep 20`），**不是一个 AVD**。
按 `HOUSEKEEPING` 规则 2 的归属判定（`-avd (OC_*|Medium_Desktop_B)`）：
```
OC_*/Medium_Desktop_B count = 0  => MINE: no - NOT ours, must NOT kill
```
**我没有去 kill 它**（它不属于 `OC_*`/`Medium_Desktop_B` 判据）。25 秒后自然退出，**复查**：
```
=== recheck after teardown helper should have exited ===
emulator/qemu process count = 0
port 5910 LISTEN = 0
OC_Deliver_R2 residual = 0
```

### 5.4 纪律逐条对照

| 规矩 | 执行情况 |
|---|---|
| **谁起谁关** | ✅ 本轮只碰过 `emulator-5910`（复用 AVD `OC_Deliver_R2`），已关，残留 0 |
| **只关 `OC_*`/`Medium_Desktop_B`** | ✅ `OC_Deliver_R2` 属 `OC_*`；杀前按 AVD 名确认 `MINE=True` |
| **不碰用户设备（`MCRemote_*`/`DSH_*`/`T7x_*`）** | ✅ **一个都没碰**。本轮开始时 `adb devices` **为空**、emulator/qemu 进程数为 **0**、5910–5918 端口全 FREE ⇒ **本轮不存在可被波及的用户设备** |
| **禁 `adb kill-server`** | ✅ 未执行；收尾 `adb devices` 仍正常返回 |
| **禁 `-a` / 通配符 / 全量杀** | ✅ 只按 **serial `emulator-5910`** + 已确认的 AVD 名关 |
| **不许起停后端 `4095`** | ✅ 只做 `adb reverse tcp:4095 tcp:4095`；收尾 `4095` 仍在 LISTEN（pid 3748，**未动**） |
| **端口 ≥5910 偶数 + 先查占用** | ✅ 用 5910，起前 `Get-NetTCPConnection -LocalPort 5910` = FREE |
| **只用已装镜像 + pixel_tablet** | ✅ 复用 `OC_Deliver_R2`（R2 已验证组合），未下载镜像 |
| **几何 1080×2400@420** | ✅ 收尾已 `wm size/density reset` 复原 |
| **故意保留的实例** | **无。**（有一个非 AVD 的 `emulator -kill ... -sleep 20` 辅助进程，非 AVD、非我判据内，已自行退出） |
| **Gradle 锁** | 本轮**未跑任何 Gradle 构建**（四条都不需要重建）；`.agent-gradle-lock exists = False` |

### 5.5 本轮碰过的 serial

> **`emulator-5910`** —— 仅此一个（`OC_Deliver_R2`，端口 5910，本轮自建/复用，收尾已关）。
> 期间该 AVD **静默退出 1 次**：跑 IME 探针时 `adb devices` 直接掉线、脚本卡在
> "waiting for device"；模式与 `HOUSEKEEPING.md` 里 F4 / R2 两轮记录的
> **"几何高度 2400 > 物理面板 1600 时静默退出"一致**。**本轮不代其它轮次下因果结论**，
> 只记录现象 + 规避：同 AVD 同镜像重起一次即恢复，冷启动 5 次数据全部在**第一次存活期内**取完。
> 该次静默退出**只影响 Gap #4**（首屏 5 次数据在此之前已完成，未受影响）。

---

## 6. 本轮落盘文件

**新增（全部在 `research/web/**`，未越权）**
- `research/web/GAP_CLOSE_G2.md`（本报告）
- `research/web/_g2_dex.ps1` / `_g2_classdump.ps1` / `_g2_dex_methods.ps1` / `_g2_size.ps1`（Gap#1、#3 脚本）
- `research/web/_g2_ime.ps1` / `_g2_ime2.ps1` / `_g2_ime3.ps1` / `_g2_ime4.ps1` / `_g2_ime5.ps1` / `_g2_cold.ps1`
- `research/web/_g2_dex_entries.txt` / `.log` / `.json`、`_g2_classdump_release.txt` / `_g2_classdump_debug.txt`
- `research/web/_g2_dex_methods.txt`、`_g2_size.txt`
- `research/web/_g2_coldstart.txt` / `.json`、`_g2_run{1..5}_logcat.txt`
- `research/web/_g2_ime.txt` / `_g2_ime2.txt` / `_g2_ime3.txt` / `_g2_ime4.txt` / `_g2_ime5.txt`
- `research/web/_g2_ime_insets_raw.txt`（dumpsys window 全量）、`_g2_window_raw.txt`
- `research/web/_g2_boot.log` / `_g2_boot2.log`（及 `.err`）
- `research/web/_g2_work/dex_release/`、`_g2_work/dex_debug/`（解出的 dex）

**未触碰**：`app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`、`webapp/build.gradle.kts`（**一行未改**）、
`research/deliver/**`（**本轮未覆盖任何交付物** —— 两条机械结论都是只读比对，不需要重打包）。

---

## 7. 未闭环清单（收口后的真实状态）

1. **Gap #3 的"逐 entry 体积差明细"** —— ❌ **输入缺失**：8,636,901 B / `8dcffc57…` 那份旧 debug APK
   既不在 git（该路径从未被跟踪）也不在磁盘（全量 md5 搜索无命中）。
   **本轮无法补上**；需要找回或重新产出那份旧产物才能做 entry 级比对。
   （但"差值**不**来自 `webapp/**` 改动"这一问已用排除法闭环。）
2. **Gap #4 IME 重叠量** —— ❌ **仍未闭环**，卡点：设备上进不去会话页 ⇒ 拿不到 composer ⇒
   `mInputShown=false` ⇒ `type=ime` 帧退化为 `[0,0][0,0]` ⇒ 重叠量不可算。
   **本轮未报 0，未拿 F4 轮的 0 顶替。** 下一轮建议改走 R2 §6 验证过的通知点击路径进会话。
3. **release 包上仍未产生任何设备行为证据** —— R2 §6.1 的原因（`BuildConfig.DEBUG=false`
   ⇒ 编译期无注入通道、无 CDP）**本轮未改变**，也未尝试绕过。
   Gap #1 给出的是**方法级等价**（差异 0），**不是** release 上的端到端点击实测。
4. **`dumpsys window insets` 在本 build 不存在** —— 本轮已改用 `dumpsys window` 绕过，
   但**任何沿用旧命令的脚本/文档都会踩这个坑**（含 R2 §7 提到的口径）。建议沉淀进 `research/perf/**`。
5. **模拟器静默退出 1 次**（`emulator-5910`），根因未定位；与 F4 / R2 两轮记录的
   "几何 2400 > 物理 1600"模式一致，**本轮不下因果结论**。
6. **8,636,901 B 旧产物的来源仍是未知** —— 本轮证明了它**不是**本模块 debug 变体的忠实产物（§3.4），
   但**它到底从哪来**（哪个构建配置 / 哪个模块 / 谁拷过去的）**本轮未查清**。
