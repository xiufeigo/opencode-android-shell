# P0：项目目录重复拼接 → 带该 session 的请求全 500（v58 真根因）

日期：2026-09-29　工程：OpenCode Android Shell（com.xiufeigo.opencodeshell）
取证现场：`research/v58_verify/5556_b/REPORT.md` §0（emulator-5556，WSL Arch `opencode serve --port 4095`）

---

## 0. 结论

App 侧缺陷，非后端故障。opencode 2.0.18 对一个建在 `<repo>/app/src` 的 session 会同时给出
**`location.directory=<repo>/app/src`** 和 **`subpath=app/src`**；壳把两者相加得到
`<repo>/app/src/app/src`，写进项目记录，又作为下一次建会话的 directory —— **每建一次会话多拼一段**。
路径最终不存在，服务端对带该 session 的每个请求
（prompt/form/permission/model/catalog/providers）抛
`FileSystem.realPath … ENOENT` → **HTTP 500**，此前多轮被误判为「后端整站 500」。

---

## 1. 根因（指认到行）

**增量点：`app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessions.kt:97`（改前）
`HomeSessions.sessionDirectory`**

```kotlin
val tail = base.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
if (tail.equals(sub, ignoreCase = true)) return base   // ← 只比「最后一段」
val joined = base.trimEnd('/', '\\') + "/" + sub        // ← `app/src` vs `src`：永远不相等 → 拼
```

服务端 2.0.18 实测（4095，`POST /api/session {"location":{"directory":"…/app/src"}}`）：

```json
{"data":{"id":"ses_f16d42b4…","subpath":"app/src",
         "location":{"directory":"/mnt/c/…/opencode-android-shell/app/src"}}}
```

即 `directory` **已经是**目标目录，`subpath` 是它相对项目根的相对段。原实现的单段比较
（`src` vs `app/src`）永远为假，于是：

1. `HomeSessions.sessionDirectory` → `<repo>/app/src/app/src`；
2. `HomeScreen.registerProject`（`HomeScreen.kt:376`）把该值 `open`/`touch` 进客户端项目记录
   → Home → 项目 → 长按 → 编辑项目 显示该路径、并提示 “This project isn't registered on the server”；
3. 该记录又作为下一次 New Session 的 `preselectedDirectory` → `NewSessionWorkspaceModel.destination`
   （`NewSessionScreen.kt:211`）→ `ChatViewModel.createSession(directory=…)`
   （`ChatViewModel.kt:3967`，改前原样透传）→ 服务端把 `subpath=app/src` 再挂一次 → **+1 段**。

闭环成立，与报告「首次 2 段 → 本轮末 4 段」「每新建一次会话就多长一段」完全一致。

---

## 2. 修复

### 2.1 归一化原语 `ProjectPaths.normalizeLocation`
`app/src/main/java/com/xiufeigo/opencodeshell/data/HomeProjectStore.kt`

- 去掉 Win32 扩展长度前缀；分隔符统一后再按输入风格（`\` / `/`）拼回，`/mnt/c/…` 与 `C:\…` 各自保持原样；
- 解析 `.` / `..`（根处不逃逸；相对路径保留相对性，不凭空造根）；
- **重复段折叠**（`foldRepeats`）：把任何「重复前一段的块」折叠掉，宽度任意。
  v58 的污染是 `app/src` 的**交替**重复（宽度 2），单段相邻重复（`a/a`）是同一规则的宽度 1 特例。
  真实文件系统不会在同一个根下出现 `a/b/a/b`，所以该折叠对真实路径无损；
  大小写按 [matchKey] 规则（Windows 根不敏感，POSIX 敏感）。
- 幂等：归一化的结果再归一化不变（测试锁）。

### 2.2 根因点
`ui/home/HomeSessions.kt` `sessionDirectory`
- 「已经是尾部」的判断改为**整段**比较（`endsWithSubpath`：`/repo/app/src` 对 `app/src`），
  Windows 根下忽略大小写，POSIX 下敏感；
- 返回值统一过 `ProjectPaths.normalizeLocation`，已污染的一次愈合、正常路径不变。

### 2.3 写入前的防御层
- `ChatViewModel.createSession`：`transport.createSession(ProjectPaths.normalizeLocation(directory), …)`，
  并把同一归一化值交给 `observeSession` —— 归一化发生在**写**，不是只在显示。
- `HomeProjectApi.register`：探针 `fsList` / `location` 也用归一化后的目录。

未改 `HomeProjectRecords.open/close/touch`：那里只做身份比较（`sameDirectory`），
本轮试过在写入点归一化会让既有拼写保真测试（`C:\…`、带尾分隔符）红，属越界改动，已回退；
污染在进入记录之前（`sessionDirectory`）就被治好了。

---

## 3. 文件清单

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/data/HomeProjectStore.kt` | 新增 `normalizeLocation` + 私有 `foldRepeats` / `isDriveLetter`；`isDrive` 复用后者 |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessions.kt` | `sessionDirectory` 整段尾部比较 + 归一化；新增私有 `endsWithSubpath` |
| `app/src/main/java/com/xiufeigo/opencodeshell/ChatViewModel.kt` | `createSession` 归一化 directory（含 `observeSession`），补 `ProjectPaths` import |
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt` | `register` 归一化 worktree，补 import |
| `app/src/test/java/com/xiufeigo/opencodeshell/ProjectDirectoryGrowthV58Test.kt` | 新增，11 条行为锁 |
| `app/src/test/java/com/xiufeigo/opencodeshell/OcPopoverDismissTest.kt` | **他桶未完成的新文件**，`KeyEvent.ACTION_UNKNOWN`（该符号在 android stub 中不存在）导致整个 test 编译失败，改为 `KeyEvent.KEYCODE_UNKNOWN`；该测试自身的断言仍红，属另一桶在途工作 |

---

## 4. 测试

命令（精确 `--tests`，未跑全量 / assembleDebug）：

```
./gradlew :app:testDebugUnitTest --tests '*ProjectDirectoryGrowthV58Test' --tests '*HomeSubpath*' \
  --tests '*HomeSession*' --tests '*HomeProject*' --tests '*ProjectPath*' --tests '*FsBrowse*' \
  --tests '*DirectoryPicker*' --tests '*NewSession*' --tests '*ComposerDrafts*'
```

结果：`BUILD SUCCESSFUL`（全部绿）。
另有单跑 `:app:compileDebugKotlin` 通过。

`ProjectDirectoryGrowthV58Test` 锁定的行为：subpath 已在尾部不再拼接；连建 5 次目录长度不变；
污染路径归一化后不增长且幂等；正常路径（POSIX / WSL / Win32 / UNC）逐字不变；
`.`/`..` 与重复分隔符不移动根；相对路径保持相对；项目记录拿到的是愈合后的路径。

基线（488 suites / 4449 tests / 0 failures）本轮未做全量复核（规则限制），以上为受影响模块的精确集合；
基线数字与 pin 未改动。

---

## 5. 共享后端清理（127.0.0.1:4095，opencode/opencode）

### 5.1 正确的删除/解绑姿势：**没有删除接口**
`_upstream/packages/protocol/src/groups/project.ts` 与运行中的 `GET /openapi.json` 一致，项目只有两条路由：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/project` | 列表 |
| `PATCH` | `/api/project/{projectID}` | `{"canonical"?, "name"?, "icon"?, "commands"?}`，`additionalProperties: false` |

无 `DELETE` / `PUT /api/project`。所以**没有解绑/删除姿势**——一条损坏记录只能被**改正**，
不能被删掉。App 侧对应 `V2Api.updateProject`（`PATCH`）已经支持 `name`/`icon`/`commands`，
目前**未**发送 `canonical`（壳没有改 canonical 的入口，行为保持不变）。

### 5.2 把 canonical 改回正确路径
```bash
curl -u opencode:opencode -X PATCH \
  -H 'content-type: application/json' \
  -d '{"canonical":"/mnt/c/sensorsdata/main/program/opencode-android-shell/app/src"}' \
  http://127.0.0.1:4095/api/project/<projectID>
```
先 `curl -u opencode:opencode http://127.0.0.1:4095/api/project` 找到 `canonical` 含
`app/src/app/src` 的那条，取其 `id`。注意 PATCH 会改 `time.updated`，也会把该目录登记成
`/…/app/src` 的一次正常项目（这正是期望的修复结果）。

### 5.3 当前后端状态
本轮实测该后端**已无污染记录**（`GET /api/project` 共 36 条，`canonical` 含 `app/src` 的 0 条）——
v58 期间那条 `src · src/app/src/app/src/app/src/app/src` 已不在（后端重启/记录已失）。
因此**未对任何项目记录做写操作**（遵守「不要动其他项目的记录」）。

若别处再出现该记录：
1. 用 5.2 的 PATCH 改 canonical；
2. 服务端 `~/.local/share/opencode/project`（WSL Arch）下的项目缓存若仍持有旧 canonical，
   停服后删除对应缓存条目再启动（存储路径以该机实际布局为准，先 `ls` 确认，不要盲删整个目录）；
3. 客户端侧脏行不需要手工改：`sessionDirectory` 会在下一次会话读取时把记录归一化，
   Home → 项目 → 长按 → 编辑项目 里同一条记录会显示为正确路径；
   若想立刻消失，长按 → 关闭项目即可（`HomeProjectRecords.close`）。

### 5.4 顺带记录（未处理，属后端噪声）
`GET /api/project` 里存在若干历史测试脏目录（`/nonexistent-dir-xyz`、`/nope`、
`/home/xiufeigo/C:\sensorsdata\…` 等），来自更早的验证运行，与本 P0 无关，未动。
