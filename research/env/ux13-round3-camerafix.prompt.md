# 轮次：UX13-P3 —— 修复相机扫码被拒（方案 A 回归）+ 重建三产物

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX13_P3_CAMERA_FIX.md`。**

## 背景与根因（已钉死，勿重新调查）

`research/web/UX13_P1_CAPABILITIES.md` 步骤 2 实测：官方 Add server 对话框 → Scan QR code → PairingScanner 正常渲染，但宿主 logcat：
`OcWebHost: camera permission DENIED origin=http://127.0.0.1:4173/ resources=[VIDEO_CAPTURE]` ×6。
根因：`WebHostActivity.kt` 的 `isTrustedWebOrigin()`（约 1022-1032 行）可信来源只有 `SecretStore.KEY_BACKEND_URL` 与默认 4095；方案 A 后页面来源是 `http://127.0.0.1:4173`（`LocalUiOriginServer.origin()`），不在名单 ⇒ 相机被拒。这是宿主实现与浏览器 `isSecureContext` 判定不一致（127.0.0.1 是 potentially trustworthy）——宿主 bug。

## 修法（唯一授权改动）

### 1. `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`
在 `isTrustedWebOrigin` 的 `candidates` buildList 里追加一行：
```kotlin
add(LocalUiOriginServer.origin())
```
位置：在 `add(SecretStore.DEFAULT_BACKEND_URL)` 之后。import 已存在（bootUrl 在用），无需新增。
同时把该函数 KDoc（约 1015-1021 行）更新一句：可信来源 = 配置的后端 origin + 默认后端 origin + **自带官方前端的页面来源 `LocalUiOriginServer.origin()`**（它只服务 App 自带 assets，与本应用同一信任级）。**不许**泛化成域名/正则；**不许**碰凭据、代理逻辑；除该函数外不改其它任何字节。

### 2. 绊线更新 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/CameraPermissionHostTest.kt`
`grantIsRestrictedToTrustedOrigins` 追加断言：把 `isTrustedWebOrigin` 函数体（从 `private fun isTrustedWebOrigin` 到其结束）切出来，断言它包含 `LocalUiOriginServer.origin()`（⚠️ 不能对整个 activity 源做 contains——`bootUrl()` 也在用同一字符串，会对旧代码假绿）。新增一条独立 @Test 亦可，命名 `bundledUiOriginIsTrustedForCamera`。

### 3. 反向验证（必须真红）
- 先跑 `.\gradlew.bat --console=plain :webapp:testDebugUnitTest` 全绿（走锁协议：等锁→建锁→前台跑→跑完单条顶层 `rm -- .agent-gradle-lock`）。
- 然后 `cmd /c "git show bc87e72:webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt > webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt"`（备份先 Copy-Item 到 $env:TEMP），复跑 `:webapp:testDebugUnitTest`：**预期 `bundledUiOriginIsTrustedForCamera` 真红**；恢复备份，复跑全绿。全过程抄原文进报告。
- 环境：JAVA_HOME 必须 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（JBR 是 Java 25 会被拒）；`$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"` 内联；gradle 长编译正常，**耐心等，不要中断**（mcode 会话结束会回收子进程——如果你预计要等很久，改用 `Start-Process` 脱离会话启动 gradle 并轮询 test-results XML 是否产出，再读结果）。

### 4. 重建三产物 + 落盘
`.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8` ⇒ BUILD SUCCESSFUL 后复制到 `research/deliver/`（覆盖三份，规范名同前：`opencode-shell-web.apk` / `opencode-shell-web-release-nor8.apk` / `opencode-shell-web-release-r8.apk`）。

### 5. 包内自查 + 报告
- 三份各查：`assets/www/ui/*`=1613、`assets/www/ui/oa/*`=1590、包内 `assets/www/oc-host.js` md5 == 源文件（源磁盘 md5 预期 `8A3B3E93EBF61BEC347067D4E4074A69`，同 UX12 批——注入件没动）。
- 三份 `Get-FileHash` 体积/md5 抄进报告（新旧对比：UX12 批 debug 16,796,038 BF688F8A… / nor8 14,304,138 B3F9D303… / r8 12,024,280 F3D3911A…）。
- 凭据扫描再跑一次（LocalUiOriginServer.kt + 改动后的 WebHostActivity.kt，同 UX12 模式）0 命中。

## 🔴 红线
不改 `app/**`；不改 `www/` 五个注入件与 `ui/` 官方资源任何字节；不 git commit；不起停 4095 后端；只关自建实例（本轮不起模拟器）；失败停下写报告。

## 汇报（最后一行）
状态 + 新测试名 + 反向验证红了哪条 + 三份新产物 体积/md5 + 包内自查三项 + 锁已释放 + 报告路径。
