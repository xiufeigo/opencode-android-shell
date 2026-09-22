# A8 会话导出 — 实现与接线说明

> 本文对应图队派给 Lead 的 A8 导出（归档见 §4，已判定 server 不支持）。上游规格见 `wave6_specs.md` 第三章；本文件只写**已落地代码 + 集成方需要接线的事项**。

## 1. 已落地（Lead 编写，新建目录零冲突）

| 文件 | 内容 |
|---|---|
| `ui/session/export/SessionExport.kt` | 纯逻辑：`filename(id,title,slug)`、`joinPages(pages)`、`serialize(info,messages)` / `serialize(Frame)`、`INDENT=2`，**以及适配器 `frameFrom(sessionInfo, messages: List<OpenCodeClient.Message>)` / `messageJson(message)`** |
| `ui/session/export/SessionExportSaver.kt` | SAF 落盘：`SessionExportSaver.save(filename, json, onResult)` + `rememberSessionExportSaver()`（`ActivityResultContracts.CreateDocument("application/json")`） |
| `test/.../SessionExportTest.kt` | **10 个**断言（含照抄上游 `export.test.ts` 的三条文件名用例 + 适配器两条） |
| 三份 `strings.xml` | 无新增（toast 文案沿用上游 key 的现有资源，见 §3） |

### 免改 `OpenCodeClient` 的落地路径（适配器）
`OpenCodeClient.Message` **不保留原始 `info`**，但每个 `Part` 保留 `raw`。因此 `frameFrom` 的做法是：
- `parts` → 直接用 `Part.raw`（服务端原始 payload，含未知字段，**无损**）；
- `info` → 由现有字段**重建**（`id/sessionID/role/time.created` + 有值才写的 `tokens/providerID/modelID/agent/time.completed`）。
⇒ 集成方**不必**先给 `OpenCodeClient` 加原始 JSON 重载就能跑通导出；等某次改动顺手加上原始 `info` 访问器后，把 `Frame.info` 换成它即可升级为逐字节等价（已在 KDoc 与文档里标注保真度，避免把重建版当"完全一致"对外宣称）。

### 规格对齐点（逐条对上游）
- payload 只有 `{info, messages}`，**不带 cursor**（`export.ts:10-34`，上游测试 `export.test.ts:43` 显式断言）——`joinPages` 丢弃分页游标，`serialize` 只写两个键。
- 文件名：`title || slug || id` → 小写 → **非 `[a-z0-9_-]` 的连续段折成单个 `-`** → 去首尾 `-` → `.json`；清洗后为空则回退**未清洗的原始 id**（`export.ts:36-43`）。注意 `" - "` 会得到三个横杠（空格→`-`、原 `-` 保留、空格→`-`），这是 `replace(/[^a-z0-9_-]+/gi,"-")` 的精确语义，已在测试里固定。
- 缩进 2 空格（`JSON.stringify(data,null,2)`）。**注意**：`JSONObject.toString(2)` 与 JS 的 `JSON.stringify(…,2)` 不保证逐字节一致（键序、转义差异），所以测试只断言「多行 + 2 空格缩进 + 键集合 + 数组顺序」，不写逐字节等价断言。
- 取消语义：SAF 返回 `null` uri → **不回调**（上游 `saveFile` 返回 false 时调用方静默、不弹 toast，`controller.tsx:169-190`）；写盘失败 → `onResult(false)`；成功 → `onResult(true)`。

## 2. 需要集成方（图队）接线的部分

1. **取数据**：导出需要 `info`（`GET /session/:id` 的**原始 JSONObject**）与 `messages`（`GET /session/:id/message` 的原始行数组）。
   - 现有 `OpenCodeClient.session()/messages()` 返回的是**解析后的数据类**；导出要的是原始 JSON，因此需要二选一：① 给 `OpenCodeClient` 加一个返回原始 `JSONObject`/`List<JSONObject>` 的重载（推荐，改动小且不动解析路径）；② 让 VM 把已解析对象再 `toJson` 回去（会有字段丢失，**不推荐**）。
   - 分页：v1 **没有 cursor 分页**（`?before=` 实测返回 400），所以只能不带 `limit` 全量取，或按 `limit` 分页后由调用方拼接（`SessionExport.joinPages` 已备好）。**这条与上游不同，属 server 能力差异**，需要在 UI 文案/文档里说明「导出为本机可见的全部消息」。
2. **入口**：`⋮` 菜单加「导出会话」项（`SessionHeader.kt:305` 的占位注释可删），点击后：
   ```kotlin
   val saver = rememberSessionExportSaver()
   saver.save(SessionExport.filename(id, title, slug), SessionExport.serialize(info, messages)) { ok ->
       if (ok) toast(getString(R.string.oc_export_success, filename)) else toast(getString(R.string.oc_export_failed))
   }
   ```
   （`oc_export_*` 若不存在请新增，前缀 `oc_export_`；上游 key 为 `toast.session.export.success/failed.*`。）
3. **串行化风险**：`messages` 可能有几千条；`serialize` 是纯字符串拼接，建议在 IO 线程调用一次并把结果交给 `save`（`save` 本身已在 IO 写盘）。若会话极大（>10 万条），考虑加进度提示。

## 3. 上游 toast 文案对照

| 上游 key（`en.ts:637-640`） | 含义 |
|---|---|
| `toast.session.export.success.title` | "Session exported" |
| `toast.session.export.success.description` | "Saved session to {{filename}}" |
| `toast.session.export.failed.title` | 导出失败标题 |
| `toast.session.export.failed.description` | 失败原因 |

## 4. 归档（A8 的另一半）——不做，且上游自己也没做

证据链（Lead 与规格提取员各自独立确认）：
- **上游关闭**：`SHOW_HOME_SESSION_ARCHIVE=false`（`home/sessions/view.tsx:27`）、`archive: async () => Promise.reject(new Error("Session archiving is unavailable"))` 带 TODO（`controller.tsx:290-306`）、时间线只有 `{/* TODO: Need a session archive API. */}`（`message-timeline.tsx:530-536`）。
- **本 server 不支持**：v1 `GET /session` 的 `time` 只有 `created`/`updated`（无 `archived`）；v2 `GET /api/session/<id>/archive` 返回 `text/html`（SPA 兜底）。
→ 结论：UI 侧按**禁用项 + 说明**处理（与 fork/revert/share 同类），不做「灰了但可点」的假实现。

## 5. 未确认 / 风险

- `?before=` 的正确用法未确认（400 响应只 21 字节错误体）；因此「分页导出」暂以「全量导出」替代。
- 大会话（数千条带 parts 的消息）在低端设备上的字符串拼接内存峰值未实测（本轮不装设备）。
- SAF 在部分国产 ROM 上不允许写 `application/json` 之外的定制 mime；已按通用 `application/json` 处理。
