# F14b 验证报告 — mwiede jsch 0.2.24 ed25519「公钥未被 offer」根因判定（task-1）

> 日期：2026-10-02 17:20 左右收敛。worker：F14b 修复 agent（glm-5.3）。
> 任务假设：**jsch 0.2.24 的 ed25519 签名因缺 optional 依赖 `net.i2p.crypto:eddsa` 而静默跳过 identity**。

## 0. 一句话结论

**假设证伪，但现象根因坐实且已半修复**：jsch 0.2.24 与 `net.i2p.crypto:eddsa` **零关系**（jar 内所有 class 无一处 `net/i2p` 引用、POM 不声明任何依赖）；真机「公钥未被 offer」的根因是 **Android 上两条 ed25519 签名实现全灭**——默认 `ssh-ed25519` 配置单值指向 `jce.SignatureEd25519`，其 jar **基线 entry 是 Java15+ 桩**（D8 只打包基线版，`META-INF/versions/15/` 的真实现进不了 APK，桩字符串在已装机 APK 的 classes19.dex 里原样可见），而唯一备胎 `bc.SignatureEd25519` 委托外部 BouncyCastle（APK 里没有）→ `KeyPairEdDSA.getSignature()` 吞掉异常返回 null → `UserAuthPublicKey` 无签名可 offer → **静默跳过 identity** → 服务端回落 keyboard-interactive。已加 `bcprov-jdk18on:1.78.1`（离线缓存已有，GREEN）；但 **光加依赖不改变默认路由**，端到端修复还差一步产品代码 `JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")`——`SshConnection.kt` 不在本任务文件清单内，**留给 Lead 拍板**（拟改 diff 见 §6）。

## 1. 静态证据链（假设证伪）

### 1.1 jsch jar 里 ed25519 相关类（`jar tf`）

```
Jar: C:\Users\xiufe\.gradle\caches\modules-2\files-2.1\com.github.mwiede\jsch\0.2.24\
     7f5441a1e5155a40b665c3bebed91f8784557ae3\jsch-0.2.24.jar
$ jar tf jsch-0.2.24.jar | findstr /i "ed25519 eddsa"
  META-INF/versions/15/com/jcraft/jsch/jce/KeyPairGenEdDSA.class   ← 真实现（仅 MR jar）
  META-INF/versions/15/com/jcraft/jsch/jce/SignatureEd25519.class  ← 真实现（仅 MR jar）
  META-INF/versions/15/com/jcraft/jsch/jce/SignatureEdDSA.class
  com/jcraft/jsch/KeyPairEd25519.class
  com/jcraft/jsch/KeyPairEdDSA.class
  com/jcraft/jsch/KeyPairGenEdDSA.class        （接口）
  com/jcraft/jsch/SignatureEdDSA.class         （接口）
  com/jcraft/jsch/bc/KeyPairGenEdDSA.class
  com/jcraft/jsch/bc/SignatureEd25519.class
  com/jcraft/jsch/bc/SignatureEdDSA.class
  com/jcraft/jsch/jce/KeyPairGenEdDSA.class    ← 基线=桩
  com/jcraft/jsch/jce/SignatureEd25519.class   ← 基线=桩
```

### 1.2 全量搜 `net/i2p` 引用 → 零命中

```
# 解压整个 jar 到 %TEMP%\jsch0224，逐个 .class 读字节搜 "net/i2p"
$hits = Get-ChildItem $tmp -Recurse -Filter "*.class" | ? { [IO.File]::ReadAllBytes($_) 里的 ASCII 串含 "net/i2p" }
→ NO-CLASS-REFERENCES net/i2p
```

### 1.3 POM 无任何依赖段

`C:\Users\xiufe\.gradle\caches\modules-2\files-2.1\com.github.mwiede\jsch\0.2.24\ff4864e0c66b21cb9d56e04a4fee9e1bd0fca726\jsch-0.2.24.pom`
只有 `<developers>/<licenses>/<scm>`，**没有 `<dependencies>`** ——所谓「optional eddsa 依赖」在 0.2.24 线已不存在（那是 0.1.x/旧文档的说法）。

### 1.4 基线 jce 类是桩（`javap -c`）

```
$ javap -cp jsch-0.2.24.jar -p -c com.jcraft.jsch.jce.SignatureEd25519
public com.jcraft.jsch.jce.SignatureEd25519();
   4: new java/lang/UnsupportedOperationException
   8: ldc "SignatureEd25519 requires Java15+."   ← 构造器即抛
（init/setPubKey/setPrvKey/sign/update/verify 同样全抛）
```

### 1.5 bc 路径的外部依赖是 bcprov，不是 eddsa（`javap -c`）

```
$ javap -cp jsch-0.2.24.jar -p -c com.jcraft.jsch.bc.SignatureEdDSA
  → org/bouncycastle/crypto/params/Ed25519PublicKeyParameters
  → org/bouncycastle/crypto/params/Ed25519PrivateKeyParameters
  → org/bouncycastle/crypto/signers/Ed25519Signer
$ javap ... com.jcraft.jsch.bc.KeyPairGenEdDSA
  → org/bouncycastle/crypto/params/Ed25519PrivateKeyParameters 等
全部在 org.bouncycastle:bcprov-jdk18on（bcpkix 不需要）。
```

## 2. 动态证据链

### 2.1 真机 APK dex 取证（决定性）

对 Lead 上一轮 `assembleDebug` 的产物 `app/build/outputs/apk/debug/app-debug.apk`（19 个 dex）解包后按字节搜索：

```
classes19.dex: java15Stub=True bouncycastle=True netI2p=False
               bcSigEd25519=True jceSigEd25519=True
其余 dex:      无 jsch 类；全部 dex 无 BouncyCastleProvider 类定义
```

判读：
- `"SignatureEd25519 requires Java15+"` 字符串在 classes19.dex **原样存在** → D8 打进 APK 的 `jce.SignatureEd25519` 是**桩**，MR versions/15 真实现没进包 → Android 运行时 jce 路径 100% 抛 `UnsupportedOperationException`。
- `bc.SignatureEd25519` 类进了 dex，但对 `org/bouncycastle/*` 的引用**无类可解析**（APK 无 BC 本体）→ `NoClassDefFoundError`。
- `net/i2p` 零命中（与 §1.2 一致）。

### 2.2 默认配置实测（JVM 探针，classpath 仅 jsch jar）

```java
JSch.getConfig("ssh-ed25519")               → "com.jcraft.jsch.jce.SignatureEd25519"   ← 单值！无 bc 回退链
JSch.getConfig("keypairgen.eddsa")          → "com.jcraft.jsch.jce.KeyPairGenEdDSA"
JSch.getConfig("keypairgen_fromprivate.eddsa") → "com.jcraft.jsch.bc.KeyPairGenEdDSA"  ← jsch 自己某些路径就要 BC
JSch.getConfig("signature.ed25519")         → null（此键在 0.2.24 不存在）
```

`KeyPairEdDSA.getSignature(data, alg)` 字节码：`JSch.getConfig(alg)` → `Class.forName` → `init()/setPrvKey/update/sign`，异常吞掉后**返回 null** —— 即「静默跳过」的实现点；`UserAuthPublicKey` 拿 null 签名 → 不 offer。

### 2.3 JVM vs Android 行为分叉（探针，classpath 仅 jsch，无 BC 无 eddsa）

```
JDK21 + jsch only:
  bc.SignatureEd25519 init: FAILED -> NoClassDefFoundError: org/bouncycastle/crypto/Signer
  jce.SignatureEd25519 init: OK（multirelease→versions/15 真实现）
  addIdentity(真实key, keypass123): algName=ssh-ed25519 blob=51B encrypted=false（解析层无罪）
  identity.getSignature(data, "ssh-ed25519") = 83B（JVM 上签名链可用）
Android（同一 jar 经 D8）:
  jce → 桩 UnsupportedOperationException（§2.1）
  bc  → NoClassDefFoundError（APK 无 BC）
  ⇒ 候选全灭 ⇒ getSignature=null ⇒ 公钥未被 offer ⇒ kbdint 回落
```

## 3. RED → GREEN

新增测试类 `app/src/test/java/com/xiufeigo/opencodeshell/SshEd25519IdentityF14Test.kt`（4 个测试）：
1. `encryptedOpensshEd25519KeyParsesAndDecryptsIntoAnOfferableIdentity` — 真实 openssh-key-v1 加密 ed25519（passphrase `keypass123`）经 `addIdentity` 解析+解密+51B blob（解析层无罪 pin）。
2. `androidShipsTheJava15StubWhileTheJvmRunsTheMultireleaseImpl` — 从 classpath 的 jsch jar 读**基线 entry** 字节断言桩字符串在，同时断言 JVM 运行时类是 versions/15 真实现（分叉 pin）。
3. `bouncyCastleEd25519SignerRoundTripsOnTheClasspath` — **RED→GREEN 核心**：`bc.KeyPairGenEdDSA` 生成 + `bc.SignatureEd25519` 按 jsch 内部调用序（init→setPrvKey→update→sign，verify 侧 SSH wire blob）完整签名/验签往返。
4. `defaultEd25519SignatureConfigIsJceOnlyWithNoBcFallback` — pin 0.2.24 默认 `ssh-ed25519` 单值 jce 无回退链（光加 BC 不改路由 = 仍然不工作，产品代码必须 setConfig 的依据）。

命令与结果（均持锁 `.agent-gradle-lock` 运行，用后即删；`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`）：

```
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SshEd25519IdentityF14Test"
```

| 阶段 | 依赖 | GRADLE_EXIT | XML 统计 |
|---|---|---|---|
| RED | 仅 jsch 0.2.24 | 1（BUILD FAILED） | tests=4 failures=1 errors=0 —— `bouncyCastleEd25519SignerRoundTripsOnTheClasspath` 抛 `java.lang.NoClassDefFoundError`（caused by `ClassNotFoundException: org.bouncycastle...`），其余 3 绿 |
| GREEN | + `bcprov-jdk18on:1.78.1` | 0（BUILD SUCCESSFUL） | tests=4 failures=0 errors=0 skipped=0 |

XML：`app/build/test-results/testDebugUnitTest/TEST-com.xiufeigo.opencodeshell.SshEd25519IdentityF14Test.xml`
（RED 阶段输出已被 GREEN 覆盖，RED 现场摘录自当时 stdout：`java.lang.NoClassDefFoundError at SshEd25519IdentityF14Test.kt:128 / Caused by: ClassNotFoundException / 4 tests completed, 1 failed`。）

中途两次非预期失败及修复（诚实记录）：
- `Ed25519Signer not initialised for signature generation`：jsch `bc.SignatureEdDSA` 的 setPrvKey=签名模式 init、setPubKey=验签模式 init，**同一 signer 后调者覆盖**；改为 jsch 内部同款调用序（签名侧不调 setPubKey）。
- `arraycopy: last source index 262148 out of bounds for byte[64]`：jsch `sign()` 返回**裸 64B 签名**而 `verify()` 收 **SSH wire blob**（string 算法名+string 签名，KeyPair 层本来负责包装）；测试自己包 blob 后通过。

## 4. 改动文件清单

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 在 `com.github.mwiede:jsch:0.2.24` 下新增 `implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")` + 说明注释（jsch 的 bc.* 需要 bcprov；Gradle 离线缓存已有 1.77/1.78.1/1.79，无离线解析风险） |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshEd25519IdentityF14Test.kt` | 新建，4 测试（§3） |

**未动**：`SshConnection.kt` 等产品代码（不在授权清单；拟改方案见 §6）。

## 5. 验收问题逐条作答

1. **jsch 0.2.24 的 ed25519 需要哪些 jar？** 类全在 `jsch-0.2.24.jar` 自身（`jce.*`/`bc.*`/`KeyPair*`/`Signature*`）；要让它**在 Android 上真能签**，只需额外 `org.bouncycastle:bcprov-jdk18on`（1.78.1 已加）。`net.i2p.crypto:eddsa` 完全不需要、加了也没用（无引用）。
2. **jar 里 ed25519 类是否存在、依赖能否解析？** 存在（§1.1 清单）。`jce` 基线类自包含但是桩（Java15+ 才有真实现，且只活在 MR versions/15，Android 拿不到）；`bc` 类编译期自包含、运行期依赖 bcprov（现状缺 → `NoClassDefFoundError`，§2.3 实测）。
3. **测试/实验支撑**：RED→GREEN（§3）+ APK dex 取证（§2.1）+ JVM/Android 分叉探针（§2.3）。
4. **精确测试命令与统计**：见 §3 表。

## 6. 未闭环项（需要 Lead 决策）

1. **端到端还差一步（产品代码，未动）**：默认 `ssh-ed25519 → jce.SignatureEd25519` 单值路由在 Android 是死桩；**必须在连接路径显式切到 bc**。拟改（`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt`，`connect()` 内 `JSch()` 创建后）：
   ```kotlin
   val jsch = JSch()
   // F14b: jsch's stock ssh-ed25519 route is the jce Java15+ stub on Android
   // (D8 never packs META-INF/versions/15); bc.* + bcprov is the live path.
   JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
   JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
   ```
   （`keypairgen_fromprivate.eddsa` 默认已是 bc，无需设。）注意 `JSch.setConfig` 是全局静态，放 `connect()` 每次设置是幂等的；若 Lead 嫌全局副作用，也可只设 `ssh-ed25519` 一项。**改动文件归属 `SshConnection.kt`，等 Lead 回话，我未动手。**
2. **真机终验未做**（本任务禁装机）：v97 计划里的「F14b+F12a 修正口径」端到端（KEY 条目、key=app 私有目录、passphrase 留空→弹卡输 `keypass123`→Connected + sshd `Accepted publickey`）应在 Lead 落 §6.1 并出新 APK 后执行。**当前 APK（17:18 构建）不含 bcprov，装机验必失败，别拿旧包验。**
3. `bcprov-jdk18on:1.78.1` 会随 `implementation` 进 APK（增量约 8MB/混淆前）；若在意体积可后续评估 `1.79` 或仅引必要类（不建议自切类，升级会碎）。
4. `team_task_get`/`team_task_update` 工具本 session 不可用（工具清单里没有），task-1 的 claim/complete 请 Lead 代操作。

## 7. 新发现（不修，只报）

- jsch 0.2.24 在 JVM 上 `KeyPair.genKeyPair(ED25519)` 后 `writePrivateKey(...)` 抛 `UnsupportedOperationException`（`KeyPairEdDSA.getPrivateKey:75`，EdDSA 私钥导出未接 bc）——与本 bug 无关（app 只读不写），记录备查。
- jsch 文档层面的「optional eddsa」说法对应 0.1.x 分支；0.2.x 分支 README 改推 BouncyCastle。团队 brief/HANDOFF 中 F14b 的措辞可照本报告更新。
