# 轮次：UX12-Build-续 —— 接着完成 UX12-Build 的第 4–8 步（测试/反向验证/构建/包自查/收尾）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**先读盘、后动手；每步一条命令 + 立即读输出；每个结论当场追加进既有报告文件 `research/web/UX12_PLAN_A_BUILD.md`（第 0–3 步已完成且干净，不要重做、不要覆盖既有内容，只在末尾追加“续”节）。不要长脚本空等。**

## 接续状态（上一轮已完成，直接采信）

- 5 个 Kotlin 文件已从 git `57f1fca` 逐字节恢复（2 新增 + 3 修改，`hash-object` 5/5 MATCH）。
- 官方前端 1613 个文件已拷入 `webapp/src/main/assets/www/ui/`（`_assets→oa`，逐文件 MD5 0 差异）。
- 凭据扫描 0 命中。
- **上一轮的第 4 步（单测）在跑测试时进程被中断，没有产出任何结果**；`.agent-gradle-lock` 与残留 daemon 已由协调方清理（你现在起 Must 重新走锁协议）。没有 test-results 残留。

## 环境修正（上轮踩过，直接用）

- `JAVA_HOME` **不要**用 Android Studio JBR —— 它在这台机器是 **Java 25.0.3**，会被 AGP 8.7.3 拒绝。用：
  `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
- 本机没有 `local.properties` / `ANDROID_HOME` 环境变量；命令里内联：
  `$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"`

## 第 4 步（重做）：单元测试

锁协议：`Test-Path .agent-gradle-lock` 为真则等 → `New-Item -ItemType File .agent-gradle-lock` → 前台跑 → 跑完**立即**单条顶层 `rm -- .agent-gradle-lock` 并 `Test-Path` 确认。
```
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --console=plain :webapp:testDebugUnitTest :app:testDebugUnitTest
```
注意 gradle 首次可能再次花几分钟编译 6136 个 app 测试 —— **这是正常的，耐心等它自然结束，不要中断**。预期 BUILD SUCCESSFUL；webapp 194 用例 / app 6136 用例 / 0 failures。把 "N tests completed" 行原文抄进报告。**若失败：完整抄错误，停下，不许放宽断言。**

## 第 5 步：反向验证（撤回修复必须真红）

工作树改动未提交，`git show dcbe158:<path>` 即修复前旧版。
1. 备份：`Copy-Item webapp\src\main\java\com\xiufeigo\encodeshell\web\WebHostActivity.kt $env:TEMP\` —— 正确路径是 `webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt`，备份到 `$env:TEMP\WebHostActivity.kt.new`。
2. 旧版覆盖（cmd 重定向保字节）：`cmd /c "git show dcbe158:webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt > webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt"`。
3. 走锁协议跑 `.\gradlew.bat --console=plain :webapp:testDebugUnitTest`。**预期真红**（断言失败而非编译错）：`DirectConnectAuthBoundaryTest.bootResolvesBackendUrlWithDefault`、`TerminalReattachTest.bootUrlIsStableAcrossLaunches` ≥2 条。抄失败测试名原文。
4. `Copy-Item $env:TEMP\WebHostActivity.kt.new webapp\src\main\java\com\xiufeigo\opencodeshell\web\WebHostActivity.kt` 恢复；走锁协议复跑 `:webapp:testDebugUnitTest` 确认恢复全绿。
5. 若第 3 步没变红 ⇒ 绊线失效，停下写报告（算失败）。

## 第 6 步：构建三份产物

锁协议下前台跑：
```
.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8
```
BUILD SUCCESSFUL 后释放锁。

## 第 7 步：包内自查 + 落盘 research/deliver/

1. `New-Item -ItemType Directory -Force research\deliver`
2. 复制规范命名：`webapp-debug.apk → research\deliver\opencode-shell-web.apk`；`webapp-release.apk → research\deliver\opencode-shell-web-release-nor8.apk`；`webapp-releaseR8.apk → research\deliver\opencode-shell-web-release-r8.apk`。
3. 三份各做（PowerShell，方法见下）：
   - `assets/www/ui/*` 条目数（三份都应 = 1613）
   - `assets/www/oa/*` 条目数
   - 包内 `assets/www/oc-host.js` 的 md5 == 源文件 md5（`Get-FileHash webapp\src\main\assets\www\oc-host.js -Algorithm MD5`，预期 `b5057679b1a419af15d81ef72ff0fd9e`）
   ```powershell
   Add-Type -Assembly System.IO.Compression.FileSystem
   $zip=[IO.Compression.ZipFile]::OpenRead('<apk路径>')
   ($zip.Entries | Where-Object FullName -like 'assets/www/ui/*').Count
   $e=$zip.GetEntry('assets/www/oc-host.js'); $ms=New-Object IO.MemoryStream; $e.Open().CopyTo($ms)
   (Get-FileHash -InputStream $ms -Algorithm MD5).Hash
   $zip.Dispose()
   ```
4. `Get-FileHash -Algorithm MD5` + 体积 三份，抄进报告。

## 第 8 步：报告收尾

在 `research/web/UX12_PLAN_A_BUILD.md` 末尾追加"续（第 4–8 步）"节，含：单测结果表 + tests completed 原文、反向验证红的测试名 + 恢复后全绿原文、三份产物 体积/md5/ui 条目数/oa 条目数/oc-host.js 自查结果、新旧体积对比表（旧：5,437,979 / 3,549,507 / 1,268,829）、`git status --porcelain -- app webapp`（app/** 必须 0 条目）、锁已释放证据、未起停后端声明。
**已知瑕疵（不用修，报告里说明即可）**：第 3.3 节有两处乱码（`支���密码字段`、`\untime/server`）是上一轮报告**文本**转写问题，源文件字节正确（hash MATCH 已证）。

## 🔴 红线

1. 不 git commit。2. 不改 `app/**`、不改 `www/` 原有 5 个注入件。3. 不起停 4095 后端、不动 adb/模拟器。4. 除已述覆盖/恢复操作外不改任何源文件字节。5. 失败停下写报告。

## 汇报（最后一行）

状态 + 单测用例数/失败数 + 反向验证红了哪几条 + 三份产物 体积/md5 + ui 条目数（三份）+ oc-host.js 自查 MATCH 与否 + 锁已释放 + 报告路径。
