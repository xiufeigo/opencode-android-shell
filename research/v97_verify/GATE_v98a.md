# GATE v98a — 全量闸门报告

- 日期：2026-10-02
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 结论：**BUILD SUCCESSFUL**（gradle 退出码 0）

---

## 1. 命令原文

工作目录＝仓库根，前台同步执行，输出经 `Tee-Object` 落盘到 `research\v97_verify\gate_v98a.log`：

```powershell
$ErrorActionPreference = 'Continue'
Set-Location 'C:\sensorsdata\main\program\opencode-android-shell'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
if (-not (Test-Path 'research\v97_verify')) { New-Item -ItemType Directory -Path 'research\v97_verify' -Force | Out-Null }
.\gradlew.bat --console=plain cleanTestDebugUnitTest testDebugUnitTest assembleDebug 2>&1 | Tee-Object -FilePath research\v97_verify\gate_v98a.log
Write-Output "=== GRADLE_EXIT_CODE=$LASTEXITCODE ==="
```

gradle 退出码：`0`

---

## 2. gradle 结尾 10 行（`gate_v98a.log` tail）

```text
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 1m 52s
108 actionable tasks: 5 executed, 103 up-to-date
```

---

## 3. 单元测试统计（`app\build\test-results\testDebugUnitTest\TEST-*.xml`）

逐文件读取 `<testsuite>` 的 tests / failures / errors / skipped 并求和，suites = XML 文件数。

| 指标 | 本轮 | 上一轮基线 | 本轮预期 | 结果 |
|---|---|---|---|---|
| suites | **620** | 617 | ≥620 | ✅ 达标 |
| tests | **5595** | 5564 | ≥5595 | ✅ 达标 |
| failures | **0** | 0 | 0 | ✅ |
| errors | **0** | 0 | 0 | ✅ |
| skipped | **0** | 0 | 0 | ✅ |

无失败任务，无需报错原文记录。

---

## 4. APK 产物

路径：`app\build\outputs\apk\debug\app-debug.apk`

| 项 | 值 |
|---|---|
| bytes | 41422492 |
| LastWriteTime | 2026-10-02 19:01:43 |
| MD5 | 787EA54FA91ABF9EBB6BF59D61208856 |

---

## 5. 说明

- 本轮为**纯闸门执行**，未修改任何源码、测试或构建文件。
- 本轮写入文件仅两个：`research\v97_verify\gate_v98a.log`（日志）、`research\v97_verify\GATE_v98a.md`（本报告）。
- 未装任何软件、未触碰 adb / 模拟器 / `_upstream/`，未执行上述三个 gradle 任务以外的其它任务。
