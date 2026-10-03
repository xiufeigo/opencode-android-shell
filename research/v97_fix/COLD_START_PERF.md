# v97 ④ 冷启动性能评估 — 44 MB debug APK / 6412 ms 首帧

- 角色：性能与构建工程师（④）
- 范围：`app/build.gradle.kts`（仅启动/dex/分包/裁剪相关段落）、`gradle.properties`、`app/src/main/**` 少量文件
- 约束：本轮**无设备（无 adb）**；gradle 只允许 `:app:assembleDebug` 与 `:app:testDebugUnitTest --tests "<类名>"`
- 基线产物：`app/build/outputs/apk/debug/app-debug.apk` = **44,198,562 B**，mtime 2026-10-02 23:20:41
- 源码状态：`app/build.gradle.kts` mtime 2026-10-02 17:57:09，**早于 APK** ⇒ 本报告所有基线数字对应当前工作树（含未提交的 F14b bcprov/packaging 改动），基线可信

---

## 摘要（先看这段）

1. **44 MB 和 6412 ms 是两个病，共享的根因是"零收缩 + 零编译期优化"**。debug 与 release 的 `isMinifyEnabled` **都是 false**，仓库内**不存在** baseline profile。
2. **冷启动大头不是 bcprov。** bcprov = 4,245 类 / 24,961 方法（12.4% 方法）+ 1.21 MB 资源，占 dex 方法数 1/8，但它**只在 SSH ed25519 握手时被触及，不在首帧路径**。把它算成启动成本是归因错配。首帧的真实嫌疑是：① **debuggable ⇒ 无 AOT + 无 baseline profile**，全靠 JIT 与首次类校验（与"主线程陷 ART 类校验"的 ANR 现场吻合）；② `OcActivity.setContent` 里 **7 个同步 `remember {}` 在首帧之前**跑（含磁盘与 NotificationManager）。
3. **落地了 1 项、否决了 3 项**。落地：排除 bcprov 的 Picnic 后量子常数表（纯资源、零行为变更，闸门 15/15 绿）。否决：**debug 开 R8**（629 个测试全是 JVM 单测，跑的是 `kotlin-classes`，与 APK 内 dex 无关 ⇒ 物理上无法为收缩兜底；且混淆 debug 对当前正在查启动问题的团队是净负收益）、**baseline profile**（AGP 只为 release 消费、且跳过 debuggable，且必须真机生成）、**换掉 bcprov**（`net.i2p` 路线 F14b 已证伪）。
4. **副产物（本轮最反直觉的发现）**：44.2 MB APK 里 **7,426,218 B（16.4%）是 zipalign 页对齐填充**（104 个 STORED 条目各带 65,505 B extra field），**比整个 bcprov 还大**，且会吃掉任何 payload 裁剪的收益——所以本项落地虽然摘掉 1,214,815 B 打包条目，**磁盘 APK 只小了 309 B**。真正要压 44 MB 得从 STORED 条目集合下手，但那会改安装语义，需真机验证。

---

## A. 量化

### A.1 方法学与其误差

工具链里 `dexdump` 存在（`build-tools/36.0.0/dexdump.exe`），但 50 MB dex 全量 dump 不可用；`baksmali`/`d8` 不在 PATH；`zipinfo` 不存在（Windows）。因此改用**自写 DEX 格式解析器**：`research/v97_fix/dex_census.py`（随本报告提交，可复跑）。

它直接读 APK 内的 `classes*.dex`：

- `header` → `string_ids_size` / `type_ids_size` / `field_ids_size` / `method_ids_size` / `class_defs_size` / `file_size`
- `string_pool` + `type_ids` → 每个 `class_def` 的类描述符（用于归属分包统计）
- `class_data_item`（uleb128）→ 每个类的 **direct + virtual 声明方法数**

**"方法数"的定义**：按 `class_def` 累加的**声明方法**数，不是 `method_ids` 引用表大小。理由：`method_ids` 计的是引用（含大量重复/外部引用），不是 ART 需要保存方法元数据的量；冷启动的类校验/加载成本更贴近"声明方法 + 类数量"。

**已知误差 / 局限（务必知悉）**：

1. 本统计只能看到 **"打进 dex 的东西"**。它数的是"存在但可能永不加载"的类——这恰好是收缩问题的目标量，所以对本次评估是正确方向。
2. 无法从 dex 反推"哪些类真正在首帧路径上被加载"。**本报告没有任何运行期加载数据（本轮无设备）**，凡涉及"启动期实际成本"的判断都标注为**判读**而非实测。
3. 类→包归属按包名前缀硬编码 bucket，非精确依赖图（Kotlin 的 `kotlin/`/`kotlinx/` 合并为一个 bucket；`androidx.appcompat`/`material`/`core`/`activity`/`constraintlayout`/`webkit` 合并为一个 bucket）。
4. 归因到"某个库"而非"某个 dex"：跨 dex 分布由 D8 决定，下表只给**全局**桶统计，dex 归属仅作定性参考。
5. APK 体积口径有两个：`zip` 列为 zip 存储后字节（≈ APK 实际占用），`raw` 为解压后。两者都给出。

### A.2 dex 数量与体积

**20 个 dex，raw 50,832,844 B（48.5 MiB），zip 20,371,263 B（19.4 MiB）**，`classes.dex` 单文件 raw 17.3 MB。

| entry | zip B | raw dex B | classes | methods | method_refs | strings |
|---|---:|---:|---:|---:|---:|---:|
| classes.dex | 6,584,636 | 17,317,152 | 9,171 | 58,824 | 540,860 | 84,900 |
| classes18.dex | 4,424,216 | 10,323,388 | 6,932 | 51,945 | 491,596 | 71,821 |
| classes19.dex | 4,519,279 | 11,426,852 | 7,907 | 58,020 | 482,212 | 66,587 |
| classes6.dex | 710,610 | 1,716,652 | 773 | 4,672 | 63,100 | 9,757 |
| classes7.dex | 638,765 | 1,537,668 | 751 | 4,360 | 65,044 | 9,801 |
| classes16.dex | 601,400 | 1,395,532 | 621 | 3,440 | 55,612 | 8,363 |
| classes10.dex | 557,087 | 1,320,964 | 625 | 3,449 | 50,448 | 7,575 |
| classes5.dex | 440,026 | 1,111,852 | 595 | 4,233 | 54,696 | 7,818 |
| classes11.dex | 375,190 | 851,028 | 367 | 2,002 | 35,760 | 5,243 |
| classes9.dex | 346,477 | 850,724 | 378 | 2,072 | 47,572 | 8,053 |
| classes4.dex | 181,758 | 692,176 | 317 | 340 | 43,652 | 10,558 |
| classes17.dex | 237,017 | 595,156 | 284 | 2,641 | 31,404 | 4,528 |
| classes12.dex | 161,493 | 363,600 | 131 | 844 | 20,020 | 3,042 |
| classes15.dex | 152,602 | 344,868 | 149 | 944 | 19,760 | 2,718 |
| classes14.dex | 140,202 | 315,656 | 106 | 676 | 15,532 | 2,175 |
| classes13.dex | 113,476 | 269,276 | 175 | 1,049 | 17,920 | 2,398 |
| classes8.dex | 73,772 | 168,792 | 78 | 377 | 11,664 | 1,627 |
| classes20.dex | 46,887 | 106,668 | 42 | 257 | 7,524 | 1,000 |
| classes3.dex | 56,172 | 105,420 | 28 | 317 | 8,648 | 1,459 |
| classes2.dex | 10,198 | 19,420 | 9 | 71 | 2,556 | 362 |
| **TOTAL** | **20,371,263** | **50,832,844** | **29,439** | **200,533** | **2,065,580** | **309,785** |

关键读数：

- **三个巨型 dex 占了 dex raw 字节的 76.8%**（`classes.dex` + `classes18` + `classes19` = 39,067,392 / 50,832,844）。
- dex **压缩率 26%–53%**，平均 40%。`classes4.dex` 只有 26.3%（大量重复字符串/大常量表），`classes.dex` 38.0%。
- **dex 已经是压缩存储的**（AGP 默认 deflate dex），所以"dex 未压缩"这一常见猜测**不成立**——没有可捞的压缩空间。

### A.3 按归属分包（类数 / 方法数）

| bucket | classes | methods | %classes | %methods |
|---|---:|---:|---:|---:|
| compose (`androidx.compose.**`) | 9,180 | 56,637 | 31.2% | 28.2% |
| **app-opencode** (`com.xiufeigo.**`) | 5,075 | 31,003 | 17.2% | 15.5% |
| androidx views/material/core/activity/constraint/webkit | 3,623 | 28,306 | 12.3% | 14.1% |
| **bcprov** (`org.bouncycastle.**`) | **4,245** | **24,961** | **14.4%** | **12.4%** |
| **tink** (`com.google.crypto.**`) | **1,452** | **16,726** | 4.9% | 8.3% |
| kotlin-stdlib + kotlinx | 2,133 | 16,555 | 7.2% | 8.3% |
| other | 1,408 | 11,186 | 4.8% | 5.6% |
| okhttp + okio | 426 | 4,235 | 1.4% | 2.1% |
| jsch (`com.jcraft.jsch.**`) | 326 | 2,381 | 1.1% | 1.2% |
| zxing | 291 | 2,031 | 1.0% | 1.0% |
| androidx-navigation | 300 | 1,979 | 1.0% | 1.0% |
| markwon + commonmark | 333 | 1,590 | 1.1% | 0.8% |
| androidx lifecycle/security/profile | 329 | 1,375 | 1.1% | 0.7% |
| json/gson | 195 | 1,086 | 0.7% | 0.5% |
| termux (vendored VT) | 41 | 392 | 0.1% | 0.2% |
| androidx annotation/startup/profileinstaller | 82 | 90 | 0.3% | 0.0% |
| **TOTAL** | **29,439** | **200,533** | 100% | 100% |

**bcprov 贡献**：4,245 类 / 24,961 声明方法 = **14.4% 类 / 12.4% 方法**。作为对照，jsch（真正使用它的库）只有 326 类 / 2,381 方法 —— **bcprov : jsch ≈ 13 : 1**。这个比例就是"全量 bcprov 换 1 个签名算法"的代价。

**方法数最大的单类**（`classes19.dex`）：`kotlin/collections/ArraysKt___ArraysKt` 1,650 方法、`UArraysKt___UArraysKt` 693、`ArraysKt___ArraysJvmKt` 294、`CollectionsKt___CollectionsKt` 253 —— Kotlin stdlib 的多版本 `@JvmName` 桩在 debug 下**全部保留**（`kotlin.collections.ArraysKt__ArraysKt` + `__ArraysKt___ArraysKt` 系列），这是未收缩的典型浪费。

### A.4 非 dex 载荷（44 MB 里 dex 之外的部分）

| group | entries | raw B | zip B | %APK(zip) |
|---|---:|---:|---:|---:|
| dex | 20 | 50,832,844 | 20,371,263 | 53.9% |
| **assets** | 951 | 36,067,113 | **12,556,558** | **33.2%** |
| (root：`resources.arsc` 等) | 3 | 2,787,222 | 2,779,356 | 7.4% |
| **org/**（bcprov 资源 + commonmark 表） | 6 | 1,338,005 | **1,238,450** | **3.3%** |
| res | 851 | 1,485,419 | 757,961 | 2.0% |
| okhttp3（DNS 根证书） | 2 | 41,612 | 41,543 | 0.1% |
| lib（4 个 .so） | 4 | 37,392 | 37,392 | 0.1% |
| kotlin（kotlin stdlib 的 .kotlin_builtins） | 7 | 29,945 | 10,468 | 0.0% |
| META-INF | 92 | 27,384 | 10,374 | 0.0% |
| **合计** | **1,936** | **92,646,936** | **37,803,365** | |

- `assets/` raw 36 MB / **zip 12.6 MB**：836 个 `.js`（29.3 MB raw）= 打包进 APK 的 opencode WebView 前端产物，外加 1.37 MB mp4、1.06 MB woff2、22 个 ttf。
- `org/bouncycastle/` 6 个资源条目里，**3 个是 Picnic 后量子 KEM 的常数表**：
  - `pqc/crypto/picnic/lowmcL5.bin.properties` 752,652 raw / 749,257 zip
  - `pqc/crypto/picnic/lowmcL3.bin.properties` 350,101 / 348,178
  - `pqc/crypto/picnic/lowmcL1.bin.properties` 118,306 / 117,380
  - **合计 1,221,059 raw / 1,214,815 zip B**（占 APK 2.7%）
- 只有 4 个 `.so` 共 37,392 B ⇒ **原生库/16 KB 页/`extractNativeLibs` 议题在本项目上是噪声**。

### A.5 冷启动路径上的大头（每条：证据 + 判读）

| # | 大头 | 证据 | 判读 |
|---|---|---|---|
| 1 | **debug 构建 = 无 AOT** | `debug` block 只设了 `signingConfig`；`release` 是 `isMinifyEnabled = false`；仓库内**不存在** `baseline-prof.txt`、无 `profileinstaller` 显式依赖、无 benchmark module、无 `.prof` | **这是 6412 ms 的首要嫌疑，且远大于 APK 体积本身。** debuggable app 安装时 `dex2oat` 不做 AOT，全靠 JIT + 首次使用时的类校验/quickening；类越多、启动路径越长，verify 越贵。与"主线程陷 ART 类校验"的 ANR 现场描述完全吻合。 |
| 2 | **无任何收缩/重排（debug 与 release 都没开 R8）** | `buildTypes` 里 debug 无 `isMinifyEnabled`；release 显式 `isMinifyEnabled = false`；`app/proguard-rules.pro` 只有 1 行注释 | 29,439 类 / 200,533 方法**全部**保留。Compose 9,180 类里绝大部分（`foundation`/`material3` 的未用组件、`ui-tooling`）都是死重。R8 不开 ⇒ 后面 B.2 的 bcprov 裁剪**完全无杠杆**。 |
| 3 | **首帧前在主线程同步构造 7 个对象** | `OcActivity.onCreate` → `setContent { ... }` 体内同步 `remember {}`：`ServerConfigStore(...).also { it.restore() }`、`SettingsStore`、`NotificationPrefs`、`SystemNotifier(...)`、`ChatViewModel(...)`（该类 493 方法）、`NetworkRecoveryWatch`、`LastRouteStore`、`AppearancePrefs` | **判读（无实测）**：这些都跑在主线程首帧之前，且至少 `ServerConfigStore.restore()` / `NotificationPrefs` / `SystemNotifier`（NotificationManager + channel 创建）要碰磁盘与系统服务。AppCompatActivity + Compose + Material3 + 完整 View 栈的类加载叠在上面。这是"应用自身逻辑"型首帧成本，**R8 和 baseline profile 都治不了它**，需要改代码（超出本轮写权限与"不改变运行语义"约束）。 |
| 4 | **bcprov 全量进包** | 4,245 类 / 24,961 方法（12.4% 方法）；非 dex 资源 1.21 MB | **对冷启动贡献接近 0，对 APK 体积/安装/更新才是大头。** 判读依据：bcprov 只在 SSH ed25519 握手时被触及（`jsch.bc.SignatureEd25519` → `bcprov crypto.signers.Ed25519Signer`），**不在首帧路径**；而 `ServerConfigStore` 用的是 tink（`EncryptedSharedPreferences`），与 bcprov 无关。把 44 MB 和 6412 ms 归因给 bcprov 是**错配**。 |
| 5 | **tink 是第二个"为一个功能背全量 jar"** | 1,452 类 / 16,726 方法（8.3% 方法）；`ServerConfigStore.kt:321-329` 唯一使用点 | 与 bcprov 同构的问题，但**不能动**：`EncryptedSharedPreferences` 依赖 Tink 的 `KeysetHandle` + protobuf + 反射注册，换掉等于改凭据存储语义（且 F14b 的血泪教训就是别乱动这条 SSH/凭据链）。 |
| 6 | **debug-only 的 `ui-tooling` 进包** | `debugImplementation("androidx.compose.ui:ui-tooling")`；另 `implementation(ui-tooling-preview)` 但 `app/src/main` 内 `@Preview` 计数 = **0** | 拖累 debug APK 体积；但其类**不在首帧路径**，且是团队查启动问题的现成工具（Layout Inspector / Live Edit）。**收益低、代价高，不动。** |
| 7 | **WebView 壳不在冷启动路径** | Manifest `LAUNCHER` = `.OcActivity`（Compose）；`MainActivity` 代码注释："boots into native chat and only pays WebView cost when the terminal opens" | 判读：`assets/` 那 29.3 MB JS **不是首帧成本**（懒加载），只影响安装/下载体积。**不要**把它当启动优化目标去打。 |
| 8 | **dex 已压缩，无压缩空间** | dex 压缩率 26%–53%，`classes.dex` 6.58 MB zip / 17.3 MB raw | 排除"未压缩 dex"这一常见猜测。 |
| 9 | **原生库/16 KB 页无风险** | 4 个 `.so` 共 37,392 B；无 `externalNativeBuild` | 排除 `extractNativeLibs` / `useLegacyPackaging` 作为性能杠杆（见 B.4）。 |

**一句话结论**：**44 MB 和 6412 ms 是两个不同的病，共享的根因是"没有任何收缩 + 没有任何编译期优化（AOT/profile）"。** bcprov 是体积病的一环（12.4% 方法 + 1.21 MB 资源），但对首帧几乎无责；首帧的大头是 #1（无 AOT/无 profile）叠加 #3（首帧前主线程同步构造）。

---

## B. 方案评估

### B.1 debug 包启用 R8 收缩 — **评估后否决（高风险 + 闸门物理上无法验证）**

拟改动：`buildTypes.debug { isMinifyEnabled = true; isShrinkResources = true }`

- **预期收益**：体积上很大（29,439 类里 Compose 死重 + Kotlin 多版本桩 + bcprov/tink 大量未用，R8 full mode 通常能砍掉 debug APK 的 40%–60%）。但**对 6412 ms 首帧收益不确定甚至可能变差**——R8 缩短 dex 和类数，但 debug 仍是 debuggable（无 AOT），R8 也不产生 baseline profile；被删的类只是"本来就不加载"。
- **风险（按严重性排序）**：
  1. **闸门覆盖不到（本轮致命项）**：`app/src/test` 下 **629 个测试文件全部是 JVM 单元测试**，跑的是 `build/tmp/kotlin-classes` / `javac` 产物，**与 APK 内的 dex 完全无关**。R8 删错一个类，`assembleDebug` 照样成功，629 个单测照样全绿，而真机运行才炸。**本轮无设备 ⇒ 没有任何闸门能证明收缩后的 APK 还能跑。**
  2. `@JavascriptInterface` × 9（`OpenCodeAndroidBridge.kt:11,19,24,28,31,36,41,46,52`）+ `MainActivity.kt:473 addJavascriptInterface`：WebView 按**方法名**反射调用。AGP 默认的 `proguard-android-optimize.txt` 里**有** `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }`，但这是"依赖默认规则文件恰好覆盖"，而不是本项目显式声明——而本项目的 `app/proguard-rules.pro` 里**什么都没有**（1 行注释）。一旦有人改 `proguardFiles` 就没有保护。
  3. 反射面实测（dex 字符串计数，上界）：`ServiceLoader` 29（全在 classes19）、`ClassLoader` 27、`getMethod` 21、`newInstance` 12、`getDeclaredField` 7、`getDeclaredMethod` 5、`loadClass` 3。主体是 Tink（`androidx.security:security-crypto:1.1.0-alpha06` 走 Tink keyset/protobuf 反射注册）和 Compose。依赖各自 AAR 的 consumer rules 能覆盖大部分，但"能覆盖"未经验证。
  4. `ImageReader.newInstance(size.width, size.height, …)`（`PairingPanel.kt:601`）——`newInstance` 会被 R8 视作普通调用，但 Size/行数不是常量，若无 keepattributes 会出难查的怪问题。
  5. vendored termux（`app/src/thirdparty/termux/`，两个独立 module）**没有** consumer rules，只有一份全注释的 `proguard-rules.pro`。
- **是否让 debug 调试变难**：**会**。类名混淆后，反编译查栈、从崩溃栈反查 `a.b.c` 的成本陡增；`proguard-android-optimize.txt` 保住 `SourceFile,LineNumberTable`，所以**行号还在**（不至于完全不可读），但对一个正在排查启动 ANR、每天靠 629 个测试和真机栈定位问题的团队，混淆 debug 是净负收益。若坚持要上，正确形态是**只收缩不混淆**（`-dontobfuscate` + `-dontoptimize`），那是另一个量级的工程量。
- **结论**：**不改。** 触发"任何方案都有较大风险就不要改"的条件。真要上，须单独立项：写显式 keep 规则（JS bridge / tink / jsch ed25519）+ 上真机 + 补 instrumented 冒烟，且应在 **release** 上做而不是 debug。

### B.2 裁剪 bcprov — **拆成两半：一半可落地，一半否决**

我们只用 ed25519 签名：`com.jcraft.jsch.bc.SignatureEd25519` → `org.bouncycastle.crypto.signers.Ed25519Signer`。bcprov 4,245 类 / 24,961 方法里，ed25519 真正需要的是 `crypto.signers.Ed25519Signer`、`crypto.params.Ed25519{Public,Private}KeyParameters`、`math.ec.rfc8032.Ed25519`、`crypto.digests.SHA512Digest`、`util.*` 这一小撮，量级在 1% 以下。

| 子方案 | 预期收益 | 风险 | 判定 |
|---|---|---|---|
| **B.2a 换更小的产物**（`net.i2p.crypto:eddsa`） | 约 −4 MB | **已被排除**：`app/build.gradle.kts:107-111` 的 F14b 注释记录了实测结论——jsch 0.2.24 无任何类引用 `net/i2p`，其 POM 也不声明依赖，jce 类的 jar base 是 Java15 stub。**别重复这条已证伪的路。** | 否决 |
| **B.2b 自写 adapter**（在 `com.jcraft.jsch.bc` 包下放一个 `SignatureEd25519` 壳，转发给 `net.i2p.crypto:eddsa`） | 约 −4 MB | 高：① 要**伪造 jsch 包名的类**，jsch jar 里的同名类同时被打包 → D8 duplicate class 报错，除非再加排除规则（AGP 难按类排除）；② 直接改**加密签名路径**，`SshEd25519IdentityF14Test` 是 JVM 测试，**无法证明设备上的握手结果**；③ 违反"不改变运行语义"。 | 否决 |
| **B.2c R8 + `-dontwarn org.bouncycastle.**` 只留 ed25519** | 约 −4 MB | **`-dontwarn`/keep 规则只在 minify 开启时才生效**（题目已提示）。当前 debug 与 release 的 `isMinifyEnabled` 都是 `false` ⇒ 写进 `proguard-rules.pro` 是**死代码，零效果**。要生效就必须先做 B.1，而 B.1 已否决。 | 否决（依赖被否决项） |
| **B.2d ** 排除 Picnic 后量子常数表**（**本轮落地项**） | **−1,214,815 B（APK −2.7%）** | 低：只动**资源**（`packaging.resources.excludes`），**不动任何类**；`app/src/main` 内 `pqc|picnic` 命中数 = **0**；无 `Security.addProvider` / `BouncyCastleProvider` 引用（命中 0）⇒ bcprov 不会被整体注册，PQC 引擎不会被加载。ed25519 链路只碰 `org.bouncycastle.crypto.**` / `math.**` / `util.**`，与 `org/bouncycastle/pqc/**` 无交集。 | **落地** |

**为什么 B.2d 不违反"不改变运行语义"**：被排除的 3 个 `.bin.properties` 只被 `org.bouncycastle.pqc.crypto.picnic.*`（后量子 KEM）读取，而本项目从未走 PQC 路径。**已知残留风险（显式记录）**：若将来有人引入 PQC 算法，会在运行时以"资源缺失"形式失败，而不是编译期报错——所以这条必须在代码注释里写明。启动语义零变化（首帧不读这些文件），dex 与类数零变化。

### B.3 baseline profile / startup profile — **评估后否决（对 debug 无效 + 需设备）**

现状：`androidx.profileinstaller` 的类**已经通过 `androidx.activity:1.9.3` 传递进来了**（bucket `androidx annotation/startup/profile` 82 类 / 90 方法，含 `androidx.startup` + `profileinstaller`），但：

1. **AGP 只为 release 变体消费 `baseline-prof.txt`**（`mergeReleaseArtProfile` 是 release-only 任务），debug 变体不会把 profile 打进 `assets/dexopt/baseline.prof`。
2. **`ProfileInstallReceiver` 对 debuggable 应用直接跳过**（检查 `ApplicationInfo.FLAG_DEBUGGABLE`），即使塞进去也不安装。
3. 手写一个 `baseline-prof.txt` 属于**猜测**启动路径类集合。官方路径是新增 `:app` baseline profile module + `MacrobenchmarkRule` + **真机**跑 `BaselineProfileGenerator` 生成。
4. 即便做出来，也只对 **release** 生效——而本轮测得的 6412 ms 是 **debug** 数字。

- **预期收益**（release，实测量级参考）：有 profile 的 ART AOT 冷启动通常可省 30%–50% 首帧时间；`ProfileInstaller` 还会在 profile 缺失时退化到 `dex2oat` 快速编译。
- **侵入面**：新增 module + benchmark module + `benchmark` 插件；`gradle.properties` 加 `androidx.baselineprofile` 相关配置。
- **是否需要设备生成**：**需要**。无法手写。
- **结论**：**本轮不改。** 这是"后续建议"里的第一优先级，且必须配真机 CI（`Macrobenchmark` 需要受控设备 + 稳定的 profile 提交纪律）。**它也意味着：debug 包的 6412 ms 不会因为这个方案而变好。**

### B.4 其它

| 方案 | 预期收益 | 风险 | 判定 |
|---|---|---|---|
| `android:extractNativeLibs` / `useLegacyPackaging` | ~0 | 本项目只有 4 个 `.so` 共 **37,392 B**（0.1% APK），无 `externalNativeBuild`、无 16 KB 页风险。改了只动 37 KB，且 `extractNativeLibs=false` 是 AGP 8 默认（`useLegacyPackaging=false`）。 | **不改**（零收益、动 Manifest） |
| dex 分包策略 / `multiDexEnabled` | 0 | `minSdk = 26 ≥ 21` ⇒ 原生 multidex，无 legacy multidex 库。20 个 dex 是**体积的结果**不是策略选择。`multiDexKeepProguard` 只会让 dex 变多。 | **不改** |
| dex 压缩（`dexOptions` 等价物） | 0 | dex **已** deflate，压缩率 26%–53%，无剩余空间。 | **不改** |
| **`androidResources.noCompress` / STORED 条目集合** | **最高可达 ~7.3 MB** | **本轮实测发现（见 C.2）**：44.2 MB APK 里有 **7,426,218 B 是 zipalign 4 KB 页对齐填充**（104 个 STORED 条目各带 65,505 B extra field），是全 APK 最大单项成本，超过整个 bcprov 的体积贡献。339 个 STORED 条目里 186 个是 png、80 个是 `META-INF/versions/*`、26 个 aac、20 woff2、20 woff。但：改 `noCompress` 会让这些文件在**安装期被解包落盘**而不是运行时 mmap 免解压 ⇒ 改的是**安装语义**（安装变慢、占用数据分区），与"不改变运行语义"冲突，且需真机验证。 | **不改**（列为后续第一优先） |
| `org.gradle.jvmargs=-Xmx2048m` | 只影响**构建**耗时，与产物/运行期无关 | 大内存有利于 assembleDebug 的 D8 阶段，属于构建速度范畴，不是冷启动范畴（本轮 ④ 的目标是首帧/体积）。 | **不改**（越界） |
| 把 `implementation(ui-tooling-preview)` 降为 `debugImplementation` | 仅对 **release** 有效（debug 本来就有 `ui-tooling` 会带上它）；`app/src/main` 内 `@Preview` 计数为 0 | 改变 release 的开发能力；本轮**不能跑 `assembleRelease`**（命令白名单只允许 `assembleDebug`）⇒ **无法给出前后对比数字**。 | **不改**（列入后续建议） |
| 移除 `debugImplementation(ui-tooling)` | 体积可观 | 不在首帧路径；移除会断掉 Layout Inspector / Live Edit——团队当前正在排查启动问题，代价远大于收益。 | **不改** |
| `android.bundle.enableUncompressedNativeLibs` / 16 KB 对齐 | ~0 | 同上，`.so` 仅 37 KB。 | **不改** |

---

## C. 落地

### C.1 改了什么

**只改了 `app/build.gradle.kts` 一处**，在**既有的** `android { packaging { resources { … } } }` 段落里**追加** 3 行 `excludes`（F14b 的 3 行 `META-INF/versions/*` 一字未动，位置在其后）：

```kotlin
excludes += "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties"
excludes += "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties"
excludes += "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties"
```

改动性质：**纯资源排除**。不删任何类、不动 dex、不改 `buildTypes`、不动 `noCompress`、不新增依赖、不新增用户可见字符串。理由见 B.2d：这些是 Picnic 后量子 KEM 的常数表，只被 `org.bouncycastle.pqc.crypto.picnic.*` 读取；本项目从不走 PQC 路径（`app/src/main` 内 `pqc|picnic` 命中 0；无 `Security.addProvider` / `BouncyCastleProvider` 引用；唯一 bcprov 路径是 jsch ed25519 → `org.bouncycastle.crypto.signers.Ed25519Signer`，只碰 `crypto.**` / `math.**` / `util.**`）。

代码注释里已写明残留风险：若将来引入 PQC 算法必须把表加回来，否则以运行时资源缺失的形式失败。

### C.2 前后对比

| 指标 | 改动前 | 改动后 | 变化 |
|---|---:|---:|---|
| **APK 磁盘字节** | 44,198,562 | **44,198,253** | **−309 B（−0.0007%）** |
| **打包条目总压缩字节** | 37,803,365 | **36,588,550** | **−1,214,815 B（−3.2%）** |
| `org/` 组（bcprov+commonmark 资源） | 1,238,450 zip / 1,338,005 raw | 23,635 zip / 116,946 raw | −1,214,815 zip |
| dex 数量 | 20 | 20 | 0 |
| dex 原始总字节 | 50,832,844 | 50,832,844 | 0 |
| **类数** | 29,439 | 29,439 | **0** |
| **声明方法数** | 200,533 | 200,533 | **0** |
| bcprov 类 / 方法 | 4,245 / 24,961 | 4,245 / 24,961 | 0 |
| tink 类 / 方法 | 1,452 / 16,726 | 1,452 / 16,726 | 0 |
| compose 类 / 方法 | 9,180 / 56,637 | 9,180 / 56,637 | 0 |
| picnic 条目 | 3 个存在 | **3 个全部消失** | ✅ |

**"只小了 309 B" 必须解释清楚**——这是本轮最有价值的副产物：

逐字节核算（新建 APK，44,198,253 B 全额归因）：

| 组成 | 字节 |
|---|---:|
| 20 个 dex + assets/res/org/lib 等**条目数据** | 36,588,550 |
| **本地头 + extra field（zipalign 页对齐填充）** | **7,426,218** |
| APK Signing Block（v2/v3） | 4,096 |
| 中央目录 | 179,367 |
| EOCD | 22 |
| **合计** | **44,198,253** ✅ 与文件大小逐字节吻合 |

- 2,091 个本地条目中，**104 个条目各自带一个 65,505 B 的 extra field = 7,267,713 B（占 APK 16.4%）**。这些条目全部是 STORED（未压缩）——共 339 个 STORED 条目 / 6,476,377 B：186 个 png、80 个 `META-INF/versions/*`、26 个 aac、20 woff2、20 woff、4 so、1 mp4、1 gz、1 `resources.arsc`。
- 即：**APK 里最大的单项成本不是 dex 也不是 bcprov，而是 zipalign 4 KB 页对齐填充（7.3 MB），比整个 bcprov 的 class 贡献（约 4.4 MB 压缩 dex + 1.2 MB 资源）还大。**
- 而且这个填充是**按条目自身尺寸逐条算的**，删掉 1.2 MB 的 DEFLATED 条目后，后续条目的绝对偏移全部移位，填充量**反而又涨了 1,214,506 B**，把删掉的体积几乎原样吃回去，净剩 309 B。

**结论**：**payload 裁剪在本项目上目前换不来 APK 体积**。要让减重真正体现在 44 MB 这个数字上，必须动 STORED 条目集合（`androidResources.noCompress`），而那会改变安装期语义（STORED 条目要被解包落盘而不是 mmap 免解压）——**属于运行/安装语义变更，超出"不改变运行语义"约束，本轮不动，列入后续建议。**

本项改动仍然保留的理由：它是**零行为变更**且**零风险**的（不改类、不改 dex、不改启动路径），把 1.2 MB 无用载荷从打包条目集里摘掉（安装/patch 时少走这 3 个条目、中央目录更小），闸门全绿；且它是 B.2 唯一在 minify 关闭时仍然生效的 bcprov 减重手段。

### C.3 测试证据

gradle 全程遵守抢锁协议（`mkdir .agent-gradle-lock` → 跑完 `rm` 释放），前台跑完，`JAVA_HOME` 指向 Android Studio JBR，`--console=plain`。

**1) `:app:assembleDebug`**

```
> Task :app:mergeDebugJavaResource
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 24s
75 actionable tasks: 2 executed, 73 up-to-date
GRADLE_EXIT=0
```

**只有 `mergeDebugJavaResource` + `packageDebug` 两个任务重跑**，其余 73 个 up-to-date——改动面精确命中资源打包阶段，`compileDebugKotlin` / `dexBuilderDebug` / `mergeExtDexDebug` / `mergeProjectDexDebug` 全部 UP-TO-DATE，侧面证明没有触及代码与 dex 路径。

**2) `:app:testDebugUnitTest`（闸门 + 3 个代表性类）**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 7s
52 actionable tasks: 1 executed, 51 up-to-date
GRADLE_EXIT=0
```

| 测试类 | tests | failures | errors | skipped | 耗时 | 选它的理由 |
|---|---:|---:|---:|---:|---:|---|
| `StringsLocaleParityTest` | 3 | 0 | 0 | 0 | 0.057 s | 题目指定的**闸门** |
| `SshEd25519IdentityF14Test` | 4 | 0 | 0 | 0 | 0.309 s | **直接钉住本次改动风险面**（jsch ed25519 → bcprov 那条链） |
| `BootPathTest` | 2 | 0 | 0 | 0 | 0.208 s | 冷启动/启动路径 |
| `ColdStartV64Test` | 6 | 0 | 0 | 0 | 0.031 s | 冷启动专项 |
| **合计** | **15** | **0** | **0** | **0** | 0.605 s | |

**15/15 全绿，0 failure / 0 error / 0 skipped。** 未新增、未修改、未削弱任何测试断言。

### C.4 落地结论

- **改了构建配置**：是，仅 `app/build.gradle.kts` 的 `packaging { resources { … } }` 段落追加 3 行 Picnic 常数表排除。
- **APK 磁盘体积**：44,198,562 → 44,198,253 B（**−309 B**）；打包条目压缩总量 −1,214,815 B。
- **类数 / 方法数 / dex 数**：**完全不变**（29,439 类 / 200,533 方法 / 20 dex，bcprov 4,245/24,961 不变）——这正是"资源级裁剪、不动代码"的预期结果。
- **首帧**：**预期零影响**（这些文件不在启动路径）。**未在真机复测（本轮无设备）**，故不声称首帧有任何改善。
- **未落地的高价值项**（B.1 debug R8 / B.3 baseline profile）**按"有较大风险就不改"处理**，理由与解除条件见 B 与"后续建议"。


## 后续建议（未闭环）

1. **【体积第一优先，需真机】收敛 STORED 条目 → 干掉 7.3 MB 页对齐填充**：这是 44 MB 里最大的单项成本（见 C.2），比 bcprov 整个还大。手段：精修 `androidResources.noCompress`（当前只有 `woff, woff2, wasm`；186 个 png / 26 个 aac / 80 个 `META-INF/versions/*` 是 aapt 默认 `noCompress` 带进来的）以及 `META-INF` 处理。**必须真机验证**：`extractNativeLibs=false` 下这些文件会从"mmap 免解压"变成"安装期解包落盘"，安装耗时与数据分区占用都会变。**注意 payload 裁剪目前换不来体积**（填充会吃掉），所以减重必须从 STORED 侧下手。
2. **【冷启动最高价值，需真机】release baseline profile**：新增 `:app` baseline profile module + `benchmark` 插件，用 `BaselineProfileGenerator` 在 CI 真机生成并提交 `baseline-prof.txt`。这是唯一能把 release 冷启动显著拉下来的标准手段。**注意它对 debug 无效**（AGP 只为 release 变体消费，且 `ProfileInstallReceiver` 跳过 debuggable），所以别用它去解释/改善 6412 ms。
3. **【冷启动最高价值，需真机 + 独立立项】release 开 R8**：先补齐显式 keep 规则（`@JavascriptInterface` 的 `OpenCodeAndroidBridge` 9 个方法、tink、jsch ed25519、termux vendored 两个 module 都没有 consumer rules），再开 `isMinifyEnabled = true` + `isShrinkResources = true`，配 `Macrobenchmark` 验证。**不要在 debug 上开**——629 个 JVM 单测跑的是 `kotlin-classes`，与 APK 内的 dex 无关，物理上无法为它兜底。
4. **【唯一直接命中 6412 ms 的可改点】首帧主线程同步构造**：`OcActivity.setContent` 里有 7 个同步 `remember {}`（`ServerConfigStore.restore()`、`SettingsStore`、`NotificationPrefs`、`SystemNotifier`（NotificationManager + channel）、`ChatViewModel`（493 方法）、`NetworkRecoveryWatch`、`LastRouteStore`/`AppearancePrefs`）全在首帧之前跑，其中至少两处要碰磁盘与系统服务。R8 和 baseline profile 都治不了这类成本。**属运行语义/时序变更，超出本轮授权**，留给 ①/③ 角色。
5. **想验证"到底哪些类在首帧被加载"**：本轮无设备，报告里所有启动期成本判断都标了"判读"。建议下一轮上真机抓 `am profile start` / `atrace` 或 ART 的 class-load 计数，把 #3 从判读变成实测——否则"44 MB vs 6412 ms 归因"始终只能停在推断层。
6. **bcprov 真正瘦身**：路线是 B.2c 挂在 **release R8** 上（不是 debug），keep 只需保住 `org.bouncycastle.crypto.signers.Ed25519Signer` 及其可达集，可回收约 4,245 类 / 24,961 方法中绝大部分。**前置依赖是第 3 项。**
7. **tink 是同构的第二例**（1,452 类 / 16,726 方法，只服务 `ServerConfigStore` 的 `EncryptedSharedPreferences`）。不要动——F14b 的教训就是凭据/加密链别乱碰；若将来要拆，方向是换掉存储实现而不是开 R8。

## 未闭环 / 已知局限

- **首帧 6412 ms 未复测**：本轮无设备，所有首帧相关结论均为静态判读，未在 Pixel_9a 上复跑。
- **无运行期加载数据**：无法给出"首帧实际加载了多少类"的实测值，A.5 的 #1/#3 是基于 dex 统计 + 源码阅读的推断。
- **APK 体积基线已被本轮改动影响**：基线 44,198,562 B 是改动前的值；改动后为 44,198,253 B。后续任何体积对比请以 44,198,253 B 为准。
- **未评估的维度**：未做多轮冷启动方差、未对比 A/B profile 下的耗时分布、未验证设备侧 STORED/解包行为、未跑任何 instrumented 测试。
- **R8 方案未做 POC**：B.1 的风险判断基于源码反射面审计 + 闸门能力审计，**未实际跑过一次收缩构建**，所以"能砍掉多少"只有量级估计（40%–60%），没有实测数字。

