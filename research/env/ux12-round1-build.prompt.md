# 轮次：UX12-Build —— 落地「方案 A」：官方前端自带 + 本机 http 静态来源（含构建与自查）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`（以下所有相对路径均以此为准）。
**先读盘、后动手；每步一条命令 + 立即读输出；每个结论当场写进报告文件。不要长脚本空等。**

## 背景（机制，30 秒版）

上游判据 `_upstream/packages/app/src/servers/connect/browser.ts:3-12`：官方「添加服务器」对话框对 http 目标报红字「无法从 HTTPS 页面连接此 HTTP 服务器」，判据 = `new URL(location.href).protocol === "https:"`。我们的壳当前 `loadUrl(配置的后端地址)` ⇒ 页面协议 = 后端协议 ⇒ 配过 https 后端就永远加不了 http 服务器。
**方案 A**：把官方前端作为 App 自带资源（`webapp/src/main/assets/www/ui/`），用只服务静态资源的本机 http 端点 `http://127.0.0.1:4173` 加载 ⇒ 页面协议恒为 `http:` ⇒ 死锁消失；该来源落在服务端 CORS 白名单（http 回环带端口）内 ⇒ 跨源可用；认证仍完全由官方 UI 完成；API 由页面直连用户配置的服务器，**绝不代理**。

这个实现**曾经完整做过一次**（git 提交 `57f1fca`，后被 revert），你要做的是**原样恢复它 + 换新一批官方前端资源 + 构建 + 自查**。不要重新发明。

## 第 0 步：建报告文件

立即创建 `research/web/UX12_PLAN_A_BUILD.md`，头部写好轮次、日期（2026-10-06）、你的第 0 步完成时间。每完成一步立刻把结果追加进去（成功/失败、原始输出关键行）。

## 第 1 步：恢复 5 个 Kotlin 源文件（从 git 对象原样取出）

```
git show 57f1fca:webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt
```
等 5 个文件，**逐字节**写入工作树对应路径（不要手打、不要"改进"）：
1. `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt`（新文件，父目录 host/ 已存在）
2. `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`（覆盖）
3. `webapp/src/test/java/com/xiufeigo/opencodeshell/web/DirectConnectAuthBoundaryTest.kt`（覆盖）
4. `webapp/src/test/java/com/xiufeigo/opencodeshell/web/LocalUiOriginServerTest.kt`（新文件）
5. `webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalReattachTest.kt`（覆盖）

写完后验证：`git status --porcelain -- webapp` 应恰好显示这 5 个路径（2 新增 + 3 修改）。`git diff --stat` 确认 WebHostActivity.kt 的 diff 与 `git diff dcbe158 57f1fca -- <该文件>` 一致。
注意 Windows PowerShell 重定向会引入 UTF-16/CRLF 风险 —— 用 `git show 57f1fca:<path> | Out-File -Encoding utf8 <path>` 或更稳妥的 `cmd /c "git show 57f1fca:<path> > <path>"`（cmd 重定向保持字节）。**取出的文件必须与 git 对象逐字节一致**（用 `git hash-object <file>` 对比 `git rev-parse 57f1fca:<path>`）。

## 第 2 步：拷入官方前端资源（1613 个文件，字节级复制 + 一处目录改名）

- 来源（已就绪，**不要重新下载**）：`research/env/opencode-dist-2.0.23/` —— 这是从在跑的官方 opencode 服务端（v2.0.23）按 PWA precache 清单完整取回的官方前端产物（1611 个清单文件 + sw.js + _headers，共 1613 个文件 / 33.58 MiB）。
- 目标：`webapp/src/main/assets/www/ui/`。
- **关键坑（必须照做）**：AGP 资源合并会**丢弃下划线开头的目录**。把来源下的 `_assets/` 目录**改名为 `oa/`** 再放进目标（即目标是 `www/ui/oa/…`），其余目录与文件（`assets/`、`icons/`、`index.html`、`sw.js`、`_headers`、`favicon*`、`site.webmanifest`、`oc-theme-preload.js`、`social-share*.png`、`web-app-manifest-*.png`、`apple-touch-icon*.png`）原样平铺到 `www/ui/` 下。**除 `_assets→oa` 外不许改任何文件内容**。
- 验证：`(Get-ChildItem webapp/src/main/assets/www/ui -Recurse -File).Count` 应为 **1613**；`Test-Path webapp/src/main/assets/www/ui/oa` 为 True；`Test-Path webapp/src/main/assets/www/ui/_assets` 为 False；`Test-Path webapp/src/main/assets/www/ui/index.html` 为 True。

## 第 3 步：凭据红线逐字扫描（新代码必须 0 命中）

```powershell
Select-String -Path 'webapp\src\main\java\com\xiufeigo\opencodeshell\web\host\LocalUiOriginServer.kt' -Pattern 'Authorization','password','passwd','Basic ','authTokenFromCredentials','KEY_SERVER_PASSWORD','Proxy','proxy' -CaseSensitive:$false
```
新增文件 LocalUiOriginServer.kt 必须**完全无输出**（0 命中）。把整个 main 源码树的严格扫描也跑一遍并记录输出：
```powershell
Select-String -Path (Get-ChildItem webapp\src\main\java -Recurse -Filter *.kt).FullName -Pattern 'Authorization|authTokenFromCredentials|KEY_SERVER_PASSWORD|BASIC_USER'
```
（允许命中：`WebHostActivity.kt` 第 64 行那条**本轮之前就存在**的注释 `支���密码字段，Basic opencode:<password>`——确认它是注释、非新增，把行内容抄进报告。）

## 第 4 步：单元测试（0 失败；用例数只增不减）

gradle 锁协议（严格遵守）：
1. `Test-Path .agent-gradle-lock` —— 若存在，等它消失（每 10 秒查一次，等到为止）。
2. 新建锁：`New-Item -ItemType File .agent-gradle-lock`。
3. **前台**运行（跑完前不做别的）：
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest
```
4. 无论成败，跑完立即释放锁：**单条顶层命令** `rm -- .agent-gradle-lock`，再 `Test-Path` 确认 False。

预期：BUILD SUCCESSFUL；`webapp` 194 用例（UX10 基线 184 + 新增 LocalUiOriginServerTest 10）、`app` 6136 用例，0 failures / 0 errors。
把每个模块的 "X tests completed" 行原文抄进报告。**若 webapp 用例数不是 194 或有失败：停下来排查，把现象写进报告，不要硬凑。**

## 第 5 步：反向验证（撤回修复必须真红）—— 在构建产物之前做

当前工作树的改动**未提交**，所以 `git show dcbe158:<path>` 就是"修复前"的旧版本。
1. `Copy-Item webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt $env:TEMP\WebHostActivity.kt.new`（先备份新版本）。
2. 旧版覆盖：`cmd /c "git show dcbe158:webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt > webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt"`。
3. 只跑 webapp 测试（同样走锁协议）：`.\gradlew.bat --console=plain :webapp:testDebugUnitTest`。**预期真红**：`DirectConnectAuthBoundaryTest.bootResolvesBackendUrlWithDefault` 与 `TerminalReattachTest.bootUrlIsStableAcrossLaunches` 至少这 2 条断言失败（不是编译错误）。把失败的测试名原文抄进报告。
4. 恢复：`Copy-Item $env:TEMP\WebHostActivity.kt.new webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt`，并 `git diff --stat` 确认工作树回到第 4 步全绿时的状态（只剩那 5 个文件 + 资源目录的差异）。
5. 再跑一次 `:webapp:testDebugUnitTest` 确认恢复全绿（这轮也要走锁协议）。
若第 3 步**没有变红** ⇒ 绊线失效，停下来把现象写进报告（这算失败，不许继续）。

## 第 6 步：构建三份产物

锁协议下同一条 gradle 前台命令依次跑（或一条命令串起来）：
```
.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8
```
产物：`webapp/build/outputs/apk/debug/webapp-debug.apk`、`…/release/webapp-release.apk`、`…/releaseR8/webapp-releaseR8.apk`。BUILD SUCCESSFUL 后释放锁。

## 第 7 步：包内自查 + 落盘 research/deliver/

1. `New-Item -ItemType Directory -Force research\deliver`（目录当前不存在）。
2. 复制并规范命名：
   - `webapp-debug.apk` → `research\deliver\opencode-shell-web.apk`
   - `webapp-release.apk` → `research\deliver\opencode-shell-web-release-nor8.apk`
   - `webapp-releaseR8.apk` → `research\deliver\opencode-shell-web-release-r8.apk`
3. 对**三份**各做包内自查（PowerShell）：
   ```powershell
   Add-Type -Assembly System.IO.Compression.FileSystem
   $z=[IO.Compression.ZipFile]::OpenRead('research\deliver\opencode-shell-web.apk')
   $ui=($z.Entries | Where-Object FullName -like 'assets/www/ui/*').Count
   $entry=[IO.Compression.ZipFileExtensions]::GetEntry?  # 不用这个；用 $z.GetEntry('assets/www/oc-host.js')
   ```
   正确做法：`$z.GetEntry('assets/www/oc-host.js')` 取出后 `CopyTo` 到内存流算 md5；与 `Get-FileHash webapp\src\main\assets\www\oc-host.js -Algorithm MD5` 对比，必须相等。
   每份记录：`assets/www/ui/*` 条目数（三份都应 = 1613；若 R8 少了 ⇒ shrinkResources 误删，必须报告并停下）、`assets/www/oa/*` 条目数、包内 oc-host.js md5、是否 == 源文件 md5（`b5057679b1a419af15d81ef72ff0fd9e`）。
4. `Get-FileHash -Algorithm MD5` 三份交付物 + 体积，写进报告。

## 第 8 步：完成报告

`research/web/UX12_PLAN_A_BUILD.md` 收尾必须含（逐条给原始输出）：
- 环境（JAVA_HOME 版本、gradle 版本行）
- 5 个源文件恢复的证据（hash-object 对比输出）
- 资源拷贝（1613 计数、oa/_assets 存在性）
- 凭据扫描命令 + 完整输出
- 单测结果表（webapp 194 / app 6136 / 0 失败）+ "tests completed" 原文
- 反向验证：变红的测试名原文 + 恢复后复跑全绿的原文
- 三份产物：体积、md5、ui 条目数、oa 条目数、包内 oc-host.js md5 与源比对结果
- 新旧体积对比表（旧：debug 5,437,979 / nor8 3,549,507 / r8 1,268,829）
- 工作区状态：`git status --porcelain -- app webapp` 输出（app/** 应为 0 条目）
- 收尾自查：`.agent-gradle-lock` 已释放（Test-Path False 的原文）；**你没有启动/停止任何后端**（本机 127.0.0.1:4095 有一个在跑的 opencode 后端，只许用 `Invoke-WebRequest` 做只读探测，不许起停）；没有动 adb/模拟器。

## 🔴 红线（违反 = 本轮失败）

1. 不许提交任何 git commit（保持工作树未提交状态）。
2. 不许改 `app/**`、不许改 `webapp/src/main/assets/www/` 下原有的 5 个注入件（`index.html`/`oc-host.js`/`oc-perf.js`/`oc-plus-chooser.js`/`oc-preflight.js`）的任何字节。
3. 不许起停 4095 后端；不许动 adb / 模拟器。
4. 除 `_assets→oa` 外不许改任何官方资源文件内容。
5. 每步单条命令 + 立即读输出；报告即时落盘。
6. 失败就停下写报告，不许硬凑、不许放宽断言。

## 汇报（最后一行）

状态 + 单测用例数与失败数 + 反向验证红了几条（测试名）+ 三份产物 体积/md5 + ui 条目数 + 包内 oc-host.js 自查是否 MATCH + 锁已释放 + 报告路径。
