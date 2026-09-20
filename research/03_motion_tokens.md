# opencode v2 前端 — 动效(Motion)与设计 Token 数值清单

> 目标：为 Android 原生 (Jetpack Compose) 1:1 复刻提供**可直接落地的具体数值**。
>
> **数据来源（权威顺序）**
> 1. 本地 vendored 官方构建产物 `app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css`（491,838 bytes，单行 minified，**CSS 全部 token 与 keyframes 都在此文件**）
> 2. 同目录 `index-CKBO9Mty.js`（2,765,259 bytes，读 **运行时** motion 参数 / spring / 组件结构）
> 3. 同目录 `index-GGAIvKVQ.css`（17,263 bytes，v2 loader）、`dialog-command-palette-v2-CTGIdC2q.css` 等分包 CSS
> 4. 字体文件实测存在：`Inter.ttf`(874,708 B)、`JetBrainsMonoNerdFontMono-Regular.woff2`(1,060,580 B)
>
> **GitHub 侧核实失败（已如实记录，非臆测）**
> - `https://github.com/anomalyco/opencode/tree/main/packages` → **HTTP 404**（默认分支不是 `main`）
> - `https://api.github.com/repos/anomalyco/opencode/contents/packages` → **HTTP 403**（未认证 API 限流）
> - `https://grep.app/api/search?...` → **HTTP 429**
> - `https://github.com/anomalyco/opencode/tree/dev/packages` → HTTP 200 但页面为 JS 渲染，**未取到文件清单**
> - 因此本文件**全部数值取自上面第 1–3 项构建产物**，未使用任何推测值。构建产物即官方 tag 的产物，数值可信。
> - 参考（仅用于确认仓库存在/默认分支为 `dev`）：[anomalyco/opencode](https://github.com/anomalyco/opencode)、[packages @ dev](https://github.com/anomalyco/opencode/tree/dev/packages)
>
> 应用版本常量（JS 内）：`e$="1.17.19"`。

---

## 0. 主题与 Token 生效机制（Compose 复刻前必读）

CSS 中 token 分层如下（按文件内出现顺序，后者覆盖前者）：

| 层级 | 选择器/位置 | 作用 |
|---|---|---|
| ① 基础调色板 | `:root` @offset 45783 | `--v2-grey-*` / `--v2-alpha-light-*` / `--v2-alpha-dark-*` / `--v2-{red,orange,yellow,green,cyan,blue,purple,pink}-{100..1200}`（**唯一一套，不随主题变**） |
| ② 旧主题默认 | `:root` @offset 37524 | 旧 token 体系（`--background-base` / `--surface-*` / `--syntax-*` 等），非 v2 |
| ③ v2 语法色覆盖 | `[data-theme=oc-2]` @offset 49343 | 用 `light-dark()` 覆盖 `--syntax-*`、`--text-diff-*` |
| ④ **v2 语义 token 默认（= light）** | `:root` @offset 49785（`color-scheme:light`） | `--v2-background-*` / `--v2-text-*` / ... 全部 v2 语义 token |
| ⑤ 显式 light | `[data-color-scheme=light]` @offset 54785 | 与 ④ **完全等价**（重复声明） |
| ⑥ **显式 dark** | `[data-color-scheme=dark]` @offset 59726 | v2 语义 token 的 dark 覆盖 |
| ⑦ 动画工具类 | `:root` @offset 379468 | `--animate-pulse` / `--animate-pulse-scale` |

**关键结论（给 Compose 的映射建议）**
- 判定"light/dark"看 `[data-color-scheme]`，**dark 是在 `:root`(light) 之上做覆盖**。Compose 里做两套 `object V2TokensLight / V2TokensDark` 即可，数值见下。
- ③ 里的 `light-dark()` 由 CSS `color-scheme` 决定解析成哪个值，**它跟随 `[data-color-scheme]`**，所以语法色也是两套。
- Token 总数：`--v2-*` 共 **240 个**唯一名。

---

## 1. 设计 Token 数值清单

### 1.1 基础调色板（light/dark 共用）

**Grey**
| token | hex |
|---|---|
| `--v2-grey-50` | `#fff` |
| `--v2-grey-100` | `#fafafa` |
| `--v2-grey-200` | `#f2f2f2` |
| `--v2-grey-300` | `#eee` |
| `--v2-grey-400` | `#dbdbdb` |
| `--v2-grey-500` | `#aeaeae` |
| `--v2-grey-600` | `gray` = `#808080` |
| `--v2-grey-700` | `#5c5c5c` |
| `--v2-grey-800` | `#3a3a3a` |
| `--v2-grey-900` | `#2e2e2e` |
| `--v2-grey-1000` | `#242424` |
| `--v2-grey-1100` | `#161616` |
| `--v2-grey-1200` | `#080808` |

**Alpha-Dark（黑，N = N% 不透明度）**
```
100 #000    90 #000000e5  80 #000c    70 #000000b2  60 #0009
50  #00000080  40 #0006   30 #0000004d 24 #0000003d 20 #0003
16  #00000029 14 #00000024 12 #0000001f 10 #0000001a 8 #00000014
6   #0000000f  4 #0000000a  2 #00000005  0 #0000
```
**Alpha-Light（白，N = N% 不透明度）**
```
100 #fff    90 #ffffffe5  80 #fffc    70 #ffffffb2  60 #fff9
50  #ffffff80  40 #fff6   30 #ffffff4d 24 #ffffff3d 20 #fff3
16  #ffffff29 14 #ffffff24 12 #ffffff1f 10 #ffffff1a 8 #ffffff14
6   #ffffff0f  4 #ffffff0a  2 #ffffff05  0 #fff0
```

**色阶**（`red / orange / yellow / green / cyan / blue / purple / pink` × `100…1200`）
```
red     100 #fceceb 200 #f6d5d3 300 #f2bbb7 400 #f29b96 500 #f17471 600 #f1484f
        700 #d92e3c 800 #b82d35 900 #97252b 1000 #7a1f23 1100 #5f1a1c 1200 #461516
orange  100 #fdf2ed 200 #ffe7dc 300 #ffd8c6 400 #ffc1a4 500 #ffa478 600 #ff8648
        700 #ee7330 800 #d16427 900 #b35624 1000 #954c27 1100 #723d22 1200 #5a2c14
yellow  100 #fefaec 200 #fcefd0 300 #f7e5b5 400 #f3da9b 500 #f2cf76 600 #f6c251
        700 #e7af36 800 #cb9f34 900 #ac8833 1000 #8e7231 1100 #68552b 1200 #4b4025
green   100 #e7f9ea 200 #d0f0d5 300 #b8e9c1 400 #96e3a6 500 #6bd586 600 #49c970
        700 #2eaf5a 800 #198b43 900 #1d783c 1000 #196130 1100 #164c26 1200 #14361d
cyan    100 #e2f7fb 200 #c4edf4 300 #a3e4ef 400 #65d9eb 500 #00c5df 600 #00abcf
        700 #0096b8 800 #007d9b 900 #006c85 1000 #005a6e 1100 #004756 1200 #00353f
blue    100 #ecf1fe 200 #d7e2fc 300 #c3d4fd 400 #a2bcff 500 #7698fd 600 #3b5cf6
        700 #3250df 800 #2c47c8 900 #263fa9 1000 #22388f 1100 #1c2e70 1200 #1b2852
purple  100 #ebecfe 200 #d5d5fc 300 #b9b8f5 400 #9e99f7 500 #8271f8 600 #7152f4
        700 #623be2 800 #5230c2 900 #442aa1 1000 #361f83 1100 #2b1b6a 1200 #221358
pink    100 #fdecf3 200 #f7d5e4 300 #fabcd8 400 #f799c6 500 #f26cb2 600 #f64aab
        700 #e4429e 800 #c83d8b 900 #aa3576 1000 #8c2d61 1100 #6f284f 1200 #5c1d3f
```

### 1.2 背景层 `--v2-background-*`

| token | light | dark | 解析值 light / dark |
|---|---|---|---|
| `bg-base` | `grey-50` | `grey-1100` | `#fff` / `#161616` |
| `bg-deep` | `grey-100` | `grey-1200` | `#fafafa` / `#080808` |
| `bg-layer-01` | `grey-100` | `grey-1000` | `#fafafa` / `#242424` |
| `bg-layer-02` | `grey-200` | `grey-900` | `#f2f2f2` / `#2e2e2e` |
| `bg-layer-03` | `grey-300` | `grey-800` | `#eee` / `#3a3a3a` |
| `bg-layer-04` | `grey-400` | `grey-700` | `#dbdbdb` / `#5c5c5c` |
| `bg-inverse` | `grey-1100` | `grey-50` | `#161616` / `#fff` |
| `bg-contrast` | `grey-1000` | `grey-700` | `#242424` / `#5c5c5c` |
| `bg-button-neutral` | `grey-50` | `alpha-light-6` | `#fff` / `#ffffff0f` |
| `bg-accent` | `blue-600` | `blue-600` | `#3b5cf6` / `#3b5cf6` |

### 1.3 文本 `--v2-text-*`

| token | light | dark |
|---|---|---|
| `text-base` | `grey-1100` `#161616` | `grey-100` `#fafafa` |
| `text-muted` | `grey-700` `#5c5c5c` | `grey-500` `#aeaeae` |
| `text-faint` | `grey-600` `#808080` | `grey-600` `#808080` |
| `text-inverse` | `grey-50` `#fff` | `grey-1100` `#161616` |
| `text-contrast` | `grey-50` `#fff` | `grey-50` `#fff` |
| `text-accent` | `blue-600` `#3b5cf6` | `blue-400` `#a2bcff` |
| `text-accent-hover` | `blue-700` `#3250df` | `blue-300` `#c3d4fd` |
| `text-code-accent` | `blue-900` `#263fa9` | `blue-400` `#a2bcff` |

### 1.4 图标 `--v2-icon-*`

| token | light | dark |
|---|---|---|
| `icon-base` | `grey-800` `#3a3a3a` | `grey-400` `#dbdbdb` |
| `icon-muted` | `grey-600` `#808080` | `grey-600` `#808080` |
| `icon-inverse` | `grey-50` `#fff` | `grey-1100` `#161616` |
| `icon-contrast` | `grey-100` `#fafafa` | `grey-100` `#fafafa` |
| `icon-accent` | `blue-600` `#3b5cf6` | `blue-400` `#a2bcff` |
| `icon-accent-hover` | `blue-700` `#3250df` | `blue-300` `#c3d4fd` |

### 1.5 边框 `--v2-border-*`

| token | light | dark |
|---|---|---|
| `border-muted` | `alpha-dark-8` `#00000014` | `alpha-light-8` `#ffffff14` |
| `border-base` | `alpha-dark-10` `#0000001a` | `alpha-light-10` `#ffffff1a` |
| `border-strong` | `alpha-dark-20` `#0003` | `alpha-light-20` `#fff3` |
| `border-inverse` | `grey-1000` `#242424` | `grey-100` `#fafafa` |
| `border-focus` | `blue-500` `#7698fd` | `blue-500` `#7698fd` |

典型用法：`border: .5px solid var(--v2-border-border-base)`（**半像素边框**，Compose 里用 `Modifier.border(0.5.dp)` 或 `drawBehind` 0.5dp 描边）。
焦点环：`outline: 2px solid var(--v2-border-border-focus); outline-offset: 2.5px;`（button-v2 / icon-button-v2）；split-button 用 `outline-offset: -2px`。
1px inset 写法也大量出现：`box-shadow: inset 0 0 0 0.5px var(--v2-border-border-muted)`。

### 1.6 状态色 `--v2-state-*`

| 语义 | light bg / fg / border | dark bg / fg / border |
|---|---|---|
| success | `#e7f9ea` / `#198b43` / `#b8e9c1` | `#14361d` / `#6bd586` / `#1d783c` |
| warning | `#fefaec` / `#cb9f34` / `#f7e5b5` | `#4b4025` / `#f2cf76` / `#ac8833` |
| danger | `#fceceb` / `#b82d35` / `#f2bbb7` | `#461516` / `#f17471` / `#97252b` |
| info | `#ecf1fe` / `#2c47c8` / `#c3d4fd` | `#1b2852` / `#7698fd` / `#263fa9` |

原始映射：
- light：bg = `{green,yellow,red,blue}-100`，fg = `-800`，border = `-300`
- dark：bg = `-1200`，fg = `-500`，border = `-900`

### 1.7 Agent 类型着色 `--v2-agent-*`

CSS 只给了 5 个 agent 语义槽（plan / build / explore / review / writer），**review 与 writer 无 border/background 定义**。

| agent | light solid / border / background | dark solid / border / background |
|---|---|---|
| plan | `#c83d8b` / `#c83d8b33` / `#fdecf31a` | `#f799c6` / `#f799c633` / `#aa35760d` |
| build | `#2c47c8` / `#2c47c81a` / `#ecf1fe1a` | `#c3d4fd` / `#a2bcff33` / `#263fa90d` |
| explore | `#ac8833` / `#cb9f3433` / `#fefaec1a` | `#f7e5b5` / `#f3da9b33` / `#ac88330d` |
| review | `#198b43`（green-800） | `#b8e9c1`（green-300） |
| writer | `#623be2`（purple-700） | `#9e99f7`（purple-400） |

> 注意：background 值是 **8 位 hex 带 alpha**（`#fdecf31a` = pink-100 @ 10%）。task-tool-card 里用 `--task-agent-color` 变量注入（`color: var(--task-agent-color, ...)`），即 agent 色**由 JS 在每个卡片上以内联变量下发**，CSS 只是 fallback。

**头像色 `--v2-avatar-*`**（bg / border）：light `orange #ee7330/#d16427`、`yellow #e7af36/#cb9f34`、`cyan #0096b8/#007d9b`、`green #2eaf5a/#198b43`、`red #d92e3c/#b82d35`、`pink #e4429e/#c83d8b`、`blue #3250df/#2c47c8`、`purple #623be2/#5230c2`、`gray #5c5c5c/#3a3a3a`；fg 恒为 `#fff`。dark 变体见 CSS（如 `orange #723d22/#ff8648`、`gray #5c5c5c/#aeaeae`）。

### 1.8 语法色（`--syntax-*`，由 `[data-theme=oc-2]` 定义）

`[data-theme=oc-2]` 用 `light-dark()`；下列左侧=light，右侧=dark。

| token | light | dark |
|---|---|---|
| comment | `#7a7a7a` | `#8f8f8f` |
| keyword | `#a753ae` | `#edb2f1` |
| string | `#00ceb9` | `#00ceb9`（两套相同） |
| primitive | `#034cff` | `#8cb0ff` |
| property | `#a753ae` | `#fab283` |
| type | `#8a6f00` | `#fcd53a` |
| critical | `#ff8c00` | `#fab283` |
| text-diff-add | `#167517` | `#c4ffc0` |
| text-diff-delete | `#fa3012` | `#ec2f14` |

> ⚠️ `constant` 在 `oc-2` 里**未覆盖**；它继承旧主题 `:root`（37524 块 dark 定义）：light `#007b80` / dark `#93e9f6`。旧主题完整一套（供 fallback 参考）：
> light `string #006656`、`primitive #fb4804`、`property #ed6dc8`、`type #596600`、`constant #007b80`、`keyword var(--text-weak)`、`variable var(--text-strong)`；
> dark `string #00ceb9`、`primitive #ffba92`、`property #ff9ae2`、`type #ecf58c`、`constant #93e9f6`、`keyword var(--text-weak)`、`operator var(--text-weak)`、`punctuation var(--text-weak)`、`variable/object var(--text-strong)`。

### 1.9 海拔 / 阴影 `--v2-elevation-*` 与 `--shadow-*`

**v2 海拔（全部展开为具体 rgba）**

| token | light | dark |
|---|---|---|
| `elevation-raised` | `0 2px 4px 0 #0000000a, 0 1px 2px -1px #00000014, 0 0 0 .5px #0000001f, 0 0 0 0 #0000` | `0 2px 4px 0 #0000004d, 0 1px 2px 0 #0000004d, 0 0 0 .5px #ffffff29, 0 -.5px 0 0 #ffffff0f` |
| `elevation-floating` | `0 8px 16px 0 #0000000a, 0 4px 8px 0 #00000014, 0 0 0 .5px #0000001f, 0 0 0 0 #0000` | `0 8px 16px 0 #0000004d, 0 4px 8px 0 #0000004d, 0 0 0 .5px #ffffff29, 0 -.5px 0 0 #ffffff0f` |
| `elevation-overlay` | `0 16px 32px 0 #0000000a, 0 8px 16px 0 #00000014, 0 0 0 .5px #0000001f, 0 0 0 0 #0000` | `0 16px 32px 0 #0000004d, 0 8px 16px 0 #0000004d, 0 0 0 .5px #ffffff29, 0 -.5px 0 0 #ffffff0f` |
| `elevation-button-neutral` | `0 1px 1.5px 0 #0000001a, 0 0 0 .5px #00000024, 0 0 0 0 #0000` | `0 1px 2px 0 #0006, 0 0 0 .5px #fff3, 0 -.5px 0 0 #ffffff1a` |
| `elevation-button-contrast` | `0 1px 1.5px 0 #0003, 0 0 0 .5px #3a3a3a, inset 0 1px 2px 0 #ffffff24, inset 0 -1px 2px 0 #0000000f, 0 0 0 0 #0000` | `0 1px 2px 0 #0006, 0 0 0 .5px #fff6, inset 0 0 0 0 #fff0, inset 0 0 0 0 #fff0, 0 -.5px 0 0 #ffffff4d` |
| `elevation-elements` | `0 .5px .5px 0 #0006` | `0 .5px .5px 0 #0006` |
| `elevation-switch-off` | `inset 0 1px 1px 0 #00000014, inset 0 .5px .5px 0 #00000014, inset 0 0 0 .5px #0000001a` | `inset 0 -.5px 0 0 #ffffff1a, inset 0 0 0 0 #fff0, inset 0 0 0 .5px #ffffff29` |
| `elevation-switch-on` | `inset 0 2px 2px 0 #0000001a, inset 0 1px 1px 0 #0000001a, inset 0 0 0 .5px #0003` | 同 `switch-off`（dark 下两者相同） |

**旧 `--shadow-*`（非 v2 组件仍在用，dialog/toast 等）**
```
--shadow-xs     0 1px 2px -.5px light-dark(#0000000a,#0000000f), 0 .5px 1.5px 0 light-dark(#00000006,#00000014), 0 1px 3px 0 light-dark(#0000000d,#0000001a)
--shadow-md     0 6px 12px -2px light-dark(#00000013,#0000001a), 0 4px 8px -2px light-dark(#00000013,#00000026), 0 1px 2px light-dark(#0000001a,#00000026)
--shadow-lg     0 16px 48px -6px light-dark(#0000000d,#00000026), 0 6px 12px -2px light-dark(#00000006,#0000001a), 0 1px 2.5px light-dark(#00000006,#0000001a)
--shadow-xxs-border 0 0 0 .5px var(--border-weak-base,#00000012)
--shadow-xs-border  0 0 0 1px var(--border-base,#0b060033), 0 1px 2px -1px #1310100a, 0 1px 2px 0 #1310100f, 0 1px 3px 0 #13101014
--shadow-xs-border-base 0 0 0 1px var(--border-weak-base,#1100001f), 0 1px 2px -1px #1310100a, 0 1px 2px 0 #1310100f, 0 1px 3px 0 #13101014
--shadow-xs-border-select 0 0 0 3px var(--border-weak-selected,#0167ff4a), 0 0 0 1px var(--border-selected,#004afffc), 0 1px 2px -1px #13101040, 0 1px 2px 0 #13101014, 0 1px 3px 0 #1310101f
--shadow-xs-border-focus 0 0 0 1px var(--border-base,#0b060033), 0 1px 2px -1px #13101040, 0 1px 2px 0 #13101014, 0 1px 3px 0 #1310101f, 0 0 0 2px var(--background-weak,#f1f0f0), 0 0 0 3px var(--border-selected,#004afffc)
--shadow-xs-border-hover 0 0 0 1px var(--border-weak-selected,#0070ff38), 0 1px 2px -1px #1310100a, 0 1px 2px 0 #1310100f, 0 1px 3px 0 #13101014
--shadow-xs-border-critical-base 0 0 0 1px var(--border-critical-selected,#fc543a)
--shadow-lg-border-base 0 0 0 1px var(--border-weak-base,#00000012), 0 36px 80px 0 #00000008, 0 13.141px 29.201px 0 #0000000a, 0 6.38px 14.177px 0 #0000000d, 0 3.127px 6.95px 0 #0000000f, 0 1.237px 2.748px 0 #00000017
```

### 1.10 圆角 / 间距 / 字号 / 行高 / 字重 / 字距

**圆角**（Tailwind `--radius-*`，`1rem = 16px`）
```
--radius-xs  .125rem = 2px
--radius-sm  .25rem  = 4px
--radius-md  .375rem = 6px
--radius-lg  .5rem   = 8px
--radius-xl  .625rem = 10px
```
组件内还硬编码：`4px`(tooltip / small icon-button / small button)、`6px`(button-v2、`[data-component=menu-v2-content][data-slot=select-v2-content]`、icon-button normal)、`8px`(settings server dialog)、`999px`(提问进度条、radio)。
抽屉/滚动条圆角：`var(--radius-lg) var(--radius-lg)`（toast 进度条）。

**间距**：`--spacing: .25rem` = **4px**（Tailwind 间距基准单位）。

**字号**
```
--font-size-small   13px
--font-size-base    14px
--font-size-large   16px
--font-size-x-large 20px
```
Tailwind 别名：`--text-sm = var(--font-size-small)`、`--text-base = var(--font-size-base)`、`--text-lg = var(--font-size-large)`。
组件内硬编码字号：tool-tool-title `14px`、button-v2 `13px`、tooltip-v2 `11px`、dialog-title `var(--font-size-large)`=16px、dialog-description `14px`。

**行高**
```
--line-height-normal   130%
--line-height-large    150%
--line-height-x-large  180%
--line-height-2x-large 200%
```

**字重**
```
--font-weight-regular 400
--font-weight-medium  500
```
组件内硬编码 **`font-weight: 530`**（button-v2、tooltip-v2 — Inter 可变字重轴，Compose 里需 `FontWeight(530)` 且加载可变字体）。

**字距**
```
--letter-spacing-normal   0
--letter-spacing-tight    -.16      （= -0.16px 量级）
--letter-spacing-tightest -.32
```
组件内硬编码：button-v2 `letter-spacing: -.04px`、tooltip-v2 `letter-spacing: .05px`。
`font-variant-numeric: tabular-nums` 在 button-v2 / tooltip-v2 / progress-value-label 上启用。

### 1.11 字体（结论明确）

| 用途 | family | 证据 |
|---|---|---|
| UI sans | **`"Inter", sans-serif`** | `--font-family-text:"Inter",sans-serif`；`--v2-font-family-sans:"Inter",sans-serif`；`@font-face{font-family:Inter;src:url(../assets/Inter.ttf);font-weight:100 900}` |
| 代码/等宽（通用） | `--font-family-mono` = `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace` | **注意：不是 JetBrains** |
| 终端 (terminal panel) | **`"JetBrainsMono Nerd Font Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`** | JS 常量 `jbe`；`@font-face{font-family:JetBrainsMono Nerd Font Mono;src:url(../assets/JetBrainsMonoNerdFontMono-Regular.woff2);font-weight:400}` |
| 数学 (KaTeX) | `KaTeX_AMS / Caligraphic / Fraktur / Main / Math / SansSerif / Script / Size1-4 / Typewriter` | 17 条 `@font-face` |

> **给 Android 的结论**：sans = **Inter 确认**（可变字重 100–900，需支持 `wght 530`）；mono 有**两套**：通用等宽走系统 mono 栈（Android 上即 `FontFamily.Monospace`），**终端**才用 JetBrains Mono Nerd Font Mono。不要把两者混为 `--font-family-mono = JetBrains`。

---

## 2. 动效 / 动画全清单

### 2.1 CSS 中全部 72 个 `@keyframes`（含具体数值）

**通用进/出场（scale + opacity）**
```css
@keyframes dropdown-menu-open  { 0%{opacity:0;transform:scale(.96)} to{opacity:1;transform:scale(1)} }
@keyframes dropdown-menu-close { 0%{opacity:1;transform:scale(1)}  to{opacity:0;transform:scale(.96)} }
@keyframes hover-card-open     { 0%{opacity:0;transform:scale(.96)} to{opacity:1;transform:scale(1)} }
@keyframes hover-card-close    { 0%{opacity:1;transform:scale(1)}  to{opacity:0;transform:scale(.96)} }
@keyframes popover-open        { 0%{opacity:0;transform:scale(.96)} to{opacity:1;transform:scale(1)} }
@keyframes popover-close       { 0%{opacity:1;transform:scale(1)}  to{opacity:0;transform:scale(.96)} }
@keyframes tooltipV2In         { 0%{opacity:0;transform:scale(.96)} to{opacity:1;transform:scale(1)} }
@keyframes tooltipV2Out        { 0%{opacity:1;transform:scale(1)}  to{opacity:0;transform:scale(.96)} }
@keyframes sessionTabPopoverIn { 0%{opacity:0;transform:scale(.96)} to{opacity:1;transform:scale(1)} }
@keyframes sessionTabPopoverOut{ 0%{opacity:1;transform:scale(1)}  to{opacity:0;transform:scale(.96)} }
@keyframes menu-v2-in          { 0%{opacity:0;transform:scale(.96)} to{opacity:1;transform:scale(1)} }
@keyframes select-open         { 0%{opacity:0;transform:scale(.95)} to{opacity:1;transform:scale(1)} }
@keyframes contentShow         { 0%{opacity:0;transform:scale(.98)} to{opacity:1;transform:scale(1)} }
@keyframes contentHide         { 0%{opacity:1;transform:scale(1)}  to{opacity:0;transform:scale(.98)} }
@keyframes overlayShow         { 0%{opacity:0} to{opacity:1} }
@keyframes overlayHide         { 0%{opacity:1} to{opacity:0} }
@keyframes contextMenuContentShow { 0%{opacity:0;transform:scaleY(.95)} to{opacity:1;transform:scaleY(1)} }
@keyframes contextMenuContentHide { 0%{opacity:1;transform:scaleY(1)} to{opacity:0;transform:scaleY(.95)} }
```

**select-v2 专用（垂直位移）**
```css
@keyframes select-v2-content-in  { 0%{opacity:0;transform:translateY(-2px)} to{opacity:1;transform:translateY(0)} }
@keyframes select-v2-content-out { 0%{opacity:1;transform:translateY(0)} to{opacity:0;transform:translateY(-2px)} }
```

**Toast / 折叠 / 其它**
```css
@keyframes toastPopIn    { 0%{opacity:0;transform:translateY(20px)} to{opacity:1;transform:translateY(0)} }
@keyframes toastPopOut   { 0%{opacity:1;transform:translateY(0)} to{opacity:0;transform:translateY(20px)} }
@keyframes toastSwipeOut { 0%{transform:translate(var(--kb-toast-swipe-end-x))} to{transform:translate(100%)} }
@keyframes slideDown { 0%{height:0} to{height:var(--kb-collapsible-content-height)} }
@keyframes slideUp   { 0%{height:var(--kb-collapsible-content-height)} to{height:0} }
@keyframes fadeUp    { 0%{opacity:0;transform:translateY(5px)} to{opacity:1;transform:translateY(0)} }
@keyframes scroll    { 0%{--bottom-fade:20px} 90%{--bottom-fade:20px} to{--bottom-fade:0} }
@keyframes enter / exit  /* Tailwind 变量驱动，见下 */
```

**循环/状态类（重点）**
```css
@keyframes blink { 0%,50%{opacity:1} 51%,100%{opacity:0} }
@keyframes pulse-opacity       { 0%,100%{opacity:.4}  50%{opacity:1} }
@keyframes pulse-opacity-dim   { 0%,100%{opacity:.15} 50%{opacity:.35} }
@keyframes pulse-scale         { 0%,100%{transform:scale(1)} 50%{transform:scale(.666667)} }
@keyframes stop-pulse          { 0%,100%{transform:scale(.95)} 50%{transform:scale(1.12)} }
@keyframes progress-indeterminate { 0%{transform:translate(-100%)} to{transform:translate(300%)} }
@keyframes titlebar-update-loader-spin { to{transform:rotate(360deg)} }
@keyframes loader-v2-spin              { to{transform:rotate(360deg)} }   /* index-GGAIvKVQ.css */
@keyframes text-shimmer-sweep  { 0%{background-position:100% 0,0 0} to{background-position:0 0,0 0} }
```

**滚动边缘渐隐（scroll timeline 驱动，非时间驱动）** — 8 个 `visibility` 关键帧：
```
home-projects-fade-top / -bottom、titlebar-tab-fade-left / -right、
prompt-attachments-fade-left / -right、prompt-input-v2-attachments-fade-left / -right、
session-review-v2-sidebar-tree-fade-top / -bottom
/* 全部为 { 0%{visibility:hidden} to{visibility:visible} } 或反向 */
```

**会话进度点（25 个）** — `session-progress-indicator-v2-dot-0 … -24`
全部为 9 段 `opacity` 关键帧（0 / 12.5 / 25 / 37.5 / 50 / 62.5 / 75 / 87.5 / 100%），基线 `opacity:.2`，峰值 `1 / .75 / .5` 依索引错相。示例：
```css
/* dot-0  */ 0%,12.5%,25%,62.5%,75%,87.5%,100%{opacity:.2} 37.5%{opacity:1} 50%{opacity:.5}
/* dot-12 */ 全段 opacity:1
/* dot-17 */ 0%{opacity:1} 12.5%{.75} 25%..87.5%{opacity:.2} 100%{opacity:1}
```
> 实现语义：25 点**跑马灯**，索引越大相位越靠后；`data-dot="12"`（正中）在 reduced-motion 下恒 `opacity:1`。

**Tailwind `enter` / `exit`（通用）**
```css
@keyframes enter { 0%{opacity:var(--tw-enter-opacity,1);
  transform:translate3d(var(--tw-enter-translate-x,0),var(--tw-enter-translate-y,0),0)
            scale3d(var(--tw-enter-scale,1)×3) rotate(var(--tw-enter-rotate,0));
  filter:blur(var(--tw-enter-blur,0))} }
@keyframes exit  { to{ 同上，用 --tw-exit-* } }
.animate-in { animation:enter var(--tw-animation-duration,var(--tw-duration,.15s))
             var(--tw-ease,ease) var(--tw-animation-delay,0s) var(--tw-animation-iteration-count,1)
             var(--tw-animation-direction,normal) var(--tw-animation-fill-mode,none) }
```
默认时长 **150ms**，缓动 `ease`。

### 2.2 `--animate-*` token（只有 2 个）

```css
:root{ --animate-pulse:        pulse-opacity 2s   ease-in-out infinite;
       --animate-pulse-scale:  pulse-scale   1.2s ease-in-out infinite }
```

### 2.3 重点动效 #1：tool 卡 running 时标题的 shimmer ✅ 已确认

**触发**：JS 中 `Ns`（TextShimmer 组件）被用于工具标题，`active` 由运行态推导：
```js
S(I, f(Ns,{ get text(){return x().title}, get active(){return s()} }))   // tool-trigger → basic-tool-tool-title
S(d, f(Ns,{ get text(){return t.t("ui.tool.shell")}, get active(){return n()} }))  // shell
S(m, f(Ns,{ get text(){return t.t("ui.messagePart.title.edit")}, get active(){return o()} })) // edit/write
```
`Ns` 内部：`o=220`（ms），`--text-shimmer-swap: ${o}ms`、`--text-shimmer-index: ${offset}`；离开 active 后 **220ms** 再停动画（`setTimeout`）。

**DOM 结构**（每个字符一份）：
```html
<span data-component="text-shimmer" data-active="true|false" style="--text-shimmer-swap:220ms;--text-shimmer-index:N">
  <span data-slot="text-shimmer-char">
    <span data-slot="text-shimmer-char-base"    aria-hidden="true"></span>
    <span data-slot="text-shimmer-char-shimmer" aria-hidden="true" data-run="true"></span>
  </span>
</span>
```

**CSS 变量（全部具体值）**
```css
--text-shimmer-step:      45ms      /* 逐字延迟步长 */
--text-shimmer-duration:  1.2s
--text-shimmer-swap:      .22s      /* JS 覆盖为 220ms */
--text-shimmer-index:     0         /* JS 按字符索引覆盖 */
--text-shimmer-angle:     90deg
--text-shimmer-spread:    5.2ch
--text-shimmer-size:      360%
--text-shimmer-base-color: var(--text-weak)
--text-shimmer-peak-color: var(--text-strong)
--text-shimmer-sweep: linear-gradient(var(--text-shimmer-angle), transparent calc(50% - var(--text-shimmer-spread)), var(--text-shimmer-peak-color) 50%, transparent calc(50% + var(--text-shimmer-spread)))
--text-shimmer-base:  linear-gradient(var(--text-shimmer-base-color), var(--text-shimmer-base-color))
```

**动画规则**
```css
[data-component=text-shimmer] [data-slot=text-shimmer-char-shimmer]{
  color:#0000; -webkit-text-fill-color:transparent;
  background-image:var(--text-shimmer-sweep), var(--text-shimmer-base);
  background-size: var(--text-shimmer-size) 100%, 100% 100%;
  background-position:100% 0, 0 0; background-repeat:no-repeat;
  -webkit-background-clip:text; background-clip:text;
}
[data-component=text-shimmer] [data-slot=text-shimmer-char-shimmer][data-run=true]{
  animation-name:text-shimmer-sweep;
  animation-duration:var(--text-shimmer-duration);      /* 1.2s */
  animation-timing-function:linear;
  animation-iteration-count:infinite;
  animation-fill-mode:both;
  animation-delay:calc(var(--text-shimmer-step) * var(--text-shimmer-index) * -1);  /* -45ms × index */
  will-change:background-position;
}
```
> **Compose 复刻要点**：这是**文字渐变扫光**（`Brush.linearGradient` + `blendMode` / `ShaderBrush` 遮罩到文字），不是 `alpha` 呼吸。扫光带宽度 = `5.2ch`（等宽字符数 × 字符宽）；`background-size: 360% 100%` 相当于渐变在水平方向拉伸到 3.6×；每字符延迟 `-45ms × index`（负延迟 = 相位前移）；单次 1.2s、线性、无限，停用时 220ms 淡出。

### 2.4 重点动效 #2：spinner（**不是旋转圆环，是 4×4 像素点阵**）✅ 已确认

CSS 里 `[data-component=spinner]` 只有尺寸（默认 `width:18px; aspect-ratio:1`；工具卡内统一 `16px×16px`），**真正实现在 JS**：

```js
svg: viewBox="0 0 15 15", fill="currentColor"
rect: width=3 height=3 rx=1
16 个点: x = (i % 4) * 4,  y = floor(i / 4) * 4      // 0,4,8,12
delay    = Math.random() * 1.5                        // 0 – 1500ms
duration = 1 + Math.random() * 1                      // 1.0 – 2.0s
corner = {0,3,12,15}  → opacity:0，无动画
outer  = {1,2,4,7,8,11,13,14} → animation: pulse-opacity-dim <duration>s ease-in-out infinite  both  <delay>s
inner  = {5,6,9,10}            → animation: pulse-opacity     <duration>s ease-in-out infinite  both  <delay>s
```
即：**外圈用 dim 呼吸（.15↔.35），内 2×2 用亮呼吸（.4↔1.0），四角不亮**；每点随机时长 1–2s、随机延迟 0–1.5s 造成"砂砾闪烁"感。
> ⚠️ **1:1 复刻注意**：delay/duration 是**每次挂载随机**的，没有确定值。若要求可复现，需用固定种子（示例值：`duration=1.5s`、`delay=(i*0.09375)s`）或直接把随机范围照搬。

**另一类 spinner（`--rotate` 式）**：
```css
/* 标题栏更新指示器 */
[data-slot=titlebar-update-loader]{
  border:2px solid var(--v2-icon-icon-accent);
  border-top-color:var(--v2-icon-icon-accent);
  border-radius:9999px; width:12px; height:12px; margin:1px;
  transform-origin:50%;
  animation:.67s linear infinite titlebar-update-loader-spin;   /* → rotate(360deg) */
}
/* 另有一条覆盖：border:2px solid color-mix(in srgb, var(--v2-icon-icon-accent) 30%, transparent) */
```
```css
/* v2 loader（index-GGAIvKVQ.css） */
[data-component=loader-v2]{ --_duration:.9s; display:block; flex-shrink:0;
  color:var(--v2-icon-icon-muted,#808080);
  animation: loader-v2-spin var(--_duration) linear infinite }   /* → rotate(360deg) */
```

**旧式进度条（非 v2）**
```css
[data-component=progress][data-indeterminate] [data-slot=progress-fill]{
  width:35%; animation:1.3s ease-in-out infinite progress-indeterminate }  /* translateX(-100%) → (300%) */
[data-component=progress] [data-slot=progress-fill]{ transition:width .2s }
[data-component=progress-circle] [data-slot=progress-circle-progress]{ transition:stroke-dashoffset .35s cubic-bezier(.65,0,.35,1) }
```
v2 版本：`[data-component=progress-circle-v2] [data-slot=progress-circle-v2-progress]{ transition:stroke-dashoffset .35s cubic-bezier(.65,0,.35,1) }`，背景 stroke = `--v2-background-bg-layer-04`，前景 stroke = `--v2-icon-icon-base`，容器 `transform: rotate(-90deg)`。

### 2.5 重点动效 #3：assistant 流式打字光标

**CSS 存在，但本构建的 JS 未引用**（如实说明）：
```css
@keyframes blink { 0%,50%{opacity:1} 51%,100%{opacity:0} }
.blinking-cursor { animation: 1s step-end infinite blink }
```
> 我在 `index-CKBO9Mty.js` / `ghostty-web-*.js` / 其它分包中检索 `blinking-cursor` = **0 命中**；在全部 CSS 中 = 1 命中（就是这条定义）。所以这条 CSS 在当前打包版本里**可能是死代码**，或由运行时动态注入的类名使用。
> **建议**：Compose 复刻可用该数值（`1s`、`step-end`、50%/51% 硬切 = 方波闪烁），但**不要断言一定是流式光标**，它在构建产物里无引用证据。

### 2.6 重点动效 #4：会话 / 状态 pulse

```css
--animate-pulse:       pulse-opacity 2s ease-in-out infinite       /* .4 ↔ 1 */
--animate-pulse-scale: pulse-scale   1.2s ease-in-out infinite     /* scale 1 ↔ .666667 */
```
- `pulse-opacity` / `pulse-opacity-dim` 的实际使用点 = **spinner 点阵**（见 2.4）。
- `pulse-scale` 在 CSS 中**仅**作为 `--animate-pulse-scale` 定义（Tailwind `animate-pulse-scale` 工具类用），未找到组件级 `animation` 引用。
- 会话进行中的指示器 = **`session-progress-indicator-v2`**（25 点 opacity 跑马灯，见 2.1），不是简单 pulse：
```css
[data-component=session-progress-indicator-v2]{
  --_duration: 1.2s; display:block; flex-shrink:0; color:var(--v2-icon-icon-muted,#808080) }
[data-component=session-progress-indicator-v2] [data-dot]{
  fill:currentColor; opacity:.2;
  animation-duration:var(--_duration); animation-timing-function:ease-out;
  animation-iteration-count:infinite; animation-fill-mode:both }
```
- **停止按钮**脉冲（`@media(prefers-reduced-motion:no-preference)` 内）：
```css
[data-component=icon-button][data-icon=stop] [data-slot=icon-svg] rect{
  transform-origin:50%; transform-box:fill-box; animation:1.8s ease-in-out infinite stop-pulse }
/* scale .95 ↔ 1.12 */
```

### 2.7 `animation-timeline` 滚动渐隐（非时间动画）

所有 `*-fade-left/right/top/bottom` 都是 **CSS Scroll-driven Animation**，配合 `scroll-timeline` + 极小 `animation-range` 做"滚到头才显示边缘渐隐"：
```css
[data-slot=home-projects-scroll]:before{ animation:linear both home-projects-fade-top;  animation-timeline:--home-projects-scroll; animation-range:0 .1px }
[data-slot=home-projects-scroll]:after { animation:linear both home-projects-fade-bottom;animation-timeline:--home-projects-scroll; animation-range:calc(100% - 1.1px) calc(100% - 1px) }
```
同类：`--titlebar-tabs-scroll`、`--prompt-attachments-scroll`、`--prompt-input-v2-attachments-scroll`、`--session-review-v2-sidebar-tree-scroll`、`--model-selector-scroll`、`--manage-models-scroll`。
> **Compose 复刻**：这不是时长动画，而是"滚动位置驱动的可见性开关"——用 `Modifier.drawWithContent` + 根据 `listState.canScrollBackward/Forward` 控制渐隐遮罩即可，**不要**当成 0.1px 的补间动画。

---

## 3. 交互动效（hover / pressed / focus）

### 3.1 覆盖层 token（`--v2-overlay-*`）

| token | light | dark |
|---|---|---|
| `overlay-simple-overlay-hover` | `alpha-dark-4` = `#0000000a` (4%) | `alpha-light-6` = `#ffffff0f` (6%) |
| `overlay-simple-overlay-pressed` | `alpha-dark-8` = `#00000014` (8%) | `alpha-light-10` = `#ffffff1a` (10%) |
| `overlay-simple-overlay-contrast-hover` | `alpha-light-12` = `#ffffff1f` | `alpha-dark-24` = `#0000003d` |
| `overlay-simple-overlay-contrast-pressed` | `alpha-light-24` = `#ffffff3d` | `alpha-dark-40` = `#0006` |
| `overlay-simple-overlay-scrim` | `alpha-dark-40` = `#0006` | `alpha-dark-60` = `#0009` |
| `overlay-simple-tab-active-scrim` | `#fafafa00`（全透明） | `#24242400`（全透明） |
| `overlay-simple-tab-hover-scrim` | `#eee0`（全透明） | `#3a3a3a00`（全透明） |
| `overlay-simple-tab-scrim` | `#fafafa00`（全透明） | `#08080800`（全透明） |
| `overlay-gradient-depth-overlay-depth-top` | `alpha-light-100` = `#fff` | 同 |
| `overlay-gradient-depth-overlay-depth-bot` | `alpha-light-0` = `#fff0` | 同 |

> `tab-*-scrim` 三个 token 的 **alpha 都是 0**，实际不可见（可能是占位/由 JS 注入）。
> `scrim` 用于 dialog 遮罩：`[data-component=dialog-overlay]{background-color:var(--v2-overlay-simple-overlay-scrim)}`（v2 分支），旧分支为 `hsl(from var(--background-base) h s l / .2)`。

### 3.2 各组件状态色用法（可直接抄）

**button-v2 / icon-button-v2（v2 主按钮体系）**
| variant | 常态 | hover | pressed/expanded |
|---|---|---|---|
| neutral | `bg: --v2-background-bg-button-neutral`（light `#fff` / dark `#ffffff0f`）+ `--v2-elevation-button-neutral` | `linear-gradient(90deg, overlay-hover 0%, overlay-hover 100%), linear-gradient(90deg, bg-button-neutral 0%, bg-button-neutral 100%)` | 同结构，用 `overlay-pressed` |
| contrast | `linear-gradient(180deg, --v2-alpha-light-20 0%, --v2-alpha-light-0 100%), linear-gradient(90deg, --v2-background-bg-contrast, same)`；`color: --v2-text-text-contrast`；`text-shadow: 0 .5px .5px rgba(0,0,0,.3)`；`--v2-elevation-button-contrast` | 叠加 `overlay-contrast-hover` | 叠加 `overlay-contrast-pressed` |
| danger | `bg-button-neutral` + `color: --v2-state-fg-danger` | `overlay-hover` 叠加 | `overlay-pressed` |
| warning | `bg-button-neutral` + `color: --v2-state-fg-warning` | 同上 | 同上 |
| outline | `background:transparent`；`color: --v2-text-text-base`；`box-shadow: inset 0 0 0 1px var(--v2-border-border-muted)` | `background-color: --v2-overlay-simple-overlay-hover` | `--v2-overlay-simple-overlay-pressed` |
| ghost | `transparent` + `--v2-text-text-base` | `overlay-hover` | `overlay-pressed`（`[data-expanded]` 也 = pressed） |
| ghost-muted | `transparent` + `--v2-text-text-muted` | `overlay-hover` | `overlay-pressed` + `color: --v2-text-text-base`；展开时 icon → `--v2-icon-icon-base` |
| loading | `background: --v2-background-bg-layer-02`；`box-shadow: inset 0 0 0 .5px var(--v2-border-border-muted)`；`pointer-events:none` | — | — |
| disabled | `opacity:.5`（contrast 为 `.4`）；`cursor:not-allowed` | — | — |

**focus**：`outline: 2px solid var(--v2-border-border-focus); outline-offset: 2.5px`（button-v2 / icon-button-v2）；split-button-action `outline-offset:-2px; z-index:1`。

**file-tree-v2 行**：hover → `--v2-overlay-simple-overlay-hover`；selected → `--v2-overlay-simple-overlay-pressed`。

**dock-prompt question-option**：`background: --v2-background-bg-layer-01` + `border:.5px solid --v2-border-border-base`；hover → `--v2-background-bg-base`；picked → `--v2-state-bg-info` + `--shadow-xs-border-hover` + `border-color:transparent`。

### 3.3 transition 时长清单（CSS 实测去重）

| 时长 / 缓动 | 出现次数 | 典型组件 |
|---|---|---|
| `opacity .15s` | 11 | 通用 |
| `background-color .15s` | 3 | 通用 |
| `transform .15s` | 3 | — |
| `background 85ms ease-out, outline-color 85ms ease-out, box-shadow 85ms ease-out` | 2 | 高亮切换 |
| `stroke-dashoffset .35s cubic-bezier(.65,0,.35,1)` | 2 | progress-circle（v1/v2） |
| `background-color .15s, border-color .15s` | 2 | radio/box |
| `color .15s` | 2 | — |
| `transform .1s ease-in-out` | 2 | — |
| `color .12s, background-color .12s, border-color .12s` | 2 | 菜单项 |
| `background-color .12s ease, color .12s ease` | 1 | file-tree-v2 行 |
| `background-color .12s ease, color .12s ease, box-shadow .12s ease` | 1 | file-tree-v2 行（带阴影） |
| `background-color .15s, border-color .15s, box-shadow .15s` | 1 | question-option |
| `transform .12s ease` / `transform .16s ease` / `transform .2s ease-out` | 各 1 | 箭头/图标旋转 |
| `transform .28s cubic-bezier(.2,0,0,1), opacity .16s ease-out, height .28s cubic-bezier(.2,0,0,1), box-shadow …` | 1 | 展开容器 |
| `width .25s linear` | 1 | toast 进度条（`--kb-toast-progress-fill-width`） |
| `width .2s` | 1 | progress-fill |
| `width .2s cubic-bezier(.22,1,.36,1), border-inline-end-width .2s cubic-bezier(.22,1,.36,1)` | 1 | 侧栏拖拽 |
| `width .2s, background-color .2s` | 1 | — |
| `background-color .2s` / `background-color .2s ease-out` | 各 1 | 提问进度段 |
| `opacity .15s, color .15s` | 1 | — |
| `opacity .2s` / `opacity .2s ease` | 各 1 | — |
| `opacity .2s ease-out, box-shadow .1s ease-in-out, width .2s cubic-bezier(.22,1.2,.36,1), height .2s cubi…` | 1 | 浮动面板 |
| `opacity .12s, transform .12s` | 1 | — |
| `opacity .15s ease-in-out` | 1 | — |
| `border-color .15s, opacity .3s` | 1 | — |
| `color .2s ease-out, opacity .2s ease-out` | 1 | — |
| `opacity var(--text-shimmer-swap) ease-out` | 1 | shimmer 停用淡出（220ms） |
| `width var(--_dur) var(--_spring-soft)` | 1 | text-reveal（`.45s` + `cubic-bezier(.34,1,.64,1)`） |
| `width var(--tool-motion-spring-ms,.48s) var(--tool-motion-ease,cubic-bezier(.22,1,.36,1))` | 1 | tool-status-title swap/tail |
| `width var(--tool-motion-spring-ms,.56s) var(--tool-motion-ease,…)` | 1 | animated-number |
| `border-color .22s var(--tool-motion-ease,…), background-color .22s …, box-shadow .22s …` | 1 | checkbox control |
| `opacity .18s var(--tool-motion-ease,…), transform .22s var(--tool-motion-ease,…)` | 1 | checkbox indicator（`transform: scale(.9)` 起始） |

**统一缓动常量**
```
--tool-motion-ease      默认回退 cubic-bezier(.22,1,.36,1)     （"柔和 spring 近似"，≈ easeOutQuint 手感）
--tool-motion-spring-ms JS 内联：560ms（session-todo 内联 style）
--tool-motion-odometer-ms JS 内联：600ms
--tool-motion-mask       JS 内联：18%
--tool-motion-mask-height JS 内联：0px
--tool-motion-fade-ms    默认回退：.24s / .28s / .32s / .2s / .22s（各规则不同）
--text-reveal-duration    .45s（`--_dur`）
--text-reveal-spring      cubic-bezier(.34,1.08,.64,1)
--text-reveal-spring-soft cubic-bezier(.34,1,.64,1)
--text-reveal-edge        17%
--text-reveal-travel      0px
/* 未定义（CSS 中检索为空）：--transition-duration / --transition-easing —— context-menu 动画引用了它们，但全 CSS 无声明，属潜在无效引用 */
```

**tool-count-summary / tool-status-title 逐属性时长（v2 工具计数动效）**
```css
[data-slot=tool-count-summary-item]{
  transition-property: grid-template-columns, opacity, filter, transform;
  transition-duration: var(--tool-motion-spring-ms,.48s), var(--tool-motion-fade-ms,.28s),
                       var(--tool-motion-fade-ms,.32s), var(--tool-motion-spring-ms,.48s);
  transition-timing-function: var(--tool-motion-ease,cubic-bezier(.22,1,.36,1)), ease-out, ease-out,
                              var(--tool-motion-ease,cubic-bezier(.22,1,.36,1));
  grid-template-columns:0fr; opacity:0; filter:blur(var(--tool-motion-blur,2px));
  transform:translateY(.06em) scale(.985);
}
[data-slot=tool-count-summary-empty]  /* 显式态 */ { grid-template-columns:1fr; opacity:1; transform:translateY(0) scale(1) }
[data-slot=tool-count-summary-empty][data-active=false] { opacity:0; filter:blur(calc(var(--tool-motion-blur,2px)*.72)); grid-template-columns:0fr; transform:translateY(.05em) scale(.985) }
[data-component=tool-status-title] [data-slot=tool-status-active] { opacity:0; filter:blur(calc(var(--tool-motion-blur,2px)*.45)); transform:translateY(.03em) }
[data-component=tool-status-title][data-active=true] [data-slot=tool-status-done] { opacity:0; filter:blur(calc(var(--tool-motion-blur,2px)*.45)); transform:translateY(.03em) }
```
> 核心手法：**`grid-template-columns: 0fr → 1fr` 做宽度补间** + `blur()` 做"聚散"过渡 + `translateY(小数 em)` 微位移。Compose 里对应 `animateDpAsState` 宽度 + `Modifier.blur()` + `graphicsLayer.translationY`。

### 3.4 进入 / 退场动画（抽屉 / 对话框 / 命令面板 / 菜单）

| 组件 | 进入 | 退场 | 缓动 |
|---|---|---|---|
| v2 菜单 `[data-component=menu-v2-content]` | `animation: menu-v2-in .12s ease-out`（scale .96→1 + opacity 0→1） | 无独立 out 关键帧 | `ease-out` |
| `[data-component=menu-v2-content][data-slot=select-v2-content]` | `animation: select-v2-content-in .12s ease-out`（`translateY(-2px)→0` + opacity） | `[data-closed]` → `animation: select-v2-content-out 80ms ease-in forwards` | 12ms: out 80ms |
| dropdown-menu content | `animation: .15s ease-out dropdown-menu-open` | `dropdown-menu-close` | `ease-out` |
| **dialog（旧 `[data-component=dialog][data-transition]`）** | `[data-slot=dialog-content][data-expanded]` → `animation:.15s ease-out contentShow`（scale .98→1 + opacity） | `animation:.1s ease-in forwards contentHide` | 150ms / 100ms |
| dialog overlay（旧） | 无 transition 规则命中（仅有 opacity keyframes 定义 `overlayShow/Hide`） | — | — |
| **dialog-v2 遮罩** | `[data-component=dialog-overlay]{ position:fixed; inset:0; z-index:50; background-color:var(--v2-overlay-simple-overlay-scrim) }` | — | **未在 CSS 中声明 animation/transition** |
| dialog-v2 容器 | `[data-variant=settings] [data-slot=dialog-container]{ background:var(--v2-background-bg-base) }`；尺寸 `min(calc(100vw - 16px),980px) × min(calc(100dvh - 16px),720px)` | 无 CSS 过渡 | — |
| `[data-component=toast]` | `[data-opened]` → `animation:.15s ease-out toastPopIn`（`translateY(20px)→0`） | `[data-closed]` → `.1s ease-in forwards toastPopOut`；`[data-swipe=end]` → `.1s ease-out forwards toastSwipeOut` | 150 / 100ms |
| `[data-component=tooltip-v2]` | `animation: tooltipV2In .12s ease-out`，`transform-origin: var(--kb-tooltip-content-transform-origin)` | `[data-closed]` → `animation: tooltipV2Out 80ms ease-in forwards` | 120 / 80ms |
| `[data-component=session-tab-popover]` | `sessionTabPopoverIn`（scale .96→1） | `sessionTabPopoverOut` | 时长未在 CSS 内联，走 keyboard 默认 |
| context-menu content | `animation: contextMenuContentShow var(--transition-duration) var(--transition-easing) forwards` | `contextMenuContentHide …` | ⚠️ 变量**全 CSS 未定义** → 该 animation 声明很可能不生效 |
| hover-card / popover | `hover-card-open` / `popover-open`（scale .96→1） | `-close` | — |
| command palette | `dialog-command-palette-v2.css` 仅有 `transition: background-color .12s ease-in-out, box-shadow .12s ease-in-out` | 容器尺寸走 dialog-v2 | 120ms |
| 展开/折叠容器 | `@keyframes slideDown/slideUp`（`height:0 ↔ var(--kb-collapsible-content-height)`）；JS 侧另有 `qR = {type:"spring", visualDuration:.35, bounce:0}`（`[data-slot=collapsible-content][data-animated]`） | 同 | spring |

> **抽屉/对话框结论**：v2 的 dialog / dialog-overlay / drawer **在 CSS 里几乎没有过渡声明**——进入退场要么由 JS motion 库接管（dialog-v2 用 `[data-expanded]`/`[data-closed]` 状态 + JS 动画），要么由 `data-transition` 开关控制（旧 dialog 明确写了 `.15s ease-out` / `.1s ease-in`）。命令面板只做了 120ms 的背景与阴影过渡。**没有找到 drawer 的进出场 CSS**（`drawer` 关键字在 CSS 中 0 命中）。
> 另外 `#review-panel` / `#terminal-panel` 侧栏只有 `width .2s cubic-bezier(.22,1,.36,1)` 的宽度过渡（拖拽 resize），不是抽屉动画。

### 3.5 v2 微交互尺寸/字号（复刻必备硬数值）

```css
/* button-v2 */
font-weight:530; font-size:13px; line-height:20px; border-radius:6px; letter-spacing:-.04px;
[data-size=small]  height:24px; padding:0 9px;  border-radius:4px
[data-size=normal] height:28px; padding:0 11px
[data-size=large]  height:32px; padding:0 15px
gap:6px; font-variant-numeric:tabular-nums
/* icon-button-v2 */
[data-size=small]  20×20px; border-radius:4px
[data-size=normal] 24×24px; border-radius:6px
[data-size=large]  28×28px
/* split-button-v2-action / menu-trigger */ height:28px; action width:28px
/* tooltip-v2 */
padding:5px 6px; gap:6px; border-radius:4px; font-weight:530; font-size:11px; line-height:12px;
letter-spacing:.05px; background:var(--v2-background-bg-layer-01); box-shadow:var(--v2-elevation-floating); z-index:1000
/* tool-trigger 标题 */
font-family:var(--font-family-sans); font-size:14px; font-weight:var(--font-weight-medium);   /* 500 */
line-height:var(--line-height-large);                                                          /* 150% */
letter-spacing:var(--letter-spacing-normal);                                                   /* 0 */
color:var(--v2-text-text-base); flex-shrink:0
/* tool spinner 容器 */
width:16px; height:16px; color:var(--v2-text-text-faint)
/* message-part-title-spinner */
width:16px; height:16px; color:var(--v2-text-text-muted); margin-left:4px
/* file-tree-v2 row */ height:28px; gap:6px; padding-inline-end:8px; border-radius:6px;
  color:var(--v2-text-text-muted); scroll-margin-block:8px
/* menu-v2-content / select-v2-content */ min-width:160px; max-width:23rem; border-radius:6px;
  background:var(--v2-background-bg-layer-01); box-shadow:var(--v2-elevation-floating); z-index:60;
  transform-origin:top center
/* 列表滚动容器 */ gap:12px; mask-image:linear-gradient(to bottom,#fff calc(100% - var(--bottom-fade)),#0000)
```

---

## 4. JS 侧 motion（spring）参数 —— Compose 里需用 `spring()` / `tween()` 对齐

前端使用 **motion（motion-dom/motion-utils，SolidJS 集成）**，非纯 CSS。实测常量：

```js
/* motion 默认 spring（Wr） */
{ stiffness:100, damping:10, mass:1, velocity:0, duration:800,
  bounce:.3, visualDuration:.3,
  restSpeed:{granular:.01, default:2}, restDelta:{granular:.005, default:.5},
  minDuration:.01, maxDuration:10, minDamping:.05, maxDamping:1 }

/* 动画定义常量 */
INe = { type:"spring", stiffness:500, damping:25,     restSpeed:10 }   /* 弹/推入类 */
DNe = (n)=>({ type:"spring", stiffness:550, damping: n===0 ? 2*Math.sqrt(550)=46.90 : 30, restSpeed:10 })
LNe = { type:"keyframes", duration:.8 }
ANe = { type:"keyframes", ease:[.25,.1,.35,1], duration:.3 }
```

**业务侧具体用到**（含上下文）：
| spring/tween | 用途 |
|---|---|
| `{type:"spring", visualDuration:.35, bounce:0}` | `[data-slot=collapsible-content][data-animated]` 折叠内容高度 |
| `{type:"spring", visualDuration:.25, bounce:0}` + 同时 `{duration:.32, ease:[.16,1,.3,1]}` 动画 `opacity:1, filter:blur(0px)` | 文本出现：宽度 spring + 模糊消散 320ms |
| `{visualDuration:.2, bounce:0}` | 卡片/模式切换进度值 `M = (mode==="normal" ? 1 : 0)`；派生 `opacity:Y, transform:scale(.98+Y*.02), filter:blur((1-Y)*2px), pointer-events:Y>.5` |
| `{visualDuration:.3, bounce:0}` | 提问面板最小化进度 `N = (minimized ? 1 : 0)` |
| `{type:"spring", stiffness:550, damping:46.9/30, restSpeed:10}` | tab/列表项数量变化 |

> **Compose 映射建议**：`visualDuration:.35, bounce:0` ≈ `spring(dampingRatio = 1f /* 无回弹 */, stiffness = Spring.StiffnessMediumLow)`（严格对齐需按 motion 公式换 stiffness）；`cubic-bezier(.22,1,.36,1)` ≈ `CubicBezierEasing(0.22f, 1f, 0.36f, 1f)`；`cubic-bezier(.34,1,.64,1)` ≈ `CubicBezierEasing(0.34f, 1f, 0.64f, 1f)`；`cubic-bezier(.65,0,.35,1)` ≈ `CubicBezierEasing(0.65f, 0f, 0.35f, 1f)`。

**JS 内联下发到 DOM 的 motion 变量（Compose 需照抄默认值）**：
```
.session-todo-toggle span  style: --tool-motion-odometer-ms:600ms; --tool-motion-mask:18%;
                                  --tool-motion-mask-height:0px; --tool-motion-spring-ms:560ms
text-shimmer               style: --text-shimmer-swap:220ms; --text-shimmer-index:N
text-reveal                动态设 --text-reveal-duration / -edge / -travel / -spring / -spring-soft
```

---

## 5. `prefers-reduced-motion` 降级（Compose 对应 `LocalMotionDurationScale` / 无障碍设置）

CSS 中 **16 处** `@media (prefers-reduced-motion:reduce|no-preference)` 精确命中，清单：

```css
/* reduce：动画全关 */
[data-component=animated-number] [data-slot=animated-number-value|strip]      { transition-duration:0s }
[data-component=text-reveal] [track|entering|leaving]                          { transition-duration:0s !important }
[data-slot=text-strikethrough-line]                                            { clip-path:none !important }
[data-component=text-shimmer] [char-base|char-shimmer]                         { transition-duration:0s }
[data-component=text-shimmer] [data-slot=text-shimmer-char-shimmer]            { color:inherit; -webkit-text-fill-color:currentColor;
                                                                                 background-image:none; animation:none !important }
[data-component=text-shimmer] [data-slot=text-shimmer-char-base]               { opacity:1 !important }
[data-component=tool-count-label] [tool-count-label-suffix]                    { transition-duration:0s }
[data-component=tool-count-summary] [empty|item|prefix]                        { transition-duration:0s }
[data-component=tool-status-title] [swap|tail|active|done]                     { transition-duration:0s }
[data-slot=titlebar-update-loader]                                             { animation:none }
[data-component=loader-v2]                                                     { animation:none }   /* index-GGAIvKVQ.css */
.toast-v2, .toast-v2 > *                                                        { animation:none !important; transition:none !important }
[data-component=tooltip-v2] / [data-closed]                                    { animation:none }
[data-component=session-progress-indicator-v2] [data-dot]                      { animation:none }
[data-component=session-progress-indicator-v2] [data-dot="12"]                 { opacity:1 }
[data-component=session-tab-popover] / [data-closed]                           { animation:none }
.motion-reduce\:translate-x-0 / .motion-reduce\:transition-none               { 无位移 / transition:none }

/* no-preference：仅在允许动画时才启用 */
[data-component=icon-button][data-icon=stop] [data-slot=icon-svg] rect          { animation:1.8s ease-in-out infinite stop-pulse }
.motion-safe\:transition-all                                                   { transition-property:all; duration:var(--tw-duration,0s) }
```
> **复刻关键**：会话进度点在 reduce 下**不是隐藏，而是把 `data-dot="12"` 恒亮为 opacity:1**（即"静态中点"）。shimmer 在 reduce 下退化为**纯色文本 + base 全显**（`background-image:none`）。

---

## 6. 给 Jetpack Compose 的复刻清单（落地建议）

1. **Token 层**：建 `V2SemanticTokens` 数据类，`V2Light` / `V2Dark` 两个实例；另建 `V2Palette`（grey/alpha/color ramps，主题无关）。全部 hex 见 §1。
2. **半像素边框**：`--v2-border-*` 普遍按 `.5px` 用（`border:.5px solid`、`inset 0 0 0 .5px`）。Compose 用 `0.5.dp` 描边或 `drawLine(strokeWidth = 0.5.dp)`，避免用 1dp 导致视觉变粗。
3. **shimmer**：`TextShimmer` 用 `Brush.linearGradient` + `drawText` 二层叠加（base = `--text-weak`，peak = `--text-strong`），扫光带 `5.2ch`、`360%` 拉伸、`1.2s` linear infinite、每字符 `-45ms × index` 相位差、停用后 `220ms` 淡出。Compose 里可以把相位差做成整条文本的单个 `infiniteRepeatable` + 每字符 `graphicsLayer` 裁剪，或用 `AnimatedContent` 逐字 offset。
4. **spinner（4×4 点阵）**：`Canvas` 画 16 个 `3×3 rx=1` 圆角方块（画布 15×15，间距 4），角 4 个不画；外圈 `pulse-opacity-dim (.15↔.35)`，内 4 个 `pulse-opacity (.4↔1.0)`；时长 1–2s 随机、延迟 0–1.5s 随机（`ease-in-out`）。**若要可复现，须自定种子。**
5. **停止按钮**：`scale .95 ↔ 1.12`，`1.8s ease-in-out infinite`。
6. **会话进度**：25 点 opacity 跑马灯，`1.2s ease-out infinite both` 循环（`--_duration:1.2s`），每点 9 段关键帧错相；reduce 时中点恒亮。
7. **loading / update 圆环**：`0.67s linear infinite`（标题栏，12×12、2px 边框、border-top 高亮色 `--v2-icon-icon-accent`）；`0.9s linear infinite`（`loader-v2`，色 `--v2-icon-icon-muted`）。
8. **交互时长**：hover/pressed 一律 **120–150ms**；菜单/下拉**进入 120–150ms `ease-out`、退场 80–100ms `ease-in`**（退场更快，是这套设计的固定节奏）；progress-circle `stroke-dashoffset .35s cubic-bezier(.65,0,.35,1)`。
9. **v2 工具计数/状态切换**：`grid-template-columns 0fr↔1fr`（宽度）+ `blur` + 微小 `translateY`，主时长 `480ms` / `560ms`，缓动 `cubic-bezier(.22,1,.36,1)`。
10. **滚动边缘渐隐**：不是时间动画，用 `canScrollBackward/Forward` 控制渐隐遮罩的 alpha。
11. **字体**：sans 用 Inter 可变字体（需支持 `wght 530`），字号 `13/14/16/20px`，行高 `130%/150%/180%/200%`；等宽：通用用系统 mono，**终端**用 JetBrains Mono Nerd Font Mono（`14px` 量级）。
12. **accent 阴影 `text-shadow: 0 .5px .5px rgba(0,0,0,.3)`**：contrast 按钮与 tooltip 上有，Compose 用 `Shadow(color = Color.Black.copy(alpha=.3f), offset = Offset(0f, 0.5f), blurRadius = 0.5f)`。

---

## 7. 明确"未确认 / 无证据"项（避免臆测）

| 项 | 状态 |
|---|---|
| `blinking-cursor` 是否真用于 assistant 流式光标 | CSS 定义存在（`1s step-end infinite blink`），但**全部 JS 分包 0 引用** → 可能是死代码或运行时注入，**不建议直接断言**。 |
| `--transition-duration` / `--transition-easing` | context-menu 动画引用了这两个变量，**全 CSS 无任何声明** → 该动画声明大概率无效（回退到 `initial`）。 |
| `drawer` 抽屉 | 全 CSS `drawer` 关键字 **0 命中**，未找到抽屉进出场动画。 |
| dialog-v2 进出场 | 只有 `[data-component=dialog-overlay]` 的静态背景色与 dialog-container 的静态尺寸，**无 CSS transition/animation**（推测由 JS motion 接管，未在 CSS 证实）。 |
| `--v2-overlay-simple-tab-*-scrim` | 三个 token 的 **alpha 均为 0**，实际不可见。 |
| `--v2-agent-review-*` / `--v2-agent-writer-*` 的 border/background | **未定义**（只有 solid）。 |
| GitHub 源码 `packages/` 交叉验证 | **未完成**（404 / 403 / 429，见文首）。所有数值仅来自本地官方构建产物。 |
| 25 点进度指示器的精确 SVG 布局（半径/点间距） | CSS 只给 `fill:currentColor` 与动画；几何由 JS/SVG 决定，**本次未提取到具体坐标**。 |
| spinner 随机参数的确定性种子 | JS 使用 `Math.random()`，**无固定种子**，逐挂载不同。 |
