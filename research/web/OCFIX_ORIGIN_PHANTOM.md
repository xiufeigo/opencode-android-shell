# OC-FIX · 幻影服务器条目（origin）改为上游注册表机制 + CI 出正式包 + 弹层不再抢键盘

- 轮次：OC-FIX（产品负责人 2026-10-07 16:35 / 18:38 两次报单）
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 设备：**自建 AVD** —— `ocfix_tab`（android-35 平板，2560×1600）+ `ocphone`（android-35 镜像 + medium_phone 档，1080×2400/420 = **411×914 CSS**，与产品负责人手机同形态）；**两台都本轮自建自删**。`emulator-5554`（`dshr_tab`）/ `emulator-5680`（`Medium_Phone_API_37.0`）**全程零写入**（只 `adb devices` 读取）。
- 后端：宿主 `127.0.0.1:4095`（v2 opencode **2.0.24**，pid 1804）——只读使用（`adb reverse` + 浏览/打开会话），**未起停**。
- 性质：**改代码 + 设备复验**。

---

## 0. 一句话结论

1. **CI 出的从来不是"正式包"**：三份产物同属 `com.xiufeigo.opencodeshell.debug`（`.debug` 后缀写在 webapp 的三个 buildType 上）。已改为：**全变体共用正式包名 `com.xiufeigo.opencodeshell`，CI 只发 release 这一个包**。
2. **4173 不是"多一行"，而是 Home 的当前上下文**：上游把页面 origin 当内建服务器，`visible[0]` 恒为它 ⇒ Home 的当前服务器恒为 4173 ⇒ 而 4173 的 API 前缀一律 404 ⇒ 永久 `Loading`。v1–v4 的 DOM 隐藏只遮界面，**遮不住上下文**（这正是报单里"设置里没了、主页还在而且不能用"的形态）。
   已改为**上游自己的注册表机制**：写 `opencode.global.dat:server` 的 `hidden[origin]=true` ⇒ Home 的当前服务器自动落到用户自己的服务器上。设备实测：**Home 渲染出 4095 后端的真实会话列表、DOM 里 4173 文本节点 = 0**。
3. **"菜单从底部往上弹 + 自己弹键盘"是同一个根因**：模型菜单本来就是 popper 锚在芯片上；是上游给菜单里搜索框的 `autofocus` 让 Android WebView 拉起了 IME，键盘吃掉半屏把菜单顶到屏幕中部。已改为**只拦"程序化 + 浮层内 + 非用户点击"的 focus**：键盘不弹、菜单回到芯片上方；用户点搜索框时键盘照常弹。


---

## 1. 报单与复现

产品负责人原话（2026-10-07 16:46）：

> 我之前要求是默认服务器是 127.0.0.1:4096 … 因为之前的人说这个默认服务器是无法删除的，所以我说用 opencode 默认的 127.0.0.1:4096 先挂着，然后添加完服务器，设置了默认服务器后就能把这个删了。但是看着现在并不是这样。现在看着服务器还是 4173，然后设置界面添加服务器后 4173 这个服务器消失了，但是主页这里还在，而且也不能用。

截图（`Screenshot_…_com.xiufeigo.opencodeshell.debug.jpg`）读到的形态：Home 左侧两行 = 「空白+红点」（= origin 4173）与「8.138.19.15:4094 绿点」，搜索框写着 `在 127.0.0.1:4173 中搜索会话`，正文 `加载中`。

---

## 2. 机制（从上游产物里逐句定位）

`webapp/src/main/assets/www/ui/oa/` 里绑定的官方前端（`@opencode-ai/app`）片段：

```js
// index-D2r3WEwY.js
var YI = `opencode.settings.dat:defaultServerUrl`;
function ZI(){ return location.origin }                 // currentServerUrl ← 页面自己
function QI(){ return localStorage.getItem(YI) }
… currentServerUrl: t, defaultServerUrl: n ?? t         // defaultServerUrl 只被"读出来展示"
let r = uL.currentServerUrl ? {type:'http', http:{url:uL.currentServerUrl, …}} : undefined;
x(kI, { defaultServer: W.Key.make(uL.defaultServerUrl), canonicalLocalServer: W.key(r), servers: r?[r]:[] })

// client-DoJd3ZuT.js —— Server store
s = e(() => G({stored: r.list, props: n.servers})),      // props(origin) 先入 Map ⇒ 恒为 list[0]
c = e(() => s().filter(e => !r.hidden[q.key(e)]));       // ★ visible = list 过滤 hidden
return { get list(){return s()}, get visible(){return c()},
         isHidden(e){return r.hidden[e]??!1}, setHidden(e,t){ i(`hidden`,e,t) } }

// index-D2r3WEwY.js —— Home 控制器
a = u(() => n.visible.find(e => W.key(e) === i().server) ?? n.visible[0]),  // ★ 当前服务器
selection: { focusServer: e => m({server: W.Key(e)}) },
server: { list: () => n.visible, focused: a, … }
```

三条硬事实：
1. **当前服务器 = `visible.find(...) ?? visible[0]`**，而 `visible = list.filter(!hidden[key])`，`list[0]` 恒为 origin ⇒ 不写 `hidden` 就永远钉在 4173。
2. `hidden` **是上游自己的机制**（有 `setHidden` / `canHide` / `isHidden`），不是我们发明的字段。
3. `defaultServerUrl` 是**死指针**：列表/切换器/事件流三方都不读它（UX18-P1 已设备实测）。

---

## 3. 改法（`webapp/src/main/assets/www/oc-host.js`）

**主机制（新增）**：`syncOriginHidden()`

| 条件 | 动作 |
| --- | --- |
| 注册表键不存在（首启，用户还没有服务器） | **一个字节都不写**（origin 行是官方 onboarding 入口） |
| `list` 里存在 url ≠ origin 的服务器 | `hidden[origin] = true`（origin 从所有读 `visible` 的表面消失，Home 落到用户服务器） |
| 用户把服务器全删了 | `delete hidden[origin]`（placeholder 回来，不留"一个服务器都没有"的死界面） |
| 已对齐 | 不写、不 reload |

**只动 `hidden` 容器**：`list` 一字不改、条目一个不删（上游的焦点/排序/project 归属都挂在 `list` 上）。

**两条生效路径**：

- ① **boot 路径**：本文件由 `addDocumentStartJavaScript` 注入 ⇒ 顶层直调，**早于页面 hydration** ⇒ 当次启动即生效，无需刷新（产品负责人手机上"已经有自己的服务器"的状态，装上这版一开就好）。
- ② **运行期路径**：`Storage.prototype.setItem` 观察器，只认注册表这一个键。用户 Add/Edit/Remove 时应用自己会写这个键 ⇒ 我们补写一次并 **reload 一次**（应用内存里的 `hidden` 只有重新 hydration 才跟得上）。reload 有三重防护：`sessionStorage` 计数上限 3、只在 `[data-opencode-mounted]` 之后、一次只排一次。

**辅助（保留）**：DOM 隐藏层（v4）不动，但**闸门换成读同一份 `hidden[origin]`**（`isOriginHiddenInRegistry()`）。它只为"上游没消费 `hidden` 的表面"兜底 —— 设备实测设置页仍有一个 `BUTTON` 渲染 origin，被它 `display:none` 掉。两层同进同退，不再出现半隐藏。

**明确放弃的路线**（写进代码注释防回潮）：预置 `defaultServerUrl`（死指针）、纯 DOM 隐藏（遮不住上下文）、往 `list` 塞占位服务器（会抢走 `focused`）。

---

## 4. 验证

### 4.1 单测（`./gradlew :webapp:testDebugUnitTest`）

**246 条，0 失败**。`PhantomServerHideTest` 重写为 12 条，钉住：首启不写 / 语义判据 / 幂等 / 删光后撤隐藏 / 不碰 list&不删条目 / origin 动态且不写死 4173 / boot+观察器 + 防递归 / reload 有界 / DOM 闸门读同一份 hidden + 反向禁 `hasUserServerRegistry` / DOM 不删节点不碰切换器 / 反向禁写 `defaultServerUrl`。

### 4.2 行为探针（Node，无需设备）

`research/web/out_ocfix/origin_hidden_probe.js`（切出真源码那一段在 `vm` 里跑，25 条断言 **ALL PASS**）：
首启零写入 · 有用户服务器时写 `hidden[origin]` 且 list/其它字段逐字节不动 · 幂等 · list 清空后撤隐藏 · 坏 JSON 与 localStorage 不可用都不抛异常 · 模拟应用写入时被补写并排一次 reload · 未挂载不 reload · 同会话最多 3 次 reload · 其它 localStorage 键不受影响。

### 4.3 设备复验（自建 AVD `ocfix_tab`，CDP over WebView devtools）

| # | 场景 | 读数 | 判定 |
| --- | --- | --- | --- |
| A | 首启（无注册表） | `registry=null`、我们零写入、`loading=true`（官方行为） | ✅ 首启语义没被破坏 |
| B | 模拟"应用 Add 成功"写注册表（`http://8.138.19.15:4094`） | 写入**当场被补写**：`hidden:{"http://127.0.0.1:4173":true}`；页面 reload | ✅ 运行期路径成立 |
| C | 改指可达后端 `http://127.0.0.1:4095`（v2 2.0.24，adb reverse） | `loading=false`；正文渲染 `Today Counting 1 to 200 / Single word reply request / v2 smoke test / Yesterday Greeting / New session …`；`127.0.0.1:4173` 文本节点 **0** | ✅ **Home 可用、幻影消失** |
| D | 设置页（`/settings`） | `Servers → opencode`；origin 文本节点 `total=1 / visible=0`；`[data-oc-phantom]` 恰好 1 个 = 含 4173 的 `BUTTON`，`display:none` | ✅ 两个表面一致 |
| E | 冷启（`force-stop` + 重启） | 注册表保留 `hidden[origin]=true`；`loading=false`；会话列表照常渲染；4173 文本节点 0 | ✅ 落盘持久、冷启仍对 |

截图：`research/web/out_ocfix/a0_first_boot.png`（首启）、`b1_cold_home.png`（冷启后 Home）。

### 4.4 诚实记录的残留

`oc-pow` 探针仍能看到 `http://127.0.0.1:4173/api/event` 的 **404 空转**（冷启窗口内 2 次；修前是 1 Hz 常驻）。原因：上游**全局 SDK 的事件流基准**同样取自 `currentServerUrl = location.origin`（UX14-P4/UX17-P2 已定位），它与 `visible` 无关，因此注册表机制管不到它。
- 影响：界面上不可见（UI 与上下文都已在用户服务器上），只是无谓的重连噪声。
- 要彻底消掉只有两条路（都不在本轮范围）：**(a)** 宿主侧把 `/api` 反代到用户服务器（UX6 已刻意删过回环代理，需重新评估）；**(b)** 用 `VITE_OPENCODE_SERVER_MODE=none` 重编前端产物（`HOST_REQUIREMENTS.md` 早列过），本仓的 `ui/` 是预编译产物、按红线不改。

---

## 5. CI / 正式包（同一轮的第二件事）

**改前**：CI assemble 三件套（debug/release/releaseR8），三份**同 applicationId、同 versionCode=1、同 label**，全部上传到 artifact + Release ⇒ 下载页三个文件看着都像"正式包"，而且同机只能装一个、装上也分不出来；其中 debug 那份还是 `debuggable`。

**改后**：

| 项 | 值 |
| --- | --- |
| 发布件 | `:webapp:assembleRelease` **一个** |
| applicationId | `com.xiufeigo.opencodeshell` —— **全变体共用这一个**（debug / release / releaseR8 都不再带 `.debug`；CI 的 `aapt2` 身份守卫断言它恰好等于这个 id） |
| versionCode / versionName | **28 / 2.0.1**（27 = 原生壳版本线 1.4.0/26 的续号，28 = 本轮续号；必须 > 设备上已装的号才能原地升级） |
| 签名 | CI secrets 的 shared keystore（`760dc05f…`），`apksigner` 守卫不变 |
| 产物名 | `opencode-<versionName>.apk`（本次 = `opencode-2.0.1.apk`），artifact 名 `opencode-apk` |
| debug 包 | **只在"该构建就是 debug"时产出**（本地 `:webapp:assembleDebug`），CI 永不发布 |

### 5.1 上一轮我写错的一条，这里更正（产品负责人 2026-10-07 18:04 指出）

我上一轮说"CI 的正式包装不到官方包上，必须先卸载"——**不准确**。查 GitHub/仓库历史后的事实是：

- 老 CI（`b3b81d4` 之前）跑的是 **`:app:assembleDebug`**（artifact 名 `OpenCode-debug-apk`，Release 标题 `OpenCode <tag>`），
- 而 `:app` 的 **debug 变体没有 `applicationIdSuffix`**（`app/build.gradle.kts:37-39`；带后缀的是它的 release 变体，:47）⇒ 老 CI 发出去的包，包名就是 **`com.xiufeigo.opencodeshell`**；
- 签名一直是 secrets 里那把 shared keystore（workflow 自检的 `76:0D:C0:5F:B4:D1:A1:25` = `760dc05f…`，从 `53f2e92` 起没换过）。

⇒ **从 GitHub 装过包的设备，这一版（同 id、同签名、versionCode 26 → 28）是原地升级，数据不丢。** 需要卸载的只有：本轮之前那两个后缀包（`…web.debug` / `….debug`），它们是另一个 App。

### 5.2 本轮验证

本机实测（`aapt2 dump badging`，三个变体逐个）：`package: name='com.xiufeigo.opencodeshell' versionCode='28' versionName='2.0.1'`（debug 变体 `application-debuggable` = YES，release/releaseR8 = no），release APK 13.65 MB。包内 `assets/www/oc-host.js` 的 SHA-256 与仓库源文件**逐位相同**（`a6253476…`，debug 与 release 两份都对过）。

CI 守卫脚本**本机干跑**（`research/web/out_ocfix/ci_guard_dryrun.sh`，用 GitHub runner 同款 `bash` 跑 workflow 里那段原文）：

```
package=com.xiufeigo.opencodeshell versionCode=28 versionName=2.0.1
APK_NAME=opencode-2.0.1.apk
GUARD OK
```

⚠️ 两条硬事实（已写进 `webapp/build.gradle.kts` 注释，别当 bug 修）：
1. CI 的正式包用 **debug 证书** ⇒ 装不到官方 release 证书签的同名包上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）：设备上旧包**必须先卸载**。
2. 旧的 CI 产物是 `com.xiufeigo.opencodeshell.debug`（另一个包名）⇒ 它会作为**第二个 App** 留着，需手动卸载。

---

## 6. 收尾

| 项 | 结果 |
| --- | --- |
| 自建 AVD `ocfix_tab` / `ocfix_phone` | 已 `emu kill`（6792 已从 `adb devices` 消失）+ AVD 目录删除 |
| `adb reverse` / `forward` | 已移除（6792） |
| 后端 `127.0.0.1:4095`（pid 1804） | **未起停**（只 `adb reverse` + 读列表） |
| 他人设备 `emulator-5554` / `emulator-5680` | **仅 `adb devices` 读取，零写入** |
| 未 commit | ✅（工作区留改动，交产品负责人定夺） |

**改动文件**：

- `webapp/src/main/assets/www/oc-host.js`（注册表机制 + DOM 闸门改同一事实源 + 弹层聚焦守卫）
- `webapp/src/test/java/com/xiufeigo/opencodeshell/web/PhantomServerHideTest.kt`（绊线重写）
- `webapp/src/test/java/com/xiufeigo/opencodeshell/web/OverlayFocusKeyboardTest.kt`（新增：弹层键盘绊线）
- `webapp/build.gradle.kts`（正式包名 + versionCode 29 / versionName 2.0.2）
- `.github/workflows/build.yml`（只发一个包 + 身份守卫）
- `research/web/out_ocfix/**`（CDP 探针、行为探针、截图）
- `local.properties`（本机 SDK 路径，gitignored）

---

## 7. OC-KEYBOARD · 弹层里的自动聚焦不再抢键盘（产品负责人 18:38 报单）

### 7.1 两条抱怨是一个根因

报单原话：

> 这类弹窗我期望是从对应选择的地方拉下来，而非从底部往上弹，这块能做不？
> 另外图2界面我选模型的时候，默认会弹出键盘，能不弹出不？按理说有且只有我点击输入框内容要写东西时才会弹出键盘

设备实测（`ocphone`，411×914 CSS，dpr 2.625，真机触摸 `input tap`）：

| 项 | 读数 |
| --- | --- |
| 模型菜单真实身份 | **popper 锚定菜单**：`[data-component=menu-v2-content][data-appearance=compact]`，包在 `[data-popper-positioner]` 里，宽 284 |
| 触发点 | 作曲家模型芯片 `button-v2`（“Fledge Alpha Free”），rect `{x:61, y:858, w:171, h:28}` |
| 修前：键盘 | **`mInputShown=true`** |
| 修前：菜单矩形 | `{x:61, **y:230**, w:284, h:286}` —— 被键盘挤到屏幕中上部（= 用户眼里的“从底部往上弹”） |
| 修前：焦点 | 菜单里的 `input[placeholder="Search models"]`（上游 `autofocus` + `onOpenAutoFocus`） |

⇒ 菜单**本来就是**贴着芯片的下拉；是键盘把可用高度砍掉一半，popper 只好把它顶上去。

### 7.2 修法（`oc-host.js` · OC-KEYBOARD 段）

包装 `HTMLElement.prototype.focus`，只在**三个条件同时成立**时不聚焦（于是不拉 IME）：

1. 目标可编辑（`input` / `textarea` / `contenteditable`）；
2. 目标位于**浮层**内（`[role=menu]` / `[role=dialog]` / `[data-component=menu-v2-content]` / `[data-slot=dialog-container]` / `[data-popper-positioner]` / `[data-corvu-drawer-content]`）；
3. 这次 focus **不是用户刚点出来的**（1 s 手势窗口 + 点的就是它/它的祖先；手势靠 `pointerdown` 记录 —— **非逐键**，不违反 `InputPathHotspotTest`）。

其余情况一律 `orig.apply(this, arguments)` 原样放行（参数透传）。不用 `inputmode=none`（要维护第二套状态）、不用 focus 后 blur（会闪）、不在宿主关 IME（会把作曲家键盘一起废）——理由都写进代码注释与绊线。

### 7.3 修后实测（同一台设备、同一路径）

| 项 | 读数 | 判定 |
| --- | --- | --- |
| 点模型芯片 → 键盘 | **`mInputShown=false`** | ✅ |
| 点模型芯片 → 菜单矩形 | `{x:61, **y:566**, w:284, h:286}`（底边 852，芯片顶边 858）⇒ **紧贴芯片上方** | ✅ |
| `window.__ocFocusGuard` | `{blocked:1, last:"Search models"}` | ✅ 只拦了那一次 |
| 用户真点搜索框 | `mInputShown=true`，输入 `fl` 过滤出 MiMo-V2.6-Flash | ✅ 键盘照常弹 |
| 点作曲家 → 键盘 + 输入 | `mInputShown=true`，编辑器内容 = `hello` | ✅ 未被波及 |
| 作曲家是否被误判成浮层 | `[data-component=composer-editor]` 的 `closest(...)` = **false** | ✅ 不误伤 |

> 截图：`research/web/out_ocfix/c2_model_menu_no_keyboard.png`（菜单贴芯片、无键盘）、`c1_model_menu_anchored.png`。

### 7.4 绊线

新增 `OverlayFocusKeyboardTest`（5 条）：包装唯一且透传参数 / 三条件必须同时成立 / 手势三条（pointerdown、记 target、1 s 窗口、目标匹配）/ 文档开始安装 + 设备可读计数 / **反向禁止第二套机制**（`inputmode`、`blur()`、直接改 `document.activeElement`）。

---

## 8. OC-TERMINAL · 手机竖屏终端列数太少（产品负责人 19:21 报单）

### 8.1 现象与根因（设备实测，411×914 CSS / dpr 2.625）

报单："手机这个终端界面看着怪怪的吧哥，不够宽显示太诡异了" —— 截图中 PowerShell 的表格被压成
`L / e / n / g / t / h` 一列一个字符，`PS …>ls` 从中间折断。

| 项 | 读数 |
| --- | --- |
| 面板 `[data-component=terminal]` | clientWidth **394**，左右各 **14px** padding ⇒ 内容 **366px** |
| 画布 CSS 宽 | **343.6px**（backing 902 = 343.6 × 2.625 ✅ 自洽） |
| 单元格宽 | `IBM Plex Mono` 14px ⇒ `measureText("M")` = **8.4px**（dpr 归一后） |
| ⇒ **列数** | `floor(343.6 / 8.4)` = **41 列** |
| 字号来源 | **上游写死**：`panel-*.js` 里 `new Terminal({ …, fontSize: 14, fontFamily: r.font('mono') … })`；设置里只有"终端字体族"，**没有字号项** |

41 列 ⇒ PowerShell 的 `Format-Table` 把最后一列挤成 1 字符宽 ⇒ 就是那张"诡异"的图。

### 8.2 为什么不用 CSS `zoom`（试过，会坏坐标）

`zoom: 0.75` 加在面板上：列数确实 41 → 57，但控件内部所有指针数学仍按未缩放布局像素算。
设备实测（注入取点探针）：点在一次真实第 **44.6** 格的位置，控件算出第 **33.4** 格 ——
比值 0.749 ≈ 缩放系数。链接点击 / 拖选 / 滚动条命中都会偏 25%。**否决。**

### 8.3 修法：把"度量"同比缩小（CSS 像素空间内自洽）

两条补丁都只作用于终端，且都在 CSS 像素空间里，因此 `metrics` / `getBoundingClientRect` /
`offsetX` 天然同一坐标系 —— 点按、拖选、滚动条**不偏移**：

1. `CanvasRenderingContext2D.prototype.measureText`：终端量单元格宽高时按 `×0.75` 返回
   （宽 + ascent/descent 一起缩）⇒ 控件自己算出更多列；画布仍铺满面板。
2. `ctx.font` 的 setter：只在**终端面板内**的画布上把 px 乘 0.75 ⇒ 字形与单元格同步变小，不重叠。

只在窄屏（`matchMedia('(max-width: 767px)')`，与上游断点一致）生效；宽屏一个像素不动。

**踩过的坑（已写成绊线）**：第一版拿"面板 CSS 字体栈"整串比对，量字那半永远不命中，而画字那半
照缩 ⇒ 字形比格子小、字距被拉开。原因是**控件字体族 ≠ 面板字体族**：控件用上游设置里的
`JetBrainsMono Nerd Font Mono`，面板 CSS 是 `"IBM Plex Mono", …` —— 首选项不同、**通用尾巴相同**。
改成按尾巴（第一个逗号之后）判据后命中。

### 8.4 修后实测

产品负责人 19:52 定档 **0.65**（"用小点字"）。两档都实测过：

| 系数 | 画布 CSS 宽 / backing | 单元格 | 列数 | 字形 |
| --- | --- | --- | --- | --- |
| 1.00（修前） | 343.6px / 902 | 8.4px | **41** | 14px |
| 0.75（上一档） | 349.7px / 918 | 6.3px | **≈55** | 10.5px |
| **0.65（默认档）** | **346.7px / 910** | **5.46px** | **63** | **9.1px** |
| 0.55（最小档） | 347.4px / 912 | 4.62px | **75** | 7.7px |

0.65 档同时验证：渲染干净（字形 5.46 = 0.6 × 9.1，**无重叠、无拉距**）、打字/换行/PTY 重排正常
（长命令在 ~63 列处换行）、面板仍铺满宽度。

> 截图：`research/web/out_ocfix/t6_scale065.png`（0.65 档观感）、`t5_metric_wrap.png`（0.75 档换行）、
> `t3_zoom_cols.png`（被否决的 zoom 方案对照）。

### 8.5 绊线

`TerminalFontScaleTest`（4 条）：只在窄屏生效且宽屏原样返回 / 量字与画字两处都包且幂等 /
**必须按字体栈尾巴判据**（反向禁止整串比对）/ 出错与上游改法时**退化为今天的 41 列**而不是画歪。

---

## 9. OC-SETTINGS · 把终端字号做成设置项（产品负责人 20:08："能不能加到设置里？让人自定义"）

### 9.1 为什么只能"注入"，不能"改上游"

`assets/www/ui/` 是上游预构建产物（红线）。手机端上游 Appearance 只有**字体族**行
（`data-action="settings-terminal-font"`，占位符 `JetBrainsMono Nerd Font Mono`），字号在
`new Terminal({ …, fontSize: 14 … })` 里写死 —— 所以字号这个开关只能由宿主提供。

### 9.2 做法

| 项 | 决定 |
| --- | --- |
| 位置 | 设置 → **外观**，紧跟在"Terminal Font"那一行**后面**（同类相邻） |
| 锚点 | 上游自己的 `[data-action="settings-terminal-font"]` → `closest('[data-component="settings-row"]')` |
| 样式 | **整行 `cloneNode(true)`** 后改文案 + 换控件 ⇒ 排版/配色/分隔线天然一致，也不怕上游改类名 |
| 克隆卫生 | 摘掉克隆体里的 `data-action`（否则等于给上游塞第二个同名字段；设备实测 `staleActions = 0`） |
| 幂等 | `data-oc-term-size-row` 标记复用；已存在只同步选中态与文案 |
| 存储 | `localStorage[oc.settings.terminalFontScale]`，读时夹回 `[0.5, 1]`，缺省 `0.65` |
| 档位 | `100% / 80% / 65% / 55%`（= 41 / 52 / 63 / 75 列） |
| 生效 | 选完**整页重载**：上游 URL 自带 `?tab=appearance` ⇒ 回到同一面板；度量补丁下次创建终端时读新值 |
| 文案 | 跟随上游语言（`opencode.global.dat:language` 里的 `locale` 字段，没存过则 `navigator.language`），中/英两套 |
| 开销 | 观察器只在 `/settings` 路径上查锚点（会话流式输出时不参与），300ms 节流 |

行里还会显示**这台设备的大致列数**："当前 65%，竖屏约 63 列"。

### 9.3 设备实测（411×914 CSS / dpr 2.625）

| 步骤 | 读数 |
| --- | --- |
| 打开 设置 → 外观 | 注入成功：标题 "Terminal font size"、说明带 "about 63 columns"、四档按钮 65% 高亮 ✅ |
| 与上游行对比 | 与 "Terminal Font" 行同款（见截图）✅ |
| 点 **55%** | `localStorage = "0.55"` ✅ → 自动重载 → 仍在 `/settings?tab=appearance` ✅ → 55% 高亮、提示 "about 75 columns" ✅ |
| 开终端实测 | 画布字形 **7.7px**（= 14 × 0.55）、单元格 **4.62px** ⇒ **75 列** ✅（与提示一致） |
| 清掉这个键 | 回到默认：65% 高亮、"about 63 columns" ✅ |

> 截图：`research/web/out_ocfix/s1_term_size_row.png`（注入行就长这样，和上游行同款）。

### 9.4 踩过的坑（已写成绊线）

列数提示第一版用 `ctx.measureText` 去量基准格宽 —— 而那个 API 已被我们自己的补丁缩放，
于是"当前 65%"被算成 **43 列**（真实 63）。改为保留一份**原生** `measureText`（`ocRawMeasureText`）
后一致。绊线里带反向断言，禁止再用被补丁过的 API 算提示。

### 9.5 绊线

`TerminalSizeSettingTest`（6 条）：存储/夹取/默认值 + 度量补丁必须消费设置项 / 克隆自上游行且插在其后 /
摘掉 `data-action` 且幂等且只在设置页跑 / 档位单一来源 + 选中态 + 重载 / 列数提示必须用原生度量 /
文案跟随上游语言。



