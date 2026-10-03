# GATE v98d — 全量闸门结果

- **执行时间**：2026-10-02 20:22:54 → 20:24:52 (Asia/Shanghai)
- **工作目录**：`C:\sensorsdata\main\program\opencode-android-shell`
- **结果**：**BUILD SUCCESSFUL**（gradle 退出码 0）
- **性质**：只跑闸门、只报告。本轮未修改任何源码 / 测试 / 构建文件。

---

## 1. 命令原文

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --console=plain cleanTestDebugUnitTest testDebugUnitTest assembleDebug 2>&1 | Tee-Object -FilePath research\v97_verify\gate_v98d.log
```

日志落盘：`research\v97_verify\gate_v98d.log`

---

## 2. gradle 结尾 10 行（原样）

```
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 1m 54s
108 actionable tasks: 5 executed, 103 up-to-date
```

---

## 3. 单元测试统计

来源：`app\build\test-results\testDebugUnitTest\TEST-*.xml` 求和

| 指标 | 本轮 (v98d) | 上一轮 (v98c) | 变化 |
|---|---|---|---|
| suites（XML 文件数） | **627** | 627 | 持平 |
| tests | **5685** | 5685 | 持平 |
| failures | **0** | 1 | **-1（已消除）** |
| errors | **0** | 0 | 持平 |
| skipped | **0** | 0 | 持平 |

- suites / tests **只增不减** 的预期成立（本轮持平）。
- 全仓**无任何** suite 携带非零 failures / errors / skipped。
- v98c 的唯一失败 `ReviewVcsReprobeTest.theHostFetchChainWalksTheReProbeCandidates`（陈旧 pin）**已修复**，本轮通过。

### 上一轮失败项复核

`TEST-com.xiufeigo.opencodeshell.ReviewVcsReprobeTest.xml`
suite=12 tests / 0 failures / 0 errors / 0 skipped，12 个用例全绿，含目标用例：

```
theHostFetchChainWalksTheReProbeCandidates   time=0.003  failure=False
```

---

## 4. APK 三元组

路径：`app\build\outputs\apk\debug\app-debug.apk`

| 项 | 值 |
|---|---|
| bytes | **44,198,562** |
| LastWriteTime | **2026-10-02 20:24:52** |
| MD5 | **886653D4A21BD158A9AE87429C7F5261** |

---

## 5. 结论

**闸门全绿。** `:app:testDebugUnitTest` 627 suites / 5685 tests 零失败零错误零跳过，
`assembleDebug` 产出可安装 APK（44,198,562 bytes）。
陈旧 pin 修复经测试侧证据确认生效，无需进一步动作。
