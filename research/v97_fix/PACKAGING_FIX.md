# v97 打包冲突修复报告（`:app:mergeDebugJavaResource` duplicate OSGi manifest）

状态：**DONE** — `:app:mergeDebugJavaResource` / `:app:mergeExtDexDebug` / `:app:mergeProjectDexDebug` 三个任务均 `BUILD SUCCESSFUL`。
唯一改动文件：`app/build.gradle.kts`（新增一个 `packaging { resources { ... } }` 块）。未触碰任何产品 Kotlin 源码或测试。

---

## 根因

两个 **multi-release jar** 同时提供完全相同路径的 OSGi bundle 元数据，
`:app:mergeDebugJavaResource` 的 duplicate 检测在遇到第一个同名条目时即 abort：

| # | group:artifact:version | 缓存中的 jar | 冲突 entry（完整路径） |
|---|---|---|---|
| 1 | `com.github.mwiede:jsch:0.2.24` | `C:\Users\xiufe\.gradle\caches\modules-2\files-2.1\com.github.mwiede\jsch\0.2.24\7f5441a1e5155a40b665c3bebed91f8784557ae3\jsch-0.2.24.jar` | `META-INF/versions/9/OSGI-INF/MANIFEST.MF` |
| 2 | `org.bouncycastle:bcprov-jdk18on:1.78.1` | `C:\Users\xiufe\.gradle\caches\modules-2\files-2.1\org.bouncycastle\bcprov-jdk18on\1.78.1\39e9e45359e20998eb79c1828751f94a818d25b8\bcprov-jdk18on-1.78.1.jar` | `META-INF/versions/9/OSGI-INF/MANIFEST.MF` |

`jsch` 与 `bcprov` 都是 `Multi-Release: true` 的 jar，每个 `META-INF/versions/N/` 目录下都放了一份同名
`OSGI-INF/MANIFEST.MF`。上一轮为修 F14b 引入 `bcprov` 后，两者首次同处一个 APK，
AGP 的 Java-res 合并器按「同路径必须唯一」处理，于是 `assembleDebug` 挂在这一步。

**注意：不是 bcpkix / bcutil。** 离线缓存里 `bcpkix-jdk18on`（1.78.1 与 1.79）和
`bcutil-jdk18on`（1.78.1 与 1.79）同样带该 entry，但它们**不在** `debugRuntimeClasspath` 上
（见下方依赖树），因此不是本次冲突的提供者。

### 取证命令

**① 依赖树**（确认哪些 artifact 真的在运行时 classpath 上）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain --offline :app:dependencies --configuration debugRuntimeClasspath
# → research/v97_fix/deps_debugRuntimeClasspath.log
```
命中行（`research/v97_fix/deps_debugRuntimeClasspath.log:683-685`）：
```
+--- com.google.zxing:core:3.5.3
+--- com.github.mwiede:jsch:0.2.24
+--- org.bouncycastle:bcprov-jdk18on:1.78.1
```
全树中 `bouncycastle` 只出现这一处 → bcpkix/bcutil 未参与。

**② 直接扫 Gradle 缓存里所有 jar 的 zip entry**

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1" -Recurse -Filter *.jar |
  ForEach-Object { $z=[System.IO.Compression.ZipFile]::OpenRead($_.FullName)
                   $hit = @($z.Entries | Where-Object FullName -eq 'META-INF/versions/9/OSGI-INF/MANIFEST.MF')
                   $z.Dispose(); if ($hit) { $_.FullName } }
```
命中 7 个 jar（缓存侧），与 ① 求交集后只剩上表两个。

**③ 改动前原样复现故障**（`research/v97_fix/before_fix_mergeDebugJavaResource.log`）
```
> Task :app:mergeDebugJavaResource FAILED
Execution failed for task ':app:mergeDebugJavaResource'.
> A failure occurred while executing com.android.build.gradle.internal.tasks.MergeJavaResWorkAction
   > 2 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF' from inputs:
      - C:\Users\xiufe\.gradle\caches\modules-2\files-2.1\com.github.mwiede\jsch\0.2.24\7f5441a1e5155a40b665c3bebed91f8784557ae3\jsch-0.2.24.jar
      - C:\Users\xiufe\.gradle\caches\modules-2\files-2.1\org.bouncycastle\bcprov-jdk18on\1.78.1\39e9e45359e20998eb79c1828751f94a818d25b8\bcprov-jdk18on-1.78.1.jar
BUILD FAILED in 2s
```

---

## 改动

只改 `app/build.gradle.kts`，在 `android { }` 块内新增一个 `packaging { }`（原文件确实没有该块）。
纯 metadata 排除，**没有** `pickFirst`、**没有**碰 `org/bouncycastle/**`、**没有**通配 `META-INF/versions/**`。

`git diff --no-index research/v97_fix/build.gradle.kts.orig app/build.gradle.kts`
（备份 `build.gradle.kts.orig` 是本轮动手前拷的，完整 diff 存于 `research/v97_fix/build_gradle_kts.diff`）：

```diff
@@ -68,6 +68,23 @@ android {
     androidResources {
         noCompress += listOf("woff", "woff2", "wasm")
     }
+
+    packaging {
+        resources {
+            // Both `com.github.mwiede:jsch` and `org.bouncycastle:bcprov-jdk18on`
+            // are multi-release jars that ship the *same* OSGi manifest under
+            // META-INF/versions/N, so `:app:mergeDebugJavaResource` aborts on the
+            // duplicate. Those entries are pure OSGi build metadata — Android
+            // neither reads them nor D8 ever sees them — so dropping them is
+            // behaviour-neutral. The bouncycastle *classes* stay in the APK:
+            // F14b (jsch ed25519) runs on bcprov's Ed25519Signer.
+            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
+            // Round 2: AGP aborts on the *next* same-named entry it walks into.
+            excludes += "META-INF/versions/11/OSGI-INF/MANIFEST.MF"
+            // Round 3: same story for the Java 15 OSGi manifest.
+            excludes += "META-INF/versions/15/OSGI-INF/MANIFEST.MF"
+        }
+    }
 }
```

新增位置：[app/build.gradle.kts:72-87](C:/sensorsdata/main/program/opencode-android-shell/app/build.gradle.kts#L72-L87)，
紧跟既有 `androidResources { }` 之后，4 空格缩进，与文件既有风格一致。

---

## 自验

抢锁协议：跑每次 gradle 前确认仓库根无 `.agent-gradle-lock`（首次确认 `Test-Path` = `False`），
随后 `Set-Content .agent-gradle-lock "fix-packaging"` 独占，全部跑完后立即删除。全程无争用。

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --console=plain --offline :app:mergeDebugJavaResource :app:mergeExtDexDebug :app:mergeProjectDexDebug 2>&1 | Tee-Object -FilePath research\v97_fix\packaging_verify.log
```

结果（`research/v97_fix/packaging_verify.log` 尾部原文）：
```
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:checkDebugDuplicateClasses UP-TO-DATE
> Task :app:desugarDebugFileDependencies UP-TO-DATE
> Task :app:mergeExtDexDebug UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:dexBuilderDebug UP-TO-DATE
> Task :app:mergeProjectDexDebug UP-TO-DATE

BUILD SUCCESSFUL in 1s
50 actionable tasks: 50 up-to-date
```
`UP-TO-DATE` 而非重新执行，是因为 round-3 那次已经真正把 `mergeDebugJavaResource` 跑成了
（见下「其他潜在冲突」round 3 日志 `BUILD SUCCESSFUL in 3s / 43 actionable tasks: 1 executed`），
本轮是在其之上再叠加两个 dex 任务，三者全绿。
`checkDebugDuplicateClasses` 也一并通过 —— 说明**没有** class 级别重复，
这正是坚持用 `excludes`（而非 `pickFirst`）的直接收益。

未跑 `assembleDebug` / `clean` / `testDebugUnitTest`（按分工，全量闸门归 Lead）。

---

## 其他潜在冲突

`jsch-0.2.24.jar` 与 `bcprov-jdk18on-1.78.1.jar` 的 entry 交集（含目录项，AGP 不校验目录）：

```
META-INF/MANIFEST.MF
META-INF/versions/9/module-info.class
META-INF/versions/9/OSGI-INF/MANIFEST.MF
META-INF/versions/11/OSGI-INF/MANIFEST.MF
META-INF/versions/15/OSGI-INF/MANIFEST.MF
```

逐条处置（**每条都对应一次真实报错**，先跑 → 看到下一个冲突 → 才加下一条，没有预先扫射式大排除）：

| entry | 动作 | 证据（该次构建确实报了同名冲突） |
|---|---|---|
| `META-INF/versions/9/OSGI-INF/MANIFEST.MF` | `excludes +=` | `before_fix_mergeDebugJavaResource.log` — `2 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'` |
| `META-INF/versions/11/OSGI-INF/MANIFEST.MF` | `excludes +=` | `round1_mergeDebugJavaResource.log` — 加入第 1 条后重跑，报 `2 files found with path 'META-INF/versions/11/OSGI-INF/MANIFEST.MF'`（提供者同上两 jar） |
| `META-INF/versions/15/OSGI-INF/MANIFEST.MF` | `excludes +=` | `round2_mergeDebugJavaResource.log` — 加入第 2 条后重跑，报 `2 files found with path 'META-INF/versions/15/OSGI-INF/MANIFEST.MF'`（提供者同上两 jar） |
| `META-INF/versions/9/module-info.class` | **不动作** | 加入第 3 条后 `round3_mergeDebugJavaResource.log` 直接 `BUILD SUCCESSFUL in 3s`，全程再没报过它。AGP/D8 默认已丢弃 `module-info`（合并产物里计数为 0），无需也**不应**手写排除。 |
| `META-INF/MANIFEST.MF`（根） | **不动作** | 从未出现在任何一次报错里；AGP 对根 manifest 走专门的 manifest merger，不受 `resources.excludes` 影响。 |
| `META-INF/LICENSE*` | **不动作** | 合并产物 `base.jar` 中 `META-INF/LICENSE.jBCrypt.txt`、`META-INF/LICENSE.JZlib.txt`（jsch 带来）**仍在**，`META-INF/androidx/annotation/annotation/LICENSE.txt` 也在 → 本次没有 LICENSE 冲突，无证据，故不排。 |

**安全性论证（为什么只排这三个是安全的）**
1. 三者都是 OSGi bundle 元数据：Android 的 `ClassLoader` 不会读 `OSGI-INF/MANIFEST.MF`，
   D8 的 dex 输入也只取 `.class`，`mergeDebugJavaResource` 产物最终进的是 Java-res 而非代码路径。
2. 多 release jar 的**类**全部位于各自 jar 的 `META-INF/versions/N/**/*.class`，本次一条都没排。
3. 合并产物 `app/build/intermediates/merged_java_res/debug/mergeDebugJavaResource/base.jar`
   实测复核（`entries=107`）：
   - `OSGI-INF` 幸存 3 条 = `META-INF/versions/10/OSGI-INF/MANIFEST.MF`、
     `META-INF/versions/16/OSGI-INF/MANIFEST.MF`、`META-INF/versions/21/OSGI-INF/MANIFEST.MF`
     （来自 kotlin-stdlib 等，**未被误排**）→ 证明用的是精确路径而非通配。
   - `module-info` 幸存 0 条（AGP 默认丢弃）。
   - `org/bouncycastle/**` 资源仍在，例如
     `org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties`、
     `org/bouncycastle/x509/CertPathReviewerMessages.properties` → bcprov 未被排掉。

---

## bcprov 进产物取证（只读，未改任何文件）

- **classpath 侧**：`research/v97_fix/deps_debugRuntimeClasspath.log:685`
  `+--- org.bouncycastle:bcprov-jdk18on:1.78.1` ✅ 仍在，未被 exclude/downgrade。
- **dex 侧**：对 `app/build/intermediates/dex/debug/` 下所有 `*.dex` 做字节级检索：

  | 检索串 | 命中文件 | 结论 |
  |---|---|---|
  | `Lorg/bouncycastle/crypto/signers/Ed25519Signer;` | `mergeExtDexDebug\classes3.dex` | ✅ F14b 真正要用的签名器在 dex 里 |
  | `Lcom/jcraft/jsch/bc/` | `mergeExtDexDebug\classes3.dex` | ✅ jsch 的 bc 实现族在 dex 里 |
  | `Lcom/jcraft/jsch/Session;` | `mergeExtDexDebug\classes3.dex` | ✅ jsch 本体在 |
  | `Lcom/jcraft/jsch/jce/edec/` | 无命中 | 与 `build.gradle.kts` 里 F14b 注释一致：`jce` 的 jar base entry 是 Java15+ stub，D8 不打包；可运行实现走 `bc` 族。属既有状态，非本次改动引入。 |

`mergeProjectDexDebug` 已产出 dex（`app/build/intermediates/dex/debug/mergeProjectDexDebug/0..15/classes.dex`，
共 16 个分片，最大 1.7 MB），`mergeExtDexDebug` 产出 3 个分片（17.3 / 10.3 / 11.4 MB）。

---

## 未闭环 / 未验到

1. **APK 级验证未做**（`assembleDebug` 属 Lead 的全量闸门，本轮明令禁止）。
   「排除三个 OSGi manifest 之后 `packageDebug` 能否走完、签名与安装是否正常」**未验到**。
2. **设备上 ed25519 实际握手未验**。dex 里有 `Ed25519Signer` + `com.jcraft.jsch.bc.*` 只能证明
   代码在包里；F14b 真机行为仍需 Lead 的 APK 验证闭环。
3. **release 变体未验**。只跑了 `debug` 相关任务；`releaseRuntimeClasspath` 与 debug 同源
   （同一份 `implementation`），冲突应当一致消失，但**未实测**。
4. `mergeExtDexDebug` / `mergeProjectDexDebug` 在最终验证里是 `UP-TO-DATE`，
   未在本次改动后被重新执行（其输入未变，Gradle 判定无变化）；bcprov 的 dex 证据取自
   17:54 那批已产出分片。

---

## 附：证据文件清单（均在 `research/v97_fix/`）

| 文件 | 内容 |
|---|---|
| `PACKAGING_FIX.md` | 本报告 |
| `build_gradle_kts.diff` | 本轮改动的 unified diff（vs 动手前备份） |
| `build.gradle.kts.orig` | 动手前的 `app/build.gradle.kts` 副本（仅作 diff 基线，不参与构建） |
| `deps_debugRuntimeClasspath.log` | `:app:dependencies --configuration debugRuntimeClasspath` 全量输出 |
| `before_fix_mergeDebugJavaResource.log` | 修复前复现的 `versions/9` 冲突 |
| `round1_mergeDebugJavaResource.log` | 加第 1 条后暴露的 `versions/11` 冲突 |
| `round2_mergeDebugJavaResource.log` | 加第 2 条后暴露的 `versions/15` 冲突 |
| `round3_mergeDebugJavaResource.log` | 加第 3 条后 `BUILD SUCCESSFUL` |
| `packaging_verify.log` | 终验：三个任务同跑，`BUILD SUCCESSFUL` |
