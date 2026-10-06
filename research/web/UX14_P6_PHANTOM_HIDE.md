# UX14-P6 — 幻影服务器条目清除（宿主注入层 DOM 级隐藏）+ 重建 + 设备复验

> 日期：2026-10-06 · 设备：`emulator-6790`（`Medium_Phone_API_37.0`，端口 6790）
> 改动面：**仅 `webapp/src/main/assets/www/oc-host.js`（宿主注入件）+ 新增 1 个源码级绊线**
> 未改 `ui/`、未改 Kotlin、未改 `app/**`、**未 commit**。
>
> **一句话结论：机制成立、界面达成，但设备复验当场抓到并修掉了本轮最严重的一个缺陷
> —— 初版 `closest()` 会把 `<html>` 本身打上标记，导致**整页连 `<style>` 一起消失、屏幕全黑**。
> 修完后 Server 页正常渲染、4173 行消失、Add server 与 4095（绿点）全部正常。**

---

## 0. 机制事实（P4/P5 已钉死，本轮直接采信）

- 服务器注册表 = `localStorage['opencode.global.dat:server']`，**首次 Add 成功才惰性创建**；
  结构 `{"list":[…],"hidden":{},…}`。
- 4173 条目 = 上游 **builtin**：来自 `location.origin`（`currentServerUrl`），**不在 `list` 里**
  ⇒ 上游 `canRemove` 永不成立；`hidden` 容器虽存在但上游**无任何写入入口** ⇒ 无 hide 出口。
  ⇒ **上游侧永远删不掉这条。**
- 产品负责人 2026-10-06 拍板："这条不应该存在"。

### 0.1 选项 A（预置 `defaultServerUrl=4095`）已放弃 —— 理由留档

幻影换成 4095 地址后，它就只是注册表里的一条普通条目，**一样要靠宿主过滤才能消失**；
而且会把"UI 静态来源 origin"与"真实服务器"两个概念搅在一起。**治标不治本。**

---

## 1. 例程要点（`oc-host.js`，纯新增，1493 → 1514 行）

| 项 | 实现 |
|---|---|
| 目标 | `phantomOriginToken()` = `location.origin` 去掉 scheme，**动态取**，无端口字面量 |
| CSS | `[data-oc-phantom="1"]{display:none!important}`（style id `oc-phantom-hide`） |
| 匹配 | 扫 `querySelectorAll('*')`，`textContent` 含该 URL 串 |
| 行容器 | `phantomRowFor(el, cap)` —— **有界上爬**（见 §2），找不到就标记自身 |
| 监听 | `MutationObserver(document.body,{subtree:true,childList:true})`，**250 ms 节流** |
| 幂等 | 已打 `data-oc-phantom` 的元素跳过 |
| 启动 | 仅 `readyState==='complete'` / `load` 之后，**不阻断首屏** |
| 保守 | **只隐藏、不删除、不改文本**；排除 `input/textarea`；**带输入框的容器整体跳过** |

**红线遵守**：例程代码中**无** `.remove()` / `removeChild`（文件里唯一的 `removeChild` 是既有的
plus-chooser 面板关闭，第 1181 行，与本轮无关）；上游数据模型完全不动，
`visible[0]` 焦点逻辑未被破坏。

---

## 2. 🔴 设备复验当场抓到的严重缺陷（初版全黑，已修）

初版按派单原文实现 `closest('a,li,[role="button"],[data-slot]')`。
**在 Server 页实测：`/settings?tab=servers` 整屏全黑。**

根因（CDP 注入读数，非猜测）：

```
markedCount=12，最外层两个是：
  HTML  textLen=58736   ← 打了标记 ⇒ 整页（含 <style>）display:none
  BODY  textLen=393
```

两条原因叠加：
1. `document.querySelectorAll('*')` **包含 `<html>`/`<body>`**，它们自身 `textContent` 就含该 URL；
2. `closest()` 在 `<html>` 上找不到匹配祖先 → 回落"标记自身" → **把根节点标了**。
   （`[data-slot]` 还会一路爬到页面外壳。）

**修复（两次收紧，均已固化进绊线）**：

| 收紧 | 做法 |
|---|---|
| ① 永不标记根节点 | 显式 `if (el === document.documentElement \|\| el === document.body) continue;` |
| ② 候选必须"紧凑" | `txt.trim().length > candMax` 直接跳过（聚合容器不是行），`candMax = token.length*6+40` |
| ③ 上爬有上界 | `phantomRowFor` 仅接受文本量 ≤ `candMax` 的祖先，否则回落标记自身 |
| ④ 保护输入框 | `el.querySelector("input,textarea")` 命中即整体跳过 |

> 收紧 ②③ 是**第二轮**才发现的：第一轮修掉 `<html>` 后，行容器仍爬到了
> **167 字符**的容器 —— 它连 "Add server" 按钮一起包着，会把按钮也藏掉。
> 加上紧凑上界后，最终命中的最外层是 `SECTION`（**45 字符**，正好是那一行）。

---

## 3. 绊线 `PhantomServerHideTest.kt`（4 条，源码级）

| # | 用例 | 钉住 |
|---|---|---|
| 1 | `routineExistsAndTargetsDynamicLocationOrigin` | 例程存在、目标动态取自 `location.origin`、**不得硬编码 `127.0.0.1:4173`** |
| 2 | `usesDataAttributeAndCssHideWithoutDeletingNodes` | `data-oc-phantom` + CSS 隐藏；**不删节点**；外加 §2 的四条收紧 |
| 3 | `mutationObserverIsThrottled` | MutationObserver / `subtree`+`childList` / 节流常量 / 幂等 |
| 4 | `skipsInputAndTextarea` | 排除 `INPUT`/`TEXTAREA` + 带输入框容器整体跳过 + `complete`/`load` 才启动 |

### 3.1 结果

- **全量：234 tests / 0 failures / 0 errors**（原 230 + 新 4）。
- **反向验证（外科撤除例程 → 4 条真红）**：

```
tests=4 failures=4
RED  skipsInputAndTextarea
RED  usesDataAttributeAndCssHideWithoutDeletingNodes
RED  routineExistsAndTargetsDynamicLocationOrigin
RED  mutationObserverIsThrottled
（原文：java.lang.AssertionError: oc-host.js 里找不到 UX14-P6 例程段（幻影服务器行隐藏），
  抛在 PhantomServerHideTest.kt:51）
```

恢复后逐字节一致：`oc-host.js` md5 = `7E89FAF5C1BFEFF47A37E04757CE6B0D`（1514 行，`node --check` 通过）。

---

## 4. 锁协议 + 产物

JAVA_HOME = `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`；ANDROID_HOME 内联；
长编译 `Start-Process` 脱离会话 + 轮询。`:webapp:testDebugUnitTest` 全绿；
`assembleDebug / assembleRelease / assembleReleaseR8` 三件套 `BUILD SUCCESSFUL`。

| 产物 | 字节 | MD5 |
|---|---|---|
| `research/deliver/opencode-shell-web.apk`（debug） | 16,827,915 | `BC6542E399D8375B99DEE201B61767D9` |
| `research/deliver/opencode-shell-web-release.apk` | 14,309,970 | `E50EB58B7C84C7D6D81E26A198186D17` |
| `research/deliver/opencode-shell-web-release-r8.apk` | 12,029,812 | `D89EC5C8CE78207C7EF9216BB5D5B1E4` |

**包内自查**

| 项 | 结果 |
|---|---|
| `assets/www/ui/*` 条目 | **1613**（1612 非空 + 1 个零长 `oa/__vite-browser-external-*.js`） |
| 包内 `oc-host.js` | md5 `7e89faf5c1bfeff47a37e04757ce6b0d`，与源码**一致**（68,564 B） |
| 包内含 `data-oc-phantom` | ✅ True |
| 包内硬编码 `127.0.0.1:4173` | ❌ **False**（例程在 JS 不在 dex） |
| `classes.dex` 搜 `127.0.0.1:4173`（ASCII + UTF-16） | **0 命中** |
| `classes2.dex` 同上 | **0 命中** |
| dex 搜 `data-oc-phantom` | **0 命中** |
| 凭据扫描 | 真实口令 `eBWz…NECI` **0 命中**；`password=` 唯一命中在自带官方产物 `ui/oa/dialog-C2XTJVXj.js`，原文为 `n.password&&(s.password=n.password)` —— **上游表单字段名，非凭据** |

---

## 5. 设备复验（六项判定）

环境：`install -r` Success · `pm clear` Success · 应用 pid **4532** ·
`adb reverse tcp:4095` · CDP `adb forward tcp:9333 localabstract:webview_devtools_remote_4532`
（**socket 名不带 `@`**，P5 已记此坑）· 页面 origin `http://127.0.0.1:4173/`。

**首启注入读数**
```json
{"origin":"http://127.0.0.1:4173","started":true,"stylePresent":true,
 "styleCss":"[data-oc-phantom=\"1\"]{display:none!important}",
 "observerLive":true,"registryRaw":null,"lsKeys":3}
```
⇒ 例程已启动、CSS 已注入、观察者在线；**3 键** = P5 首启基线，registry 尚未惰性创建。

| # | 判定 | 结果 |
|---|---|---|
| 1 | 设置左侧导航**无** 4173 服务器项 | ✅ **通过**。`/settings?tab=servers` 首启态下 Connection 区**空**，只剩 "+ Add server"。见 `p6_s04` |
| 2 | 首启 Home 上下文切换器/服务器分组**无** 4173 行 | ✅ **通过**。首启 Home 正常渲染（`p6_s01`），可见 4173 元素 **0 个** |
| 3 | Add server 对话框正常 | ✅ **通过**。占位符 `http://localhost:4096` / `Localhost` / `password` 全部完好（`p6_s05`）；三字段成功填入并提交 |
| 4 | 加 4095+opencode ⇒ 绿点、列表只有 4095、切换/进会话正常 | ✅ **通过**。注册表与 P5 **逐字节一致**：`{"list":[{"type":"http","http":{"url":"http://127.0.0.1:4095","password":"opencode"},"displayName":"opencode"}],…}`；4095 页**绿点**，**只有这一行**，**无 4173 行**（`p6_s09`）。下拉组内 `Servers` 分组容器保留、内含 4173 的 `BUTTON` 为 `display:none` ✅ |
| 5 | 4173 空转仍在（1 Hz 404）；设默认后应停止 | ⚠️ **部分**。空转**确实仍在**：`/api/event` 严格 1 Hz（14:56:18→:19→:20→:21），`status=404`，`OPEN→NON2XX→disconnected` 循环 —— 桌面端同款行为，如实记录。**但"设为默认后停止"未成立**：`Set as default` 后 `defaultServerUrl=http://127.0.0.1:4095` 已写入，4173 仍以 **20 s 内 3 次**继续（14:57:33 仍在打）。**该预期不成立，列为遗留。** |
| 6 | 控制台无 MutationObserver 相关报错 | ✅ **通过**。`logcat` 抽查 `OcWebHost` / `chromium.*console` / `MutationObserver` / `Uncaught`：**无任何 JS 异常**，仅既有 `[oc-pow]` / `OcPerfJS` 正常日志 |

### 5.1 截图

`research/web/p6_shots/`：`p6_s01_home_firstboot` · `p6_s02_settings` · **`p6_s03_server_page`（初版全黑，缺陷证据）**
· `p6_s04_server_page_fixed` · `p6_s05_add_server_dialog` · `p6_s06_after_add_4095` ·
`p6_s07`/`p6_s08_dropdown`（下拉未成功弹开，见遗留）· `p6_s09_server_4095_row` · `p6_s10_menu_4095`

---

## 6. 遗留 / 未验证

1. **4173 空转未因"设为默认"而停止**（判定 5 后半段预期不成立）。
   本轮只隐藏 UI 行，**没有**停掉对 origin 的事件流订阅 —— 那是另一件事。
2. **下拉菜单未成功弹开截图**：`Preferences` 头的下拉本轮多次点击未能稳定展开
   （疑似与前一次 CDP `click()` 的开合状态叠加）。判定 4 的结论改由
   **4095 服务器页绿点单行**（`p6_s09`）+ **下拉组 DOM 读数**（4173 `BUTTON` = `display:none`，
   `Servers` 分组容器保留）双证据支撑，结论不依赖那张截图。
3. 首启 Home 仍停在 4173 上下文 `Loading` —— **既有行为，本轮明确不在范围内**（不碰上游数据模型）。
4. 未验证 `<html>` 全黑缺陷在**其它**路由下的表现（本轮在 Server 页触发并修复）。

---

## 7. 收尾

- `adb reverse tcp:4095` → **已 remove**（`reverse --list` 空）
- `adb forward tcp:9333` → **已 remove**
- 自建 `emulator-6790` → **已关闭**（`OK: killing emulator, bye bye`）
- `emulator-5554`（他人 AVD，Pixel_Tablet）**未触碰**；**未** `kill-server`
- 后端 `opencode.exe` pid **21092**、起止时间 22:16:19 **全程未变**，`/api/info` **HTTP 200**
- 本轮临时件（`_p6_*` 共 22 个）→ 已清理
- **未 commit**；改动面 = `oc-host.js` + 新增 `PhantomServerHideTest.kt`

**本轮碰过的 serial：`emulator-6790`（仅此一个）。**