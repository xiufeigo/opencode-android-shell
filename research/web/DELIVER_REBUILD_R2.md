# DELIVER_REBUILD_R2 �?交付物重�?+ 设备复验

> **轮次**：R2（交付重建）�?*动机**：提�?`f989907` 修了**通知点击回会�?*（`webapp/src/**` 宿主缺陷），
> �?`research/deliver/` 下的两份 Web �?APK **都早于该修复** �?交付物不含修复、与当前代码不一致�?> **本轮目的**：重建两份产�?�?落盘 �?**在设备上证明**（不采信"应该能跑"）�?>
> - 执行 agent：mavis（R2）　开始：2026-10-05 01:29　硬期限：2026-10-07 之前
> - 仓库：`C:\sensorsdata\main\program\opencode-android-shell`，`HEAD = f989907`
> - 铁律遵守�?*不采�?应该能跑"**�?*测不到就写测不到 + 原因**�?*不许拿旧 APK 的旧数顶替新 APK 的数**�?
---

## 0. 结论速览（先看这里）

| �?| 状�?| 说明 |
|---|---|---|
| `:webapp:assembleRelease` 重建 | �?真重�?| 47 actionable tasks: 14 executed |
| `:webapp:assembleDebug` 重建 | �?真重建（**需强制**�?| 首次跑是 **UP-TO-DATE 假绿**，已�?`--rerun-tasks` 强制重跑�?5 tasks: 35 executed |
| 两份新产物落 `research/deliver/` | �?| 体积/md5 �?§2 |
| 签名有效 | �?| 两份�?`Verifies`（v2 scheme�?|
| 产物确实�?`f989907` 修复 | �?| APK �?`assets/www/oc-host.js` 与源�?**md5 逐字节相�?* |
| 官方 UI 渲染 | �?| �?§4 |
| 首屏 | �?| �?§5（用 UI 可用标记�?*�?*最后一�?`_assets` GET�?|
| **N2 修复设备复验（release 包）** | ⚠️ **测不�?+ 原因** | �?§6 —�?release 构建**编译期没�?* eval 通道 |
| IME 重叠�?| �?§7 | |
| 交接文档更新 | �?| �?§8 |
| 收尾（关自己起的模拟器） | �?§9 | |

---

## 1. 重建命令�?BUILD SUCCESSFUL 原文

环境：`JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`�?**Gradle 锁纪�?*：`research/web/_r2_build.ps1` 先等 `.agent-gradle-lock` 消失 �?写锁 �?前台跑完 �?释放�?
### 1.1 第一次：`assembleDebug` 出现 **UP-TO-DATE 假绿**（重要，如实记录�?
```
--- assembleDebug ---
> Task :webapp:packageDebug UP-TO-DATE
> Task :webapp:assembleDebug UP-TO-DATE

BUILD SUCCESSFUL in 1s
35 actionable tasks: 35 up-to-date
DEBUG_EXIT=0
```

> **这不算重建�?* 交付要求�?重建两份"，�?`BUILD SUCCESSFUL` + `UP-TO-DATE` 意味着
> **gradle 认为产物与当前源码一致、根本没重新打包**。若就此收工，交付的仍是旧字节�?> �?处置：用 `--rerun-tasks` 强制重跑（�?.2）�?
同一次运行里 `assembleRelease` �?*真重�?*�?
```
--- assembleRelease (non-R8, existing config) ---
> Task :webapp:compileReleaseKotlin
> Task :webapp:mergeReleaseResources
> Task :webapp:processReleaseResources
> Task :webapp:optimizeReleaseResources UP-TO-DATE
> Task :webapp:compileReleaseJavaWithJavac UP-TO-DATE
> Task :webapp:generateReleaseLintVitalReportModel
> Task :webapp:dexBuilderRelease
> Task :webapp:mergeReleaseGlobalSynthetics UP-TO-DATE
> Task :webapp:processReleaseJavaRes UP-TO-DATE
> Task :webapp:mergeReleaseJavaResource UP-TO-DATE
> Task :webapp:mergeDexRelease
> Task :webapp:compileReleaseArtProfile
> Task :webapp:packageRelease
> Task :webapp:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :webapp:lintVitalAnalyzeRelease
> Task :webapp:lintVitalReportRelease UP-TO-DATE
> Task :webapp:lintVitalRelease
> Task :webapp:assembleRelease

BUILD SUCCESSFUL in 12s
47 actionable tasks: 14 executed, 33 up-to-date
RELEASE_EXIT=0
```

### 1.2 强制重跑 debug（真重建�?
```
> Task :webapp:packageDebug
> Task :webapp:createDebugApkListingFileRedirect
> Task :webapp:assembleDebug

BUILD SUCCESSFUL in 7s
35 actionable tasks: 35 executed
DEBUG_RERUN_EXIT=0
```

> **35 actionable tasks: 35 executed** —�?每一个都真跑了，这才叫重建�?
**release 配置沿用既有配置、非 R8**：`webapp/build.gradle.kts:51` 用开�?`providers.gradleProperty("webapp.r8").orNull == "true"`�?*本轮未传该属�?�?R8 关闭**�?`isMinifyEnabled=false` / `isShrinkResources=false`，与既有发布件口径一致�?**本轮未修�?`webapp/build.gradle.kts` 任何一�?*（构建配置无问题，不需要动）�?
---

## 2. 两份新产物：体积 / md5 / badging / 签名指纹（原始输出）

### 2.1 体积�?md5�?*新产�?*，非旧数�?
| ���� | ����·�� | ��� | md5 | sha256 |
|---|---|---|---|---|
| **release��R8��δǩ����** | `research/deliver/opencode-shell-web-release-nor8.apk` | **3,968,173 B**����3.78 MB�� | **`5aa0b63657cb599cd288ebc5597e9d21`** | `d9091db2cc236a17c096602b19a0842f943739e4d2c3ba49666b6053de48c049` |
| **debug** | `research/deliver/opencode-shell-web.apk` | **5,200,219 B**����4.96 MB�� | **`de1a476c32dc25c53d1034ea01ca1e6f`** | `0e9943c91e12027db8e1283518cee11892a75193edd3d6b6af2a3ebb47b0b1b0` |

> **F3 �֣�2026-10-05���ؽ�**���� R2 �֣�release `7b578ceec3d7e35e2fffa4da67fe2d3b` / debug `b716cbeb4c66e55fcb0f497640ae5e0c`����ȣ�
>
> | �� | R2 �� | F3 �� | �仯 |
> |---|---|---|---|
> | release | 3,961,217 B / `7b578cee��` | **3,968,173 B / `5aa0b636��`** | +6,956 B������������**�ǻ���**�� |
> | debug | 5,145,604 B / `b716cbeb��` | **5,200,219 B / `de1a476c��`** | +54,615 B������������**�ǻ���**�� |
>
> **���ְ��ں����޸�**������ `assets/www/*` �� md5 ��Դ������һ�£�DEBUG/RELEASE �������ѱȶԣ���
>
> - **N-1**��ɾ���Լ�ҳ����**�ƹ�����ȫ���ſ�**������Ͷ�ݿ� ���� `assets/www/index.html` ����ֱ�� `oc.postMessage`������ `window.Notification` ��Ƭ����������� SPA ��**ͬһ��·��**��ͬ���� `web.ts:24-29` Ȩ��/�����ſء�`coordinator.ts:73-93` eventID ������ȥ�ء�`notification.tsx:240`���ûỰ�����д� tab ��֪ͨ��Լ����
> - **N-2**������ `android.permission.CAMERA` �� `<uses-feature android:name="android.hardware.camera" android:required="false" />`��`aapt2 dump` ��ȷ�Ͻ����������� `WebChromeClient.onPermissionRequest`��**ֻ�Կ�����Դ**���ػ����� origin / ���õĺ�� origin������ `grant(RESOURCE_VIDEO_CAPTURE)`������һ�� `deny`���豸ʵ�����õ� live �������������档
>
> **ǩ��ָ��δ��**������ͬһ�� debug keystore����
> `Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a`
> `Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd`
>
> ��� `research/web/FIX_F3.md`��
### 2.2 `aapt2 dump badging` �?`package:` �?
**release**（`opencode-shell-web-release-nor8.apk`）：
```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
application-label:'OpenCode Web'
launchable-activity: name='com.xiufeigo.opencodeshell.web.WebHostActivity'  label='' icon=''
```

**debug**（`opencode-shell-web.apk`）：
```
package: name='com.xiufeigo.opencodeshell.web.debug' versionCode='1' versionName='0.1.0-webhost' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
application-label:'OpenCode Web'
```

> 两份 `applicationId` **相同**（`build.gradle.kts:55` �?release 也有 `applicationIdSuffix = ".debug"`�?> �?同一台设备上二�?*互相覆盖**，本轮设备验证靠"装哪份测哪份"切换�?
### 2.3 `apksigner verify --print-certs --verbose` —�?**签名有效**

**release**�?```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
Signer #1 public key SHA-256 digest: 64cc7c4dcbadbd2bda19d3b80edc085ac18f829aaa1b18fb9095e1de021e8af0
Signer #1 public key SHA-1 digest: daad64a253a93206c4a18cbd62a561612ab5200c
Signer #1 public key MD5 digest: 2362f8eeb5360c10c38ba099ac924781
```

**debug**�?*逐字节相�?*（同一�?`sharedDebug` keystore，`build.gradle.kts:54`）：
```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: e86b70066546bd0bdc2e6cb4d970704bc48b1a244af59c664564f030cca0510a
Signer #1 certificate SHA-1 digest: 426b682d492e08b8b5cff4a57bac2bef4070f18d
Signer #1 certificate MD5 digest: a8a01dcb84f4932ae6a181cf2524afbd
```

> �?**两份�?`Verifies`**，v2 scheme 有效，签名者证书指纹一�?�?**覆盖安装链未�?*，与既有发布件同钥匙�?
### 2.4 产物**确实**�?`f989907` 修复（逐字节证明）

`f989907` 改动的文件（`git show --stat`）：
```
webapp/src/main/assets/www/oc-host.js            |  24 +
.../opencodeshell/web/host/NotifierHost.kt        |  70 ++
.../opencodeshell/web/host/WebHostBridge.kt      |  33 +-
```

�?APK 里的注入层取出来，和工作区源码对 md5�?
```
source oc-host.js md5 = bf54276929d5f044f92ab08670b2dbbe  (bytes=13804)
DEBUG   apk oc-host.js md5 = bf54276929d5f044f92ab08670b2dbbe (bytes=13804)  MATCHES_SOURCE=True
RELEASE apk oc-host.js md5 = bf54276929d5f044f92ab08670b2dbbe (bytes=13804)  MATCHES_SOURCE=True
```

修复点原文（`f989907` �?`oc-host.js` 的新增）�?```js
+    __openSessionByRoute: function (route, sessionId) {
+      try {
+        window.__lastOpenSessionRoute = route;
+        window.__lastOpenSessionId = sessionId;
```
> 这是本仓自己留的自证钩子（`__lastOpenSessionRoute` / `__lastOpenSessionId`），§6 用它做回读断言�?
> ⚠️ **一处自我更�?*：本轮第一次探针用 `notificationclick|routeToSession|...` 这类**自造关键字**
> �?grep 资产，打�?`fix-marker hits: 0`，一度像"修复没进�?�?*该结论是错的**—�?> 关键字根本不是代码里的真实标识符�?*改用与源码逐字节比 md5** 后确认两份都含修复�?> （记录在此，避免后来人踩同一个坑。）

---

*(§3 起：设备复验 —�?见下)*

---

## 3. 设备环境（自建，规矩逐条对齐�?
| �?| �?| 依据 |
|---|---|---|
| AVD | **`OC_Deliver_R2`**（本轮新建） | 克隆自已验证组合�?`OC_FF_F4`；属 `OC_*` �?允许自建/自关 |
| serial | **`emulator-5900`** | **端口 5900（≥5900 偶数�?*；起之前查过 `Get-NetTCPConnection -LocalPort 5900` = **FREE**，无冲突 |
| 镜像 | `system-images\android-35\google_apis_playstore_tablet\x86_64` + `hw.device.name = pixel_tablet` | **只用已装镜像**，未下载 |
| 几何 | `Override size: 1080x2400` / `Override density: 420`（物理面�?`2560x1600` / `320`�?| 派单要求 1080×2400@420 |
| WebView | `G1 webview version=124.0.6367.219 major=124 min=105 tooOld=false` | 与要求一�?|
| 后端 | **未起�?*。`adb reverse tcp:4095 tcp:4095` �?`host-16 tcp:4095 tcp:4095` | 只做 `adb reverse`；`4095` �?Lead 起好（收尾时仍在 LISTEN�?|
| �?| 两次构建都等�?�?写锁 �?前台跑完 �?释放 | 收尾复核 `.agent-gradle-lock exists = False` |

**启动开销（实测）**：`BOOTED poll=3` �?�?**21 s**（派单预�?~71 s）�?
⚠️ **本轮设备不稳定，如实记录**：这�?AVD **静默退�?3 �?*（第二次 N2 前、第三次 N2 前、IME 期间），
`adb devices` 直接掉线、脚本卡在已失联�?adb 调用上。模式与 `HOUSEKEEPING.md` �?F4 轮记录的
"E7 §1.2：几何高度超出物理面板时静默退�?一致（本轮几何 2400 > 物理高度 1600）�?**本轮不代其它轮次下因果结�?*，只记录现象与规避手段：**串行跑、一次只开一�?adb 客户�?*
（早期两次挂死正是两个后台任务同时抢 adb 导致）�?
---

## 4. 前置身份检�?�?确认�?*官方 UI**

**通道说明（重要）**：本�?*没有**开 `WebView.setWebContentsDebuggingEnabled`（全 `webapp/src` 无此调用），
所�?**CDP（devtools socket）不可用**——`/proc/net/unix` �?`webview_devtools` / `chrome_devtools` **无匹�?*�?本轮用的�?*本仓自带的官方验收通道** `am start --es oc_debug_eval`（`WebHostActivity.kt:67-74`），
它走 `evaluateJavascript`、结果落 logcat �?`OcWebHost/Eval`—�?*�?`research/perf/**`、`n2-verify.ps1` 同一套口�?*�?
口令前置：装完首启即直连�?*未弹配置�?*；宿主日志自�?```
OcWebHost: home=http://127.0.0.1:43893/ backend=http://127.0.0.1:4095 passwordPresent=true
```
�?`passwordPresent=true`�?*不存�?拿配置框冒充首屏"的风�?*�?
**�?title / DOM / 可见文本（原始输出）**�?```
OcWebHost/Eval: eval=JSON.stringify({title:document.title,ready:document.readyState,
  href:location.href,rootKids:(document.getElementById("root")||{children:[]}).children.length,
  txt:(document.body.innerText||"").replace(/\s+/g," ").trim().slice(0,300)})
 -> "{\"title\":\"OpenCode\",\"ready\":\"complete\",\"href\":\"http://127.0.0.1:44977/\",
     \"rootKids\":2,\"txt\":\"Home 0 All projects Today O Counting from 1 to 4000, one number per line
     opencode-android-shell Yesterday D Friendly greeting DSH 临时会话 Older S android-e2e System32 C
     DRAFT__OK chenjieyan_test S Count from 1 to 999, one number per line System32 D New session
     DSH 临时会话 D Android 启动时手动选择 server 连接 DSH-R\"}"
```

判据�?*官方 UI 成立**）：
1. `title = "OpenCode"` —�?官方 SPA 自己的标题（回退页不是这个）�?2. `readyState = "complete"`�?3. `root` �?**2 �?*子节点（真实 SPA 已挂载，非空壳）�?4. 可见文本�?*真实业务数据**：项目名 `opencode-android-shell`、会话标�?   `Counting from 1 to 4000, one number per line` / `Count from 1 to 999, one number per line`�?   分组 `Today / Yesterday / Older`、`All projects`�?   �?**这是官方界面本身，不是本仓写的回退/占位页�?*

> 补充（release 包侧）：第一次启动装的就�?**release 签名�?*，它同样拉起了官�?SPA—�?> 宿主日志�?359 �?`GET http://127.0.0.1:4095/_assets/...`（代理到本地后端�?
> `OcWebHost: web[LOG] OcPerfJS res #9/#27/#33 asset ...`�?> **release 包拿不到 `title`/DOM 文本**，原因见 §6.1（一句话：`BuildConfig.DEBUG=false` �?eval 通道编译期不存在）�?
---

## 5. 首屏（方法与 `research/perf/**` 一致）

**方法（刻意不�?最后一�?`_assets` GET"当首屏）**：`STARTUP_AND_FRAMETIME.md` 的做法是
**�?UI 可用"的宿主标�?*（`OcPerf` �?`firstContent` / `onPageFinished`，页面侧 `OcPerfJS`），
并明确指�?`_assets` 会在 UI 已可用之�?*继续**在后台刷完（`upstreamMs=1~4`、每�?`totalMs�?4 ms`）�?本轮照此口径�?
**冷启动实测（debug 包，force-stop 后重启，`t` = 进程内相对时刻）**�?```
A.onCreate.enter            t=597ms
A.onCreate.superDone        t=633ms
B.helpersReady              t=637ms
C.webviewCreated+setContentView t=842ms
D.settingsConfigured        t=843ms
E2.perfProbeRegistered      t=858ms
E.facadeInstalled           t=859ms
F.insetsReady               t=884ms
H.launch.enter              t=888ms  backend=http://127.0.0.1:4095 mode=gzip
I.proxyListening            t=1005ms port=40669
J.g1.preflightOk            t=1005ms major=124 unknown=false
J.loadUrlIssued             t=1020ms url=http://127.0.0.1:40669/
G.onCreate.exit             t=1020ms
Z.firstDraw                 t=1124ms     �?宿主首帧
K.onPageStarted             t=1356ms
M.g1.firstContent           t=1945ms kind=raf at=null   �?页面首个内容（≈FCP�?L.onPageFinished            t=2222ms
L.onPageFinished            t=3486ms
```

| 读数 | �?|
|---|---|
| 宿主首帧 `Z.firstDraw` | **1,124 ms** |
| 页面首内�?`M.g1.firstContent`（≈FCP�?| **1,945 ms** |
| `onPageFinished` | **2,222 / 3,486 ms** |
| 同期 `P.fwd` 资源 GET �?| **359**�?*明确不作为首�?*，与 `STARTUP_AND_FRAMETIME.md` 同口径） |

> �?P2 那轮（FCP 2130 ms / DOM 2572 ms / 会话列表 3645 ms�?*同量�?*�?> 本轮 1,945 ms 甚至略快�?*不宣�?更快"**——本轮只跑了一次冷启动、未做多轮取中位数�?
---

## 6. N2 修复的设备复验（**本轮核心**�?
### 6.1 ⚠️ 先说清楚�?*release 包上测不到，原因是编译期�?*

派单要求"�?*签名 release �?*"�?�?`n2-verify.ps1` 的思路"复验�?*本轮没能做到�?release 包上完成这一�?*，原因明确：

`WebHostActivity.kt:683-685`
```kotlin
private fun consumeDebugEval(intent: Intent?) {
    if (!BuildConfig.DEBUG) return          // �?release 构建在此直接返回
    val js = intent?.getStringExtra(EXTRA_DEBUG_EVAL) ?: return
```
- `n2-verify.ps1` 的两个动�?*�?*依赖这条通道�?  **�?发通知**要注�?`window.__ocAndroid.postMessage(...)`�?  **�?回读断言**要注入读 `location` / `__lastOpenSessionRoute`�?- **release �?`BuildConfig.DEBUG == false` �?注入通道不存�?*（这是有意的安全设计，注释里写明"release 构建直接忽略"）�?- 也没有替代通道：未开 `setWebContentsDebuggingEnabled` �?**�?CDP/devtools socket**�?  `adb shell cmd notification post` 发的通知**不带本应用的 `PendingIntent`**，点了也不会�?  `consumeNotificationIntent` �?**测不到本修复**�?
�?**所以：�?release 包上无法产生 N2 的设备证据�?*
�?测不到就写测不到 + 原因"的硬规矩�?*明确记为"测不�?**，并改在 **debug �?*上完成真点复验�?
**为什�?debug 包的结果�?release 包成立（不是�?A 冒充 B�?*�?两份 APK �?*注入层字节完全相�?*（�?.4 已用 md5 逐字节证明）�?```
source  oc-host.js md5 = bf54276929d5f044f92ab08670b2dbbe (13804 B)
debug   oc-host.js md5 = bf54276929d5f044f92ab08670b2dbbe (13804 B)  MATCHES_SOURCE=True
release oc-host.js md5 = bf54276929d5f044f92ab08670b2dbbe (13804 B)  MATCHES_SOURCE=True
```
�?`f989907` �?*页面侧改动全在这一个文件里**（`__openSessionByRoute`，`oc-host.js`）�?**仍然要如实说�?*：宿主侧 `NotifierHost.kt` / `WebHostBridge.kt` 的改动是�?**dex** 里，
本轮**没有**�?release �?dex �?debug �?dex 逐条比对"来补齐这一�?�?**这一环未闭环**（见 §10）�?
### 6.2 注入 vs 真实点击 —�?**逐步明确标注**

| 步骤 | 性质 | 说明 |
|---|---|---|
| �?`window.__ocAndroid.postMessage("N2-R2", �?` | **注入（不是用户动作）** | 通过 eval 通道让页面替我们发通知�?*它只负责"把通知摆出�?**�?|
| �?`cmd statusbar expand-notifications` | 注入（系统命令） | 只展开通知栏�?|
| �?`uiautomator dump` + 正则解析 bounds | 只读 | **当场**（点击前 5 s 内）解析，不复用旧坐标�?|
| �?**`input tap 588 728`** | �?**真实点击** | `adb shell input` = 系统合成触摸事件，走正常输入管线命中 SystemUI 卡片�?*这一步是用户等价动作**�?|
| �?回读 `location` | 注入（只读） | 读回断言�?*不参�?*跳转�?|

### 6.3 原始输出

**�?注入发通知**�?```
OcWebHost/Eval: eval=typeof window.__ocAndroid.postMessage -> "function"
OcWebHost/Eval: eval=window.__ocAndroid.postMessage("N2-R2","R2 recheck",
                 "ses_efc722bf9ffeWaRdoFTwbmYj1Y") -> "0"
```
**先确认它真到了系统通知管理�?*（避�?没发出去却以为测�?）：
```
SYSTEM NOTIFICATION HAS 'N2-R2' = True
  ND:  android.title=String (N2-R2)
```

**�?展开 + 当场解析 bounds**�?```
shade texts: Internet, Networks available | Internet | Bluetooth, On | Bluetooth |
             Auto-rotate, On | Auto-rotate | Device controls | N2-R2 | R2 recheck | 5:45 | ...
NODE RAW: <node index="0" text="N2-R2" ... bounds="[179,699][996,756]" ... />
PARSED bounds=[179,699][996,756] -> tap=588 728
```

**�?真实点击**�?```
>>> REAL TAP: input tap 588 728 (system touch) <<<
REAL_TAP_PERFORMED = True
```

**�?回读（点击前 `location.pathname = "/"`�?*�?```
BEFORE: {"loc":"/","href":"http://127.0.0.1:46851/"}
FOCUS : mCurrentFocus=Window{2e2b508 u0 com.xiufeigo.opencodeshell.web.debug/
                             com.xiufeigo.opencodeshell.web.WebHostActivity}
TOP   : topResumedActivity=ActivityRecord{bfa6a22 u0 com.xiufeigo.opencodeshell.web.debug/
                             com.xiufeigo.opencodeshell.web.WebHostActivity t26}
AFTER : {"loc":"/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y",
         "href":"http://127.0.0.1:46851/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y"}
ASSERT routed-to-session = True
```

**断言**：点击前 `pathname = "/"`；点击后 `pathname = "/server/<base64url(serverKey)>/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y"`
—�?与注入通知里带�?**同一�?sessionId**。这正是 `f989907` 的修复目�?（上游唯一支持的入�?`/server/<key>/session/<id>`，`_upstream/packages/app/src/shell/routes/routes.tsx:39-40`）�?�?**通知点击确实回到了会话，复验通过�?*

**�?前台/焦点**：`mCurrentFocus` �?`topResumedActivity` 都回�?`WebHostActivity`（不是留�?SystemUI/通知栏）�?
> 宿主侧确实消费了点击（`consumeNotificationIntent` �?路由）：
> `OcPerfJS res #58 api �?/api/session/ses_fd2f8112affehAV1Hwev1z0qOp`、`/api/session/<id>/form`�?> `/api/session` 在点击后被拉取�?> ⚠️ **一处如实说�?*：这�?`api/session` 里的 id �?`ses_fd2f811…`�?*与通知里注入的
> `ses_efc722…` 不同**�?*最�?`location.pathname` 是正确的目标会话**（这才是断言对象）；
> 差异原因**本轮未查�?*（可能是该页先前已打开的会话、或 SPA 预取），**不下结论**�?>
> 另：`__lastOpenSessionRoute` / `__lastOpenSessionId` 两个自证钩子回读�?`null`�?> 原因应是**路由后整页重载，`window` 上的临时变量被清�?*；断言因此**只依�?`location`**，未依赖钩子�?
---

## 7. IME 重叠�?—�?�?**测不�?*（不�?0�?
派单要求�?读前断言 `mInputShown=true`，抄 `type=ime frame=` 原始�?�?应为 0"�?
**实测（原始行�?*�?```
ASSERT mInputShown = false
RAW type=ime frame= LINE:            �?空：该行�?dumpsys 里根本不存在
```
composer 探针（真�?`input tap` 点了两处 + 两次上滑之后）：
```
input tap 540 1900 �?mInputShown = false
input tap 540 2150 �?mInputShown = false
上滑两次 + �?8s �?querySelectorAll("input,textarea,[contenteditable],[role=textbox],[data-placeholder]") -> "[]"
                    document.body.innerHTML.indexOf("contenteditable") > -1 -> "true"
                    document.activeElement -> "BODY" ; window.innerHeight = 915
```

**为什么测不到（原因）**�?1. **`mInputShown = false`** �?IME 压根没被抬起�?�?**没有 `type=ime frame=` 行可�?*�?2. composer **�?shadow root �?*：`innerHTML` �?*�?* `contenteditable` 字样�?   但从顶层文档 `querySelectorAll` **一个都选不�?*（返�?`[]`），`activeElement` 停在 `BODY`
   �?本轮的注入式探针**够不到它**，拿不到 composer �?`getBoundingClientRect().bottom`�?
�?**本轮不给�?IME 重叠�?= 0"�?* 按铁律，**测不到就写测不到 + 原因**�?�?0"写上去就是造假�?*该项未闭�?*（�?0）�?（F4 轮在 442 dp / 708 dp 上量�?0 �?*那一�?*的读数，**不能拿来顶替本轮**。）

---

## 8. 交接文档改动�?*只改交付物小�?*�?
文件：`HANDOFF_WEB_SHELL.md`　`git diff --stat`�?*1 file changed, 8 insertions(+), 4 deletions(-)**
完整 diff 另存：`research/web/_r2_handoff.diff`�?
改动只落�?*两处"产物清单�?**（同一批交付数字的正文�?+ §1.1 表）�?1. **§0「当前可交付」表**：两�?APK �?体积/md5 换成新值，标注 **已含通知点击修复（`f989907`�?*�?   顶部加一行说�?旧数已作�?+ 指向本报�?�?2. **§1.1 产物�?*：同样换成新值并�?**R2 重建 / 已含 `f989907`**�?
```diff
+| **`opencode-shell-web-release-nor8.apk`** | **3,961,217 B（≈3.78 MB�?* | `7b578ceec3d7e35e2fffa4da67fe2d3b` | �?*已含通知点击修复（`f989907`�?* |
-| **`opencode-shell-web-release-nor8.apk`** | **3,948,209 B（≈3.95 MB�?* | `9ab8ee300027f9847c2b220bb26f2b98` | …（旧）
+| `opencode-shell-web.apk`（debug�?| **5,145,604 B（≈4.91 MB�?* | `b716cbeb4c66e55fcb0f497640ae5e0c` | �?*已含通知点击修复（`f989907`�?* |
-| `opencode-shell-web.apk`（debug�?| 8,636,901 B | `8dcffc572ad469f472f61594f12ea990` | …（旧）
```
**未改�?*：�? 决策依据、�? 延迟结论、R8 风险清单、以及本文档其它任何小节�?
---

## 9. 收尾复核输出（原始）

`wm` 复原（我们改�?`size`/`density`）：
```
wm size reset / wm density reset
after reset: Physical size: 2560x1600 | Physical density: 320      �?已无 Override �?```
只关**自己起的**那台（先�?AVD 名确认归属，再按 serial 关）�?```
--- our OC_* emulator processes (AVD-name filtered, per HOUSEKEEPING 2) ---
pid=51956 avd=OC_Deliver_R2 port=5900 MINE=True
--- adb -s emulator-5900 emu kill (ONLY our serial) ---
  OK: killing emulator, bye bye
```
复核�?```
(a) emulator/qemu process count = 0  (PASS)
    OC_Deliver_R2 residual count = 0  (PASS if 0)
(b) port 5900 LISTEN = 0  (PASS)
    (TimeWait remnants: 1 sockets, OwningProcess values: 0)
(c) adb devices �?List of devices attached        �?未执�?adb kill-server
(d) .agent-gradle-lock exists = False
```
- **未执�?* `adb kill-server`�?*未用** `-a` / 通配符；只按 **serial `emulator-5900`** �?  **已确认属自己�?AVD �?`OC_Deliver_R2`** 关�?- **故意保留：无�?*
- 全程**未起停后�?*（`4095` �?Lead 提供，只 `adb reverse`）；收尾�?`4095` 仍在 LISTEN（未动）�?- **碰过�?serial：只�?`emulator-5900`**（本轮自�?`OC_Deliver_R2`）�?  全程**没有**对任�?`MCRemote_*` / `DSH_*` / `T7x_*` 或来源不明实例发过任何命令�?  （本轮开始时 `adb devices` 为空、emulator/qemu 进程数为 0。）

---

## 10. 未闭环（如实列出，不掩饰�?
1. **N2 复验是在 debug 包上完成的，不是�?release 包上**�?   release 编译期没有注入通道（�?.1），**没有**产生 release 包的真点证据�?2. **release �?debug �?dex 未逐条比对**。页面侧（`oc-host.js`）已�?md5 证明字节相同�?   但宿主侧 `NotifierHost.kt` / `WebHostBridge.kt` 的改动在 dex 里，**本轮未验�?dex 等价**�?3. **IME 重叠量未测到**（�?）：`mInputShown=false` + composer �?shadow root �?�?无原始行可抄、无 0 可报�?4. **首屏只跑�?1 次冷启动**，没有多轮取中位�?分位�?�?不给"更快/更慢"的结论�?5. **release 包的官方 UI 身份未取�?`title`/DOM 文本**（同样因�?eval/CDP 通道）；
   release �?官方 UI 渲染"目前�?`359` �?`_assets` 代理 GET + `OcPerfJS res` 日志 + 前台 `WebHostActivity` 支撑�?   **强度弱于 debug 侧的 `title="OpenCode"`**�?6. **debug 产物体积 8.64 MB �?5.15 MB 的原因未查清**（�?.1），本轮只声明新数字来自真重建�?7. **模拟�?3 次静默退�?*（�?），未定位根因；只记录现象与规避手段�?8. **`.agent-gradle-lock` 的创�?删除由脚本完�?*，两次都确认释放（收尾复�?`(d)`）�?9. **R8 产物仍未产出**（本轮沿用既有非 R8 配置，未尝试 R8）⇒ 该风险项与本轮无关、维持原状�?
---

## 11. 附：本轮落盘/改动的文�?
**新增（本轮）**
- `research/web/DELIVER_REBUILD_R2.md`（本报告�?- `research/web/_r2_evidence.txt`（体�?md5/badging/apksigner 原始输出�?- `research/web/_r2_handoff.diff`（交接文�?diff�?- `research/web/_r2_identity_debug.txt`、`_r2_seed_evidence.txt`、`_r2_markers_debug.txt`（首屏标记）
- `research/web/_r2_n2c.log`（N2 全过程原始日志）、`_r2_n2_after.txt`、`_r2_n2_hostlog.txt`、`_r2_n2_notifydump.txt`
- `research/web/_r2_ime*.log` / `_r2_ime_dump.txt`（IME 尝试记录�?- `research/web/_r2_cleanup.log`（收�?+ 复核�?- `research/web/_r2_*.ps1`（可复跑脚本�?
**覆盖（交付物 + 交接文档�?*
- `research/deliver/opencode-shell-web-release-nor8.apk`�?,961,217 B / `7b578cee…`�?- `research/deliver/opencode-shell-web.apk`�?,145,604 B / `b716cbeb…`�?- `HANDOFF_WEB_SHELL.md`�?*�?*交付物小节，8+/4-�?
**未触�?*：`app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`�?**`webapp/build.gradle.kts`（一行未改）**、`research/deliver/` 里的另两份原生壳 APK
（`opencode-shell-debug.apk`、`opencode-shell-release-debugsigned.apk` —�?属原生壳，非本轮范围）�?
