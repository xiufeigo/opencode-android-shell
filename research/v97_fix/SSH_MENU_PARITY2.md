# SSH_MENU_PARITY2 — 行菜单第二段补全：分组标签 / 分隔线 / `domain.defaults` 语义核查

> **一句话结论：A 级（分组标签 + 分隔线）已落地，零新增用户可见字符串；B 级（`Set as default` / `Remove default`）**确定不做** —— 不是「概念完全没有」，而是本壳的同名概念是**另一个事实**（HTTP 服务器的 *active* 身份、且 SSH 条目连算它的 `url` 都没有），把它挂在 "Set as default" 上会是「说一套做一套」。上游把 default 定义为 *app 启动时*连的服务器，本壳的 `setDefault` 是*立刻切活动服务器*。**

---

## 0. 承接与本轮边界

`research/v97_fix/SSH_ROW_MENU_FIX.md` §7.2 留下三项（对应 `_upstream/packages/app/src/servers/ssh/menu.tsx`）：

| 上游元素 | 上游行号 | v99 后本壳 | 本轮 |
|---|---|---|---|
| `<Menu.GroupLabel>{ssh.server.menu.label}` | `:29` | 缺 | **✅ 已补（A 级）** |
| `<Menu.Separator />` | `:45` | 缺 | **✅ 已补（A 级）** |
| `Set as default` / `Remove default` | `:35` / `:40` | 缺 | **⛔ 确定不做（B 级）**，理由见 §2 |

判定的唯一标尺仍是上游 `menu.tsx`。v99 已闭环的 `⋮` 可见性（`:16`）、`Connect`/`Authenticate` 显隐（`:30`）与连接中禁用（`:31`）、`Delete` 任何态可用（`:46`）本轮原样保留，并各有一条新 pin 复验。

---

## 1. 上游 `domain.defaults` 到底是什么（判定的唯一标尺）

### 1.1 调用点

`menu.tsx:35` / `:40`（原文）：

```tsx
<Show when={props.domain.defaults.available() && props.domain.defaults.key() !== key()}>
  <Menu.Item onSelect={() => props.domain.defaults.set(key())}>
    {language.t("dialog.server.menu.default")}
  </Menu.Item>
</Show>
<Show when={props.domain.defaults.available() && props.domain.defaults.key() === key()}>
  <Menu.Item onSelect={() => props.domain.defaults.set(null)}>
    {language.t("dialog.server.menu.defaultRemove")}
  </Menu.Item>
</Show>
```

`key` 的构成（`menu.tsx:14`）：`ServerConnection.Key.make(\`ssh:${props.id}\`)` —— 键空间里 SSH 条目与 HTTP 服务器是同一个 `ServerConnection.Key` 命名空间。

### 1.2 实现（`_upstream/packages/app/src/servers/registry/controller.ts:19-48`，原文）

```ts
function useDefaultServer() {
  const language = useLanguage()
  const platform = usePlatform()
  const [defaultKey, defaultKeyActions] = createResource(
    async () => {
      try {
        return (await platform.getDefaultServer?.()) ?? null
      } catch (err) {
        showRequestError(language, err)
        return null
      }
    },
    { initialValue: null },
  )

  const set = async (key: ServerConnection.Key | null) => {
    try {
      await platform.setDefaultServer?.(key)
      defaultKeyActions.mutate(key)
    } catch (err) {
      showRequestError(language, err)
    }
  }

  return {
    key: () => defaultKey.latest,
    available: createMemo(() => !!platform.getDefaultServer && !!platform.setDefaultServer),
    set,
  }
}
```

**逐条回答工单的三个问题：**

1. **`available()` 的条件**（`controller.ts:45`）= `!!platform.getDefaultServer && !!platform.setDefaultServer`。
   这是**平台能力探测**，不是逐条目的业务条件 —— 与「这条服务器能不能被设为默认」无关。

2. **`key()`**（`controller.ts:44`）= `defaultKey.latest`，来自 `createResource` 的初次读取，**初值 `null`**（`:31`）。即「没有默认服务器」是合法常态。

3. **`set(key | null)`**（`controller.ts:34-41`）= `platform.setDefaultServer?.(key)` + 本地 `mutate`。`set(null)` 是**清除**，不是置空字符串。

### 1.3 「默认服务器」到底意味着什么（关键）

**`runtime/platform/platform.tsx:82-86`（原文，接口注释）：**

```ts
  /** Get the configured default server URL (platform-specific) */
  getDefaultServer?(): Promise<ServerConnection.Key | null>

  /** Set the default server URL to use on app startup (platform-specific) */
  setDefaultServer?(url: ServerConnection.Key | null): Promise<void> | void
```

⇒ **语义是「app 启动时连哪个服务器」，不是「现在切到哪个服务器」。**

**持久化位置 —— `runtime/platform/web.ts:5` / `:41-45` / `:66-85`（原文）：**

```ts
const DEFAULT_SERVER_URL_KEY = "opencode.settings.dat:defaultServerUrl"
...
    getDefaultServer: async () => {
      const stored = readDefaultServerUrl()
      return stored ? ServerConnection.Key.make(stored) : null
    },
    setDefaultServer: writeDefaultServerUrl,
...
function readDefaultServerUrl() {
  if (typeof localStorage === "undefined") return null
  try {
    return localStorage.getItem(DEFAULT_SERVER_URL_KEY)
  } catch {
    return null
  }
}
```

**并且只有 web 平台实现了它。** `runtime/platform/` 目录只有
`browser-pane.ts` / `external-link.tsx` / `file-picker.ts` / `keyboard.ts` /
`platform.tsx` / `pwa.ts` / `web.ts` —— `getDefaultServer` / `setDefaultServer`
全仓仅出现在 `web.ts:41-45` 与接口声明里。`pwa.ts` 只管路由持久化（`LAST_ROUTE_KEY`），
不碰 default server。

⇒ **在非 web 平台上 `available()` 恒为 `false`，这两项上游自己也永不显示。**
本壳是 Android，天然落在 `available() === false` 这一支。

### 1.4 副作用（除了「启动时连哪个」之外还有两处）

1. **列表排序**：`controller.ts:113-120` 把 `defaults.key()` 喂进 `sortServerConnections`，
   后者 `controller.ts:62-64`：

   ```ts
   const preferred =
     Number(ServerConnection.key(b) === input.defaultKey) - Number(ServerConnection.key(a) === input.defaultKey)
   if (preferred !== 0) return preferred
   ```

   ⇒ 默认服务器**排到列表最前**，且该优先级**压过健康度**。

2. **删除时自动清除**：`controller.ts:85`

   ```ts
   if ((await platform.getDefaultServer?.()) === key) await defaults.set(null)
   ```

   ⇒ 删掉当前默认服务器会连带把 default 清成 `null`（否则会指向一个不存在的键）。

**一句话**：`defaults` = 一条**持久化的、启动期生效的**服务器指针；副作用是「列表置顶 + 删除时自愈」。

---

## 2. 本壳有没有等价概念？（只读排查）

### 2.1 结论先说

**有一个同名的 `isDefault`，但它不是同一个事实，所以 B 级不做。** 分三层说明。

### 2.2 证据：同名概念的原文

| 位置 | 原文 | 是什么 |
|---|---|---|
| `ui/settings/SettingsServerBridge.kt:137` | `isDefault = server.url == store?.activeUrl(),` | **default ≡ 当前活动服务器**（身份相等判定） |
| `ui/settings/SettingsModel.kt:1885` | `val isDefault: Boolean = false,` | `ServerListItem` 的字段 |
| `ui/settings/SettingsModel.kt:1893-1896` | `sortServers` … `sortedWith(compareBy({ if (it.value.isDefault) 0 else 1 }, …))` | 与上游 `sortServerConnections` 同构的置顶（✅ 这一处 parity 是有的） |
| `ui/settings/SettingsServerBridge.kt:205-209` | `override fun setDefault(url: String) { … current.replaceAll(current.listServers(), url) … }` | **立刻切换活动服务器** |
| `ui/settings/ServersPanel.kt:279-282` | `onMakeDefault = { bridge?.setDefault(server.url) }` | HTTP 行的调用点 |
| `ui/settings/ServersPanel.kt:459-471` | `DropdownMenuItem(text = { Text(stringResource(R.string.oc_servers_menu_default) …) }, onClick = { onMakeDefault() })` | HTTP 行的「Set as default」项 |
| `ui/settings/SettingsScreen.kt:241` | `fun setDefault(url: String)` | 接口声明 |

### 2.3 为什么它**不能**当 `domain.defaults` 用（三条独立的理由，任一条即足够）

**① 语义相反。** 上游 = *app 启动时*连的服务器（`platform.tsx:85-86` 原文 "to use on app startup"），是**未来**的指针；
本壳 = `store.replaceAll(list, url)`（`SettingsServerBridge.kt:207`），是**现在**就切换活动服务器。
把 `Set as default` 挂到 `setDefault(url)` 上，用户看到「设为默认」、实际立刻被切走 —— 说一套做一套。

**② SSH 条目根本无法满足这个判定。** `ServerListItem.url: String`（`SettingsModel.kt:1880`）是**非空必填**，
而 SSH 条目走的是另一套存储：`SettingsServerBridge.kt:219-226` 的 `sshEntries()` / `putSshEntry()` / `removeSshEntry()`，
落在 `ssh_json` 里，**没有 url 字段**（`:317-319` 注释明说「`SshEntry` has no field for it」）。
`isDefault` 是 `server.url == store?.activeUrl()` 的**字符串相等**，SSH 条目连参与比较的左值都没有。

**③ 接线点在授权范围之外。** `setDefault` 声明在 `SettingsScreen.kt:241`、实现在
`SettingsServerBridge.kt:205`，两者都在 `ui/settings/**`；本轮写权限只有 `ui/servers/**`。
要接 B 就必须改这两个文件 + `SshEntry` 存储形状 + 启动期读取路径。

**④ 附带证据（说明「补 B」不是加两项就完事）：** 即便在 HTTP 行上，上游那对**互斥**项本壳也只做了一半 ——
`ServersPanel.kt:459-471` 的「Set as default」是**无条件渲染**的，没有 `isDefault` 互斥，
也没有 `Remove default` 分支（`oc_servers_menu_default` 全文仅此一处消费点，
上游的 `dialog_server_menu_default` / `dialog_server_menu_defaultRemove` 两个键
在 `strings_oc_i18n.xml:408-409` 三语齐备但**当前无消费点**，是 i18n 导入带来的死键）。
所以「B 级只差 SSH 行」是错的：互斥逻辑本壳在 HTTP 行上都还没有。

### 2.4 若要实现 B，最小改动面（本轮**未做**，仅记录）

按依赖顺序，**最小面是 4 处 + 1 个决策**：

1. **决策（无代码）**：先定语义 —— 是「启动期默认」（忠于上游 `platform.tsx:85-86`）还是
   「当前默认」（忠于本壳 `setDefault`）？两者的存储、失效时机、UI 都不同。**这个决策不定，下面三处都会写错。**
2. **状态存放**：`ServerConfigStore` 增加一条 launch-default 指针
   （形态应比照 `activeUrl`：`SettingsServerBridge.kt:137/142` 用的就是 `store?.activeUrl()`），
   且需能在**冷启动**读取 —— 这是与 `activeUrl` 最实质的差别。
3. **能力开关**：仿 `available()`，需要一个「本平台支持启动默认」的判定，
   否则菜单项会像上游非 web 平台那样**永不显示**（白接线）。
4. **SSH 键空间**：为 `SshEntry` 引入上游式的 `ServerConnection.Key`（`menu.tsx:14` 的 `ssh:${id}`），
   否则 `key()` 与条目身份无法比较。
5. **菜单接线 + 删除自愈**：`ServersPanel.kt` / `SshTunnelHost.kt` 画两项并做 `key()` 互斥；
   删除路径补上游 `controller.ts:85` 的「删掉的正是默认 ⇒ `set(null)`」。

**影响面**：`ServerConfigStore` 序列化（存量用户的默认值迁移）、启动流程（`ui/App.kt` / `OcActivity.kt`，
本轮禁改）、会话新建时的默认服务器选择、列表排序（`SettingsModel.kt:1893-1896` 与
`SettingsServerBridge.kt:137` 的 `isDefault` 语义会分叉，需要决定是「同一个」还是「两个」）。

---

## 3. A 级改动（已做）

### 3.1 改了什么

`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt`：

1. **新增纯规则 `SshRowMenuEntry` + `sshRowMenuEntries(stage, defaultsAvailable, isDefault)`**
   —— 把 `menu.tsx:28-49` 的子元素**集合与顺序**表达成一条可测的函数：

   ```kotlin
   internal enum class SshRowMenuEntry { GroupLabel, Connect, SetDefault, RemoveDefault, Separator, Delete }

   internal fun sshRowMenuEntries(
       stage: SshStage,
       defaultsAvailable: Boolean = false,
       isDefault: Boolean = false,
   ): List<SshRowMenuEntry> = buildList {
       add(SshRowMenuEntry.GroupLabel)
       if (sshRowMenuShowsConnect(stage)) add(SshRowMenuEntry.Connect)
       if (defaultsAvailable && !isDefault) add(SshRowMenuEntry.SetDefault)
       if (defaultsAvailable && isDefault) add(SshRowMenuEntry.RemoveDefault)
       add(SshRowMenuEntry.Separator)
       add(SshRowMenuEntry.Delete)
   }
   ```

   `SetDefault` / `RemoveDefault` 两个分支**按上游真值表建模但永不触发**（调用点不传
   `defaultsAvailable`，即恒为 `available() === false`）—— 这样上游那一格不会被悄悄忘掉，
   而 UI 也不会长出一个没有语义的项。

2. **`SshTunnelRowMenu` 改为由该函数驱动**（`forEach` + `when`），并在 `GroupLabel`
   分支渲染分组标签、`Separator` 分支渲染分隔线。

3. **新增 `SshRowMenuGroupLabel()`**：复用 `NewSessionScreen.kt:1917-1927` 的
   `MenuGroupLabel` 写法（`textMuted` / 11 sp / 530 字重 / 12 dp 内缩），
   使两个下拉不读起来像两套设计系统。

### 3.2 上游位置对照（逐行）

| 上游 | 位置 | 本壳 | 一致性 |
|---|---|---|---|
| `<Menu.Group>` | `:28` | `DropdownMenu` 的内容 lambda | — |
| `<Menu.GroupLabel>{language.t("ssh.server.menu.label")}</Menu.GroupLabel>` | `:29` | `SshRowMenuEntry.GroupLabel -> SshRowMenuGroupLabel()`，`R.string.ssh_server_menu_label` | ✅ 位置（首项、无 `Show` 包裹）与文案键均对齐 |
| `<Show when={item().stage !== "ready"}>` | `:30` | `sshRowMenuShowsConnect(stage)` | ✅ v99 已有 |
| `disabled={ssh.pending(props.id)}` | `:31` | `sshRowMenuConnectEnabled(stage)` | ✅ v99 已有 |
| `defaults.available() && key() !== key()` | `:35` | 建模、不渲染 | ⛔ §2 |
| `defaults.available() && key() === key()` | `:40` | 建模、不渲染 | ⛔ §2 |
| `<Menu.Separator />` | `:45` | `SshRowMenuEntry.Separator -> HorizontalDivider(0.5.dp, c.borderMuted)` | ✅ **在 `Connect` 的 `Show` 之外**（`ready` 行也画），紧邻 `Delete` 之上 |
| `<Menu.Item … remove(key())>` | `:46` | `enabled = sshRowMenuDeleteEnabled(stage)` | ✅ v99 已有 |

### 3.3 分组标签的文案选择（工单要求二选一并说明依据）

**选「复用上游同义串」**：`R.string.ssh_server_menu_label`，**不新增任何字符串**。

依据：

- 上游 `menu.tsx:29` 的键就是 `ssh.server.menu.label`，`i18n/en.ts:9` = `"SSH server"`；
- 本壳 `strings_oc_i18n.xml:1381` 已三语齐备 —— **但中文两版是本壳自己的本地化，不是照抄上游**：
  逐键核对后确认上游 `i18n/zh.ts` 与 `i18n/zht.ts` **都没有** `ssh.server.menu.label` 这个键
  （`Select-String '"ssh\.server\.menu\.label"'` 在 `zh.ts` / `zht.ts` 上零命中，`en.ts:9` 命中），
  而 `zh.ts:1` 是 `import en from "./en"` + `:3 type Keys = keyof typeof en` 的**部分覆盖**结构，
  缺键时回落英文 ⇒ **上游在简体中文/繁体中文下这一行实际也显示 `SSH server`**。
  本壳的 `SSH 服务器` / `SSH 伺服器` 是更进一步的本地化。
  这不影响本轮结论（那两个串**早已存在**、不是新增），但值得记一笔：这是本壳优于上游的一处，
  不要为了「对齐上游」把它改回英文。
- 分隔线用 `HorizontalDivider`（`NewSessionScreen.kt:1864` 同处 dropdown 内的既有写法），
  同样零新串。

**新增用户可见字符串：无。**

---

## 4. 测试证据

环境：`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`；
仅 `:app:testDebugUnitTest --tests "<类名>"`；前台跑完；`.agent-gradle-lock` 抢锁 → 跑完 → 删锁，全程遵守。

### 4.1 新增类 `SshMenuParity2V100Test`

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.SshMenuParity2V100Test" --console=plain
```

```
BUILD SUCCESSFUL in 29s
```

`TEST-com.xiufeigo.opencodeshell.SshMenuParity2V100Test.xml` → **tests=10 failures=0 errors=0 skipped=0**

| # | 用例 | 钉住的规则 |
|---|---|---|
| 1 | `theGroupLabelLeadsTheMenuInEveryStage` | `:29` 在**全部 10 态**都是首项且只出现一次 |
| 2 | `theGroupLabelUsesTheUpstreamI18nKeyInAllThreeLocales` | 用上游键；三语文本逐字等于 `SSH server` / `SSH 服务器` / `SSH 伺服器`（防第四语系悄悄长出来） |
| 3 | `theSeparatorSitsImmediatelyAboveDelete` | `:45` 与 `Delete` **相邻**（不是「含有一个分隔线」） |
| 4 | `theSeparatorSurvivesARowWithNothingToConnect` | `ready` 行 = 标签/分隔线/删除；`connecting` 行 = 标签/连接/分隔线/删除 |
| 5 | `theSeparatorIsARealDividerNotPadding` | 必须是 `HorizontalDivider(0.5.dp)`，等高 `Spacer` 会被挡住 |
| 6 | `theEntryOrderMatchesTheUpstreamGroupForEveryStage` | 逐态写出期望顺序，**重排即失败** |
| 7 | `theMenuHandlesEveryEntryOfTheEnumeration` | `when` 覆盖 6 个枚举值，漏一个就掉一行 |
| 8 | `theUpstreamDefaultTruthTableIsPinnedButUnwired` | `:35`/`:40` 四格真值表 + 互斥性（**上游语义的可执行记录**） |
| 9 | `theDefaultEntriesAreNeverDrawn` | 全 10 态不出现；且 composable **无** `SetDefault -> DropdownMenuItem` 分支；并钉住本壳 `isDefault` 是 active-URL 判定 |
| 10 | `theDangerEntryKeptItsLookAndTheConnectGateItsRule` | v99 结论复验（危险色、只 arm、两个 `enabled`、Authenticate 文案） |

### 4.2 回归（47 类 / 412 例，0 失败）

覆盖 `Ssh*`（27）/ `Server*`（11）/ `ServersFormIme*`（5）/ `SessionTabServerPrune` /
`SettingsConnectionDelta` / `SettingsShortWindowImeV84` / `IncompatibleServerPanel` /
`Wave23aSettingsPaletteTerminal` / **`StringsLocaleParityTest`**，
外加本轮新增的 `SshMenuParity2V100Test`。

```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 7s
```

聚合 XML：**classes=47 tests=412 failures=0 errors=0 skipped=0**

### 4.3 两处既有 pin 的调整（均为**收紧或等强**，无一放宽）

1. **`SshRowMenuParityV99Test.theMenuComposesConnectBeforeDeleteAndGatesOnlyConnect`**
   —— 菜单已改为由 `sshRowMenuEntries(stage)` 驱动，故 `if (sshRowMenuShowsConnect(stage))`
   这条字面断言失效。改为**更强**的等价物：不仅断言菜单读了这条列表，还断言 stage
   **确实**改变结果（`READY` 无 `Connect`、`DISCONNECTED` 有 `Connect`）——
   原来的「body 里有个 `if`」无法证明该 `if` 生效。
   `SshRowMenuEntry.Connect` / `sshRowMenuEntries` 两个 import 同步补入。

2. **`SshTunnelNarrowStructureV93Test.noNewUserVisibleStrings`**
   —— 该 pin 是**精确集合相等**（扫 `fun SshTunnelCard(` 之后的全部 `R.string.*`），
   故补入 `ssh_server_menu_label`。
   **这不是新增文案**：该串早已三语齐备（`strings_oc_i18n.xml:1381`）且逐字等于上游
   `ssh.server.menu.label`。集合仍是**精确相等**（不是「包含」），任何真·新资源 id 仍会失败。

### 4.4 一个已修的自身失误（记录在案）

首轮编译失败于 `Syntax error: Unclosed comment` —— 原因是 KDoc 里写了
`` `ui/servers/**` ``，而 **Kotlin 块注释可嵌套**，`/**` 在注释内会被当成嵌套注释开头。
已改写为「the `ui.settings` package」。全仓扫描确认这是唯一一处
（`MarkdownRenderer.kt:357`、`SessionScreen.kt:1618` 等同类写法都在 `//` 行注释里，安全）。

---

## 5. 真机复验步骤（**未执行** — 无设备）

前置（沿用 v99 §0 的真根因）：**建条目/提交后必须先把面板滚到列表最底再 dump**，
否则会重演 `row not visible` / `kebabs=0` 而误判为菜单缺陷。

**A. 非 `ready` 态（如 `Authentication required`）—— 本轮新增能力的正向取证**
1. Servers → SSH，Add server，target 填 `127.0.0.1:2222` + 故意填错口令，提交；
2. **滚到列表最底**，轮询到行停在认证态（认证卡若上浮遮住行，先 Cancel/Trust 收回）；
3. 点 `⋮` ⇒ 断言菜单**从上到下依次为 4 项**：
   1. **分组标签 `SSH server`**（非交互、11 sp 弱化灰字）← **本轮新增**
   2. `Authenticate`（可点）
   3. **一条 0.5 dp 分隔线** ← **本轮新增**
   4. `Delete`（红色，可点）
4. 断言**不出现** `Set as default` / `Remove default`（§2 的结论：无此概念）；
5. 断言分隔线**位于 `Authenticate` 与 `Delete` 之间**，且 `Delete` 是最后一项。

**B. `ready`（`Connected`）态 —— 分隔线不随 Connect 消失**
1. 用可用凭据连上同一条目，轮询到 `Connected`；
2. 点 `⋮` ⇒ 断言菜单为 **3 项**：`SSH server`（标签）→ 分隔线 → `Delete`；
3. 关键断言：**`Connect`/`Authenticate` 不出现**（`menu.tsx:30`），但
   **分隔线仍在**且仍在 `Delete` 之上（`menu.tsx:45` 在 Connect 的 `Show` 之外）——
   这是本轮最容易被实现错掉的一格。

**C. `Connecting` 态 —— v99 结论未被 A 级改动破坏**
1. target 填黑洞地址 `ssh -p 2222 sshtest@10.255.255.1`，提交后滚到底；
2. 点 `⋮` ⇒ 断言 4 项齐全，`Reconnect` **灰显不可点**，`Delete` **可点**；
3. 点 `Delete` ⇒ 确认条出现、正文为 `This removes "<本行名>"`；确认后行消失、落库回退一条。

**D. 三语（工单的字符串纪律）**
1. 依次切 English / 简体中文 / 繁體中文，重复 A 的第 3 步；
2. 断言分组标签分别为 `SSH server` / `SSH 服务器` / `SSH 伺服器`，
   且**没有第四种语言**（缺 key 时 a11y 树里该行会退化为空/资源名）。

---

## 6. 未闭环

1. **B 级两项（`Set as default` / `Remove default`）确定不做** —— §2 的四条理由。
   语义、缺口、最小改动面见 §2.4；其中**第 1 条是一个需要人来拍板的产品决策**（启动期默认 vs 当前默认），
   定了之后 §2.4 的 2–5 才是可执行的工单。
2. **HTTP 行的 default 项本身也缺互斥**（`ServersPanel.kt:459-471` 无条件渲染、
   无 `Remove default`）—— 与上游 `row-menu.tsx:95-100` 的 `canDefault && !isDefault` /
   `canDefault && isDefault` 不一致。属 `ui/settings/**`，本轮禁改，仅记录。
3. **`dialog_server_menu_default` / `dialog_server_menu_defaultRemove` 是死键**
   （`strings_oc_i18n.xml:408-409`，三语齐备但无消费点）。若将来做 B，它们可直接复用，
   无需新增字符串。
4. **真机复验未执行**（无设备）。§5 为待执行步骤。
5. **未提交**。本仓 `app/src/main/.../ui/servers/**` 与相关测试在 git 中仍为未纳管 WIP，
   故本节以手工 diff 记录而非 `git diff`。

---

## 7. 改动文件清单

| 文件 | 性质 |
|---|---|
| `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt` | **A 级主改动**：`SshRowMenuEntry` 枚举 + `sshRowMenuEntries` 纯函数、`SshTunnelRowMenu` 改为由其驱动、新增 `SshRowMenuGroupLabel()`、补 `HorizontalDivider` import |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshMenuParity2V100Test.kt` | **新增，10 例** |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshRowMenuParityV99Test.kt` | 结构调整为更强断言（§4.3-1）+ 2 个 import |
| `app/src/test/java/com/xiufeigo/opencodeshell/SshTunnelNarrowStructureV93Test.kt` | 精确集合补入既有串 `ssh_server_menu_label`（精确性不变，§4.3-2） |

未触碰：`_upstream/**`、`app/build.gradle.kts`、`ChatViewModel.kt`、`ui/session/**`、`ui/home/**`、
`ui/terminal/**`、`ui/nav/**`、`ui/App.kt`、`OcActivity.kt`，以及 `ui/settings/**`（只读引用）。

**新增用户可见字符串：无。**（A 级复用既有串 `ssh_server_menu_label`——三语早已在
`values/` / `values-zh-rCN/` / `values-zh-rTW/` 齐备；其中文两版是本壳自己的本地化，
上游 `zh.ts` / `zht.ts` 缺该键、回落英文，见 §3.3。）
