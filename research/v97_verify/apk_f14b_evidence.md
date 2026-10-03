# APK 级 F14b 取证（Lead，2026-10-02 18:0x）

对象：`app/build/outputs/apk/debug/app-debug.apk`
- bytes = 41,422,492（旧包 36,902,739 → bcprov 净增 ~4.5MB）
- mtime = 2026-10-02 18:01:07
- md5 = `b1c177f0da5d438dc66c9f7e7cfed2cd`
- 闸门：`:app:cleanTestDebugUnitTest` + `:app:testDebugUnitTest` + `:app:assembleDebug` = **BUILD SUCCESSFUL**（日志 `research/v97_verify/gate_v97c.log`）
- 单测：**615 suites / 5539 tests / 0 failures / 0 errors / 0 skipped**（`app/build/test-results/testDebugUnitTest/`，18:01:06）

## dex 字符串检索（把 20 个 `classes*.dex` 按 ISO-8859-1 解串后 `Contains`）

| 检索串 | 命中 dex |
|---|---|
| `org/bouncycastle/crypto/signers/Ed25519Signer` | **classes19.dex** ✅ |
| `com/jcraft/jsch/bc/SignatureEd25519` | **classes19.dex** ✅ |
| `org/bouncycastle/jcajce/provider/asymmetric/edec` | **classes19.dex** ✅ |
| `routeEd25519ThroughBouncyCastle` | classes15.dex ✅ |
| `com/jcraft/jsch/jce/SignatureEd25519`（基线路径） | classes19.dex |

结论：F14b 需要的**两条 BC 路径**（`bc.SignatureEd25519` 签名器 + `Ed25519Signer` 底层）都真的进了装机包；
`routeEd25519ThroughBouncyCastle` 也编进产物。

## 源码调用链

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt`
- `:83` `routeEd25519ThroughBouncyCastle()`（在 `connect()` 里调用）
- `:374` `fun routeEd25519ThroughBouncyCastle()`
- `:377` `JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")`
- `:378` `JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")`

## 未闭环（由真机测试员闭环）
静态证据只证明「类在包里、路由被装上」。**真实握手是否走 publickey** 由 5554 真机终验断言
（`Accepted publickey` vs 回落 `keyboard-interactive`），见 `research/v97_verify/5554/REPORT_v97b.md`。
