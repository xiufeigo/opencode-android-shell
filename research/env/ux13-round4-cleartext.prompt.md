# 轮次：UX13-P4 —— 产品负责人拍板：全局明文放行（选项 C，与官方桌面端同形态）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
**每步一条命令 + 立即读输出；结论当场写进 `research/web/UX13_P4_CLEARTEXT_GLOBAL.md`。**

## 背景（产品决策，已拍板，直接执行）

产品负责人在知情代价后明确选择**选项 C**：全局 `usesCleartextTraffic=true`，使 App 能像官方桌面端一样连接**任意 http 服务器**（公网 http 的 Basic 口令明文传输与桌面端一致，此代价已由产品负责人 2026-10-06 接受并拍板）。
当前状态：上一修复轮刚把相机可信 origin 修复并重建过一轮产物（若其已落盘，本轮产物会覆盖它——正常）。**本轮只改下面列出的文件，其余一律不动。**

## 改动清单（恰好两处 + 一个新绊线测试）

### 1. `webapp/src/main/AndroidManifest.xml`
- `android:usesCleartextTraffic="false"` → `android:usesCleartextTraffic="true"`。
- 把第 35-40 行那段注释改写为决策记录：2026-10-06 产品负责人拍板选项 C（与官方桌面端同形态，任意 http 服务器可直连）；代价 = 公网 http 目标全部明文（含 Basic 口令），与桌面端浏览器行为一致；这是**刻意的安全姿态选择**，不是疏漏。

### 2. `webapp/src/main/res/xml/network_security_config.xml`
⚠️ Android 语义：`networkSecurityConfig` 存在时会**覆盖** manifest 的 usesCleartextTraffic——所以必须改这里才真生效。
- `<base-config cleartextTrafficPermitted="false">` → `="true"`；`trust-anchors` 保持 system 不变。
- 头部注释改写为同一决策记录（选项 C、拍板人、日期、代价、与桌面端同形态）；说明下方 domain-config 在全局放行后仅作历史参考保留。
- 其余字节不动。

### 3. 新增绊线 `webapp/src/test/java/com/xiufeigo/opencodeshell/web/CleartextPolicyTest.kt`（把拍板钉成可执行断言，防止未来被静默回退）
3 条用例：
1. `manifestEnablesCleartextGlobally`：manifest 含 `android:usesCleartextTraffic="true"`；
2. `networkSecurityConfigPermitsCleartext`：`res/xml/network_security_config.xml` 的 base-config 含 `cleartextTrafficPermitted="true"`；
3. `systemTrustAnchorsKept`：配置仍保留 `<certificates src="system" />`（没有借机放宽证书信任——明文放行不等于信任自签证书）。
读取方式参考 CameraPermissionHostTest 的 src() 向上找文件法。

### 4. 测试 + 反向验证
- 锁协议（等锁→建锁→前台跑→跑完单条顶层 `rm -- .agent-gradle-lock`）跑 `.\gradlew.bat --console=plain :webapp:testDebugUnitTest` ⇒ 全绿（预期 194+3=**197** 用例；若有其它数目变化如实记录并解释）。
- 反向验证：`cmd /c "git show bc87e72:webapp/src/main/AndroidManifest.xml > webapp/src/main/AndroidManifest.xml"`（先备份到 $env:TEMP），复跑 ⇒ 预期 `manifestEnablesCleartextGlobally` **真红**；恢复备份 ⇒ 全绿。抄原文。
- 环境：JAVA_HOME=`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`；`$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"`；gradle 编译耐心等（mcode 会话结束会回收前台子进程——长等待时用 Start-Process 脱离会话起 gradle 并轮询 test-results）。

### 5. 重建三产物 + 落盘 + 自查
`.\gradlew.bat --console=plain :webapp:assembleDebug :webapp:assembleRelease :webapp:assembleReleaseR8` ⇒ 复制三份到 `research/deliver/` 规范名（覆盖）。包内自查：三份 `assets/www/ui/*`=1613、`oa/*`=1590、包内 `oc-host.js` md5==源（8A3B3E93EBF61BEC347067D4E4074A69）。`Get-FileHash` 三份 md5/体积抄报告（与 UX13-P3 批对比）。
安装层面快速冒烟（可选，本轮不起模拟器就跳过并说明）。

## 🔴 红线
不改 Kotlin 源码（相机修复是上一轮的，别动）；不改 `www/` 注入件与 `ui/` 资源；不 git commit；不起停 4095 后端；失败停下写报告。

## 汇报（最后一行）
状态 + 新用例数（预期 197）+ 反向验证红了哪条 + 三份新产物 体积/md5 + 包内自查 + 锁已释放 + 报告路径。
