# SSH_TARGET_EDGE_FIX — `-p` vs `:port` 冲突优先级 / IPv6 / 连写式 `-p2222`

- 轮次：修复轮（承接 `research/v97_fix/SSH_TARGET_PARSE_FIX.md` §8 的未闭环 1/2/3）
- 生产改动：**仅** `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt`
- 新增测试：`app/src/test/java/com/xiufeigo/opencodeshell/SshTargetParseEdgeV100Test.kt`（9 个用例 / 173 格断言）
- 未跑 assembleDebug / clean / 全量 / 装机；未动 `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/session|home|terminal|nav|App.kt|OcActivity.kt`、`SshTunnelHost.kt`；**零新增用户可见字符串**

> ## 一句话结论
>
> **上游的冲突规则不是"某条代码"，而是"什么都不裁决"**：`parseTarget` 把 `-p` 与
> `host:port` 一起原样交给系统 `ssh`（`command.ts:104` → `tunnelArgs:151`），**真正裁决的是
> ssh 二进制**，实测口径是：`-p` 独占端口、**首个 `-p` 胜**、`host:port` 后缀压根不被当成端口
> （而是一段无法解析的主机名）。本壳据此把规则定死为
> **`-p` 独占端口 → 重复 `-p` 取首个 → 后缀仅在无 `-p` 时生效 → 否则 22**，
> 并修掉一处**真实分歧**：改前重复 `-p` 取**末个**（与 ssh 相反 ⇒ 同一串配置在设备和终端拨不同端口）。
> IPv6 与连写式 `-p2222` 改前已支持，本轮把它们的**真值表补全并逐格钉住**，同时补上上游的两处
> 字符集/取值门（`command.ts:100`/`:107`）——本壳在这两处曾是**更宽**的，现在与上游一致。

---

## 1. 上游口径（判定的唯一标尺）

SSH target 门在 `_upstream/packages/desktop/src/main/ssh/command.ts` 的 `parseTarget`（`command.ts:45-111`），
不是 `name.ts`。关键段原文（`command.ts:91-110`）：

```ts
 91:  while (tokens[0]?.startsWith("-")) {
 92:    const token = tokens.shift() ?? ""
 93:    if (["-4", "-6", "-C", "-A", "-a"].includes(token)) { args.push(token); continue }
 97:    const flag = token.slice(0, 2)
 98:    if (!["-p", "-l", "-i", "-F", "-J", "-o"].includes(flag)) throw new SshFailure("input")
 99:    const value = token.length > 2 ? token.slice(2) : tokens.shift()
100:    if (!value || value.startsWith("-")) throw new SshFailure("input")
101:    if (flag === "-p" && (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535))
102:      throw new SshFailure("input")
103:    if (flag === "-o" && !options.has((value.split(/[=\s]/)[0] ?? "").toLowerCase())) throw new SshFailure("input")
104:    args.push(flag, value)
105:  }
106:  const host = tokens[0]
107:  if (tokens.length !== 1 || !host || !/^[a-zA-Z0-9_@.:[\]%-]+$/.test(host) || host.startsWith("-"))
108:    throw new SshFailure("input")
109:  if (host.includes("@") && host.slice(0, host.lastIndexOf("@")).includes(":")) throw new SshFailure("input")
110:  return { host, args }
```

落盘处（`command.ts:141-153`，`tunnelArgs`）证明 **`args`（含 `-p`）与 `target.host` 都原样交给系统 ssh**：

```ts
146:    ...sshArgs(target),
151:    target.host,
```

### 1.1 关键事实：**上游不解析端口，所以"冲突规则"是 ssh 的规则，不是上游代码的规则**

本轮在宿主实测（`ssh -G` = 只 dump 生效配置、不拨号；`ssh -v … true` = 回环拨号）：

```console
$ wsl -d Arch -e bash -lc "ssh -G -p 2222 -- 'sshtest@127.0.0.1:2200' | grep -E '^(user|hostname|port) '"
user sshtest
hostname 127.0.0.1:2200          ← 后缀进了"主机名"，不是端口
port 2222                        ← -p 独占端口

$ wsl -d Arch -e bash -lc "ssh -G -p 2222 -p 2200 devbox | grep -E '^port '"
port 2222                        ← 首个 -p 胜（OpenSSH 只在 options.port 未设时赋值）

$ wsl -d Arch -e bash -lc "ssh -G -p 0 devbox"          # 无 port 行 ⇒ ssh 自己拒
$ wsl -d Arch -e bash -lc "ssh -G -p 70000 devbox"      # 同上
```

⇒ **上游口径**：`-p` 独占端口；**重复 `-p` 取首个**；`host:port` 对 ssh **不是端口语法**，
它只是主机名的一部分，因此**必然 DNS 失败**（v99 报告已实测 `Could not resolve hostname 127.0.0.1:2222`）。
冲突时上游既不报错也不改写，只是**拨不出去**。

### 1.2 上游对 IPv6 的处理（实测，本轮新增）

```console
$ wsl -d Arch -e bash -lc "ssh -G -oPort=2222 -- 'user@[::1]:2222' | grep -E '^hostname '"
hostname [::1]:2222
$ wsl -d Arch -e bash -lc "timeout 8 ssh -v -o BatchMode=yes -o ConnectTimeout=4 'sshtest@[::1]' true"
debug1: resolve_canonicalize: hostname [::1] is an unrecognised address
ssh: Could not resolve hostname [::1]: Name or service not known     ← 方括号 IPv6 直接不可用
$ wsl -d Arch -e bash -lc "timeout 8 ssh -v -o BatchMode=yes -o ConnectTimeout=4 'sshtest@::1' true"
debug1: Connecting to ::1 [::1] port 22.
debug1: Connection established.                                       ← 裸字面量可用
```

⇒ **上游（= 系统 ssh）根本没有方括号 IPv6 语法**：`[::1]`、`[::1]:2222` 都解析失败；
只有裸 `2001:db8::1` 能连。上游的字符集 `[a-zA-Z0-9_@.:[\]%-]+`（`command.ts:107`）
**允许**方括号与冒号，但允许 ≠ 可用 —— 它只是"不过度拒绝"，方括号要到 ssh 才被丢弃/报错。

### 1.3 上游口径表（本壳须对齐的逐条）

| 写法 | 上游 `parseTarget` | 再交给 ssh 后 | 依据 |
|---|---|---|---|
| `ssh -p 2222 sshtest@127.0.0.1:2200` | `{host:"sshtest@127.0.0.1:2200", args:["-p","2222"]}` | 拨 `port 2222` @ `hostname 127.0.0.1:2200` → **DNS 失败** | `command.ts:104,151` + 实测 |
| `ssh -p 2222 -p 2200 devbox` | 两个 `-p` 都进 args | `port 2222`（**首个胜**） | 实测 |
| `ssh -p2222 host` | 接受（`slice(0,2)`=flag，`slice(2)`=value） | 拨 2222 | `command.ts:97-99` |
| `-p 0` / `-p 70000` / `-p twentytwo` | 拒绝 `input` | — | `command.ts:101-102` |
| `ssh host:2200 -p 2222`（选项在主机**之后**） | 拒绝 `input`（`tokens.length!==1`） | — | `command.ts:91,107` |
| `-i-keys` / `-l-p2222`（连写值以 `-` 开头） | 拒绝 `input`（取值门**不分**连写/分离） | — | `command.ts:100` |
| `ssh -4/-6/-C/-A/-a host` | 接受（透传） | ssh 实现之 | `command.ts:93-96` |
| `-F` / `-J` / `-o <白名单10项>` | 接受（`-o` 键须在 `command.ts:79-90` 白名单） | ssh 实现之 | `command.ts:98-103` |
| `-t` / `-L` / `-o RemoteCommand=` | 拒绝 `input` | — | `command.ts:98,103` |
| `ssh://user@host:2222`（含 `/`） | 拒绝 `input`（字符集无 `/`） | — | `command.ts:107` |
| `host/x` / `host;whoami` / `host?x` | 拒绝 `input`（字符集） | — | `command.ts:107` |
| `user:password@host` | 拒绝 `input` | — | `command.ts:109` |
| `ssh user@[::1]` / `[::1]:2222` | **接受**（字符集允许） | **DNS 失败**（见 §1.2） | `command.ts:107` + 实测 |
| `::1` / `2001:db8::1` | 接受 | **可用**，拨 22 | 实测 |
| `user@host:2222`（无方括号裸写法） | 接受 | 拨 22 @ `hostname host:2222` → **DNS 失败** | 实测 |
| `host ls` / `host;whoami` / 不配对引号 | 拒绝 `input` | — | `command.ts:107,75` |

---

## 2. 本壳现状（修前）逐分支表

全部位于 `SshConnectionModel.kt`（修前行号 = v99 报告 §2.2 所记）。

| 分支 | 认得 | **丢弃 / 静默错** | 修前行号 |
|---|---|---|---|
| `tokens[0]=="ssh"` | 前缀 | — | 83 |
| `token.startsWith("-")` | `flag=take(2)` ∈ `{-p,-i,-l}` | `-F/-J/-o/-4/-6/-C/-A/-a` 一律拒（**与上游分歧：上游转发**） | 95-97 |
| 取值 `value` | 分离式 + **连写式**（v99 已补） | **`!attached && value.startsWith("-")`** ⇒ 连写值以 `-` 开头**被接受**（**与上游 `command.ts:100` 分歧**） | 105 |
| `-p` | `optionPort = parsed`（**覆盖式**） | **重复 `-p` 取末个 ⇒ 与 ssh 的"首个胜"相反（真实分歧）** | 107-111 |
| `-i` / `-l` | `identityPath` / `user` | — | 113-114 |
| `else` → `parseSshHostPart` | `user@`、IPv6 方括号、尾部 `:port`（v99 已补） | **无字符集门** ⇒ `host/x`、`host;whoami`、`host?x` 解析成功 → jsch DNS 失败 → 落 `else -> CONNECTION`，**又回到"检查你的网络"** | 174-196 |
| `splitSshHostPort` | 方括号 / 裸支 | 三冒号按主机名（刻意，已钉） | 208-223 |
| 端口裁决 | `optionPort ?: hostPort ?: 22` | **规则未文档化、重复 `-p` 取末个** | 134 |

**"哪种写法会静默错"（修前，按危害排序）**

1. **`ssh -p 2222 -p 2200 devbox`** ⇒ 拨 **2200**，而 ssh/上游拨 **2222**。同一串配置，两个读法两个端口 ——
   这是本轮唯一的**新增真实分歧**。
2. **`host/x`、`host;whoami`、`host?x`、`host!`** ⇒ 解析"成功" ⇒ 错误诞生于 jsch ⇒ 归类 `CONNECTION` ⇒
   面板说"检查你的网络和 SSH 配置"，而要改的是 Host 文本框（v99 只把 `host:` 这一种从这条路上救回来）。
3. **`-i-keys`、`-l-p2222`** ⇒ 被接受（上游拒绝）。危害低（不会误连），但门与上游不一致，
   "取值门"名存实亡。
4. 已由 v99 闭环、不再复现：`-p 2222 user@host`（不带端口后缀）本来正常；`user@host:2222` 已在 v99 修好。

---

## 3. 修（最小、可解释、与上游对齐）

### 3.1 **冲突优先级规则的最终定义**

> **R1 `-p` 独占端口。** `host:port` 后缀与 `-p` 同时存在时，端口取 `-p`。
> **R2 重复 `-p` 取首个**（ssh 的 `options.port` 只在未设时赋值；实测 `ssh -G -p 2222 -p 2200` ⇒ 2222）。
> **R3 后缀仅在无 `-p` 时生效**，且必须读得出 `1..65535`，否则**整条 target 被拒**，绝不回落 22。
> **R4 都没有 ⇒ `SSH_DEFAULT_PORT` = 22。**
> **R5 选项必须在主机之前**（对齐 `command.ts:91` 的 `while (tokens[0]…)`），主机之后的 `-p` 是远程命令 ⇒ 拒。

**为什么是 R1/R2（"最不容易误连"的论证）**

上游把裁决权交给 ssh，所以"照上游"= "照 ssh 实测"。在两个候选端口之间，本壳能选的只有三条路：

| 方案 | 后果 | 判断 |
|---|---|---|
| **R1+R2（采纳）** | 端口 = ssh 会拨的端口 | 唯一与"操作员终端里同一串字符串"的读法**一致**的方案；分歧风险为零 |
| 后缀胜 | 丢掉**选项**指定的端口，去拨用户写在主机名后缀里的端口 | 凭空换一个端口去拨号 —— 典型误连 |
| 冲突即拒 | 不连，安全 | 但 ssh 对同一串**能拨**（只是必然 DNS 失败）；更关键：`SshTargetParseV99Test.dashPStillWinsOverTheSuffix:71` 已把 `-p 胜` 钉成不变量（既有断言不得降强度），且拒掉会凭空砍掉一条用户可诊断的路径 |
| 末个 `-p` 胜（**改前行为**） | 与 ssh 相反 | **否决**：唯一会造成"设备拨 2200、终端拨 2222"的分歧 |

后缀一侧的取舍也讲清楚：上游/系统 ssh 把 `user@host:2200` **整体当主机名**（无法解析 ⇒ 必失败）；
本壳把它**分解**掉，于是冲突行的端口与上游**逐位相同**（都是 `-p` 的值），差别只在于
"垃圾主机名还在（必失败）"还是"被分解掉了（能连上）"。**不新增误连面，只新增可达性。**

### 3.2 IPv6 与连写式 `-p2222`

两者 v99 已具备实现，本轮**不改行为、只补齐真值表并钉死**：

- IPv6：`[::1]`、`[::1]:2222`、`user@[2001:db8::1]:2222`、裸 `::1` / `2001:db8::1`、
  zone id `fe80::1%eth0` / `[fe80::1%eth0]:2222` 全部正确分解；方括号只是显示用，剥掉才可拨
  （jsch 无方括号语法，而 OpenSSH 保留方括号后**根本解析不了** ⇒ 本壳在 IPv6 上是**严格超集**）。
- 连写式：`-p2222` / `-ldeploy` / `-i/keys/k` 与分离式同义（`command.ts:97-99`）。

### 3.3 不认识的 `-o/-J/-F/-4/-6/-C/-A/-a`：**明确拒绝**（既不转发也不忽略）

**结论：拒绝**，落既有 `ssh_error_input`（`SshErrorCode.INPUT`），**不新增任何字符串**。

依据（三选一里的第一选不可行、第二选有害）：

1. **按上游转发 —— 不可行**：转发需要一个 shell 执行 `ssh`，本壳是 jsch 常驻库，进程模型里没有这条通道。
2. **忽略 + 提示 —— 有害**：丢弃 `-6` 会让"我要 IPv6"的操作员**在 IPv4 上连上**（语义被静默改写），
   丢弃 `-J` 会让"我要经跳板机"的请求**直连另一台机器**（目标被静默改写）。这与 `:port` 被吞是
   **同一类静默错连**（v99 的 P1 就是这一类）。
3. **拒绝 —— 采纳**：代价是操作员不能用这些选项；收益是 target **要么按字面被拨，要么一个字节都不拨**，
   且失败落在**指向该文本框**的既有句子上（`"Enter a host or SSH connection command. Remote commands
   and unsupported SSH options aren't allowed."`），三语资源**零改动**。

### 3.4 顺带补齐的两处上游门（本壳此前更宽，现与上游一致）

- **取值门**（`command.ts:100`）：`!attached && value.startsWith("-")` ⇒ `value.startsWith("-")`。
  连写取值以 `-` 开头（`-i-keys`、`-l-p2222`、`-p-1`）与上游一样被拒。
- **字符集门**（`command.ts:107`）：`parseSshHostPart` 入口加 `isUpstreamHostToken(token)`，
  字符集照抄上游 `/^[a-zA-Z0-9_@.:[\]%-]+$/`（含 `%`，为 IPv6 zone id 保留）。
  效果：`host/x`、`host;whoami`、`host?x`、`host!`、`host#1`、`*` 从"拨号失败于 jsch、误报网络问题"
  变成"target 错误、指向 Host 框"。

两处都是**只收窄、不放宽**，且不触碰任何既有断言。

---

## 4. diff（本轮对 `SshConnectionModel.kt` 的逐块改动）

```diff
@@ fun parseSshTarget @@  (选项分支)
-                if (value.isBlank() || (!attached && value.startsWith("-"))) return null
+                // `command.ts:100` — `if (!value || value.startsWith("-")) throw` — is
+                // applied by upstream to the attached spelling too, so `-i-keys` and
+                // `-l-p2222` are rejected here exactly as they are upstream. An
+                // option's value is never another option.
+                if (value.isBlank() || value.startsWith("-")) return null
                 when (flag) {
                     "-p" -> {
                         val parsed = value.toIntOrNull() ?: return null
                         if (parsed !in 1..65535) return null
-                        optionPort = parsed
+                        // **First `-p` wins**, measured against the system binary this
+                        // target is written for (`ssh -G -p 2222 -p 2200 devbox` →
+                        // `port 2222`; OpenSSH's getopt loop only assigns
+                        // `options.port` while it is still unset). Upstream forwards
+                        // both, so its authority here *is* ssh's rule; taking the last
+                        // one would make the same string dial a different port on the
+                        // device than it does in the operator's terminal. A later `-p`
+                        // is still validated — upstream's gate runs per token.
+                        if (optionPort == null) optionPort = parsed
                     }

@@ 返回处 @@
     val resolvedHost = host ?: return null
-    // `-p` is the dedicated port option and wins over a `host:port` suffix, the
-    // same precedence the system `ssh` gives an explicit `-p`.
+    // **Port arbitration, in one line — the rule this shell commits to.** …（R1..R4 全文字面量证据）
     return SshTarget(..., port = optionPort ?: hostPort ?: SSH_DEFAULT_PORT, ...)   // 语义未变，规则被文档化

@@ 私有辅助 @@
+/**
+ * The characters upstream's host token may contain, transcribed from the class in
+ * `/^[a-zA-Z0-9_@.:[\]%-]+$/` (`command.ts:107`) so the two cannot drift.
+ */
+private const val SSH_HOST_TOKEN_CHARS: String =
+    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_@.:[]%-"
+
+private fun isUpstreamHostToken(token: String): Boolean =
+    token.isNotEmpty() && token.all { it in SSH_HOST_TOKEN_CHARS }
+
@@ private fun parseSshHostPart @@  (现 :237)
-private fun parseSshHostPart(token: String, fallbackUser: String?): SshHostParts? {
-    val at = token.lastIndexOf('@')
+private fun parseSshHostPart(token: String, fallbackUser: String?): SshHostParts? {
+    // Upstream `command.ts:107` — `/^[a-zA-Z0-9_@.:[\]%-]+$/`, spelled out in
+    // [SSH_HOST_TOKEN_CHARS] … `%` stays legal so an IPv6 zone id (`fe80::1%eth0`) passes …
+    if (!isUpstreamHostToken(token)) return null
+    val at = token.lastIndexOf('@')
```

另加 `parseSshTarget` KDoc 的 v100 段（三问三答 + 未知选项决策依据 + IPv6 超集论证，
含本报告 §1.1/§1.2 的实测读数）。**无新增资源、无新增三语字符串、无签名变更。**

改动落点行号（修后）：取值门 `SshConnectionModel.kt:132`、首个 `-p` 胜 `:145`、
端口裁决 + R1..R4 注释 `:150-188`、字符集常量 `:230-234`、字符集门 `:247`。

---

## 5. 为什么不降强度

| 既有 pin | 状态 | 证据 |
|---|---|---|
| `SshTargetParseV99Test`（9 用例，含 `dashPStillWinsOverTheSuffix:71` 把 `-p` 优先钉成不变量） | **未改一个字节，全 PASS** | `tests=9 failures=0` |
| `SshConnectionModelTest.parseSshTargetAcceptsUpstreamsTargetForms` / `…ReadsTheSupportedOptions` / `…RejectsRemoteCommandsAndUnsupportedOptions`（8 用例） | **未改，全 PASS**；`-J`/`-o`/悬空 `-p`/越界端口的拒绝面**只增不减** | `tests=8 failures=0` |
| `SshKeyFileFailureF14Test`（含 3 条 `parseSshTarget` 断言） | **PASS** | `tests=7 failures=0` |
| `SSH_KEY_AUTH_FIX`（`SshKeyAuthV100Test`） | **PASS** | `tests=14 failures=0` |
| `SSH_FORM_REACHABILITY_FIX` | **PASS** | `tests=11 failures=0` |
| `HOST_KEY_CARD_FIX` / `SSH_ROW_MENU_FIX`（`SshRowMenuParityV99Test`） | **PASS** | `tests=12 failures=0` |
| 三语一致性（`StringsLocaleParityTest`） | **PASS** | `tests=3 failures=0` |

**两处改动都是收窄，且收窄面全部由新 pin 单独覆盖**（上游同样拒绝的写法），
因此"超集"退化为"与上游同集"不会削弱任何既有断言：

- 取值门：新增拒绝 `-i-keys`/`-l-p2222`/`-l-`/`-p-1`/`-p-`/`-p-0` —— 上游 `command.ts:100` 同样 `throw`。
- 字符集门：新增拒绝 `host/x`/`host;whoami`/`host?x`/`host|x`/`http://host`/`user@host/x`/`user@host;rm -rf /`/`*`/`host!`/`host#1`
  —— 上游 `command.ts:107` 同样 `throw`。
- 唯一**放宽**方向的改动是"重复 `-p` 从末个改首个"：这不是放宽，是把**唯一一处与 ssh 相反**的读法改正；
  既有 pin 全部是单个 `-p`，不受影响。

**不新增用户可见字符串**：拒绝一律落既有 `SshErrorCode.INPUT` → `ssh_error_input`
（`aRejectedEdgeTargetRidesTheExistingInputSentence` 钉住 `R.string.ssh_error_input`）。
三语 `values/`、`values-zh-rCN/`、`values-zh-rTW/` **未改**（`StringsLocaleParityTest` PASS 佐证）。

---

## 6. 测试证据

### 6.1 命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.xiufeigo.opencodeshell.SshTargetParseEdgeV100Test `
  --tests com.xiufeigo.opencodeshell.SshTargetParseV99Test `
  --tests com.xiufeigo.opencodeshell.SshKeyAuthV100Test `
  --tests com.xiufeigo.opencodeshell.SshFormReachabilityV98Test `
  --tests com.xiufeigo.opencodeshell.SshRowMenuParityV99Test `
  --tests com.xiufeigo.opencodeshell.StringsLocaleParityTest `
  --tests com.xiufeigo.opencodeshell.SshConnectionModelTest `
  --tests com.xiufeigo.opencodeshell.SshKeyFileFailureF14Test
```

纪律：等 `.agent-gradle-lock` 消失 → 写锁 → **前台跑完** → 删锁（`rm -- <lock>`，可恢复删除）；
**未跑** assembleDebug / clean / 全量 / 装机；`--tests` 逐类限定。

### 6.2 结果原文

```console
LOCK ACQUIRED
RUNNING 8 CLASSES
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 8s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
LOCK RELEASED
```

### 6.3 用例数（`app/build/test-results/testDebugUnitTest/*.xml`）

```
com.xiufeigo.opencodeshell.SshTargetParseEdgeV100Test         tests=9    failures=0   ← 新增
com.xiufeigo.opencodeshell.SshTargetParseV99Test              tests=9    failures=0
com.xiufeigo.opencodeshell.SshKeyAuthV100Test                 tests=14   failures=0
com.xiufeigo.opencodeshell.SshFormReachabilityV98Test         tests=11   failures=0
com.xiufeigo.opencodeshell.SshRowMenuParityV99Test            tests=12   failures=0
com.xiufeigo.opencodeshell.StringsLocaleParityTest            tests=3    failures=0
com.xiufeigo.opencodeshell.SshConnectionModelTest             tests=8    failures=0
com.xiufeigo.opencodeshell.SshKeyFileFailureF14Test           tests=7    failures=0
TOTAL tests=73 failures=0 errors=0 skipped=0
```

**73 用例 / 0 失败**（新 pin 9 个方法；源文件 54 处断言调用点，循环展开后**实际执行 161 次断言**：
36 格 `assertTarget` × 3 条 `(user, host, port)` + 43 格循环内 `assertNull` + 5 格独立 `assertNull`
+ 5 处直接 `assertEquals`）。

新 pin 的 9 个方法（`SshTargetParseEdgeV100Test`）：

```
dashPOwnsThePortOnEveryCombinationWithTheSuffix      冲突优先级 R1 × {分离,连写,一致,相反,带 -l/-i}
theFirstDashPWinsWhenItIsRepeated                    R2（含"第二个 -p 非法仍整条拒绝"4 例）
optionsAfterTheHostAreNotOptions                     R5（选项必须前置）
ipv6DecomposesWithAndWithoutBrackets                 IPv6 方括号/裸字面量/zone id
ipv6ComposesWithBothPortSpellings                    IPv6 × 两种端口写法 + 8 条畸形
attachedOptionValuesAreReadOffTheToken               连写式取值 + 6 条上游同样拒绝的取值
hostsOutsideUpstreamsCharsetAreRejectedNotDialed     上游字符集门（10 拒 / 5 收）
everyOptionUpstreamForwardsIsRejectedHereRatherThanIgnored   未知选项决策（15 条枚举）
aRejectedEdgeTargetRidesTheExistingInputSentence     诊断落既有句子（零新增字符串）
```

> 首轮 1 处失败是**我自己新 pin 的期望值写错**：我以为 `-l deploy` 会盖住内联 `sshtest@`，
> 实际内联 `user@` 优先（与 ssh 读 destination 的口径一致）。已按正确语义拆成两格
> （`@127.0.0.1:2200` ⇒ `deploy`；`sshtest@127.0.0.1:2200` ⇒ `sshtest`），第二轮 0 失败。

---

## 7. 真机复验步骤

> **前置**（照抄 v99 报告 §7，未变）：`adb` 全路径
> `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`；宿主 sshd 在 22 监听；
> `wsl -d Arch -e bash -lc "sudo faillock --user sshtest"` 输出为空（有 `V` 行先 `--reset`）；
> 公钥在位（`SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8`）；
> 私钥在 app 私有目录（`md5 0120a8270f7f0b0c71d31f4933384c63`）；
> `adb -s emulator-5554 reverse --list` 含 `tcp:2222 tcp:22`。
> **本轮修复需重新装机**（`lastUpdateTime` 须晚于本次构建），否则测的还是旧二进制。
> 装机需 Lead 授权（任务纪律禁装机）⇒ 以下步骤**本轮全部是待执行步骤，不是已取得的读数**。

### 用例 D —— 冲突写法（本轮新增规则 R1，回归闸）

| # | 动作 | 预期读数 |
|---|---|---|
| 1 | Add SSH server → Host 填 `ssh -p 2222 -p 2200 sshtest@127.0.0.1:2200`，Name 填 `V100EDGE-D` | 写入成功 |
| 2 | Authentication 选 `Private key` → Key file path 填 `/data/user/0/com.xiufeigo/opencodeshell/files/id_ed25519`，Passphrase 留空 → Add server | 行状态 `Connecting over SSH…` + 弹 `Verify host identity` 卡 |
| 3 | 校验卡读数 | **`[127.0.0.1]:2222`**（= **首个 `-p`**，若读成 `:2200` 即 R2 未生效）+ `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24` |
| 4 | Trust and connect → 认证 | ⚠ 预期仍可能弹 `Password:` 卡（v99 报告未闭环 P3，不属本轮） |
| 5 | `wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-2min' \| tail -5"` | 出现 sshd 连接记录（反向 2222→22 生效） |

> **决定性判据是第 3 步的 `[127.0.0.1]:2222`**：`reverse` 只把 2222 转到 22，
> 2200 端口无监听 ⇒ 若显示 `:2200`，连接必然 `Connection refused`/超时；显示 `:2222` 且拿到
> 同一指纹即证明"首个 `-p` 胜 + 后缀不误拨"。

### 用例 E —— IPv6（本轮**无环境，仅 JVM**，见下）

| # | 动作 | 预期读数 |
|---|---|---|
| 1 | Host 填 `ssh user@[::1]:2222` | — |
| 2 | Add server | 见下 |

> **IPv6 本轮只做了 JVM 验证，没有真机环境**：`adb reverse` 只支持 IPv4
> （`tcp:2222 tcp:22`，无 IPv6 变体），而 `::1` 指的是**设备自身**回环 —— 设备上没有任何
> 进程监听 `::1:2222`，因此真机无法产生"连上 IPv6"的读数。
> 可判定的只有**失败形态**：改前 `[::1]:2222` 解析为 `host="[::1]"`（方括号未剥）⇒ jsch 解析失败 ⇒
> 落 `CONNECTION` ⇒ 面板显示"检查你的网络和 SSH 配置"；改后应落 **target 句**
> `Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.`
> （**注意**：这台设备上**没有** `[::1]:2222` 监听，所以"能否连上"本轮在真机上不可判定，
> 只能判定"错误指向是否正确"——这本身就是 v99 P2 的判据）。
> **"IPv6 能否真连"仅 JVM 覆盖**：`ipv6DecomposesWithAndWithoutBrackets`、
> `ipv6ComposesWithBothPortSpellings`（含 `user@[::1]:2222` ⇒ `("user","::1",2222)`）。

### 用例 F —— 上游同款缺陷的对照（可选，确认本壳是超集）

| # | 动作 | 预期读数 |
|---|---|---|
| 1 | 宿主执行 `wsl -d Arch -e bash -lc "ssh -o BatchMode=yes -o ConnectTimeout=4 'sshtest@[::1]:2222' true"` | `Could not resolve hostname [::1]:2222`（上游/系统 ssh **必失败**） |
| 2 | 宿主执行 `wsl -d Arch -e bash -lc "ssh -G -p 2222 -- 'sshtest@127.0.0.1:2200' \| grep -E '^(hostname|port) '"` | `hostname 127.0.0.1:2200` / `port 2222`（上游/系统 ssh 必失败） |

> 对照结论：上游在这两种写法上**连不上**，本壳把它们分解成可拨的 `(host, port)`。
> 这是**有意的超集**，代价是本壳在"用户照抄上游行为"这件事上与上游不同 ——
> 报告不认为这是回归，但需 Lead 知晓（`SSH_TARGET_PARSE_FIX.md` §8 未闭环 5 同款）。

### 清理

D/E/F 跑完：`Disconnect` → 行 kebab → `Delete` → 确认条原文 → `Delete`；`/sdcard` 零残留；
`reverse` 隧道一条不动；`files/ssh/known_hosts` 会新增 `[127.0.0.1]:2222` 一条（既有 `Trust and connect` 行为），登记但不删。

---

## 8. 未闭环

1. **IPv6 未上真机**（§7 用例 E）：`adb reverse` 无 IPv6 ⇒ 无法验证"真连上 `::1:2222`"。
   `SshTargetParseEdgeV100Test` 只证明**分解正确**；真实 IPv6 目标需要一个设备可达的 IPv6 监听
   （例如在模拟器宿主侧开 `[::1]` 转发，或改用局域网 IPv6 地址），需 Lead 提供环境。
2. **冲突优先级无法上真机验证"负例"**：`reverse` 只提供 2222，D 用例可证"拨 2222"，
   但**证不了**"若拨 2200 会失败"这条因果链的另一半（需临时在宿主开 2200 sshd 才有对照读数）。
   ⇒ D 是**正向**闸门。
3. **P3（私钥退化）仍未修**（承接 v99 §8.1）：D/E 第 4 步预期仍可能弹 `Password:` 卡。
4. **P2 只闭环了 target 一侧**（承接 v99 §8.3）：`sshFailure()` 仍无 `UnknownHostException` 分类。
   本轮把**更多**拼写（`host/x` 等）从那条路上赶到了 target 侧句子，但 DNS 失败本身的分类仍待单独决策
   （需新增 `unknown-host` 三语字符串，超出本轮授权）。
5. **三冒号 `host:2222:3333` 仍按主机名处理**（v99 §8.6 结论不变，V99 与 V100 均钉住）：
   与裸 IPv6 字面量不可区分，唯一安全读法是"这是地址"。
6. **上游同款缺陷未修**：`command.ts:107` 允许方括号 IPv6 与 `host:port`，而系统 ssh 两者都无法解析
   （本报告 §1.1/§1.2 实测）。本壳是超集，**上游侧仍需单独修**，本轮按纪律**不碰 `_upstream/`**。
7. **未跑全量**：只跑了 §6.1 的 8 个类。`ui/servers/**` 其余测试类（`SshModelTest` /
   `SshHostKeyCardV99Test` / `SshReconnectFidelityV97Test` 等）本轮**未跑** ——
   它们的断言不经过 `parseSshTarget` 的字符集门，预期无影响，但**未验证**。
8. **回归期间存在并发改动（本轮不负责、也未触碰）**：核对文件 mtime 时发现同一时间窗内还有
   `SshTunnelHost.kt`、`SshMenuParity2V100Test.kt`、`SshRowMenuParityV99Test.kt`、
   `SshTunnelNarrowStructureV93Test.kt`、`research/v97_fix/SSH_MENU_PARITY2.md` 被**另一个 agent** 改动。
   其中 `SshRowMenuParityV99Test.kt` 在本轮 `:app:testDebugUnitTest` 运行**前后**可能被改动，
   因此报告中该类 `tests=12 failures=0` 只对**当次构建快照**成立。
   本轮写入的文件**仅两个**：`SshConnectionModel.kt` + `SshTargetParseEdgeV100Test.kt`（+ 本报告）。
