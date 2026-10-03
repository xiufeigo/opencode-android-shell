# SSH_KEY_AUTH_FIX — P3 私钥退化：选了 `Private key` 却弹 `Password:` 卡

- 轮次：修复轮（承接 `research/v97_verify/5554_v98d3/REPORT_SSH_DIAG.md` §4.4 的 **P3**）
- 改动范围（**仅 `ui/servers/**` + 1 个新测试文件**）：
  - `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt`
  - `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt`
  - `app/src/test/java/com/xiufeigo/opencodeshell/SshKeyAuthV100Test.kt`（新增，14 用例）
- 未跑 gradle 之外的任何构建；**未装机**；未动 `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、
  `ui/session/**`、`ui/home/**`、`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`
- **未新增任何用户可见字符串**（0 个新资源，§4 有说明）

> ## 一句话结论
>
> **断在第 4 环：`PreferredAuthentications` 从来没有被设置过。**
> `SshConnection` 把私钥正确加载了（探针实测 `encrypted=false`，密钥可签名），
> jsch 却被允许按它自己的默认**方法行走** `gssapi-with-mic → publickey → keyboard-interactive → password`：
> publickey 一旦没被服务器接受，jsch 就**继续往下走**，走到 `keyboard-interactive`，
> 而本壳的 `promptKeyboardInteractive` 在一个 `Private key` 条目上照画了一张 **`Password:`** 卡。
> 运维输了账号口令，sshd 记 `Accepted keyboard-interactive/pam`，行状态 `Connected` —— 私钥**一次都没用过**，
> 界面也没有任何提示。**这才是「退化」：不是 publickey 失败，而是失败后悄悄换了一种凭证去成功。**

---

## 1. 链路逐环表

从「表单选 `Private key` + keypath」到「jsch 用私钥做 publickey 认证」，逐环定性：

| # | 环 | 现状（修前） | 是否断点 |
|---|---|---|---|
| 1 | 表单 `auth` 是否传到连接模型 | `SshDialog.kt:135` `var auth by remember { mutableStateOf(SshAuthMethod.PASSWORD) }` → `:461` `auth = SshAuthMethod.valueOf(value)` → `:254` `SshConnectionConfig(auth = auth, …)`。**`KEY` 值如实送达，未被默认成 `Password`** | ❌ **不是断点** |
| 2 | keypath 是否被读成 `Identity` | `SshConnection.kt` 修前 `val path = config.privateKeyPath?.trim().orEmpty()` → `jsch.addIdentity(File(path).expandUser().absolutePath, …)`。探针实测 `identities=1  alg=ssh-ed25519`，**身份确实建出来了** | ❌ **不是断点** |
| 3 | passphrase 是否被传入 / 是否与账号口令弄错 | 修前 `jsch.addIdentity(…, config.passphrase)`，字段正确（账号口令在 `config.password`，是另一个字段）。探针实测：给 `keypass123` → `encrypted=false`（**已解密、可签名**）；给 `null` → `encrypted=true` | ❌ **不是断点** |
| 4 | `PreferredAuthentications` 是否只跑 publickey | **全仓 `app/src/main` 命中 0 次。`grep "PreferredAuthentications"` 只有测试和本轮新增代码。** jsch 用默认值 `gssapi-with-mic,publickey,keyboard-interactive,password` = **一次行走**，不是一次选择 | ✅ **← 断在这里** |
| 5 | `com.jcraft.jsch.bc` 的 `SignatureEd25519` / `edec` 是否运行时真注册 | **是**。`SshConnection.kt:105` `routeEd25519ThroughBouncyCastle()` 在 `:106` `JSch()` **之前**调用（顺序正确），`:508-511` 设 `ssh-ed25519` / `keypairgen.eddsa` 两个 config；`keypairgen_fromprivate.eddsa` 本就默认 bc。dex 证据 `apk_f14b_evidence.md`：`classes19.dex` 含 `com/jcraft/jsch/bc/SignatureEd25519` + `Ed25519Signer` | ❌ **不是断点** |
| 6 | 失败是否被静默吞掉并回落 password | **是，这是退化语义本身**。`KeyPairEdDSA.getSignature()` 吞异常返回 `null` → `UserAuthPublicKey` 无签名可递 → 该身份被静默跳过 → jsch 走下一方法；`promptKeyboardInteractive`（修前无方法闸门）照画 `Password:` 卡，`respondPrompt` 把它当答案交回 | ✅ **← 第二处断点** |

### 6 环合起来的因果链（修前）

```
表单选 Private key + keypath=/data/user/0/…/id_ed25519 (+ 或未填 passphrase)
  → SshDialog.kt:250  SshConnectionConfig(auth=KEY, privateKeyPath=…, passphrase=…)
  → SshConnection.kt  addIdentity(path, config.passphrase)      ← 密钥确实加载了（encrypted=false）
  → getSession(user, host, port)                                  ← 主机键核验卡正常弹出（真机已证）
  → ⚠ 没有任何 PreferredAuthentications → jsch 默认 4 方法行走
  → publickey：服务端不认 / 签名不成立 / 重连时秘密未持久化
      → KeyPairEdDSA.getSignature() 返回 null（吞异常）→ 身份被静默跳过
  → jsch 继续：keyboard-interactive
  → SshUserInfo.promptKeyboardInteractive 画卡：「SSH authentication / Password:」  ⚠ P3
  → 运维输入账号口令 opencode → arrayOf("opencode")
  → sshd: Accepted keyboard-interactive/pam          ← 全窗口无 Accepted publickey
  → 行状态 Connected，私钥从未被使用，UI 无任何异常提示
```

**为什么 F14/F14b 没能发现**：那条链上**每一环单独看都是通的**（表单、路径、passphrase、bc 路由都正确）。
断的是「jsch 拿到一个没被接受的 publickey 之后会做什么」，而这一步**没有本壳自己的代码**——
所以它在 F14b 修好 ed25519 路由后依然存在，是**同一条链上的第二个缺陷**，不是 F14b 的回归。

### 一个必须记的排除项

- **`REPORT_SSH_DIAG` §4.2 里的 `Passphrase 留空`**：诊断轮那条复现确实没填 passphrase。
  `addIdentity(path, null)` 让身份停在 `encrypted=true`（本轮探针已实测），
  这是**合法**的「重连时秘密不持久化、由交互卡补收」形态（v94 已修），
  **不是** P3 的成因。P3 在**填了 `keypass123`** 的情况下同样发生——因为 publickey 没被接受时
  jsch 本来就会往下走。修法因此不能是「强制要求 passphrase」。

---

## 2. 根因（文件:行号 + 原文）

### 断点 1（主因）：方法列表从未收窄

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt`，修前 `connect()` 内：

```kotlin
            building = jschSession
            session = jschSession
            jschSession.setConfig("StrictHostKeyChecking", "ask")
            jschSession.userInfo = SshUserInfo(repository, attempt)
            if (config.auth == SshAuthMethod.PASSWORD) jschSession.setPassword(config.password)
            val startedAt = System.currentTimeMillis()
            jschSession.connect(connectTimeoutMs)
```

`StrictHostKeyChecking` 设了，**`PreferredAuthentications` 一行都没有**。
jsch 的 `Session.connect` 因此按 `JSch.getConfig("PreferredAuthentications")` 的默认值行走四方法。

### 断点 2（放大器）：交互回调没有方法闸门

`SshConnection.kt` 修前 `promptKeyboardInteractive`：

```kotlin
        ): Array<String>? {
            val text = listOfNotNull(instruction, prompt?.joinToString("\n"))
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val challenge = publishPrompt(attempt, text, confirm = false, hostKey = null) ?: return null
            val answer = awaitAnswer(attempt, challenge)
            return if (answer.accepted) arrayOf(answer.value.orEmpty()) else null
        }
```

它**不看 `config.auth`**：一个 `Private key` 条目走到这里，照样画 `Password:` 卡并把输入交回 jsch。
`promptPassword` 同理（修前 `config.password?.let { … }` 对 KEY 条目也是可走的分支）。

---

## 3. 修

### 3.1 纯函数：方法表 + 凭证表（新增到 `SshConnectionModel.kt`）

`sshPreferredAuthentications(auth)` —— **一个方法宽**，由表单选择决定：

| 表单 `auth` | `PreferredAuthentications` | 理由 |
|---|---|---|
| `Private key` | **`publickey`** | 身份签不出名就**失败并被如实上报**，而不是问一个用户没选过的 secret |
| `Password` | **`password,keyboard-interactive`** | 账号口令能正当回答的两种方法；`publickey` 排除在外，免得陈旧身份悄悄救场或悄悄顶替 |

`sshAuthPlan(config)` → `SshAuthPlan(method, identityPath, passphrase, password, preferredAuthentications)`：
两个秘密严格分槽，且 `Private key` 的 `password` **恒为 `null`**、`Password` 的 `identityPath` **恒为 `null`**；
空白 secret 归一为 `null`（`asSecret()` 用 `isNotBlank`）而不是 `""`——
jsch 会把 `""` 当成一个错误的 passphrase 去解密，与传 `null`（"没带"）语义完全不同。

### 3.2 接线（`SshConnection.kt`）

```diff
             val jsch = JSch()
             val repository = KnownHostsRepository()
             jsch.setHostKeyRepository(repository)
-            when (config.auth) {
+            val plan = sshAuthPlan(config)
+            // v100 (P3): the method guards in [SshUserInfo] read this. It is set
+            // before the UserInfo is handed to the session and only for the
+            // attempt being started, so a superseded dial's callbacks read the
+            // same table its own `attempt` stamp already muted.
+            activePlan = plan
+            when (plan.method) {
                 SshAuthMethod.KEY -> {
-                    val path = config.privateKeyPath?.trim().orEmpty()
+                    val path = plan.identityPath.orEmpty()
                     if (path.isEmpty()) {
                         return@withContext fail(attempt, SshFailure(SshStage.FAILED, SshErrorCode.INPUT, "key path"))
                     }
-                    jsch.addIdentity(File(path).expandUser().absolutePath, config.passphrase)
+                    // v100 (P3): the passphrase is handed to `addIdentity` so
+                    // the key is decrypted **now**; a key that stays encrypted
+                    // (no passphrase, or a wrong one) is a key that cannot sign,
+                    // and jsch's answer to that used to be "ask for a password
+                    // instead". With `PreferredAuthentications` pinned to
+                    // `publickey` below, it now fails here instead.
+                    jsch.addIdentity(File(path).expandUser().absolutePath, plan.passphrase)
                 }
 
                 SshAuthMethod.PASSWORD -> {}
             }
             val jschSession = jsch.getSession(
                 target.user ?: defaultSshUser(),
                 target.host,
                 target.port,
             )
             building = jschSession
             session = jschSession
             jschSession.setConfig("StrictHostKeyChecking", "ask")
+            // v100 (P3): one method wide, derived from the form's selection.
+            // Without this jsch walks `publickey → keyboard-interactive →
+            // password`, which is how a `Private key` entry ended up asking for
+            // an account password and connecting with it.
+            jschSession.setConfig("PreferredAuthentications", plan.preferredAuthentications)
             jschSession.userInfo = SshUserInfo(repository, attempt)
-            if (config.auth == SshAuthMethod.PASSWORD) jschSession.setPassword(config.password)
+            if (plan.method == SshAuthMethod.PASSWORD) jschSession.setPassword(plan.password)
```

### 3.3 纵深防御：三个回调各自加方法闸门

```diff
         override fun promptPassword(message: String): Boolean {
+            // v100 (P3): a `Private key` entry has no account password to give.
+            // Answering this would be the degradation itself — the user is asked
+            // for a secret the form never collected for this method.
+            if (activePlan.method != SshAuthMethod.PASSWORD) return false
             config.password?.let {
                 suppliedPassword = it
                 return true
             }
 
         override fun promptPassphrase(message: String): Boolean {
+            // v100: symmetric guard — the key passphrase is a `Private key`
+            // entry's secret and must never be collected for a password one.
+            if (activePlan.method != SshAuthMethod.KEY) return false
             config.passphrase?.let { return true }
 
         ): Array<String>? {
+            // v100 (P3): keyboard-interactive is only ever a *password* method.
+            // A `Private key` entry reaching it means publickey produced nothing
+            // offerable, and the card drawn here is the `Password:` prompt the
+            // device recorded (`REPORT_SSH_DIAG.md` §4.4). Refusing hands the
+            // failure back to jsch, which now has no further method and reports
+            // it — instead of silently succeeding on a different credential.
+            if (activePlan.method != SshAuthMethod.PASSWORD) return null
             val text = listOfNotNull(instruction, prompt?.joinToString("\n"))
```

配套新增字段（`SshConnection.kt:83`）：

```diff
+    @Volatile
+    private var activePlan: SshAuthPlan = sshAuthPlan(config)
```

> `activePlan` 用**字段**而不是 `SshUserInfo` 构造参数，是为了保住既有 pin
> `SshReconnectFidelityV97Test.connectStampsItsAttemptBeforeItSpeaks` 对字面量
> `SshUserInfo(repository, attempt)` 的断言（见 §5）。它在 `connect()` 里、绑定 UserInfo 之前赋值，
> 且只描述当前 attempt；被顶替的 attempt 早已被 `guard` 静音并在更早处被拒。

---

## 4. 为什么不降强度

### 4.1 被本次改动打破的 4 个既有 pin —— 全部靠**改生产代码**满足，**没有削弱任何断言**

第一轮回归确实红了 4 条，处理方式是**让生产代码长成它们要的样子**，不是改测试：

| 既有 pin | 它要的字面量 | 满足方式 |
|---|---|---|
| `SshPassphraseAnswerV94Test.aFormPassphraseAnswersWithoutEverPublishingAChallenge` | `promptPassphrase` 内 `config.passphrase?.let { return true }` 且在 `publishPrompt(` 之前 | 保留该字面量原样，闸门放在它**之前** |
| `SshPassphraseAnswerV94Test.getPassphrasePrefersTheRetainedAnswerOverTheFormValue` | `getPassphrase(): String? = suppliedPassphrase ?: config.passphrase`、`getPassword(): String? = suppliedPassword ?: config.password` | 两个 getter **逐字保留**；方法闸门在 `prompt*` 侧完成，不动 getter 的 v94 语义（收集到的答案仍优先于表单值） |
| `SshPassphraseAnswerV94Test.aBlankFormPassphraseStillDecryptsThroughTheFallback` | 同上 + `private var suppliedPassphrase: String? = null` | 同上，未动 |
| `SshReconnectFidelityV97Test.connectStampsItsAttemptBeforeItSpeaks` | `SshUserInfo(repository, attempt)` | 改用 `activePlan` 字段承载 plan，构造调用恢复两参 |

> 这 4 条的**语义**（v94 的答案不能丢、v97 的 attempt 戳记）**一条都没有被放松**，
> 且我的新 pin 额外断言 `activePlan = plan` 发生在 `SshUserInfo(...)` 之前。

### 4.2 安全边界只紧不松

| 既有语义 | 状态 |
|---|---|
| 主机键永不自动信任 | **未动**：`setConfig("StrictHostKeyChecking", "ask")` 原样；`promptYesNo` 原样；`known_hosts` 只在 `ssh.trust` 后 `add` |
| 口令认证路径不得退化 | **未动且更宽于最小集**：`Password` 条目保留 `password,keyboard-interactive` 两种方法（没有收窄成只剩 `password`） |
| D1/D2/D3 attempt guard（F12b） | **未动**：闸门放在 `publishPrompt`/`awaitAnswer` 之外，只做「方法对不对」，不碰「这一代 attempt 说不说话」 |
| ed25519 → BouncyCastle 路由（F14b） | **未动**：`routeEd25519ThroughBouncyCastle()` 仍在 `JSch()` 之前调用；新 pin `theEd25519RoutingIsStillInstalled` 钉住 |
| F14a 错误归因（`KEY_FILE`） | **未动**：`sshFailure` 一行未改；新 pin 钉住 `KEY_FILE` / `INPUT` / `AUTHENTICATION` 三档归属 |
| 用户可见字符串 | **新增 0 个**：方法表与凭证表都是内部值，`ssh_error_*` 全部复用既有句子 |

### 4.3 失败从「静默回落」变成「可诊断」

| 场景 | 修前 | 修后 |
|---|---|---|
| keypath 错 / 不可读 | `addIdentity` 抛 `FileNotFoundException` → `KEY_FILE`（本就正确，F14a） | 同左，**未退化** |
| passphrase 错 / 缺失 | 身份被跳过 → 弹 `Password:` → 输口令 → **`Connected`（静默成功）** | publickey 是唯一方法 → jsch 报 `Auth fail` → `AUTHENTICATION` 阶段 + `Could not establish…`；或有 passphrase 时走 `promptPassphrase` 弹 **passphrase 卡**（不再是要账号口令的 `Password:` 卡） |
| keypath 为空 | `INPUT` + `key path` | 同左（改由 `plan.identityPath` 判空） |

---

## 5. 测试证据

### 5.1 新增 JVM pin（纯 JVM，14 用例）

`app/src/test/java/com/xiufeigo/opencodeshell/SshKeyAuthV100Test.kt` —— 钉住「auth 选择 → 连接参数」真值表、
passphrase 与账号口令不得混用、以及产品代码的接线：

| 用例 | 钉住什么 |
|---|---|
| `aPrivateKeyEntryCarriesIdentityAndPassphraseAndNeverAnAccountPassword` | `Private key` ⇒ 带 identity+keypath+passphrase，且 **`password` 恒 `null`**（哪怕 config 里躺着 `opencode`） |
| `aPasswordEntryCarriesThePasswordAndNoIdentity` | `Password` ⇒ 带 password，**`identityPath`/`passphrase` 恒 `null`** |
| `theKeypathIsReadAsAnIdentityPathNotADroppedExtra` | 设备路径 `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519` 原样存活（trim 后逐字相等） |
| `aBlankSecretIsAbsentRatherThanAnEmptyString` | 空白 secret ⇒ `null`，**不把 `""`/`"   "` 当 passphrase 递给 jsch** |
| `aKeyEntryWithoutAKeypathIsStillKeyAndCarriesNoIdentity` | 无 keypath 时**仍然是 KEY**，不偷偷切到 password |
| `thePassphraseAndTheAccountPasswordAreNeverInterchangeable` | **两 secret 不得互换**（`keypass123` ≠ `opencode` 双向） |
| `aKeyEntryMayOnlyRunPublickeyAndAPasswordEntryOnlyPasswords` | 方法表真值：`KEY ⇒ publickey`；`PASSWORD ⇒ 含 password+kbdint 且不含 publickey` |
| `theRealDeviceKeyDecryptsWithItsOwnPassphraseAndStaysLockedWithoutIt` | **用真机那把 openssh-key-v1 加密 ed25519 密钥实测**：`keypass123` ⇒ `encrypted=false` + 51 字节公钥 blob（可签名）；`null` ⇒ `encrypted=true` |
| `anUnusableKeyStillReportsAKeyFileFailureRatherThanAConnectionOne` | 不可用密钥仍报 `KEY_FILE`；口令错仍 `INPUT`；服务端拒绝仍 `AUTHENTICATION` |
| `theDialNarrowsTheMethodListBeforeItConnects` | 产品代码：方法表在 `jschSession.connect(` **之前**设置；identity/password 都来自 `plan` |
| `everySecretCallbackRefusesTheSecretOfTheOtherMethod` | 三个 secret 回调**各自**带方法闸门；两个 getter 仍读各自 config 字段 |
| `theHostKeyTrustPathIsUntouched` | `StrictHostKeyChecking=ask` + `promptYesNo` 仍发 confirm 卡 + 拒绝即 `false` |
| `theEd25519RoutingIsStillInstalled` | F14b 的 bc 路由仍在，且仍在 `JSch()` 之前 |
| `theDeviceScenarioIsPinnedEndToEndAsAPlan` | `REPORT_SSH_DIAG` §4.4 那条记录端到端钉成 plan |

> 密钥字节取自 `SshEd25519IdentityF14Test` 内嵌的**同一把**密钥
> （公钥 `SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8`，与 sshd `authorized_keys` 一致）。
> `research/v97_verify/id_ed25519` **在本工作区已不存在**（见 §7 未闭环），故无法在本轮复算 md5。

### 5.2 回归命令与结果

```console
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SshKeyAuthV100Test" `
  --tests "com.xiufeigo.opencodeshell.SshConnectionModelTest" `
  --tests "com.xiufeigo.opencodeshell.SshTargetParseV99Test" `
  --tests "com.xiufeigo.opencodeshell.SshRowMenuParityV99Test" `
  --tests "com.xiufeigo.opencodeshell.SshFormReachabilityV98Test" `
  --tests "com.xiufeigo.opencodeshell.SshHostKeyCardV99Test" `
  --tests "com.xiufeigo.opencodeshell.SshEd25519IdentityF14Test" `
  --tests "com.xiufeigo.opencodeshell.SshKeyFileFailureF14Test" `
  --tests "com.xiufeigo.opencodeshell.SshReconnectFidelityV97Test" `
  --tests "com.xiufeigo.opencodeshell.SshTunnelWiringTest" `
  --tests "com.xiufeigo.opencodeshell.SshAuthenticateModelTest" `
  --tests "com.xiufeigo.opencodeshell.SshPassphraseAnswerV94Test" `
  --tests "com.xiufeigo.opencodeshell.SshPromptOfferV94Test" `
  --tests "com.xiufeigo.opencodeshell.SshModelTest" `
  --tests "com.xiufeigo.opencodeshell.SshSubmitChainTest" `
  --tests "com.xiufeigo.opencodeshell.SshDialogRenderTest" `
  --tests "com.xiufeigo.opencodeshell.SshDeletePathV97Test" `
  --tests "com.xiufeigo.opencodeshell.SshSeedForgetV92Test" `
  --tests "com.xiufeigo.opencodeshell.SshTunnelHostModelTest" `
  --tests "com.xiufeigo.opencodeshell.SshPanelTunnelTest" `
  --tests "com.xiufeigo.opencodeshell.SshPromptCancelDoesNotDismissF13bTest" `
  --tests "com.xiufeigo.opencodeshell.SshImeNextRoutingF13aTest" `
  --tests "com.xiufeigo.opencodeshell.SshTunnelDeleteV91Test" `
  --tests "com.xiufeigo.opencodeshell.SshTunnelNarrowRowV93Test" `
  --tests "com.xiufeigo.opencodeshell.SshTunnelNarrowStructureV93Test" `
  --tests "com.xiufeigo.opencodeshell.ServerConfigStoreSshTest" `
  --tests "com.xiufeigo.opencodeshell.Wave23aSettingsPaletteTerminalTest" `
  --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

**BUILD SUCCESSFUL 原文：**

```plain
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 7s
```

**用例数（`app/build/test-results/testDebugUnitTest/`，本轮 28 个 suite）：**

```plain
suites=28 tests=268 failures=0 errors=0 skipped=0
```

新类单独一次：

```plain
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 7s
tests=14 failures=0 errors=0
```

> 纪律：只跑了 `:app:testDebugUnitTest --tests "<类名>"`；未 `assembleDebug`、未 `clean`、
> 未跑全量、未装机。`.agent-gradle-lock` 由本轮取用并已释放。

---

## 6. 真机复验步骤（由真机测试员闭环）

**先决条件（缺一不可）**

| # | 条件 | 校验命令 | 预期 |
|---|---|---|---|
| 0 | adb 全路径 | — | `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 1 | 宿主 sshd 在 22 | `wsl -d Arch -e bash -lc "sudo ss -ltnp \| grep :22"` | 两行 `LISTEN … 22 … sshd` |
| 2 | `sshtest` **未被锁**（最易踩的坑） | `wsl -d Arch -e bash -lc "sudo faillock --user sshtest"` | **输出为空**；有 `V` 行先 `sudo faillock --user sshtest --reset` |
| 3 | 公钥在位 | `wsl -d Arch -e bash -lc "sudo ssh-keygen -l -f /home/sshtest/.ssh/authorized_keys"` | `SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8` |
| 4 | reverse 隧道在位 | `adb -s emulator-5554 reverse --list` | 含 `tcp:2222 tcp:22` |
| 5 | **本轮新包**已装机 | `adb -s emulator-5554 shell dumpsys package com.xiufeigo.opencodeshell \| grep lastUpdateTime` | **晚于** `2026-10-03 03:09:27`（那是 F14b 之后、仍带 P3 的那份） |
| 6 | 铺钥 | `research\v97_verify\5554\E2ECHK5554_provision_key.ps1` | 设备上 `run-as … md5sum files/id_ed25519` = `0120a8270f7f0b0c71d31f4933384c63` |

**A. 主用例 —— 断言「不弹 `Password:` 且走 publickey」**

1. 清 sshd 日志起点：`wsl -d Arch -e bash -lc "sudo journalctl -u sshd -n0 --no-pager"` 记下时间戳。
2. app → Servers → `Add server`：
   - `Host or SSH command` = **`ssh -p 2222 sshtest@127.0.0.1`**（`user@host:port` 简写也可用，P1 已修）
   - `Server name` = `E2ECHK-keyauth-100`
   - `Authentication` = **`Private key`**
   - `Key file path` = `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`
   - `Passphrase` = **`keypass123`** ← 必填
   - 提交
3. 主机核验卡出现 → 点 `Trust and connect`。
4. **断言 1（UI）**：`SSH authentication` 卡**必须消失，行状态直接进 `Connected`**。
   **不得**出现标题为 `Password:` 的卡；**不得**要求账号口令 `opencode`。
5. **断言 2（sshd 侧，判定性）**：
   ```console
   $ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-2 min' --no-pager | grep -E 'Accepted'"
   Accepted publickey for sshtest from 127.0.0.1 port <N> ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
   ```
   **必须**是 `Accepted publickey`；**不得**出现 `Accepted keyboard-interactive` / `Accepted password`。
6. **断言 3（口令未退化）**：新建第二个条目，`Authentication` = `Password`、口令 = `opencode`，
   同样提交并 Trust。sshd 侧应出现 `Accepted password`（或 `keyboard-interactive/pam`），
   行状态 `Connected` ⇒ 口令通道未被本轮收窄破坏。

**B. 反向用例 —— 密钥不可用时必须「可诊断失败」而非静默回落**

7. 同 A，但把 `Passphrase` 改成 `wrongpass`：
   - 期望：**不弹 `Password:` 卡**；最终落到失败态（`Authentication required` /
     `Could not establish the SSH connection…`），而不是 `Connected`。
   - sshd 侧**不得**出现 `Accepted keyboard-interactive`。
8. 同 A，但把 `Key file path` 改成 `/data/user/0/com.xiufeigo.opencodeshell/files/nope`：
   - 期望：既有 `KEY_FILE` 句子（"…private key…path…permission…"），阶段 `failed`，
     **不弹 `Password:` 卡**、**不连上**。

---

## 7. 未闭环

1. **未做真机验证**：本轮纪律禁止装机，故 §6 全部断言**尚未在真机执行**。
   静态侧只能证明「publickey 是该条目唯一允许的方法」+「密钥确实能解密」，
   **不能**证明真实握手已被服务端接受。
2. **未重建 APK、未取 dex 证据**：`apk_f14b_evidence.md` 的 dex 检索针对
   `2026-10-02 18:01` 那份包。本轮**没有** `assembleDebug`，所以
   「新包里 `PreferredAuthentications` 已被写进 `SshConnection`」只有源码证据，无 dex 证据。
   真机复验前需补一次 `assembleDebug` + dex 字符串检索。
3. **`research/v97_verify/id_ed25519` 在本工作区已不存在**（`Test-Path` = `MISSING`），
   本轮无法复算 md5 `0120a8270f7f0b0c71d31f4933384c63`。
   `SshKeyAuthV100Test` 改用 `SshEd25519IdentityF14Test` 内嵌的同款密钥字节
   （公钥指纹与 sshd `authorized_keys` 一致，可互证），但**md5 未在本轮复核**。
4. **重连形态未在真机观察**：秘密**刻意不持久化**（`SshTunnelForget.kt:113-125` 的
   `toRestingTunnel()` 不带 passphrase），所以重连一条 resting 行时，
   预期弹的是 **`Passphrase for …id_ed25519` 卡**（v94 语义，保留），
   而**不是** `Password:` 卡；填对则走 publickey，填错/取消则失败。
   这一形态的 UI 表现本轮**未真机确认**。
5. **`gssapi-with-mic` 被一并移除**：stock 列表第一项是 `gssapi-with-mic`，
   收窄后 `Private key` / `Password` 两个条目都不再尝试它。
   本壳从未配置任何 GSSAPI 凭据，故**判断为无行为变化**，
   但这属于「收窄方法列表」这一手段的固有副作用，未做穷举验证。
6. **方法表是字符串常量**：jsch 的 `PreferredAuthentications` 值未做存在性校验，
   若未来 jsch 改名某方法，行为会静默变化。已由 pin 钉住当前值，
   但没有运行时断言。

---

## 8. 附：本轮的一次性探针（已删除）

定性期间跑过一个临时 `SshProbeScratchTest`，用 jsch 0.2.24 实测同一把密钥
（跑完即 `mavis-trash` 删除，不在仓库内），输出：

```plain
PROBE| stock ssh-ed25519=com.jcraft.jsch.jce.SignatureEd25519
PROBE| stock/goodpass addIdentity=ok identities=1
PROBE| stock/goodpass   [0] alg=ssh-ed25519 encrypted=false
PROBE| stock/nullpass addIdentity=ok identities=1
PROBE| stock/nullpass   [0] alg=ssh-ed25519 encrypted=true
PROBE| bc/goodpass addIdentity=ok identities=1
PROBE| bc/goodpass   [0] alg=ssh-ed25519 encrypted=false
PROBE| bc/nullpass addIdentity=ok identities=1
PROBE| bc/nullpass   [0] alg=ssh-ed25519 encrypted=true
```

这条证据**已被永久 pin** 进 `SshKeyAuthV100Test.theRealDeviceKeyDecryptsWithItsOwnPassphraseAndStaysLockedWithoutIt`
（stock / bc 两种路由下 goodpass ⇒ `encrypted=false`、nullpass ⇒ `encrypted=true`），
探针文件本身不再需要。
