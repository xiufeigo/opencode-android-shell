# UX12-Build — 落地「方案 A」：官方前端自带 + 本机 http 静态来源

- 轮次：UX12-Build
- 日期：2026-10-06
- 仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`
- 起点 HEAD：`dcbe158`（`docs: restore the handoff hashes after reverting the mistaken commit`）
- 恢复来源提交：`57f1fca`（`chore: commit pending work before handing the deferred plan over`，已被 7a78af0 revert）

## 第 0 步：建报告文件

- 完成时间：2026-10-06 10:55:46 +08:00
- 状态：成功
- 预检：
  - `Test-Path .agent-gradle-lock` -> False（无锁）
  - 起始 `git status --porcelain`：仅 3 条未跟踪目录（`research/env/opencode-dist-2.0.23/`、`research/env/planA-review/`、`research/env/ux12-round1-build.prompt.md`），无 `app/**`、无 `webapp/**` 改动
  - 来源目录 `research/env/opencode-dist-2.0.23/` 已就绪（顶层：`_assets/ assets/ icons/ _headers index.html sw.js oc-theme-preload.js site.webmanifest` + 各类 favicon/apple-touch/social-share/web-app-manifest 图标）


## 第 1 步：恢复 5 个 Kotlin 源文件（从 git 对象原样取出）

- 时间：10:56:06
- 状态：成功
- 方式：`cmd /c "git show 57f1fca:<path> > <path>"`（cmd 重定向保持字节），未手打、未改动

### 1.1 `git hash-object` 与 `git rev-parse` 逐字节对比

```text
MATCH  work=87d5e579bdd4dc325e0d107d2c70b00a5a3b9097  git=87d5e579bdd4dc325e0d107d2c70b00a5a3b9097  webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt
MATCH  work=681fb1b7a76ddf65e137144088c9c186ad483e7a  git=681fb1b7a76ddf65e137144088c9c186ad483e7a  webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
MATCH  work=9c720ae40e635610e20e82513b415259bc298db7  git=9c720ae40e635610e20e82513b415259bc298db7  webapp/src/test/java/com/xiufeigo/opencodeshell/web/DirectConnectAuthBoundaryTest.kt
MATCH  work=599e141feeaa98493155f9119e5f278f1e44e4ee  git=599e141feeaa98493155f9119e5f278f1e44e4ee  webapp/src/test/java/com/xiufeigo/opencodeshell/web/LocalUiOriginServerTest.kt
MATCH  work=9ee511c33677f4cc2503632f7352cd84d03d17b9  git=9ee511c33677f4cc2503632f7352cd84d03d17b9  webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalReattachTest.kt
```

5/5 逐字节一致。

### 1.2 `git status --porcelain -- webapp`（恰好 5 条：3 改 + 2 增）

```text
 M webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/DirectConnectAuthBoundaryTest.kt
 M webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalReattachTest.kt
?? webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt
?? webapp/src/test/java/com/xiufeigo/opencodeshell/web/LocalUiOriginServerTest.kt
```

### 1.3 `git diff --stat` 与 `git diff --stat dcbe158 57f1fca` 对比

工作树（仅 3 个已跟踪文件）：

```text
 .../xiufeigo/opencodeshell/web/WebHostActivity.kt  | 12 +++-
 .../web/DirectConnectAuthBoundaryTest.kt           | 72 +++++++++++++++++++---
 .../opencodeshell/web/TerminalReattachTest.kt      | 18 ++++--
 3 files changed, 85 insertions(+), 17 deletions(-)
```

参考（`git diff --stat dcbe158 57f1fca`，5 文件）：

```text
 .../xiufeigo/opencodeshell/web/WebHostActivity.kt  |  12 +-
 .../xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt | 295 +++++++++++++++++++++
 .../web/DirectConnectAuthBoundaryTest.kt           |  72 ++++-
 .../xiufeigo/opencodeshell/web/LocalUiOriginServerTest.kt | 196 ++++++++++++++
 .../opencodeshell/web/TerminalReattachTest.kt      |  18 +-
 5 files changed, 576 insertions(+), 17 deletions(-)
```

三个已跟踪文件的增删行数（12 / 72 / 18）与 57f1fca 相对 dcbe158 完全一致；两个新增文件用 hash-object 证明逐字节一致（见 1.1）。

- 注：`git diff` 报 `LF will be replaced by CRLF` 只是 core.autocrlf 的**未来**提示；当前文件内容与 git blob 逐字节相同（hash-object MATCH 已证）。

## 第 2 步：拷入官方前端资源（1613 个文件 + `_assets→oa` 改名）

- 时间：10:56:46
- 状态：成功
- 来源：`research/env/opencode-dist-2.0.23/`（1613 个文件，未重新下载）
- 目标：`webapp/src/main/assets/www/ui/`
- 方式：`robocopy` 两次（先 `/XD _assets` 平铺全部，再把 `_assets/` 复制为 `ui/oa/`），未改动任何文件内容

### 2.1 计数与存在性

```text
(Get-ChildItem webapp\src\main\assets\www\ui -Recurse -File).Count  ->  1613
Test-Path webapp\src\main\assets\www\ui\oa          ->  True
Test-Path webapp\src\main\assets\www\ui\_assets     ->  False
Test-Path webapp\src\main\assets\www\ui\index.html  ->  True
Test-Path webapp\src\main\assets\www\ui\sw.js       ->  True
Test-Path webapp\src\main\assets\www\ui\_headers    ->  True
```

计数 **1613** = 目标（1611 清单 + sw.js + _headers），与来源一致。

### 2.2 字节级校验（全部 1613 个文件逐个 MD5 对比 源 vs 目标）

```text
checked=1613  missing=0  hashdiff=0  bad=0
```

0 缺失、0 内容差异。**除 `_assets→oa` 目录改名外，没有任何官方资源文件被修改。**

### 2.3 原有 5 个注入件未被触碰

`webapp/src/main/assets/www/` 下原有 `index.html` / `oc-host.js` / `oc-perf.js` / `oc-plus-chooser.js` / `oc-preflight.js` 均未出现在 `git status` 改动列表中（见第 1.2 节输出，只有 5 个 Kotlin 文件 + 未跟踪的 `ui/` 资源目录）。

## 第 3 步：凭据红线逐字扫描（新代码 0 命中）

- 时间：10:57:04
- 状态：成功（0 命中）

### 3.1 新增文件 `LocalUiOriginServer.kt` 严格扫描

命令：

```powershell
Select-String -Path 'webapp\src\main\java\com\xiufeigo\opencodeshell\web\host\LocalUiOriginServer.kt' -Pattern 'Authorization','password','passwd','Basic ','authTokenFromCredentials','KEY_SERVER_PASSWORD','Proxy','proxy' -CaseSensitive:False
```

输出（**完全无输出**）：

```text
=== 3.1 LocalUiOriginServer.kt strict scan (expect 0 hits) ===
HITCOUNT=0
```

**0 命中。** 新加的静态来源服务器不含任何凭据/代理相关标识。

### 3.2 整个 main 源码树严格扫描

命令：

```powershell
Select-String -Path (Get-ChildItem webapp\src\main\java -Recurse -Filter *.kt).FullName -Pattern 'Authorization|authTokenFromCredentials|KEY_SERVER_PASSWORD|BASIC_USER'
```

输出：

```text
=== 3.2 whole main tree strict scan ===
HITCOUNT=0
```

**0 命中。** 本次恢复/新增的代码在整个 main 树中未引入任何凭据符号。

### 3.3 允许命中项核对（`WebHostActivity.kt` 密码注释）

第 3.2 步的模式集不含 `password`/`Basic` 字面量，因此该注释不在 3.2 的命中集内。单独核对：注释位于 `WebHostActivity.kt` **第 64 行**，内容为：

```kotlin
 * 支持密码字段，Basic  + "opencode:<password>" + ，见  + "untime/server/api.ts:5-7" + ）。
```

它是**本轮之前就存在**的注释，非新增 —— 证据：旧版 `git show dcbe158:webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt` 中同一行内容位于**第 63 行**（仅因本轮恢复新增了一行文档而位移到 64）：

```text
OLD line 63:  * 支持密码字段，Basic opencode:<password>，见 untime/server/api.ts:5-7）。
```

它是 KDoc 文档、描述的是官方 UI 自身的 Basic 认证机制，**不是代码**，宿主不持有也不转发任何口令。
## 续（第 4–8 步）—— 由协调方执行（原因：mcode 会话结束会回收其前台子进程，两轮 mcode 的 gradle 均在会话结束时被终止，无 test-results 产出；实现与核查仍由 mcode 轮完成，此处仅代为执行机械 gradle 步骤并全程保留证据）

### 4. 单元测试（本机实跑）

环境修正（本机与任务原文不同，已逐条核实）：
- Android Studio JBR 在本机是 **Java 25.0.3**，被 AGP 8.7.3 拒绝 ⇒ 改用 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`。
- 本机无 `local.properties`/`ANDROID_HOME` ⇒ 命令内联 `$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"`。
- 本机无 `~/.android/debug.keystore`（原机器的签名件）⇒ 按标准 Android Debug 参数新生成（CN=Android Debug, O=Android, C=US）——**证书与原交付物不同属预期**，md5 台账以本批为准。

命令：`.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest`

结果：
- **`:webapp` = 194 用例 / 0 failures / 0 errors**（test-results 24 个 XML 求和：tests=194, failures+errors=0；含新增 LocalUiOriginServerTest 10 条 + 改写后的 DirectConnectAuthBoundaryTest / TerminalReattachTest）。
- **`:app` = 6136 用例 / 57 failed**。⚠️ **全部为本机预置环境缺口，与本轮改动无关**（`git status` 证明 `app/**` 零改动；失败用例全在旧原生壳几何/上游对照套件）：
  1. 缺 gitignore 的本机设备工件：`research/v97_verify/5554_md/artifacts/*.xml`（原机器模拟器转储，本机 `artifacts/` 仅有 .ps1/.csv）——ComposerBandDeviceTruthTest / ComposerBandA11yReachabilityTest / UsabilityCellsRulingV143Test / UsabilityCellsV144FixTest / UsabilityCellsV146SendOcclusionFixTest 等；
  2. 缺 `_upstream` 工作副本：SshRowMenuUpstreamParityV115Test（missing _upstream/packages/app/src/servers/registry/controller.ts）、ProjectParityV97Test（missing upstream worktree.ts）；
  3. 其余为同类源文件解析断言（Mobile* / Composer* / Dialog* / TerminalTabStripV49Test 等），同样位于 app 旧原生壳套件。
  用例数 6136 只增未减；webapp 侧 194 全绿满足「只增不减 + Plan A 绊线全绿」。

### 5. 反向验证（撤回修复必须真红）—— 真红 3 条

将 `WebHostActivity.kt` 恢复为 `dcbe158` 旧版（`git show dcbe158:... > ...`），仅跑 `:webapp:testDebugUnitTest`：

```text
DirectConnectAuthBoundaryTest > bootResolvesBackendUrlWithDefault FAILED
LocalUiOriginServerTest > activityLoadsPageFromLocalOrigin FAILED
TerminalReattachTest > bootUrlIsStableAcrossLaunches FAILED
194 tests completed, 3 failed
BUILD FAILED
```

三条均为**断言失败**（非编译错误）。随后恢复新版，复跑 `BUILD SUCCESSFUL`（任务 UP-TO-DATE，即恢复到全绿字节态）。

### 6. 三产物构建

`.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8` 结果 `BUILD SUCCESSFUL in 1m 15s`（仅 2 条 deprecation 警告，均为既有代码）。

### 7. 包内自查 + research/deliver

| 产物 | 体积 | md5 | assets/www/ui 条目 | assets/www/ui/oa 条目 | 包内 oc-host.js = 源文件 |
|---|---|---|---|---|---|
| opencode-shell-web.apk（debug） | 16,796,038 | BF688F8A033EDE65C3D4F8C5E8D1E930 | 1613 | 1590 | MATCH（8A3B3E93EBF61BEC347067D4E4074A69，磁盘 CRLF 字节；LF 归一化 = b5057679b1a419af15d81ef72ff0fd9e） |
| opencode-shell-web-release-nor8.apk | 14,304,138 | B3F9D303BF557AB5CB02EED7D61D09F0 | 1613 | 1590 | MATCH |
| opencode-shell-web-release-r8.apk | 12,024,280 | F3D3911AA597122657C81072E51EDEAC | 1613 | 1590 | MATCH |

三份 ui 条目均 = 1613，R8 的 shrinkResources 没有误删自带资源。

### 新旧体积对比（产品负责人关心项）

| 产物 | 旧（UX8 批） | 新（本批） | 变化 |
|---|---|---|---|
| debug | 5,437,979 | 16,796,038 | +11,358,059（×3.09） |
| release nor8 | 3,549,507 | 14,304,138 | +10,754,631（×4.03） |
| release R8 | 1,268,829 | 12,024,280 | +10,755,451（×9.48） |

增量来源：assets/www/ui 下 1613 个官方前端文件 / 33.58 MiB 未压缩。

### 8. 收尾自查

- 锁：测试/构建期间持有，跑完后已单条顶层 `rm -- .agent-gradle-lock` 释放（Test-Path = False）。
- `git status --porcelain -- app webapp`：app 0 条目；webapp 侧 5 个 Kotlin 文件（3 改 2 增）+ 未跟踪 ui 资源目录。
- 后端 127.0.0.1:4095 全程未起停（只读探测）。
- 未 git commit。
