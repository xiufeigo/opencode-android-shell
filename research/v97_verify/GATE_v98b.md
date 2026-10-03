# GATE v98b — 全量闸门执行报告

- 执行时间：2026-10-02 19:36 – 19:38（中国标准时间）
- 工作目录：`C:\sensorsdata\main\program\opencode-android-shell`
- 性质：只跑闸门、只报告；未修改任何源码/测试/构建文件

## 1. 命令原文

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --console=plain cleanTestDebugUnitTest testDebugUnitTest assembleDebug 2>&1 | Tee-Object -FilePath research\v97_verify\gate_v98b.log
```

完整日志：`research\v97_verify\gate_v98b.log`

## 2. gradle 结尾 10 行

```
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 1m 49s
108 actionable tasks: 5 executed, 103 up-to-date
```

**BUILD SUCCESSFUL**，无失败任务、无报错。

## 3. 单元测试统计（app/build/test-results/testDebugUnitTest/TEST-*.xml）

| 指标 | 数值 |
|---|---|
| suites（XML 文件数） | 622 |
| tests | 5615 |
| failures | 0 |
| errors | 0 |
| skipped | 0 |

### 与基线对比（只增不减校验：PASS）

| 指标 | 上一轮 19:01 基线 | 本轮 | 差值 | 判定 |
|---|---|---|---|---|
| suites | 620 | 622 | +2 | PASS（只增） |
| tests | 5595 | 5615 | +20 | PASS（只增） |
| failures | 0 | 0 | 0 | PASS |
| errors | 0 | 0 | 0 | PASS |
| skipped | 0 | 0 | 0 | PASS |

本轮基线后落地的 4 个修复（冷启动 route、标题热区、移动抽屉、图标落盘）已体现为 suites +2 / tests +20，无回归。

## 4. APK 产物三元组（app/build/outputs/apk/debug/app-debug.apk）

| 属性 | 值 |
|---|---|
| bytes | 41422492 |
| LastWriteTime | 2026-10-02 19:38:26 |
| MD5 | 482C5D3EC3CDBE08D19886E5FD6EB58B |

## 5. 结论

**闸门全绿。** BUILD SUCCESSFUL（1m 49s，108 actionable tasks）；622 suites / 5615 tests / 0 failures / 0 errors / 0 skipped，全部不低于基线；APK 已产出且 MD5 = 482C5D3EC3CDBE08D19886E5FD6EB58B。
