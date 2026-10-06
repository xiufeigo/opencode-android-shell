# UX13-P5 复验报告：相机扫码（应转绿）+ 通知链路决定性诊断

- 执行时间：2026-10-06 17:43 ~ 18:12（CST）
- 仓库：`D:\XiaoZhuTouTou\opencode-android-shell`
- 后端：`127.0.0.1:4095`（opencode/opencode，v2.0.24），**全程未起停**
- AVD：`Medium_Phone_API_37.0`，端口 **6790**，serial `emulator-6790`
- 被测包：`research/deliver/opencode-shell-web.apk`

> 前置修复：相机可信 origin 已加入 4173；产品负责人拍板全局明文放行 = 选项 C。

## 被测包

| 项 | 值 |
| --- | --- |
| SHA256 | `ACBC520A0BE08779697ED92E42CF98BD796A494848DE639AF51AAA81CDC7AA67` |
| 大小 | 16,821,985 bytes |
| mtime | 2026/10/06 17:36:14 |
| package | `com.xiufeigo.opencodeshell.web.debug` |
| activity | `com.xiufeigo.opencodeshell.web.WebHostActivity` |

---

## 判定摘要

| 项 | 结论 |
| --- | --- |
| **相机扫码** | 🟢 **绿（PASS）** — `GRANTED origin=http://127.0.0.1:4173/`，**DENIED 计数 = 0**，页面无报错，视频流真实出画 |
| **通知断点** | 🔴 **断在客户端（宿主）事件流 + 缺失 POST_NOTIFICATIONS 申请**，**不是**上游闸门；本轮**无法**对上游策略下结论（事件没送达） |
| 事件流 URL | 页面流 = **`http://127.0.0.1:4095/api/event`**（指向后端）；宿主 global-sdk 流 = `http://127.0.0.1:4173/api/event`（页面 origin，死循环） |
| round end | ❌ **完全缺失**（`round end` / `execution.succeeded` / `execution.failed` 全 0 次） |
| `notifCalls` | ❌ **空数组**（上游从未调用 `new Notification()`） |
| 重连风暴 | 446 次 OPEN / 467.4s = **0.954 次/秒**，众数间隔 **1015ms**，OPEN→断开 **均值 0.5ms（max 3ms）** |
| 后端真相 | ✅ 后端**已成功完成回合**：assistant 输出 `OK`，`idle outcome=succeeded` @09:52:06.8（**发生在后台期间**） |

### 断点一句话定位

**后端完成了回合，但客户端 SSE 通道是死的**：每次 `/api/event` 连接在 **0.5ms** 内被拆掉、按 **~1.015s** 无限重连，**终止事件 `session.execution.succeeded` 永远到不了页面**；页面因此从未评估通知闸门，`new Notification()` 一次都没被调用。同时宿主**从未申请 `POST_NOTIFICATIONS`**（`Notification.permission === "default"`，Android 侧 `granted=false`），即便闸门触发也投不出去。

---

## 步骤 0：起机 / 装包 / 首屏注入 ✅

| 项 | 结果 |
| --- | --- |
| adb | `C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe`（不在 PATH） |
| boot | `sys.boot_completed=1`，第 3 次轮询命中（约 15s） |
| reverse | `host-15 tcp:4095 tcp:4095` |
| install / clear | `Success` / `Success` |
| 启动 | `home=http://127.0.0.1:4173/ backend=http://127.0.0.1:4173` |

**首屏立即注入**（先于任何操作），b64 包装 `fetch` + 附加 `EventSource` 包装：

```
{"fetchWrapped":"object","esWrapped":true,"href":"http://127.0.0.1:4173/"}
```

> **工具备忘**：eval 回显 tag 是 `OcWebHost/Eval`（含斜杠），`logcat -s "OcWebHost:Eval"` **取不到**，必须 `logcat -d | grep`。

**探针存活验证**（进会话后）：`nEvt=2`、`nEs=0`、`href=/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/session/ses_ef43fab08ffeoHiNxtinjW9V0c`
→ `aHR0cDovLzEyNy4wLjAuMTo0MDk1` = `http://127.0.0.1:4095`；**`nEs=0` 证明上游事件流走 `fetch` 而非 `EventSource`**，fetch 探针选型正确。

## 步骤 1：连服务器 + 切上下文 + 进会话 ✅

全部真实点击：

1. Home 抽屉 (67,120) → `Settings` (179,2324)
2. `Preferences` 下拉 (870,216) → `Server` (768,988)
3. `+ Add server` (906,482) → 对话框
4. 键入 `http://127.0.0.1:4095` / `opencode` → 提交 (924,1652)，等 16s
5. 回到 Home → 项目行 chevron (994,263) → 服务器列表 → 4095 行 (312,468)
6. 进会话 `v2 smoke test` (250,730)

| 验证点 | 证据 |
| --- | --- |
| 4095 已添加 | 服务器列表 `127.0.0.1:4095` **绿点**（4173 红点）→ **P4 全局明文放行生效** |
| 上下文已切 | Home 副标题 `All projects / 127.0.0.1:4095`，搜索框 `Search sessions in 127.0.0.1:4095` |
| 已进会话 | `v2 smoke test` 渲染历史多轮，composer 可见 |

> **P2 路径修正**：加入 4095 之后，Settings 分区下拉的中段（Projects/Worktrees/Providers/Models/Extensions/**Server**）会被服务器条目替换掉。
> **Add server 仍可达的稳定入口 = Settings 页头部无标签 `+`，坐标 (681,216)**——与本轮任务描述一致，P2 的 (906,482) 只在 Server 分区页有效。

## 步骤 2：相机扫码复验 🟢 **绿**

真实点击 Add server → **Scan QR code** → 系统弹窗选 `While using the app`。

**logcat（`OcWebHost`，级别 I）**

```
09:56:45.451  camera permission REQUEST origin=http://127.0.0.1:4173/ (runtime grant pending)
09:57:00.317  camera permission GRANTED origin=http://127.0.0.1:4173/
09:57:01.465  camera permission GRANTED (already held) origin=http://127.0.0.1:4173/
```

| 判据 | 值 |
| --- | --- |
| `GRANTED`（含 already held） | **2 次**，origin 均为 `http://127.0.0.1:4173/` |
| `DENIED` | **0 次** |
| Android `CAMERA` | `granted=true, flags=[USER_SET\|USER_SENSITIVE_WHEN_GRANTED\|…]` |
| 页面 video（只读注入） | `readyState=4`（HAVE_ENOUGH_DATA）、`720x1280`、`paused=false`、`src=""`（getUserMedia 流） |
| 页面报错 | `hasCameraErr=false`；logcat 无 `Could not open the camera` / `NotAllowedError` / `NotFoundError` / `NotReadableError` |

**判定：通过 = GRANTED 且 DENIED 归零且无页面报错 ✅**
流状态如实记录：模拟器**有虚拟摄像头且真实出画**——截图 `_p5_26_scanner.png` 可见完整房间场景（书架 + 电视测试图），非黑屏。`readyState=4` + `720x1280` 为硬证据。
P3「把 4173 加入相机可信 origin」的修复**确认生效**。

## 步骤 3：通知链路决定性诊断 🔴

> 执行顺序说明：步骤 3 在已处于会话内时执行、步骤 2 随后进行（省一次会话往返），两步均完整真实点击，结论不受顺序影响。

### 3.1 探针与基线

`Notification` 包装（**已透传静态属性** `permission` / `requestPermission`，未犯 P2 的错）：

```
{"notifWrapped":true,"perm":"default","vis":"visible","focus":true}
```

Android 侧（`dumpsys package`）：

```
android.permission.POST_NOTIFICATIONS: granted=false, flags=[USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
android.permission.CAMERA:             granted=false   （相机测试前）
```

### 3.2 时间线

| 时刻 | 事件 |
| --- | --- |
| 09:51:56 | 发送 `Reply with the single word OK` |
| 09:51:57.280 | `round start session.execution.started` ✅ **唯一一条** `session.execution*` |
| 09:51:59 | `KEYCODE_HOME` 切后台 |
| **09:52:06.75** | **后端完成回合**（`idle outcome=succeeded`，见 3.4） |
| 09:52:24 | 25s 到，回前台（实际约 27s 后台） |

### 3.3 回读打点

```json
{"evtFetches":[
  {"u":"http://127.0.0.1:4095/api/event","m":"GET","t":1791280102109},
  {"u":"http://127.0.0.1:4095/api/event","m":"GET","t":1791280224637},
  {"u":"http://127.0.0.1:4095/api/event","m":"GET","t":1791280258072},
  {"u":"http://127.0.0.1:4095/api/event","m":"GET","t":1791280260240},
  {"u":"http://127.0.0.1:4095/api/event","m":"GET","t":1791280345652},
  {"u":"http://127.0.0.1:4095/api/event","m":"GET","t":1791280356256}],
 "notifCalls":[]}
```

闸门值：`{"vis":"visible","focus":true,"perm":"default"}`

| 读数 | 值 | 解读 |
| --- | --- | --- |
| 事件流 URL | **6/6 全部 `http://127.0.0.1:4095/api/event`** | **指向后端 4095**，不是 4173 |
| `notifCalls` | **`[]`** | 上游**从未构造** Notification |
| `perm` | **`default`** | 未 granted（既非 granted 也非 denied ⇒ 宿主从未申请） |
| `vis` / `focus` | `visible` / `true` | 回前台后的快照；这两个不构成阻断 |

> **6 次事件流请求的分布很关键**：换算后落在 09:49:07、09:51:10、09:51:44、09:51:46、09:53:11、09:53:22。
> **09:51:47 → 09:53:11 之间一次都没有**——即**回合进行中与刚回前台时，页面根本没在拉事件流**。

### 3.4 风暴量化（logcat 全量 44,518 行）

| 指标 | 值 |
| --- | --- |
| `event stream OPEN` | **446** 次 / 467.4s = **0.954 次/秒** |
| 间隔众数 | **1015ms ×109**、1014ms ×98、1013ms ×63、1016ms ×58、1017ms ×34 |
| 间隔 min/max/avg | 1ms / 27479ms / 1050.4ms |
| **OPEN → disconnected** | **min 0 / max 3 / avg 0.5ms（n=436）** |
| `round end` | **0** |
| `session.execution.succeeded` | **0** |
| `session.execution.failed` | **0** |

**风暴是「秒开秒断」**：连接建立后 **0.5ms** 就被拆掉，等于从未真正建立。

### 3.5 两条不同的流（P2 结论需修正）

| 流 | URL | 是否经 `window.fetch` | 观测 |
| --- | --- | --- | --- |
| **宿主 global-sdk 流** | `http://127.0.0.1:4173/api/event` | **否**（故 fetch 探针抓不到） | OcPerfJS 在 09:51–09:52 窗口命中 **91 次**，~1s 风暴，每次秒断 |
| **页面/上游流** | `http://127.0.0.1:4095/api/event` | 是 | 探针捕获 6 次，回合窗口内 0 次 |

即：**P2「SSE 走 4173 页面 origin」只对宿主 global-sdk 流成立**；切到 4095 上下文后，**页面自身的事件流是直连 4095 的**。宿主流被钉死在页面 origin 4173（无后端）→ 秒断 → 1s 重连风暴。

### 3.6 通知落点

```
NotificationChannel{mId='oc_turns', mImportance=3, ..., mLastNotificationUpdateTimeMs=0}
AppSettings: com.xiufeigo.opencodeshell.web.debug (10235) importance=NONE userSet=false
NotificationRecord 总数 = 1（就是 AppSettings 那行）
```

- `oc_turns` 渠道**存在但从未被写过**（`mLastNotificationUpdateTimeMs=0`）。
- 本包**无任何新通知**；`OcNotify` 相关日志 0 条。

### 3.7 后端侧真相（只读查询）

`GET /api/session/ses_ef43fab08ffeoHiNxtinjW9V0c/message`：

```json
{"id":"msg_110a0a86...","type":"idle","outcome":"succeeded","time":{"created":1791280326757}}
{"id":"msg_110a084f...","type":"assistant","finish":"stop",
 "content":[{"type":"reasoning",…},{"type":"text","text":"OK"}],
 "time":{"created":1791280317901,"completed":1791280326751}}
{"id":"msg_110a0827...","type":"user","text":"Reply with the single word OK","created":1791280317678}
```

**后端成功跑完并产出 `OK`**，时间戳 09:52:06.75——**正处于后台窗口内**。
回前台后 UI **确实渲染出了 `OK`**（截图 `_p5_18_returned.png`），但那是**恢复时重新拉取**补上的，**不是**事件流推来的。

### 3.8 判定（对照任务决策树）

- 「`__evtFetches` 全指向 4173」→ ❌ 不成立，实际**全指向 4095**。
- 「指向 4095 且 round end 出现、notifCalls 有」→ ❌ round end **没有**，notifCalls **空**。
- ✅ 落在第三支：**「指向 4095 但 round end 缺失 ⇒ 流被重连风暴截断」**，风暴间隔已量化（~1.015s，OPEN→断 0.5ms）。

**断点层级（本轮结论）**

1. **宿主（我方，可修）**：① global-sdk 事件流钉在页面 origin 4173 而非所选服务器 4095，秒断 + 1s 风暴；② **从未申请 `POST_NOTIFICATIONS`**（Android `granted=false`，页面 `perm=default`），即便闸门触发也投不出去。
2. **上游**：**本轮无法判定**。事件没送达 ⇒ 闸门根本没被评估，`notifCalls=[]` 只能证明「没被调用」，**不能**推出「上游策略会拦」。P2 提到的上游 sound/notification 用户开关是否默认关闭，仍是**未验证**项。

---

## 步骤 4：收尾 ✅

| 项 | 结果 |
| --- | --- |
| `adb emu kill` (6790) | `OK: killing emulator, bye bye` |
| `adb devices -l` | 空（无设备残留） |
| `adb reverse --list` | **0 条**（设备销毁后规则随之消失；kill 前为 `host-15 tcp:4095 tcp:4095`） |
| 端口 6790 | 仅剩 `TimeWait` 残留（`OwningProcess=0`），**无监听者** |
| emulator / qemu 进程 | **均已退出**（无 `qemu-system-x86_64`） |
| 后端 4095 pid | **15096 → 15096（未变）** |
| `GET /api/info` | **HTTP 200** `{"version":"2.0.24","pid":15096,…}` |

未执行 `kill-server`，未改任何代码，未碰 gradle。只关自建 `emulator-6790`。
（收尾核对时曾见 2 个 `emulator.exe` 瞬态启动进程 PID 36832/36976，经 `Win32_Process` 复查已全部退出，非他人在用实例，未做任何处理。）

## 截图 / 证据文件

| 文件 | 内容 |
| --- | --- |
| `UX13_P5_s00_home.png` | 首屏 |
| `_p5_05_adddlg.png` / `_p5_07_filled2.png` / `_p5_08_added.png` | 加服务器对话框 / 填好 / 4095 绿点 |
| `_p5_12_projlist.png` / `_p5_13_ctx4095.png` | 服务器列表 / 上下文已切 4095 |
| `_p5_14_session.png` / `_p5_16_typed2.png` / `_p5_18_returned.png` | 会话 / 输入 / 回前台后拿到 `OK` |
| `_p5_23_adddlg4.png` / `_p5_25_scanperm.png` / `_p5_26_scanner.png` | 无标签 `+` 开对话框 / 权限弹窗 / **扫码器真实出画** |
| `_p5_logcat_full.txt` | 回合期间全量 logcat（38,122 行） |
| `_p5_logcat_final.txt` | 含相机测试的最终 logcat（44,518 行） |

## 复现备忘

- adb 不在 PATH，须用 `C:\Users\xiufeigo\AppData\Local\Android\Sdk\platform-tools\adb.exe`。
- eval 回显用 `logcat -d | grep OcWebHost/Eval`，`-s` 无效（tag 含 `/`）。
- 弹窗里 `keyevent 111`(ESC) 会**直接关掉对话框**；收键盘请用 `keyevent 4`(BACK)。
- `adb shell input text` **空格必须写成 `%s`**，否则只输入到第一个空格为止。
- 加入第二个服务器后，Settings 分区下拉的 `Server` 项会消失；Add server 改用头部无标签 `+` (681,216)。