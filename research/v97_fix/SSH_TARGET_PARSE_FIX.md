# SSH_TARGET_PARSE_FIX — `user@host:port` 被静默丢弃 ⇒ P1/P2 闭环

- 轮次：修复轮（承接 `research/v97_verify/5554_v98d3/REPORT_SSH_DIAG.md`）
- 改动范围：`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnectionModel.kt`（唯一生产文件）
- 新增测试：`app/src/test/java/com/xiufeigo/opencodeshell/SshTargetParseV99Test.kt`
- 未跑 gradle 之外的任何构建；未装机；未动 `_upstream/`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/session|home|terminal|nav|App.kt|OcActivity.kt`

> ## 一句话结论
>
> **根因：`parseSshTarget` 的 `parseSshHostPart` 把 `hostPart` 原样返回，从不剥离 `:port`，
> 于是 `sshtest@127.0.0.1:2222` 被解析成 `host="127.0.0.1:2222"` + `port=22`。**
> 已修：`host:port` 现在被正确分解为 `{host, port}`，且**任何读不出端口的冒号一律拒绝、不回落 22**；
> 上游支持的写法全部对齐（含上游的 `-p2222` 连写式）；畸形输入走**既有** `ssh_error_input` 句子（未新增任何用户可见字符串）。

---

## 1. 上游口径（判定的唯一标尺）

上游的 SSH target 门在 `_upstream/packages/desktop/src/main/ssh/command.ts` 的 `parseTarget`，**不是** `name.ts`。

### 1.1 上游支持哪些写法

`_upstream/packages/desktop/src/main/ssh/command.ts:45-111` 原文（关键段）：

```ts
45: export function parseTarget(input: string) {
50:     if (c === "\n" || c === "\r" || c === "\0") throw new SshFailure("input")
77:   if (tokens[0] === "ssh") tokens.shift()
91:   while (tokens[0]?.startsWith("-")) {
93:     if (["-4", "-6", "-C", "-A", "-a"].includes(token)) { args.push(token); continue }
97:     const flag = token.slice(0, 2)
98:     if (!["-p", "-l", "-i", "-F", "-J", "-o"].includes(flag)) throw new SshFailure("input")
99:     const value = token.length > 2 ? token.slice(2) : tokens.shift()
100:    if (!value || value.startsWith("-")) throw new SshFailure("input")
101:    if (flag === "-p" && (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535))
102:      throw new SshFailure("input")
103:    if (flag === "-o" && !options.has((value.split(/[=\s]/)[0] ?? "").toLowerCase())) throw new SshFailure("input")
106:  const host = tokens[0]
107:  if (tokens.length !== 1 || !host || !/^[a-zA-Z0-9_@.:[\]%-]+$/.test(host) || host.startsWith("-"))
108:    throw new SshFailure("input")
109:  if (host.includes("@") && host.slice(0, host.lastIndexOf("@")).includes(":")) throw new SshFailure("input")
110:  return { host, args }
111: }
```

**上游口径表（写法 × 上游结果）**

| 写法 | 上游 `parseTarget` 结果 | 依据 |
|---|---|---|
| `devbox` | `{host:"devbox", args:[]}` | `command.test.ts:56` |
| `ssh devbox` | 同上（`ssh` 前缀被 `shift`） | `command.ts:77` |
| `user@devbox` | `host="user@devbox"`，**user 不分解** | `command.test.ts:52-55` |
| **`user@host:port`** | **通过**（字符集含 `:`，`command.ts:107`），**整串原样进 `host`** | `command.ts:107` |
| `user:password@host` | **拒绝** `input`（`@` 左侧含 `:`） | `command.ts:109`；`command.test.ts:65` |
| `ssh -p 2222 user@devbox` | 接受，`-p` 进 args | `command.test.ts:52-55` |
| **`ssh -p2222 host`** | **接受**（flag 取 `slice(0,2)`，值取 `slice(2)`） | `command.ts:97-99` |
| `-p 0 / -p 70000 / -p twentytwo` | 拒绝 `input` | `command.ts:101-102`；`command.test.ts:69` |
| `ssh -4/-6/-C/-A/-a host` | 接受（透传） | `command.ts:93-96` |
| `-F` / `-J` / `-o <allowlist>` | 接受（`-o` 键须在白名单） | `command.ts:98-103` |
| `-t` / `-L` / `-o RemoteCommand=` | 拒绝 `input` | `command.test.ts:67-68` |
| `ssh://user@host:2222` | **拒绝**（字符集无 `/`） | `command.ts:107` |
| `ssh host whoami` / `host;whoami` / `host\nwhoami` | 拒绝 `input` | `command.test.ts:62-64,71` |
| `ssh user@[::1]` | `host="user@[::1]"`（**括号不剥**） | `command.test.ts:57` |
| `  ssh 'brendan-'box.exe.xyz  ` | 接受（引号只影响切分） | `name.test.ts:10` |

### 1.2 上游解析出的 `{user, host, port}` 各字段的默认值

**上游不产生这三个字段。** 它返回 `{host, args}`，`host` 是**原样 token**，`user`/`port` 从不分解
——因为端口最终由**系统 `ssh` 二进制**读，端口来自 `-p`（`tunnelArgs` 把 `target.host` 原样
交给 ssh：`command.ts:151 target.host`）。

- **port 默认 22**：上游没有默认值这个概念；由 ssh 的 `Port` 默认值 22 承担。本壳的 `22`
  显式命名成 `SSH_DEFAULT_PORT`，与 jsch 语义一致。
- **user 默认**：上游不分解；本壳 `user=null` 时由 `SshConnection.kt:112` 的 `defaultSshUser()` 兜底（既有行为，未改）。

> ### ⚠ 关键实测：OpenSSH **没有** `host:port` 简写
>
> 上游能"接受" `user@host:port` 只是因为它**原样转发给系统 ssh**，而系统 ssh 自己也不认这种写法。
> 本轮在宿主实测：
>
> ```console
> $ wsl -d Arch -e bash -lc "ssh -o BatchMode=yes -o ConnectTimeout=4 sshtest@127.0.0.1:2222 true"
> ssh: Could not resolve hostname 127.0.0.1:2222: Name or service not known
> ```
>
> ⇒ **上游在 `user@host:port` 上同样连不上。** 上游 display 侧也把它整串留在行名里
> （`name.ts:3-12` `sshHostname` = `split("@").at(-1)`，不碰 `:`），这正是诊断报告 §4.3
> 说的"行名回落为 `host:port`"——**同一处破绽**。
>
> **对本壳的结论**：jsch 的 `getSession(user, host, port)` **强制**要拆出三段，端口没有第二个
> 传参通道。所以本壳**必须**做对分解——这正是上游可以偷懒、本壳不能的地方。修好后本壳在这条
> 写法上**优于上游**（上游转发即失败，本壳真连）。

### 1.3 上游对畸形输入的处理

- **报错文案**：统一 `new SshFailure("input")`（`command.ts:98/100/102/103/107/109`），`detail` 留空。
  UI 侧渲染成 `ssh_error_input`（`types.ts:39-49` → `connection-panel.tsx:29`）。
- **静默回落**：**没有**。上游任何畸形输入都是 `throw`，绝不替换成默认值。
  `command.test.ts:60-76` 的 10 个反例全部 `expect(...).toThrow()`。
- 唯一"静默"的是**显示**侧 `sshHostname`（`name.ts:3-12`）对畸形串给一个降级显示名——但那是展示，不是连接语义。

---

## 2. 本壳解析器逐分支表（修前 / 修后）

### 2.1 修前 —— 认得什么、丢弃什么

`SshConnectionModel.kt` 修前的 `parseSshHostPart`（原文）：

```kotlin
private fun parseSshHostPart(token: String, fallbackUser: String?): Pair<String?, String>? {
    val at = token.lastIndexOf('@')
    val user = if (at >= 0) token.substring(0, at).ifEmpty { fallbackUser } else fallbackUser
    val hostPart = if (at >= 0) token.substring(at + 1) else token
    val host = when {
        hostPart.startsWith("[") && hostPart.endsWith("]") && hostPart.length > 2 ->
            hostPart.substring(1, hostPart.length - 1)   // 只剥 IPv6 方括号
        else -> hostPart                                   // ← 原样返回，不剥 :port
    }
    if (host.isBlank()) return null
    return user to host                                    // ← 根本没有 port 这一路返回
}
```

| 修前分支 | 认得 | **丢弃** |
|---|---|---|
| `tokens[0] == "ssh"` | 前缀 | — |
| `token == "-p" && next` | `port = toIntOrNull()` | — |
| `token == "-i" && next` | `identityPath` | — |
| `token == "-l" && next` | `user` | — |
| `token.startsWith("-")` | — | 全部 `-J/-o/-F/-t` 拒绝（**无连写式**，`-p2222` 落此支被拒） |
| `else` → `parseSshHostPart` | `user@`、`host`、IPv6 括号 | **`:port`（本 P1）**、user 里的 `:`（不校验） |

**为什么 `:port` 被丢**：不是正则、不是 `split` 顺序、不是 `substringBefore(':')`——
是**根本没有这条路径**。`parseSshHostPart` 的返回类型是 `Pair<String?, String>`（user, host），
**没有第三个槽位承载 port**；`else -> hostPart` 原样返回；而 `port` 是循环外的 `var port = 22`，
**只有 `-p` 这一条赋值路径**。所以 `:2222` 唯一的归宿是变成主机名的一部分。

**因果链**

```
用户在 Host 填 "sshtest@127.0.0.1:2222"
  → tokenize → ["sshtest@127.0.0.1:2222"]
  → else 支：parseSshHostPart
      at=6, user="sshtest", hostPart="127.0.0.1:2222"
      不以 "[" 开头 → else → host="127.0.0.1:2222"   ← :2222 进了 hostname
  → SshTarget(user="sshtest", host="127.0.0.1:2222", port=22)   ← port 只可能是 22
  → SshConnection.kt:111-114  jsch.getSession("sshtest", "127.0.0.1:2222", 22)
  → DNS 解析 "127.0.0.1:2222" 失败 ⇒ 拨号前即失败（秒级）
  → sshd 侧零日志（连接从未抵达 22）
  → sshFailure(...) 落 else ⇒ SshErrorCode.CONNECTION
  → UI: "Could not establish the SSH connection. Check your network and SSH configuration." + "Connection failed"
```

**P2 为什么没有可诊断提示**：不是"没写日志"这么表面，而是**这个错误根本没被分类成 target 错误**。
解析器**成功返回了一个非空 target**（它认为自己解析对了），所以 `SshConnection.kt:94-95` 的
`?: fail(INPUT)` 这条"名字指向 target"的句子**根本没被走到**；错误在 jsch 里以
"无法解析这个主机名"的形式诞生，被 `sshFailure()` 的 `else -> CONNECTION` 兜住。
于是"用户在文本框里少写了一个 `-p`"被翻译成"检查你的网络和 SSH 配置"——**指向错误的方向**。

### 2.2 修后 —— 逐分支

| 分支 | 修后行为 |
|---|---|
| `tokens[0] == "ssh"` | 不变 |
| `token.startsWith("-")` | `flag = take(2)`；不在 `{-p,-i,-l}` → 拒；支持**连写**（`-p2222`/`-i/k`/`-ldeploy`），分离式同旧 |
| `-p` | `optionPort`，仍校验 `1..65535` |
| `-i` / `-l` | 不变（`identityPath` / `user`） |
| `else` → `parseSshHostPart` | 返回 **`SshHostParts(user, host, port?)`**：剥 `user@`、剥 IPv6 方括号、**剥尾部 `:port`** |
| `splitSshHostPort` | 方括号支（`]` 后才可能是端口）/ 裸支（**多个冒号 ⇒ IPv6 字面量，绝不拆**） |
| user 含 `:` | **拒绝**（对齐 `command.ts:109` 的 `user:password@host`） |
| 端口读不出/越界 | **拒绝**，绝不回落 22 |
| 最终 port | `optionPort ?: hostPort ?: SSH_DEFAULT_PORT`（**`-p` 优先**） |

**写法 × 修后结果**（全部由 `SshTargetParseV99Test` 钉住）

| 写法 | user | host | port |
|---|---|---|---|
| `devbox` / `ssh devbox` | null | `devbox` | 22 |
| `ssh anomaly@devbox` | anomaly | `devbox` | 22 |
| **`sshtest@127.0.0.1:2222`** | **sshtest** | **`127.0.0.1`** | **2222** |
| `127.0.0.1:2222` | null | `127.0.0.1` | 2222 |
| `ssh -p 2222 sshtest@127.0.0.1` | sshtest | `127.0.0.1` | 2222 |
| `ssh -p2222 devbox` | null | `devbox` | 22 |
| `ssh -p 2222 sshtest@127.0.0.1:2200` | sshtest | `127.0.0.1` | **2222**（`-p` 优先） |
| `ssh user@[2001:db8::1]` | user | `2001:db8::1` | 22 |
| **`user@[2001:db8::1]:2222`** | user | `2001:db8::1` | **2222** |
| `2001:db8::1`（裸 IPv6） | null | `2001:db8::1` | 22（**不拆**） |
| `::1` | null | `::1` | 22 |
| `devbox:22` | null | `devbox` | 22 |
| `  ssh 'brendan-'box.exe.xyz  ` | null | `brendan-box.exe.xyz` | 22 |
| `ssh -p 2222 -i "/keys/my key" anomaly@devbox` | anomaly | `devbox` | 2222（`identityPath="/keys/my key"`） |

**畸形输入（拒绝，绝不回落）**：`host:`、`host:ssh`、`host:22ab`、`host:0`、`host:65536`、`host:-1`、
`host:22222222`、`host: 22`（跨 token ⇒ 远程命令）、`user:password@host`、`ssh://user@host:2222`、
`[2001:db8::1`（括号不配对）、`[2001:db8::1]x`、`user@`、`-p 0/-p 70000/-p twentytwo/-p`（悬空）、
`-J/-o/-F/-t`、远程命令、不配对引号。

**一个刻意的边界**：三冒号（`host:2222:3333`）无法与裸 IPv6 字面量区分，按**主机名**处理（port 22）。
理由见 `splitSshHostPort` 注释：唯一安全的读法是"这是地址"，猜端口等于凭空拨一个没人要求的端口。
这与上游一致（上游原样转交给 ssh，ssh 同样只当主机名）。

---

## 3. 根因（文件:行号）

| # | 位置 | 角色 |
|---|---|---|
| 1 | `SshConnectionModel.kt:115-127`（修前）`parseSshHostPart` | **P1 直接原因**：返回类型 `Pair<String?, String>` 没有 port 槽位；`else -> hostPart` 把 `:2222` 并进主机名 |
| 2 | `SshConnectionModel.kt:54`（修前）`var port = 22` | **P1 放大器**：port 的唯一赋值路径是 `-p`；`:port` 分支不存在 |
| 3 | `SshConnectionModel.kt:65-68`（修前）`"-p" -> port = …` | 同上 |
| 4 | `SshConnectionModel.kt:265-284` `sshFailure` 的 `else -> CONNECTION` | **P2**：解析"成功"⇒ 错误诞生于 jsch ⇒ 被归为连接错误 |
| 5 | `SshConnection.kt:94-95` `parseSshTarget(...) ?: fail(…, SshErrorCode.INPUT, …)` | **P2 的正确出口**（修前够不着）；修后畸形 target 走这里 |
| 6 | `SshConnection.kt:111-114` `jsch.getSession(user, target.host, target.port)` | 端口**唯一**的消费点 ⇒ 吞掉即拨错 |
| 7 | `SshModel.kt:80` `sshHostname` | display 侧保留 `host:port`（对齐 `name.ts`），**无需改**，修后行名 = 用户所填，正确 |

---

## 4. diff

> 仓库当前有 58k+ 行未提交基线（`git diff` 全仓输出不可读），下表是**本轮对
> `SshConnectionModel.kt` 的逐块改动**（修前原文见 §2.1 与本文首读记录）。

```diff
+/** `[user@]host` with no port anywhere: the IANA SSH port, as jsch's default. */
+const val SSH_DEFAULT_PORT: Int = 22
+
+/** The only option flags jsch can honour (upstream's wider set, see [parseSshTarget]). */
+private val SSH_TARGET_OPTIONS = setOf("-p", "-i", "-l")
+
+/** `[user@]host[:port]` torn apart, [port] `null` when the target named none. */
+private data class SshHostParts(val user: String?, val host: String, val port: Int?)
+
 fun parseSshTarget(command: String): SshTarget? {
     val tokens = tokenizeSshTarget(command) ?: return null
     if (tokens.isEmpty()) return null
     var index = if (tokens[0] == "ssh") 1 else 0
     var user: String? = null
-    var port = 22
+    var optionPort: Int? = null
     var identityPath: String? = null
     var host: String? = null
+    var hostPort: Int? = null
     while (index < tokens.size) {
         val token = tokens[index]
         if (host != null) return null // remote command (or trailing junk)
         when {
-            token == "-p" || token == "-i" || token == "-l" -> {
-                if (index + 1 >= tokens.size) return null
-                val value = tokens[index + 1]
-                when (token) {
+            // Upstream `command.ts:97-99` takes the flag from the first two
+            // characters, so `-p 2222` and `-p2222` are one option, not two.
+            token.startsWith("-") -> {
+                val flag = token.take(2)
+                if (flag !in SSH_TARGET_OPTIONS) return null // -J, -o, -F, -t, …
+                val attached = token.length > 2
+                val value = if (attached) {
+                    token.substring(2)
+                } else {
+                    if (index + 1 >= tokens.size) return null
+                    tokens[index + 1]
+                }
+                if (value.isBlank() || (!attached && value.startsWith("-"))) return null
+                when (flag) {
                     "-p" -> {
-                        port = value.toIntOrNull() ?: return null
-                        if (port !in 1..65535) return null
+                        val parsed = value.toIntOrNull() ?: return null
+                        if (parsed !in 1..65535) return null
+                        optionPort = parsed
                     }
-
-                    "-i" -> if (value.isBlank()) return null else identityPath = value
-                    "-l" -> if (value.isBlank()) return null else user = value
+                    "-i" -> identityPath = value
+                    "-l" -> user = value
                 }
-                index += 2
+                index += if (attached) 1 else 2
             }
-
-            token.startsWith("-") -> return null // unsupported option (-J, -o, …)
             else -> {
                 val parsed = parseSshHostPart(token, user) ?: return null
-                user = parsed.first
-                host = parsed.second
+                user = parsed.user
+                host = parsed.host
+                hostPort = parsed.port
                 index += 1
             }
         }
     }
     val resolvedHost = host ?: return null
-    return SshTarget(user = user, host = resolvedHost, port = port, identityPath = identityPath)
+    // `-p` is the dedicated port option and wins over a `host:port` suffix, the
+    // same precedence the system `ssh` gives an explicit `-p`.
+    return SshTarget(
+        user = user,
+        host = resolvedHost,
+        port = optionPort ?: hostPort ?: SSH_DEFAULT_PORT,
+        identityPath = identityPath,
+    )
 }
@@
-private fun parseSshHostPart(token: String, fallbackUser: String?): Pair<String?, String>? {
+private fun parseSshHostPart(token: String, fallbackUser: String?): SshHostParts? {
     val at = token.lastIndexOf('@')
-    val user = if (at >= 0) token.substring(0, at).ifEmpty { fallbackUser } else fallbackUser
+    val rawUser = if (at >= 0) token.substring(0, at) else null
+    // Upstream `command.ts:109` — `user:password@host` is not a target shape, and
+    // a colon here is a credential the shell must not read as a port.
+    if (rawUser != null && rawUser.contains(':')) return null
+    val user = rawUser?.ifEmpty { fallbackUser } ?: fallbackUser
     val hostPart = if (at >= 0) token.substring(at + 1) else token
-    val host = when {
-        hostPart.startsWith("[") && hostPart.endsWith("]") && hostPart.length > 2 ->
-            hostPart.substring(1, hostPart.length - 1)
-        else -> hostPart
+
+    val (hostLiteral, portLiteral) = splitSshHostPort(hostPart) ?: return null
+    val host = when {
+        hostLiteral.startsWith("[") -> hostLiteral.substring(1, hostLiteral.length - 1)
+        else -> hostLiteral
     }
     if (host.isBlank()) return null
-    return user to host
+    if (portLiteral == null) return SshHostParts(user, host, null)
+    // A colon the parser cannot read as a port is a malformed target, not a
+    // reason to fall back to 22: `host:`, `host:ssh`, `host:0` and `host:99999`
+    // all have one reading, and it is "the user mistyped the port".
+    val port = portLiteral.toIntOrNull() ?: return null
+    if (port !in 1..65535) return null
+    return SshHostParts(user, host, port)
 }
+
+/**
+ * Splits a trailing `:port` off [hostPart] → `(host literal, port digits)`, the
+ * port `null` when there is none. `null` ⇔ the colon cannot be a port, so the
+ * whole token is malformed.
+ *
+ * The only subtle case is IPv6, whose colons are part of the address and not
+ * separators: a bracketed literal ends at `]`, and a *bare* literal has more
+ * than one colon — which is what tells `2001:db8::1` (a host) apart from
+ * `127.0.0.1:2222` (a host and a port).
+ */
+private fun splitSshHostPort(hostPart: String): Pair<String, String?>? {
+    if (hostPart.startsWith("[")) {
+        val close = hostPart.indexOf(']')
+        if (close < 0) return null // unbalanced bracket
+        val literal = hostPart.substring(0, close + 1)
+        val rest = hostPart.substring(close + 1)
+        if (rest.isEmpty()) return literal to null
+        if (!rest.startsWith(":")) return null
+        return literal to rest.substring(1)
+    }
+    val colon = hostPart.lastIndexOf(':')
+    if (colon < 0) return hostPart to null
+    // More than one colon and no brackets ⇒ a bare IPv6 literal, never a port.
+    if (hostPart.indexOf(':') != colon) return hostPart to null
+    return hostPart.substring(0, colon) to hostPart.substring(colon + 1)
+}
```

另加 `SshTarget` / `parseSshTarget` 的 KDoc 扩写（记录上游口径、本次 P1、jsch 无 shell 故不能照抄
`-J/-o/-F` 的理由）。**无新增资源、无新增三语字符串。**

---

## 5. 为什么不降强度

| 既有 pin | 状态 | 证据 |
|---|---|---|
| `SshConnectionModelTest.parseSshTargetAcceptsUpstreamsTargetForms`（5 断言） | **未删未改，全 PASS** | 该类 8 tests / 0 failures |
| `SshConnectionModelTest.parseSshTargetReadsTheSupportedOptions`（6 断言） | 同上 | 同上 |
| `SshConnectionModelTest.parseSshTargetRejectsRemoteCommandsAndUnsupportedOptions`（12 断言） | 同上，**一个都没松**（`-J`/`-o`/悬空 `-p`/越界端口仍拒） | 同上 |
| `SSH_FORM_REACHABILITY_FIX` | **PASS** | `SshFormReachabilityV98Test` 11 tests / 0 failures |
| `HOST_KEY_CARD_FIX` | **PASS** | `SshHostKeyCardV99Test` 7 / 0 |
| `SSH_RECONNECT_FIX`（D1/D2/D3/F12b） | **PASS** | `SshReconnectFidelityV97Test` 20 / 0 |
| `SSH_ROW_MENU_FIX`（`Reconnect`/`Delete`） | **PASS** | `SshRowMenuParityV99Test` 12 / 0 |
| `SshTunnelForget`（确认条） | **PASS** | `SshSeedForgetV92Test` 14 / 0；`SshTunnelDeleteV91Test` 9 / 0 |

**只增不减**：新 pin 全部是**新增断言**，既有文件 `SshConnectionModelTest.kt` / `SshModelTest.kt` /
`SshDialogRenderTest.kt` **一个字节都没改**。修后解析器是上游已接受写法的**超集**，
`-p` 的 `1..65535` 校验原样保留，远程命令/不支持选项的拒绝面**只增不减**。

**为什么不选"更保守"的方案**（报告 §7 建议的候选 B："在表单校验里显式拒绝该写法"）：
那会让最常见的写法继续失败，只是换个错误文案——用户仍然连不上。P1 的性质是**产品缺口**，
不是提示缺失。候选 A（`parseSshHostPart` 剥 `host:port`）即本方案。

**未新增用户可见字符串**：畸形 target 复用既有 `ssh_error_input`
（`SshErrorCode.INPUT` → `SshModel.kt:219`），由 `aRejectedTargetRidesTheExistingInputSentence` 钉住。
三语 `values/`、`values-zh-rCN/`、`values-zh-rTW/` **未改**。

---

## 6. 测试证据

### 6.1 命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.xiufeigo.opencodeshell.SshTargetParseV99Test `
  --tests com.xiufeigo.opencodeshell.SshConnectionModelTest `
  … （共 25 个类，逐类 --tests）…
```

纪律遵守：抢锁（`.agent-gradle-lock` 不存在 → 写锁 → 跑完释放）；**前台跑完**；未跑
assembleDebug / clean / 全量 / 装机。

### 6.2 结果原文

```
LOCK ACQUIRED
RUNNING 25 CLASSES
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 7s
52 actionable tasks: 2 executed, 50 up-to-date
GRADLE_EXIT=0
LOCK RELEASED
```

> 首轮曾有 1 处失败（我自己新 pin 的 `assertEquals(R.string.oc_ssh_error_input, …)`）——
> 面板实际用的资源是 `ssh_error_input`（`ssh.error.*` → `ssh_error_*` 映射），已改正；
> 同处另一条断言 `host:2222:3333` 我原先写成"应拒绝"，实际应按裸 IPv6 主机名处理，已改为正向钉住。

### 6.3 用例数（`app/build/test-results/testDebugUnitTest`）

```
com.xiufeigo.opencodeshell.SshTargetParseV99Test                        tests=9   failures=0   ← 新增
com.xiufeigo.opencodeshell.SshConnectionModelTest                       tests=8   failures=0
com.xiufeigo.opencodeshell.SshModelTest                                tests=6   failures=0
com.xiufeigo.opencodeshell.SshDialogRenderTest                         tests=5   failures=0
com.xiufeigo.opencodeshell.SshFormReachabilityV98Test                  tests=11  failures=0
com.xiufeigo.opencodeshell.SshHostKeyCardV99Test                       tests=7   failures=0
com.xiufeigo.opencodeshell.SshImeNextRoutingF13aTest                   tests=7   failures=0
com.xiufeigo.opencodeshell.SshKeyFileFailureF14Test                    tests=7   failures=0
com.xiufeigo.opencodeshell.SshPanelTunnelTest                          tests=7   failures=0
com.xiufeigo.opencodeshell.SshTunnelWiringTest                         tests=7   failures=0
com.xiufeigo.opencodeshell.SshEd25519IdentityF14Test                   tests=4   failures=0
com.xiufeigo.opencodeshell.SshTunnelHostModelTest                      tests=4   failures=0
com.xiufeigo.opencodeshell.SshAuthenticateModelTest                    tests=8   failures=0
com.xiufeigo.opencodeshell.SshPassphraseAnswerV94Test                 tests=8   failures=0
com.xiufeigo.opencodeshell.SshPromptCancelDoesNotDismissF13bTest       tests=9   failures=0
com.xiufeigo.opencodeshell.SshPromptOfferV94Test                       tests=14  failures=0
com.xiufeigo.opencodeshell.SshReconnectFidelityV97Test                tests=20  failures=0
com.xiufeigo.opencodeshell.SshRowMenuParityV99Test                     tests=12  failures=0
com.xiufeigo.opencodeshell.SshSeedForgetV92Test                        tests=14  failures=0
com.xiufeigo.opencodeshell.SshSubmitChainTest                          tests=10  failures=0
com.xiufeigo.opencodeshell.SshDeletePathV97Test                        tests=12  failures=0
com.xiufeigo.opencodeshell.SshTunnelDeleteV91Test                      tests=9   failures=0
com.xiufeigo.opencodeshell.SshTunnelNarrowRowV93Test                   tests=12  failures=0
com.xiufeigo.opencodeshell.SshTunnelNarrowStructureV93Test             tests=8   failures=0
com.xiufeigo.opencodeshell.ServerConfigStoreSshTest                    tests=8   failures=0
TOTAL tests=226 failures=0
```

**226 用例 / 0 失败**（其中新 pin 9 个）。

---

## 7. 真机复验步骤

> **前置**（照抄诊断报告 §5c，逐条可校验）：`adb` 用全路径
> `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`；宿主 sshd 在 22 监听；
> `wsl -d Arch -e bash -lc "sudo faillock --user sshtest"` **输出为空**（有 `V` 行先
> `sudo faillock --user sshtest --reset`）；公钥在位（`SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8`）；
> 私钥在 app 私有目录（`md5 0120a8270f7f0b0c71d31f4933384c63`）；`adb -s emulator-5554 reverse --list` 含 `tcp:2222 tcp:22`。
> **本轮修复需重新装机**（`lastUpdateTime` 须晚于本次构建），否则测的还是旧二进制。

### 用例 A —— `sshtest@127.0.0.1:2222`（**修前必失败的那条**）

| # | 动作 | 预期读数 |
|---|---|---|
| 1 | Add SSH server → Host 填 `sshtest@127.0.0.1:2222`，Name 填 `V99FIX-A` | 写入成功 |
| 2 | Name 填完 ⇒ 认证区出现 → Authentication 选 `Private key` → Key file path 填 `/data/user/0/com.xiufeigo/opencodeshell/files/id_ed25519`，Passphrase 留空 | 字段集切换正常 |
| 3 | Add server | **不再是 `Connection failed`**；行状态 `Connecting over SSH…`，弹 `Verify host identity` 卡 |
| 4 | 校验卡读数 | **`[127.0.0.1]:2222`** + `MD5:9b:19:f4:e8:83:e3:ca:ad:1d:c0:62:76:76:48:9b:24`（与 F14 同指纹 ⇒ 真连上了 2222） |
| 5 | `Trust and connect` → 认证卡 | ⚠ **预期仍是 `Password:` 卡（P3 未修，不属本轮）**；输 `opencode` → `Continue` |
| 6 | `wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-2min' \| tail -5"` | **必须有** `Accepted … for sshtest`（修前是 `-- No entries --`） |
| 7 | 读 UI | 行状态 `Connected`，出现 `Reconnect` / `Disconnect`，隧道形如 `127.0.0.1:<port>`，用户 `sshtest` |

> **决定性对比**：第 3 步之前 = `Connection failed` + sshd `-- No entries --`；
> 第 3 步之后 = 主机核验卡 + sshd `Accepted …`。

### 用例 B —— `ssh -p 2222 sshtest@127.0.0.1`（报告里那条"可用替代"，必须**仍然**能连）

| # | 动作 | 预期读数 |
|---|---|---|
| 1 | Host 填 `ssh -p 2222 sshtest@127.0.0.1`，Name 填 `V99FIX-B`，其余同 A | — |
| 2 | Add server | `Connecting over SSH…` + 核验卡 **`[127.0.0.1]:2222`** + 同指纹 |
| 3 | Trust and connect → 认证 → `Connected`；sshd 见 `Accepted …` | 与 A 一致 |

> **B 是回归闸**：证明新增的 `:port` 路径没有抢走 `-p` 的语义。

### 用例 C —— 畸形端口必须**可见**（P2 闭环）

| # | 动作 | 预期读数 |
|---|---|---|
| 1 | Host 填 `sshtest@127.0.0.1:`（尾部冒号无数字），Name 填 `V99FIX-C`，提交 | 行状态 `Connection failed`，句子为 **`Enter a host or SSH connection command. Remote commands and unsupported SSH options aren't allowed.`**（`ssh_error_input`）——**不是**"Check your network and SSH configuration" |
| 2 | 读 sshd 日志 | 仍零日志（正确：拨号前就被拒，没到网络） |

> 修前这条会**成功解析**成 `host="127.0.0.1:"` + `port=22` ⇒ 走 A 那条"怪网络"的错误文案。
> 修后落到指向 target 的那句 ⇒ P2 闭环的**决定性证据**。

### 清理

A/B/C 三条跑完：`Disconnect` → 行 kebab → `Delete` → 确认条原文 `This removes "V99FIX-x" from the list.` → `Delete`。
`/sdcard` 零残留；`reverse` 隧道一条不动；`files/ssh/known_hosts` 本轮会新增一条（`[127.0.0.1]:2222`），
属既有 `Trust and connect` 行为，登记但不删。

---

## 8. 未闭环

1. **P3（私钥退化）未修**：用例 A/B 第 5 步预期**仍弹 `Password:` 卡**，`Accepted publickey` 依然缺席。
   本轮 P1 只修 target 解析，`SshConnection` 的认证方法顺序未动。
   ⇒ **"F14b 在当前二进制上是否还 PASS" 仍未知**，需先修 P3。
2. **本轮未上真机**：只做了 JVM 回归。§7 的 A/B/C **全部是待执行步骤**，不是已取得的读数。
   装机需 Lead 授权（任务纪律禁装机）。
3. **P2 只闭环了 target 一侧**：`sshFailure()` 仍无 `java.net.UnknownHostException` 分类
   （"无法解析这个主机名"目前落 `else -> CONNECTION`）。真正把 DNS 失败和"网络不通"分开，
   需要新句子（`unknown-host`），属**新增三语字符串**，需单独决策。`ui/servers/**` 在写权限内，
   可在下一轮做。
4. **app 侧 SSH 日志仍缺**：报告 §7.3 未量化项——本轮未读全量异常链，
   `UnknownHostException` 是否真的能到达 `sshFailure()` 尚未验证。
5. **上游同款缺陷未修（本壳已超集）**：上游 `parseTarget` 原样转发 `user@host:port` 给系统 ssh，
   实测同样 `Could not resolve hostname`。这是**上游**的缺口，本轮**不碰 `_upstream/`**。
6. **`host:2222:3333` 按主机名处理**（§2.2 边界）：与上游一致，但若 Lead 认为应报错，
   需要一个"多冒号 ⇒ 非法"的额外判定；未实施，因为会误伤裸 IPv6。
