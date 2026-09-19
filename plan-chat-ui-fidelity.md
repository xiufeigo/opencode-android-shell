# Chat Tab 无感替换规格：像素级对齐官方 v2 UI（dark 优先，兼 light）

> 依据：vendored 官方 CSS（`app/src/main/assets/opencode-app/assets/index-*.css`，token 提取方法见文末）+ 上游源码
> `packages/session-ui/src/components/{message-part,basic-tool,tool-status-title}.tsx`、
> `packages/session-ui/src/v2/components/prompt-input/index.tsx`、`packages/app/src/pages/session/timeline/message-timeline.tsx`、
> `packages/app/src/pages/session/composer/session-composer-region.tsx`。
> 只改 **聊天 tab（原生）**，不改终端 tab / WebView / patches。M1 行为（附件、@、SSE、省电）全部保留。

## 1. 设计 token（已从官方 CSS 提取，直接用）

### Dark（默认，跟随系统 values-night）
```
bg_base      #161616   (grey-1100)   屏幕/页面背景
layer_01     #242424   (grey-1000)   用户气泡、工具卡、抽屉选中项
layer_02     #2e2e2e   (grey-900)    代码块背景、工具输出背景、附件 chip
layer_03     #3a3a3a   (grey-800)
border_base  #FFFFFF1A (alpha-light-10)  0.5px~1px 边框
border_strong#FFFFFF33 (alpha-light-20)
text_base    #FAFAFA   (grey-100)    正文
text_muted   #AEAEAE   (grey-500)    次级文本
text_weak    #8F8F8F                 meta/时间（官方 --text-weak）
text_faint   #AEAEAE? → 官方 faint=grey-600=gray，用 #808080
accent       #3B5CF6   (blue-600)    发送按钮/链接/进度环
success_fg   #198B43   (green-800)   工具完成状态（dark 下官方 fg 常用亮色变体，可按下方语法色调整）
danger_fg    #B82D35   (red-800)
```
### Light（values）
```
bg_base #FFFFFF  layer_01 #FAFAFA  layer_02 #F2F2F2  layer_03 #EEEEEE
border_base #0000001A  text_base #161616  text_muted #5C5C5C  text_weak #8F8F8F
accent #3B5CF6  inline_code_fg #263FA9 (blue-900)
```
### 语法高亮（dark / light 两套，values-night）
```
property(file mention/属性) #FF9AE2 / #ED6DC8
type(agent mention/类型)    #ECF58C / #596600
string                      #00CEB9 / #006656
constant                    #93E9F6 / #007B80
primitive                   #FFBA92 / #FB4804
keyword/comment/weak        #8F8F8F(dark=weak) / #5C5C5C(light=weak)
variable/strong             #FAFAFA / #161616
punctuation/operator        text_muted
```
### 字号/字重/圆角（官方值）
```
正文 assistant: 14px, w400, line-height 1.5
user bubble 文本: 14px, pre-wrap, break-word
meta/时间: 12px, w400, text_weak
composer 输入: 13px, w500(440→500), line-height 20px(1.54), placeholder text_faint
工具卡标题: 14px, w500;  副标题: 13px, w400, text_muted;  行高 32px
markdown: p 底距 12px; li 底距 8px; ul/ol 缩进 32px(disc/decimal); h1 17/600 h2 15/600 h3 13/500;
blockquote 左 0.5px border + text_muted; pre 顶12底24 隐藏滚动条; inline code: mono, bg=前景8%混合(dark≈#FFFFFF14), 圆角4
radius: 卡片/代码块/按钮 6px(radius-md), 用户气泡 10px, composer/抽屉 10px(radius-xl), chip 全圆
阴影: composer 用 elevation-raised 等效 → 0.5px hairline border_base + 2dp 阴影
字体: sans 系统默认; mono: monospace(等宽)
```

## 2. 布局结构（照上游 DOM）

### 消息时间线（RecyclerView 多 ViewType）
- 页面 padding: `px-4`(16dp)，行间距 pt-3(12dp)；TurnGap 24dp。列表 stackFromEnd，底部留 composer 高度。
- **UserMessage 行**（ViewType USER）：
  - 容器：`flex col align-end`（右对齐），`max-width = 82%`（`maxWidth = (screenWidth*0.82)`）
  - 附件缩略图行（有附件时）：图片 58x46dp 圆角6dp hairline border_base，横向排列 gap 8dp，右边距 2dp；非图片文件 chip（layer_02 圆角6 图标+文件名 12px）
  - 气泡：bg layer_01，圆角10dp，padding 8x12，text_base 14px pre-wrap；`@file` 引用整体着色 property 色、`@agent` 着 type 色（Spannable 简单正则实现）
  - meta 行（气泡下方右对齐，12px text_weak）: `{Agent} · {modelID} · {HH:mm}`（agent 取 message.agent capitalize；model 取 message.model.modelID；无则跳过该段）
- **AssistantMessage 行**（ViewType ASSISTANT）：纵向 LinearLayout，子项间 12dp：
  - text part → Markwon markdown（14sp text_base；代码块 layer_02 圆角6 mono 12sp + Prism 语法色；inline code 如上）
  - reasoning part → 折叠卡（见下），默认收起，标题 "Thought" / 思考中 shimmer；展开正文 13sp text_muted markdown
  - tool part → **工具卡**（详见下）
  - file part → chip（layer_02，文件图标+文件名，12px）
  - 未流完（runStatus RUNNING 且最后一条 assistant 最后一 part text）→ 末尾追加打字光标 `▍`（alpha 闪烁动画 800ms）
- **工具卡**（程序化构建或独立 layout include）：
  - 容器：bg layer_01，圆角6dp，padding 10x12，左侧 2dp 竖条 `--card-accent`：running→accent蓝，error→danger，completed→layer_03（灰）
  - trigger 行高 32dp：[icon 16dp muted] 标题 14px w500 text_base（running 时 shimmer=alpha 0.55↔1 动画）[副标题 13px text_muted 截断] [右侧 chevron 16dp icon_muted，展开时旋转180°]
  - 展开内容：layer_02 圆角6，mono 12sp，max-height 220dp 内滚动，内容=state.input/output 的格式化 JSON（pretty print，key muted，string 绿）或 bash 命令文本；编辑类工具显示 diff 增删行色：add #9BCD97 / del #FAA494（dark）
  - 状态映射：`state.status`: pending/running → running（可点展开允许）；completed → done；error → 红条 + 副标题错误摘要
  - 常用工具标题文案（照官方）：bash→"Shell"、edit→"Edit"、write→"Write"、read→"Read"、glob→"Find"、grep→"Search"、task→"Task"、webfetch→"Fetch"、todowrite→"To-dos"；副标题取 input 关键字段（command/filePath/pattern/query）
- **错误卡**（message role=assistant 且含 error part / session.error）：bg layer_01 + danger 左条 + 12px danger 文本

### Composer（照 prompt-input-v2）
- 外层容器：水平 padding 12dp，底部 padding 12dp（+ bottom_nav 上方）
- 输入面板：`rounded-xl(10dp)` bg layer_01（官方 bg-base+raised；dark 下 layer_01 视觉等效更清晰，用 layer_01），0.5dp border_base，min-height 96dp：
  - 附件预览行（有附件时）：横向 ScrollView，58x46dp 缩略图/文件 chip，右上角 16dp 圆形 × 删除（bg icon_muted，白叉）
  - EditText：13sp w500 text_base，bg transparent，padding 16/16/8，min 60dp max 180dp 自适应高，hint text_faint，多行，`@`-mention 着色（file→property、agent→type，Spannable）
  - 工具行 h 44dp padding 8：
    - 左：[+ 附加按钮 28dp 圆角6 icon_muted]（点击沿用 SAF launcher）；[模型 chip：16dp 图标 + 当前模型名 13px text_base 截断 max 220dp + ▾]（M1 静态展示 message 默认模型名，不可点或点了 toast "选择模型 M2"）
    - 右：发送按钮 28dp 圆角6 bg layer_03 icon text_base；RUNNING 时变为 [停止方块 icon danger_fg]（照官方 stopping 状态）；disabled opacity 50%
- 官方 placeholder: "Plan, build, and fix anything"（照抄 en 文案）

### Header（照 session-header 简化）
- 高 48dp，bg_base：左 [☰ 24dp icon_base → 打开会话抽屉]，中偏左 [会话标题 14px w500 截断]（无会话="New session"），右 [上下文环 16dp：灰环+accent 进度弧，M1 静态 0%]
- header 与列表之间 0.5dp 分隔线 border_base

### 会话抽屉（DrawerLayout 左侧 78% max 320dp）
- bg_base，右缘 0.5dp border_base
- 顶部：+ New session 行（高44dp icon+14px w500），下方 0.5dp 分隔
- 会话行：高 48dp padding 16：标题 14px text_base 截断 + 右侧相对时间 12px text_weak；选中项 bg layer_01；点击→切会话
- 数据：`client.listSessions()` 按 updatedAt 降序（已有模型字段）；切会话调 `viewModel.observeSession(id)`（已有，会重拉快照）；抽屉打开时刷新
- 新会话：`viewModel.newSession()`（observeSession(null) 清空 state，下条消息触发 createSession——现有行为）

## 3. 实现约束
- 全部走 ViewBinding + 现有 `ChatView`/`ChatViewModel`/`ChatStore` 架构；**不要**改 OpenCodeClient/EventStream/ChatStore 数据层语义（可加只读方法如 listSessions 包装）。
- RecyclerView ViewTypes: USER=1, ASSISTANT=2；ASSISTANT 行内 parts 程序化创建（LinearLayout 容器），工具卡展开状态 key=part.id 存在 adapter 内 Map<String,Boolean>。
- DiffUtil 对 assistant 行用 contents 比较（parts List data class 已可比较）；工具卡展开不影响 item 内容比较（展开态存 adapter，不进 model）。
- 光标/shimmer 动画在 onViewRecycled 停止；RUNNING 才启动。
- 主题色全部走 res color 资源（values/values-night），禁止硬编码 hex 在 kt/layout。
- 抽屉用 `androidx.drawerlayout.widget.DrawerLayout`（appcompat 传递依赖已含）；composer 替换 `view_chat.xml` 的旧 composer_bar 结构（btn_attach/btn_send/composer_input id 保留可复用逻辑，但布局重排）。
- 保留：chatError 顶栏（样式改为官方 error 卡：layer_01 + danger 左条）、附件 CAS、mention 防抖、IDLE grace、clearError、dismissMentionIfShowing。
- 既有测试（ChatStoreTest 等）不许红；MainActivity 只允许动 strings 引用，不动生命周期逻辑。
- Markwon 语法高亮依赖：`io.noties.markwon:syntax-highlight:4.6.2`（含 prism4j）。若引包冲突可在 build.gradle 只加该一行。
- i18n：新文案进 strings.xml（英文），chat_role_user 等旧 key 处理好引用。

## 4. Token 提取方法（需要更多值时自查）
```python
import pathlib,re
css="".join(p.read_text() for p in pathlib.Path("app/src/main/assets/opencode-app/assets").glob("index-*.css"))
m=re.search(r'--v2-XXXX:\s*([^;}]+)',css); print(m.group(1))
# dark scope: 以 [data-color-scheme=dark] 块内为准
```

## 5. 验收（reviewer 按此核对）
- dark 下：屏幕 #161616；用户右对齐灰泡(#242424 圆角10)；工具卡左色条+32dp行+chevron；composer 圆角10 #242424 + [+|模型▾|→] 工具行；@提及粉/黄绿着色；代码块 #2e2e2e mono。
- light 同结构换 values。无 hex 硬编码散落 kt/xml（全走 color 资源）。
- 旧功能回归：附件、@、发送/中止、错误显示、返回键。
