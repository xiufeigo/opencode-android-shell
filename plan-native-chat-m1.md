# 阶段2 M1：原生聊天界面（Native Chat MVP）计划

> 目标：聊天主界面原生化（消息流 + 流式输出 + 原生 composer 含文件/图片附件 + @-mention），
> 同时落地后台省电三件套；WebView 保留为终端/旧版 UI（可切换），不做大爆炸重写。

## 0. 已确认的事实（摸底结论）

- API 面（vendored SDK，`index-CKBO9Mty.js`）：`GET/POST /session`、`GET /session/{id}/message`、
  `POST /session/{id}/prompt_async`（body: `{agent?,model?,variant?,parts:[...]}`）、
  `POST /session/{id}/abort`、`DELETE /session/{id}`、`GET /find/file?query=`、SSE `GET /event`。
- part 格式：`{type:"text",text}`；`{type:"file",mime,url:"data:<mime>;base64,..."|uri,filename,source?}`（attachment 无 mention 时直接 url=data URL，mime 用 `F$(uri)` 探测）；`{type:"agent",name}`。
- 事件（`/event` SSE）：`message.updated`、`message.part.updated`、`session.updated`、`session.error`、`permission.updated` 等；前端 store 以 messageID+parts 归并。
- 壳：无 Compose（Views+ViewBinding）、OkHttp 4.12 已有、`LocalGateway` 是给 WebView 的回环代理（原生层**不经过它**，直连服务器 URL）。
- 耗电：`MainActivity` 无 `onPause/onStop` 处理，后台 SSE/WS/JS 定时器照跑；gateway 常驻。
- 文件入口：壳已实现 `onShowFileChooser`，但上游 composer 无附件按钮 → 原生化后走 SAF 直接解决。

## 1. 架构（M1）

```
MainActivity（壳，保留）
 ├─ NativeChatScreen（新增，默认页）
 │   ├─ ChatViewModel + ChatStore（StateFlow，纯 JVM 可测）
 │   ├─ MessageList（RecyclerView + Markwon 渲染，流式增量刷新）
 │   └─ Composer（EditText + @-mention 弹层 + SAF 附件 + 发送/中止）
 ├─ WebViewScreen（现有 WebView，收进“终端/旧版”页）
 └─ OpenCodeClient（OkHttp 直连 server URL）+ EventStream（SSE，前台持有）
```

- **不引入 Compose**（M1 控制依赖与风险：RecyclerView+Markwon 足够；Compose 留 M2 评估）。
- **直连而非过 gateway**：原生 client 直接用服务器 URL（与 WebView 同源配置），少一跳本地代理省电省内存；gateway 只为 WebView 继续存在。
- 依赖新增：`io.noties:markwon:4.6.2`（markdown）、`testImplementation org.json:json`。

## 2. 组件与文件

| 文件 | 职责 |
|---|---|
| `OpenCodeClient.kt` | REST：sessions CRUD、messages、prompt、abort、findFile；org.json 解析；directory 查询参数 |
| `EventStream.kt` | SSE OkHttp 流：空闲 35s watchdog、断线指数退避重连、`close()` 幂等；回调 `onEvent(type,json)` |
| `ChatStore.kt` | 纯 Kotlin：`reduce(state,event)` 合并 REST 快照与 SSE 增量（message.updated 全量替换、part.updated 按 partID 替换/追加） |
| `ChatViewModel.kt` | 持有 client+store+EventStream；`onStart` 连接 `onStop` 断开；暴露 StateFlow 给 UI |
| `ChatActivity.kt`（或 MainActivity 内嵌容器） | 消息列表 + composer + 附件选择（`ActivityResultContracts.OpenMultipleDocuments/GetContent`）+ @-mention 弹层 |
| `MainActivity.kt` | 底部导航「聊天 / 终端」；`onStop` 三件套（`webView.onPause()+pauseTimers()`、EventStream close、gateway.pause）+ `onStart` 恢复 |
| `LocalGateway.kt` | 加 `pause()/resume()`：pause 后 accept 直接快速关闭新连接（WebView 不可见时无流量） |
| 布局 | `activity_chat.xml`、`item_message_user/assistant.xml`、`view_mention_popup.xml` |

## 3. 关键行为定义

- **流式**：SSE `message.part.updated` 到达即 diff 该 part → `ListAdapter.notifyItemChanged`（文本节流 80ms 合帧）；`message.updated` 全量替换该消息。
- **发送**：composer 文本 → `parts=[{type:text}]` + 附件逐个 `{type:file,mime,url:dataURL,filename}`；成功后清空；`session.status` 显示 running → 显示中止按钮（`POST /session/{id}/abort`）。
- **附件**：SAF 选图/任意文件（≤10MB）→ 读 bytes → base64 dataURL；mime 用 `contentResolver.getType` + 扩展名兜底；@-mention 输入 `@` 触发 `/find/file`，选中插入文件 part（mention source 同上游）。
- **模型/agent**：M1 沿用会话默认（prompt body 不带 model），设置页原生化放 M2。
- **省电**：`onStop`：EventStream close（SSE 断开服务器即停推）、`webView.onPause()+pauseTimers()`、`gateway.pause()`；`onStart`：逆序恢复 + `EventStream` 立即重连 + 拉一次 messages 快照对齐（防漏事件）。不使用前台服务；进程被杀后重开靠 REST 快照恢复，无本地持久化需求（M1）。
- **旧 UI 兼容**：底部导航切到「终端」即回到现有 WebView 全功能（补丁全保留）；M1 期间默认进「聊天」，设置开关可切回「启动进终端」。

## 4. 测试

- `OpenCodeClientTest`（MockWebServer）：sessions 解析、prompt body 形状（text+file base64）、abort、directory 参数。
- `ChatStoreTest`：快照+增量归并、part.updated 追加/替换、message.removed、乱序幂等。
- `EventStreamTest`：SSE 解析（event/data 多行、注释心跳）、35s watchdog 触发 close、重连退避。
- UI 手测清单：真机发送文本/图片附件/@-mention、流式渲染、后台 5 分钟电量曲线对比、终端页回归。

## 5. 非目标（M1 明示不做）

- 会话列表/设置/服务器管理原生化（M2）；权限/问题交互卡片（M2，先由终端页 WebView 承接）；Compose；离线缓存；通知。
- 多服务器切换 UI（沿用现有配置）。

## 6. 请 deepseek-v4.1-flash 重点质疑

1. 直连 vs 过 gateway：直连是否丢失现有 token 前缀/鉴权逻辑（`ProxyPath.publicPrefix`）？服务器 URL 与 directory 从哪读最稳？
2. SSE 断线重连与 REST 快照对齐的竞态：重连后先拉快照再订阅是否会漏/重？`Last-Event-ID` 服务器支持否？
3. 附件 10MB base64 内存峰值与 OOM 风险；`data:` URL 服务器侧是否真支持 uri 形式（`source.type==="uri"` 分支）？
4. onStop 断 SSE：`prompt_async` 在途请求（发送后立刻切后台）是否会被中断导致消息丢失？
5. 不引 Compose 的 M1 UI 工作量与后续 M2 迁移成本是否成立？


## 7. M1 落地状态（实施后补记）

- 已实现全部 §1-§4，并经三轮 deepseek-v4.1-flash（max）审查迭代：M2/M3 生命周期与共享 OkHttp、M4/5 谓词、
  终审 MF-1（SSE `properties` 信封解包——不修则流式/idle/error 全部静默丢失）、MF-2（`/find/file` 返回字符串数组）、
  MF-3（ImageDecoder API 28 守卫，minSdk 26）、MF-4（mention 弹窗按 composerBar 窗口坐标定位，IME 两种状态均可见）。
- SHOULD 已采纳：切终端停 SSE、无配置 toast 节流、无连接发送置 ERROR、back 收起弹窗不退应用、
  mime 扩展名兜底、附件 CAS、mention 防抖、4xx 重连上限、OpenCodeClient.close 只 evict。
- 顺延到 M2（记录在案）：`session.status` 事件解析（现用 10s 宽限回落 IDLE 兜底）、启动进终端偏好开关、
  会话列表/设置原生化、35s watchdog 注入式测试、OpenCodeClientTest snapshot 竞态类用例。
- 已知限制：directory 参数恒空（服务器默认项目）；附件全 base64 常驻内存（上限 6×8MB）；渲染为整条 Markdown 重绑（80ms 合帧）。
