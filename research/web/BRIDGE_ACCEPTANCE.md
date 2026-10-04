# BR1 原生桥真机验收报告

- **日期**：2026-10-04
- **设备**：`emulator-5636`（**全程只用这一台**，未触碰 5642 / 5650 / 5670 / 5690）
- **被测包**：`com.xiufeigo.opencodeshell.web.debug`（`webapp` 模块，v `0.1.0-webhost`）
- **后端**：`127.0.0.1:4095`（Lead 起的，**全程未起停**），每轮 `adb reverse tcp:4095 tcp:4095`
- **窗口基准**：`1366x768` / density `160`（`Override size 1366x768` / `Override density 160`）
- **API**：34
- **构建**：`gradlew :webapp:assembleDebug --offline` → 安装到 5636（覆盖安装，签名同 `sharedDebug`）

---

## 0. 测试手段说明（重要，先读这段）

官方 UI（opencode 网页版）**自己不会主动调** `postMessage` / `pickFile` / `share` / `setKeepScreenOn` 这些桥——
它们只在用户走到特定界面时触发，本轮无法从 UI 稳定走到。因此按任务授权，
**从宿主侧用 `evaluateJavascript` 注入一次调用**来完成验收。

为此我在 `webapp/**` 内加了一个**仅 DEBUG 构建生效**的注入通道（这是本轮唯一的宿主侧功能改动之一）：

- `WebHostActivity.kt` 新增 `EXTRA_DEBUG_EVAL = "oc_debug_eval"`
  + `consumeDebugEval()`：从 `onCreate` / `onNewIntent` 取 extra，
  等 1200ms 让 facade 装好后 `webView.evaluateJavascript(...)`，结果打进 logcat 的 `OcWebHost/Eval`。
- **只在 `BuildConfig.DEBUG` 下生效**，release 构建直接忽略该 extra。
- ⚠ **残留风险（如实记录）**：`WebHostActivity` 在 manifest 里是 `exported="true"`，
  所以 debug 包上**同设备任意应用**都能用一条 `am start --es oc_debug_eval "<js>"`
  往 WebView 注入任意 JS。release 包无此面。若 debug 包要长期留在设备上，建议后续给该 extra 加签名校验或 `android:permission`。
- 测试脚本：`research/web/br1-eval.ps1`（注入）、`br1-ime.ps1`（IME 原子采集）、`br1-ime-collapse.ps1`（塌缩复现）、`br1-t34.ps1`（T3/T4）。
- ⚠ **JS 里只能用双引号**：`adb shell` 远端解析会吃掉括号/引号，脚本用单引号包住整段。

**另有一处环境坑（非宿主 bug）**：这台模拟器上 `webapp` 一度以 **freeform 小窗**运行
（视口仅 303×618，窗口 frame `[617,75][920,693]`），导致 `input tap` 全部落空
（`activeElement` 停在 `BODY` / `DIV`）。必须用
`窗口 frame 原点 + input 的 getBoundingClientRect()` 换算成屏幕坐标再点。
本轮通过 `settings put global enable_freeform_support 0` + 重启 app 缓解，**收尾已恢复为 `1`**。

---

## 1. 返回键 —— **PASS**

宿主逻辑（`installBackHandler`）：`webView.canGoBack()` → `goBack()`；否则
`evaluateJavascript` 问页面 `__ocAndroid.onBack()`；**两条都不成立也不 finish**。

### 1a 有历史 → canGoBack 优先

```
注入: location.hash="#br1-back-test"
注入: JSON.stringify({canGoBack:history.length>1,len:history.length})
  -> "{\"canGoBack\":true,\"len\":2}"
命令: adb -s emulator-5636 shell input keyevent 4
返回后:
  mCurrentFocus=Window{635f5c5 u0 com.xiufeigo.opencodeshell.web.debug/
                 com.xiufeigo.opencodeshell.web.WebHostActivity}
  pidof com.xiufeigo.opencodeshell.web.debug -> 17212
```
**判定 PASS**：有历史时走 `goBack()`，**Activity 未退出**，进程仍在。

### 1b 无历史 → 交给页面 onBack，且不退出

```
注入: (function(){window.__ocAndroid.setBackHandler(function(){
        window.__ocBackFired=true;return true});return "handler set"})()
  -> "handler set"
命令: adb -s emulator-5636 shell input keyevent 4
验证: String(window.__ocBackFired)
  -> "true"
返回后:
  mCurrentFocus=Window{635f5c5 u0 com.xiufeigo.opencodeshell.web.debug/
                 com.xiufeigo.opencodeshell.web.WebHostActivity}
```
**判定 PASS**：页面 handler 确实被调用（`__ocBackFired=true`），
宿主**没有** finish 掉 Activity（前台仍是 WebHostActivity）——符合"不直接退出"的硬要求。

---

## 2. 通知 —— **PASS**（有一处未能按原样执行，见下）

### 2a 通知真出现

```
注入: JSON.stringify({ret:window.__ocAndroid.postMessage(
        "BR1-ACCEPT","BR1 通知正文验收","br1-session-001"),
        perm:window.__ocAndroid.hasNotificationPermission()})
  -> "{\"ret\":0,\"perm\":1}"        # 0=ok, 1=已授权
```
`dumpsys notification --noredact`（原文）：
```
NotificationRecord(0x01c38690: pkg=com.xiufeigo.opencodeshell.web.debug
  user=UserHandle{0} id=-89703282 tag=null importance=3
  key=0|com.xiufeigo.opencodeshell.web.debug|-89703282|null|10190:
  Notification(channel=oc_turns shortcut=null contentView=null vibrate=null
  sound=null defaults=0x0 flags=0x10 color=0x00000000 category=status vis=PRIVATE))
  uid=10190 userId=0
  opPkg=com.xiufeigo.opencodeshell.web.debug
```
标题原文（extras）：
```
android.title=String (BR1-ACCEPT)
```
`contentIntent`（点击会发的那条）：
```
contentIntent=PendingIntent{6a05b89: PendingIntentRecord{72b0792
  com.xiufeigo.opencodeshell.web.debug startActivity
  (allowlist: 68ce563:+30s0ms/0/NOTIFICATION_SERVICE/NotificationManagerService)}}
```
**判定 PASS**：通知真的进了系统，`channel=oc_turns`、`vis=PRIVATE`、标题原文正确。

### 2b 点击回到会话

> **未按原样执行的部分（如实记录）**：任务要求"点击"通知。这台模拟器上
> `cmd statusbar expand-notifications` + 两次 swipe 都**没能把通知卡片留在屏上**
> （截图只有 QS 面板 / 桌面），`uiautomator dump` 里始终搜不到 `BR1-ACCEPT` 节点，
> 因此**没能用 `input tap` 真点卡片**。
>
> 替代做法：取 `dumpsys` 里那条 `contentIntent` 的**原文**（component + 两个 extra + `SINGLE_TOP|CLEAR_TOP`），
> 用 `am start` 发**逐字相同**的 intent，等价于点击触发的 PendingIntent。

```
命令: adb -s emulator-5636 shell am start \
  -n com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity \
  --ez oc_from_notification true --es oc_session_id br1-session-001
  -> Warning: Activity not started, intent has been delivered to currently running top-most instance.
```
前台 Activity：
```
mCurrentFocus=Window{e9030ff u0 com.xiufeigo.opencodeshell.web.debug/
               com.xiufeigo.opencodeshell.web.WebHostActivity}
topResumedActivity=ActivityRecord{... WebHostActivity t70}
```
宿主日志（intent extra 原文被正确消费）：
```
I OcWebHost: opened from notification, session=br1-session-001
```
**判定 PASS（带保留）**：`singleTask` + `SINGLE_TOP|CLEAR_TOP` 生效（提示
"delivered to currently running top-most instance"，**没有叠第二个 Activity**），
extra `oc_session_id=br1-session-001` 被取出并交给页面
（`window.__ocAndroid.onOpenSession(...)`）。**保留点**：真·手指点卡片这一步没测到。

---

## 3. 文件选择（SAF） —— **PASS**

```
注入: window.__ocAndroid.pickFile("text/plain",false)
  -> "undefined"（facade 的 pickFile 无返回值，正常）
命令: adb -s emulator-5636 shell dumpsys window | grep mCurrentFocus
  mCurrentFocus=Window{4fab5bf u0 com.google.android.documentsui/
                 com.android.documentsui.picker.PickActivity}
```
**SAF 选择器确实起来了**（`com.android.documentsui.picker.PickActivity`）。

取消后不崩：
```
命令: adb -s emulator-5636 shell input keyevent 4
返回后 mCurrentFocus=Window{635f5c5 u0 .../WebHostActivity}
pidof com.xiufeigo.opencodeshell.web.debug -> 17212
logcat: 无 AndroidRuntime / FATAL / OcWebHost/FileChooser 异常
```
**判定 PASS**：SAF 出现；取消后正常回到宿主，进程存活，无崩溃。

> **未测到**：**选中文件后的成功路径**（`describe()` 元数据 + `readPickedFile()`
> 的 base64 inline）本轮没走——取消路径已验证。这条桥的"选到文件"分支留待下一轮。

---

## 4. 分享 —— **PASS**

```
注入: window.__ocAndroid.share("BR1 share body","BR1 title")
  -> "true"（startActivity 未抛异常）
命令: adb -s emulator-5636 shell dumpsys window | grep mCurrentFocus
  mCurrentFocus=Window{1b783e7 u0 com.android.intentresolver/
                 com.android.intentresolver.ChooserActivity
关闭: input keyevent 4
返回后 mCurrentFocus=Window{635f5c5 u0 .../WebHostActivity
pidof -> 17212（宿主存活）
```
**判定 PASS**：chooser（`ChooserActivity`）确实出现；关闭后正常回宿主，不崩。

---

## 5. 凭据 —— **PASS**（落盘非明文）+ **修掉 1 个高危宿主 bug**

### 5a 落盘不是明文

```
命令: adb -s emulator-5636 shell run-as com.xiufeigo.opencodeshell.web.debug ls -l shared_prefs/
  -rw-rw---- 1 u0_a190 u0_a190  465 2026-10-04 09:12 AwOriginVisitLoggerPrefs.xml
  -rw-rw---- 1 u0_a190 u0_a190  127 2026-10-04 09:04 WebViewChromiumPrefs.xml
  -rw-rw---- 1 u0_a190 u0_a190 1448 2026-10-04 09:04 oc_webhost_secrets.xml   <-- 凭据文件
```
**"不是明文"的证据**（**全程未打印任何口令**）：
```
命令: run-as ... grep -c serverPassword shared_prefs/oc_webhost_secrets.xml
  -> 0                       # 连"键名"本身都不是明文（AES256-SIV 加密了 key）

命令: run-as ... cat shared_prefs/oc_webhost_secrets.xml   （截断显示）
  <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">12a9016f5e93f9b38e1052...
  <string name="__androidx_security_crypto_encrypted_prefs_value_keyset__">128801e645fba2d15ff941...
  <string name="ARuHx5wcj2DIgFzJ8ulIwa+AF8fWa0pXx+oI+ghp4c6raHc=">AVWRaFcOjXBr7seDgLqFGGGJhEEaj1NsRl...
  <string name="ARuHx5xiw2cNJF5Vo3XcrJjzgQ+bZUNQ5t43zqDbmw==">AVWRaFe/tYA9ugeOu+TY2kRYvvZGd/QRFCb...
```
两个真实键（`backendUrl` / `serverPassword`）落盘形态是
**base64 的 AES256-SIV 密文 key + AES256-GCM 密文 value**，
且 `__androidx_security_crypto_encrypted_prefs_*_keyset__` 两条说明确实走了
`EncryptedSharedPreferences`（主密钥在 Android Keystore）。

**判定 PASS**：`oc_webhost_secrets.xml` 存在，键名与值**均非明文**。

### 5b 🔴 修掉的宿主 bug：明文口令可经 JS 桥读出

**问题**：`WebHostBridge.getSecret(key)` 是 `@JavascriptInterface`，**任意键**都能读。
`WebHostActivity` 自己的设计注释写着：

> "而不能把密码交给页面（`window.__ocAndroid.getSecret` 页面能读，等于把口令散给第三方脚本）"

但代码没兑现这条承诺——页面一行
`__ocAndroid.getSecret('serverPassword')` 就能把**明文口令**读进 JS 作用域，
等于把凭据散给官方 UI 页面上的每一个第三方脚本。

**改前**（`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`）：
```kotlin
@JavascriptInterface
fun getSecret(key: String?): String? = SecretStore.getSecret(ctx, key.orEmpty())

@JavascriptInterface
fun setSecret(key: String?, value: String?): Boolean {
    if (key.isNullOrBlank()) return false
    SecretStore.setSecret(ctx, key, value)
    return true
}
```

**改后**：新增"宿主专用键"概念，桥层直接拒绝。
- `SecretStore.kt`：新增
  ```kotlin
  private val HOST_ONLY_KEYS = setOf(KEY_SERVER_PASSWORD)
  fun isHostOnly(key: String): Boolean = key in HOST_ONLY_KEYS
  ```
- `WebHostBridge.kt`：
  ```kotlin
  @JavascriptInterface
  fun getSecret(key: String?): String? {
      val k = key.orEmpty()
      if (SecretStore.isHostOnly(k)) { Log.w(TAG, "getSecret denied for host-only key"); return null }
      return SecretStore.getSecret(ctx, k)
  }
  @JavascriptInterface
  fun setSecret(key: String?, value: String?): Boolean {
      if (key.isNullOrBlank()) return false
      if (SecretStore.isHostOnly(key)) { Log.w(TAG, "setSecret denied for host-only key"); return false }
      SecretStore.setSecret(ctx, key, value)
      return true
  }
  ```

**为什么这样修不会打断 R1**：回环代理补 `Authorization` 时走的是
`passwordProvider = { SecretStore.getSecret(this, SecretStore.KEY_SERVER_PASSWORD) }`
——**Kotlin 侧直连，不经 JS 桥**，所以代理照常工作（下面 `backendUrl` 仍可读、
首页照常加载即为证）。

**反向验证（改后实测）**：
```
注入: JSON.stringify({pw:window.__ocAndroid.getSecret("serverPassword"),
        url:window.__ocAndroid.getSecret("backendUrl"),
        len:(window.__ocAndroid.getSecret("serverPassword")||"").length})
  -> "{\"pw\":null,\"url\":\"http://127.0.0.1:4095\",\"len\":0}"   # 口令读不到，非秘密键照常

注入: JSON.stringify({setRet:window.__ocAndroid.setSecret("serverPassword","pwned")})
  -> "{\"setRet\":false}"                                            # 写入也被拒
```
**判定 PASS**：漏洞已闭合并反向验证；非宿主专用键（`backendUrl`）不受影响。

---

## 6. IME + insets —— **PASS**（本轮重点）

> **断言纪律**：任何 ime 数值读取**之前**先 `dumpsys input_method | grep mInputShown`。
> 脚本 `br1-ime.ps1` 里 `mInputShown != true` 就直接 `exit 1`，**不读数值**。
> 前几轮确实多次撞上 `mInputShown=false`（JS `.focus()` 不弹软键盘、freeform 窗口点偏、
> `am start` 把 IME 收掉），那些次的 ime 数字**一律作废、没被采信**。

### 6a `mInputShown=true` 断言

```
命令: adb -s emulator-5636 shell input tap <窗口原点+input中心>
      （窗口 frame=[617,75][920,693]，input 视口中心 165,127 → 屏幕 782,202）
命令: adb -s emulator-5636 shell dumpsys input_method | grep mInputShown
  mInputShown=true          <-- 断言通过
佐证: dumpsys input_method
  mRequestedShowExplicitly=true mShowForced=false
  mIsInputViewShown=true mStatusIcon=0
页面: document.activeElement -> "INPUT/Search sessions"   （确实聚焦到真实输入框）
```

### 6b `type=ime frame=...` 原始行

> `dumpsys window insets` 在本 build（API 34）**不是合法子命令**，原文报错：
> `Bad window command, or no windows match: insets`。
> 改用等价的 `dumpsys window | grep 'type=ime'`。

```
InsetsSource id=3 type=ime frame=[0,294][1366,768] visibleFrame=[0,294][1366,768] visible=true flags= insetsRoundedCornerFrame=false
InsetsSourceControl: {3 mType=ime mSurfacePosition=Point(0, 0) mInsetsHint=Insets{left=0, top=0, right=0, bottom=474}}
```

### 6c 页面 CSS 安全区变量 vs 实际 insets —— **一致**

同一时刻页面读到：
```json
{"css_imeBottom":"399.00px","css_safeBottom":"0.00px","css_imeVisible":"1","css_scheme":"light",
 "native":{"safeTop":"0.00","safeBottom":"0.00","safeLeft":"0.00","safeRight":"0.00",
           "imeBottom":"399.00","imeHeight":"399.00","imeVisible":true,
           "windowHeight":"618.00","imeMode":"vars","dark":false,"scheme":"light"}}
```
**对账**（本窗口 frame 底边 y=693）：
```
ime 顶边（frame top）        = 294
窗口底边                     = 693
实际遮挡高度 = 693 - 294     = 399 px
页面 --oc-ime-bottom          = 399.00px      ✓ 完全一致
InsetsSourceControl 里的 bottom=474（IME 自身窗高 768-294）
```
`InsetsHost` 类注释里担心的"平台按 窗口底−IME窗顶 上报、读数远大于键盘真实高度"
在本轮**没有出现**：`ime.bottom` 交到页面的是**裁剪到本窗口之后**的 399，
不是 474。**判定 PASS**。

### 6d 复现"IME 高度随窗口变矮而塌缩"（两级 + 一级基线）

改 `wm size` 只改窗口高度，每级**重启 app**（否则 freeform 窗口不重排），
每级都**先断言 `mInputShown=true`** 再读数：

| 级别 | `wm size` | 窗口 frame 高 | 断言 | `type=ime frame` 原始行 | IME 实测高 | 页面 `--oc-ime-bottom` | 一致 |
|---|---|---|---|---|---|---|---|
| L1 基线 | 1366x768 | 618px | `mInputShown=true` | `frame=[0,294][1366,768]` | 474px | `399.00px`（=693−294 裁剪到窗口） | ✓ |
| L2 | **1366x560** | 618px | `mInputShown=true` | `frame=[0,251][1366,560]` | **309px** | `309.00px` | ✓ |
| L3 | **1366x420** | 618px | `mInputShown=true` | `frame=[0,135][1366,420]` | **285px** | `285.00px` | ✓ |

L2 原文：
```
[ASSERT]   mInputShown=true
RAW:  InsetsSource id=3 type=ime frame=[0,251][1366,560] visibleFrame=[0,251][1366,560] visible=true
  ime frame top=251 bottom=560  => ime height=309px
RAW:  InsetsSourceControl: {3 mType=ime ... mInsetsHint=Insets{left=0, top=0, right=0, bottom=309}}
PAGE: {"css_imeBottom":"309.00px",... "imeBottom":"309.00","imeHeight":"309.00","imeVisible":true,...}
```
L3 原文：
```
[ASSERT]   mInputShown=true
RAW:  InsetsSource id=3 type=ime frame=[0,135][1366,420] visibleFrame=[0,135][1366,420] visible=true
  ime frame top=135 bottom=420  => ime height=285px
RAW:  InsetsSourceControl: {3 mType=ime ... mInsetsHint=Insets{left=0, top=0, right=0, bottom=285}}
PAGE: {"css_imeBottom":"285.00px",... "imeBottom":"285.00","imeHeight":"285.00","imeVisible":true,...}
```

**塌缩结论**：窗口变矮 768→560→420 时，IME 高度实测 **474 → 309 → 285px**，
确实随窗口变矮而收缩；**每一级 CSS 变量都与平台 insets 逐像素对得上**，
**没有出现 `InsetsHost` 注释里描述的"僵死读数"**（例如 v77 那种 2195 vs 766）。
`imeVisible` 也在 IME 收起时正确回 `false`。
**判定 PASS**。

### 6e 附带：`setImeMode` 往返

```
setImeMode("resize") -> getImeMode() -> "resize"
setImeMode("vars")   -> getImeMode() -> "vars"
```
桥的读写往返正常。**注意**：`ImeMode.RESIZE` 的**实际压矮效果本轮没验证**
（见 §9 未闭环）。

---

## 7. 屏幕常亮 —— **PASS**

`dumpsys power` 里 `mWakefulness=Awake` 一直不变，**不反映**这个 flag
（`FLAG_KEEP_SCREEN_ON` 是窗口属性，不是 wakelock），所以取**窗口 `fl=` 行**为证据
（`dumpsys window windows`，定位到本包自己的窗口块）：

```
OFF 基线:
  fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
  -> 无 KEEP_SCREEN_ON

注入: window.__ocAndroid.setKeepScreenOn(true)
ON:
  fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
  -> >>> KEEP_SCREEN_ON PRESENT

注入: window.__ocAndroid.setKeepScreenOn(false)
再关:
  fl=LAYOUT_IN_SCREEN LAYOUT_IN_SCREEN ... （无 KEEP_SCREEN_ON）
  -> >>> cleared
```
**判定 PASS**：开时窗口带上 `KEEP_SCREEN_ON`，关时清掉，往返干净。

---

## 8. 主题 —— **PASS**

```
命令: adb -s emulator-5636 shell cmd uimode night yes
  Night mode: yes
注入: JSON.stringify({scheme:__ocAndroid.getInsets().scheme,
        cssScheme:document.documentElement.style.getPropertyValue("--oc-scheme"),
        dark:__ocAndroid.getInsets().dark,
        mq:matchMedia("(prefers-color-scheme: dark)").matches,
        bodyBg:getComputedStyle(document.body).backgroundColor})
  -> {"scheme":"dark","cssScheme":"dark","dark":true,"mq":true,"bodyBg":"rgb(8, 8, 8)"}

命令: adb -s emulator-5636 shell cmd uimode night no
  Night mode: no
注入: 同上
  -> {"scheme":"light","cssScheme":"light","dark":false,"mq":false,"bodyBg":"rgb(250, 250, 250)"}
```
截图对照：`BR1_T8_dark.png`（body 背景 rgb(8,8,8) 深色）vs `BR1_T8_light.png`（rgb(250,250,250) 浅色）。
宿主 `InsetsHost.systemIsDark()` → `payload.scheme` → facade `--oc-scheme` + `colorScheme`
→ 页面 `prefers-color-scheme` 同步变化，链路通。
**判定 PASS**。

---

## 9. 修掉的宿主 bug 汇总

| # | 严重度 | 位置 | 问题 | 修法 | 反向验证 |
|---|---|---|---|---|---|
| 1 | **高（安全）** | `host/WebHostBridge.kt` + `host/SecretStore.kt` | `getSecret`/`setSecret` 是 `@JavascriptInterface`，**任意键**可读写，页面能直接把**明文 `serverPassword`** 读进 JS 作用域——与 `WebHostActivity` 启动链注释"不能把密码交给页面"的承诺直接冲突 | 新增 `SecretStore.HOST_ONLY_KEYS = setOf(KEY_SERVER_PASSWORD)` + `isHostOnly()`；桥层 `getSecret`/`setSecret` 命中即拒绝（读返回 `null`、写返回 `false`）。代理走 Kotlin 侧 `passwordProvider`，不经桥，**不受影响** | `getSecret("serverPassword")` → `null`（len 0）；`setSecret("serverPassword",…)` → `false`；同轮 `getSecret("backendUrl")` → `http://127.0.0.1:4095` 照常，首页照常加载 |
| — | 工具（已加） | `WebHostActivity.kt` | 无任何宿主侧 JS 注入通道，官方 UI 不主动调桥 → 7 项桥里 4 项无法从 UI 触发 | 新增 `EXTRA_DEBUG_EVAL` + `consumeDebugEval()`，**仅 `BuildConfig.DEBUG` 生效**；⚠ debug 包上因 `exported=true` 存在同设备注入面（已在 §0 记录，建议后续加 `android:permission`） | 8 项桥的注入调用全部经它触发并回读到结果 |

**其余七项未发现需要修的宿主 bug。**

---

## 10. 测不到 / 未闭环

1. **通知的"真·手指点卡片"**（§2b）——模拟器 `expand-notifications` + swipe 没能把卡片留在屏上，
   `uiautomator dump` 搜不到 `BR1-ACCEPT` 节点。改用 `contentIntent` 原文的等价 `am start` 验证。
   *PendingIntent 的 component/extras/flags 与点击完全一致，但"点卡片"这一 UI 动作本身没测到。*
2. **文件选择的成功路径**（§3）——SAF 起来与取消都验了，但**选中文件 → `describe()` → `readPickedFile()` base64** 没走。
3. **文件保存 `saveContent`** ——骨架报告里"文件选择保存"含保存侧，本轮**只测了选择侧**，
   `saveContent` → `ACTION_CREATE_DOCUMENT` → 写入 → `emitFileSaved` **未测**。
4. **`ImeMode.RESIZE` 的实际压矮效果**（§6e）——`setImeMode("resize")` 往返正常，
   但**没验证 WebView 高度真的被压矮**。且**代码里已有一个可疑点**（见下）。
5. **`onShowFileChooser` 路径**——官方 UI 走的是 `WebChromeClient.onShowFileChooser`，
   本轮走的是**桥的 `pickFile`**，前者未测。

### ⚠ 4 的可疑点（只记录，本轮未改）
`InsetsHost.applyResize()` 里：
```kotlin
val lp = host.layoutParams ?: return
val want = (target - pad).coerceAtLeast(1)
if (lp.height != want && lp.height != -1 && lp.height != -2) {   // <-- 守卫
    lp.height = want
    ...
}
```
`host` 是 `android.R.id.content` 的 `FrameLayout`，其 `layoutParams.height` 初始为
`MATCH_PARENT`（即 **-1**）。守卫里 `lp.height != -1` 会让这一分支**在初始态永远不成立**，
即 **RESIZE 模式很可能从未真正压矮过 WebView**。本轮没有实测证据（见上第 4 点），
**没有下"这是 bug"的结论**，但建议下一轮专门复现确认；确认后再改守卫
（例如先固化一次基准高度，或改用 `MATCH_PARENT` → 显式高度的转换判定）。

---

## 11. 收尾确认

| 项 | 状态 | 证据 |
|---|---|---|
| `cmd uimode night no` | ✅ 已恢复 | `Night mode: no` |
| `wm size` 显式重设 | ✅ | `Physical size: 3840x2160` / **`Override size: 1366x768`** |
| `wm density` 显式重设 | ✅ | `Physical density: 320` / **`Override density: 160`** |
| `enable_freeform_support` 还原 | ✅ 改回 `1` | 本轮为跑 IME 曾设 `0` |
| `show_ime_with_hard_keyboard` | ✅ 保持 `1` | 本轮查过并显式设回 |
| `/sdcard` 无新增残留 | ✅ | `rm` 后 `ls /sdcard/ \| grep -iE 'br1\|s2.xml\|clean.png\|max.png\|ff.png\|imeup\|ime2\|now.png\|notif\|^t3\|^t4\|^t8'` **无输出**（`Notifications` 是系统目录，非本次产生） |
| `adb reverse` | ✅ 保留 | `host-19 tcp:4095 tcp:4095` |
| 后端 `127.0.0.1:4095` | ✅ **全程未起停** | 只做 `adb reverse` 挂载 |
| 宿主进程 | ✅ 已 `force-stop`，无残留 | `pidof` 已空 |

### 🔒 设备纪律
**全程只对 `emulator-5636` 执行过 adb 命令。**
`5642` / `5650`（Pixel_Tablet）等用户设备**一次都没有**被 install / force-stop / shell 写入。
收尾时 `adb devices -l` 显示在列的 `5642`、`5650`、`5670`、`5690` 均**未被本轮触碰**
（`5642` 已不在列，为用户自己关闭；本轮未对其做任何操作）。

### 🔒 gradle 锁
`.agent-gradle-lock` 于 `17:08:16` 由本轮取得（等锁至消失后写入），
`:webapp:assembleDebug` **前台**跑完，**收尾已释放**。

### 📦 产出物
- 报告：`research/web/BRIDGE_ACCEPTANCE.md`（本文件）
- 截图证据：`BR1_T2_shade.png`、`BR1_T2_shade2.png`、`BR1_T3_saf.png`、`BR1_T4_chooser.png`、
  `BR1_T6_ime_shown.png`、`BR1_T6_clean.png`、`BR1_T6_max.png`、`BR1_T6_nofreeform.png`、
  `BR1_T8_light.png`、`BR1_T8_dark.png`
- 测试脚本：`br1-eval.ps1`、`br1-ime.ps1`、`br1-ime-collapse.ps1`、`br1-t34.ps1`
- 宿主改动：`webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/SecretStore.kt`、
  `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt`、
  `webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt`
- **未触碰**：`app/**`、`_upstream/**`、`.github/**`
