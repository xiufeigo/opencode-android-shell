# v58 复验 B — emulator-5556 (Small_Desktop) — Stop 真中止 + 失败后 composer 留文

日期：2026-09-28 17:25–17:55
设备：emulator-5556，app v1.4.0 (com.xiufeigo.opencodeshell)
截图：`research/v58_verify/5556_b/01..83*.png`
会话前缀：E2ECHK5556-

---

## 结论速览

| # | 项目 | 结论 |
|---|---|---|
| 0 | 阻塞项排查：上轮「后端整站 500」 | **根因已定位 = App 侧路径拼接 BUG，非后端** |
| 1 | Stop 真中止（v57-B1 复刻） | **PASS** (a)(b)(c) 全部满足；无空壳/空气泡 |
| 1b | 2 次短回复对照 | 对照 1 无效（回复太快）；对照 2 **通过但暴露一条观察**（见 O2） |
| 2 | 失败后 composer 留文 | **保稿重试，非双发** —— 观察项，非缺陷 |
| 3 | 清理 + reverse 端口 | **PASS**（4095/4096 在位） |

---

## 0. 阻塞项排查：HTTP 500 的真根因（重要新发现）

### 现象
在 home 里从 `src` 项目新建会话、发 prompt，app 立刻报 `Something went wrong HTTP 500`，
`GET /api/session/<id>/form`、`/permission`、`POST /session/<id>/model`、`/prompt` 全部 500。

### 预期 vs 实际
- **预期**：后端已恢复，prompt 应往返正常。
- **实际**：后端健康（`/global/health` 200、`GET /api/provider` 200、provider 列表完整含 `opencode-go`），
  但**凡是带该 session 的请求 500**。

### 根因（服务端日志 + UI 双向取证）

WSL Arch 内 `opencode serve --port 4095` 日志 `~/.local/share/opencode/log/opencode.log`：

```
PlatformError: NotFound: FileSystem.realPath
  (/mnt/c/sensorsdata/main/program/opencode-android-shell/app/src/app/src)
[cause]: ENOENT: no such file or directory,
  lstat '/mnt/c/.../opencode-android-shell/app/src/app/src'
http.method=POST http.url=/api/session/ses_f16f2ee.../model http.status=500
```

App 发给服务端的 directory 是**被重复拼接**的。

UI 侧独立佐证 —— home → Projects → 长按 → `Edit project`，弹窗顶部路径为：

```
/mnt/c/sensorsdata/main/program/opencode-android-shell/app/src/app/src/app/src/app/src
…
This project isn't registered on the server
```

`app/src` **重复了 4 段**，且每在该项目下新建一次会话就多长一段（首次 2 段 → 本轮末 4 段）。
截��：`82_editproj.png`、`66_home.png`（列表中出现两条 `src` 脏项目条目）。

### 结论
- 这是 **app 的 project/worktree 目录拼接缺陷**（大概率在 `NewSessionScreen` 用「project 目录 + subpath」
  组合时，subpath 已是绝对路径仍被当作相对路径再拼一次），**不是后端故障**。
- 规避方式：新建会话时选 **`Local repository`**（而非默认的 `src` 子目录），
  目录即 `/mnt/c/.../opencode-android-shell/app`，全链路正常，本轮 Stop 测试即在此路径下完成。
- 上轮 v57 被判 BLOCKED 的「后端整站 500」若同样走 `src` 项目，则**同为该 BUG 所致**。

> 附注：服务端另有周期性 `InterruptError: All fibers interrupted`（每 36s 一次，17:39–17:46）
> 与 `Failed to fetch models.dev TimeoutError`，属后端自身噪声，与本轮结论无关。

---

## 1. Stop 真中止 —— PASS

会话：`E2ECHK5556-stop`（模型 opencode-go / space-bunny-free，目录 Local repository）

### 复现步骤
1. 新建会话，发 `describe 20 countries in detail, 2000 words`
2. 输出开始后点 Stop（`40_stop_1s.png`）
3. 等 1s，再发 `say hello`
4. 观察 25s+

### 验收逐条

**(a) Interrupted 标记出现 —— PASS**
被中断的 assistant 行页脚显示 `Build · space-bunny-free · 33s · Interrupted`，
其下有独立的 `Interrupted` 分隔线。
截图：`43_hello_25s.png`、`51_nokb2.png`

**(b) 后端该轮真停 —— PASS**
输出停在 France 段中途，最后一个字符是残句 `…low-carbon power. The pressures`，
25s 观察窗内**没有**任何后续 token、没有工具调用、没有续写。
截图：`43_hello_25s.png`、`51_nokb2.png`

**(c)「say hello」是新 turn 不并入 —— PASS**
`say hello` 作为独立 user bubble 出现在 Interrupted 分隔线**下方**，
自带独立 model 分隔线 `space-bunny-free`，并拿到独立回复 `Hi! What can I do for you?`。
没有并入被中止的那一轮，也没有被 steer 进已死 turn。
截图：`47_newturn_23s.png`、`49_newturn_58s.png`、`51_nokb2.png`

**(d) Stop 失败时有错误提示而非静默 idle —— 本轮未触发（Stop 成功）**
代码路径存在且本轮未被走到：`ChatViewModel.abort()` 会区分
`interrupted=true` / `interrupted=false` / 传输异常 / 未连接，并经 `reportStopUndelivered` 抛可见错误，
见 `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt:3298`。
**本轮无法给出 PASS 证据**（需要制造 Stop 失败态），留作下轮。

**本轮修复项（Stop 后不得出现空气泡 / 极细分隔线空壳）—— PASS**
被中止的 bubble 保留了真实内容并正常收尾（页脚 + Interrupted），
`Interrupted` 之下直接是下一条 user turn，中间**没有**空白 bubble、没有无内容的细分隔线壳。
`58_c2_reload.png` / `59_c2_truth.png` 是切换 tab 后从服务端重读 transcript 的结果，与内存态一致，
排除「只是没刷新」。

### 短回复对照（2 次）

**对照 1：`count to 30 slowly` 中途 Stop —— 无效样本**
回复仅 ~33 token，点 Stop 之前 turn 已自然结束。
结果：正常完整回复（`1 2 3 … 30` + `There you go — 1 through 30.`），
**没有**误报 Interrupted，`14s` 正常页脚。
这本身是正确的（不该给已完成的 turn 补 Interrupted），但不能作为 Stop 的对照证据。
截图：`54_c1_stopped.png`

**对照 2：`write a 800 word essay about rain`，发后 ~4s 点 Stop —— 通过，暴露观察 O2**
Stop 后 composer 立即回到 idle（发送箭头），无残留 Working 态。
但转录里**只有 user bubble，既无 assistant 行，也无 `Interrupted` 分隔线**。
切换 tab 强制从服务端重读后仍然如此（`59_c2_truth.png`），说明**服务端也确实没写任何 assistant 行**，
不是客户端丢帧 —— 与 `ChatStore.kt:1258-1263` 注释描述的「服务端在任何内容落盘前就 abort 的那一轮」一致。

---

## 2. 失败后 composer 留文 —— 保稿重试，非双发（观察项，非缺陷）

### 现象
prompt 发送后遇 HTTP 500 失败，错误横幅 `Something went wrong HTTP 500` 出现，
**prompt 文本完整留在输入框**中（`13_sent.png`、`14_panel.png`）。

### 定性：保稿重试
取证：该失败会话的 Review 面板显示 `Messages (all frames): 0`，而 composer 里躺着完整 prompt。
说明**这次写入一条消息都没被服务端收录**。用户再点发送，只会产生**第一次**写入，
不存在「已发一条 + 又发一条」的双发。
代码侧同源证据：`ChatViewModel.kt:2786` 的 catch 分支只做 `restoreDraft(id, text, …)`，
并有显式注释（BUG-1，v32 tB）说明「一次写入**绝不**自动重放 —— 超时的 POST 可能已被受理，
盲目重试会复制 turn」。

### 结论
**保稿单发 = 设计意图（draft restore），不是缺陷。记为观察。**

### 附带发现 O1：草稿恢复的存活期有限
草稿只在同一 app 进程、同一 session 内有效。本轮把该会话切走再切回后，
输入框已被清空、草稿丢失（`61_failsess.png`）。若需要跨会话/重启保稿，得另做持久化。

---

## 3. 清理

- 本轮新建的 3 个会话已全部删除（截图 `75/76/79_deldlg` 确认弹窗 → `80_final_home.png` 复核）：
  - `E2ECHK5556-stop`（Stop 主测试）
  - `E2ECHK5556-scratch`（原名 `E2top`，早先改名事故产物）
  - `Untitled`（`src` 项目，HTTP 500 失败会话）
- tab 条已清空（`83_done.png`）。
- `adb -s emulator-5556 reverse --list` → `4096` 与 `4095` **均在位**。

**遗留脏数据（未动，需研发处理）**：Projects 里多出一条损坏项目条目
`src · src/app/src/app/src/app/src/app/src`（`82_editproj.png`），标注
`This project isn't registered on the server`。
按「只操作自己前缀会话」的纪律未删除（project 条目不是会话，且删除会动到用户目录注册）。
建议修完 BUG 后手动删掉，或由研发确认是否该在加载时自动剔除未注册的坏路径项目。

---

## 操作会话列表（本轮实际操作）

| 会话名 | 目录 | 用途 | 状态 |
|---|---|---|---|
| `Untitled`（项目 src） | 坏路径 `app/src/app/src` | 复现 HTTP 500 | 已删 |
| `E2top` → `E2ECHK5556-scratch` | opencode-android-shell | 冒烟：验证后端往返 | 已删 |
| `E2ECHK5556-stop` | opencode-android-shell | **Stop 真中止主测试 + 2 次对照** | 已删 |

另曾打开过一个 `Untitled`（MiniMax-M3.1-Flash-Preview）会话（非本轮创建），仅关闭 tab，未做任何写入。

---

## 附：本轮环境/操作踩坑（供后续轮次直接复用）

1. `input text` **空格会被吞**，必须用 `%s`：`input text 'say%shello'`。用普通空格会得到 `sayhello`。
2. **发送按钮 `input tap` 经常无效**，可靠路径是 IME 的 send/confirm 键（本布局 `1220,519`）。
   直接 tap 发送箭头会落空（该位置与 model 选择行重叠，误开会打开模型下拉）。
3. **会话内联改名基本不提交**（Enter 不提交、点空白不提交），且 IME 提交会把长串截断成
   `E2top` 这种残串。**可靠路径是 home 列表长按 → Rename → IME 确认键**，一次成功。
4. `adb shell input text` 注入到 TextField 时，IME 联想会吞字符；分段 ≤25 字符 + sleep 1.5 可缓解。
5. 关软键盘用 `keyevent 111`（ESC）；不要按 BACK。
6. 判定「后端是否真停」不能只看 UI，要**切换 tab 触发服务端 snapshot 重读**后再看一遍。

---

## 待办 / 建议

- **P0（研发）**：`NewSessionScreen` / worktree 目录拼接重复追加 `app/src`，导致目录无限增长且服务端 500。
  入口线索：`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/` 与 `NewSessionScreen.kt`。
- **P1（研发）**：加载 Projects 时自动剔除服务端未注册的坏路径项目（当前脏条目会一直留着）。
- **下轮补测**：验收项 (d)「Stop 失败时有错误提示而非静默 idle」—— 需制造 Stop 失败态
  （如发送中切断 reverse 隧道 / 停掉 WSL 里的 opencode serve），本轮未覆盖。
- **下轮补测**：对照 1 需要更长的短回复（如 `write a 200 word poem`）才能构成有效 Stop 对照。
- **待定**：观察 O2（服务端在写任何内容前就 abort 的 turn，UI 上完全无痕）是否算缺陷。
  当前实现是有意丢弃；但用户视角是「我发了 prompt，Stop 了，然后什么都没发生」，缺一个可解释的落点。
