# frontend/terminal — 独立 xterm adapter

一个独立的 ESM 模块，替换 opencode 打包产物里的 Ghostty 模块槽位
（`/_assets/ghostty-web-*.js` → 本目录构建出的 `oc-terminal.js`）。
渲染、VT 解析、IME 组字、序列化全部由**固定版本**的 xterm 实现，上游业务代码
只看到原来的导出形状。

## 精确依赖

`package.json` 全部锁死，不使用 `^`/`~`：

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `@xterm/xterm` | `6.0.0` | 终端内核 |
| `@xterm/addon-fit` | `0.11.0` | 行列自适应（被继承后追加 mobile 逻辑） |
| `@xterm/addon-serialize` | `0.14.0` | 应用自己 dispose 的 serializer |
| `esbuild`（dev） | `0.25.12` | 打包 |

## 保留上游 PTY / connect helper

adapter **不实现** PTY。它只导出应用已经 import 的那组符号
（`Terminal`、`FitAddon`、`Ghostty`、`init`），所以：

- 上游 `index-*.js` 仍然自己 lazy-import 这个 URL，模块替换只发生在 HTTP 路由层；
- PTY 创建与 `/api/pty/{id}/connect` WebSocket 仍由上游 helper 负责，socket 归属不变；
- `Ghostty.load()` / `init()` 保持原签名（当前返回空实现，因为 xterm 不需要它们）；
- `loadAddon()` 兼容旧的 serialize addon 形状，但 serializer 的 dispose 权仍归应用。

## 反向 viewport 换算

Ghostty 的 scrollback 位置是**从最新输出往回数**的；xterm buffer 用绝对行号。
适配层在两者之间来回换算，而不是改写 buffer：

- `getViewportY()` → `baseY - viewportY`（反向：距底部多少行）
- `scrollToLine(offset)` → `baseY - offset`

`fit()` 前会记下是否处于跟随底部（`viewportY >= baseY`）；原本贴底才继续跟随。
读历史时使用公共 marker 记录顶行，跟随宽度重排，在 xterm 的下一次渲染同步后恢复。
xterm 6 的 `scrollToLine()` 在 resize 后会用尚未同步的像素视口计算相对位移，因此
adapter 用两次同步的公共 `scrollLines()` 完成绝对定位；中间位置不会绘制。

手机旋转跨越 768px 断点会重建面板。`panel-lifecycle.mjs` 在构建时修正固定的上游
`panel-3c8jLlNq.js`：等旧实例 flush、将快照写入原 store 后，再读取最新快照；先在原
行列下恢复滚动位置，再 fit。交接 Map 只存等待中的 Promise，不保存另一份历史。
同一 PTY 的连续交接会串联等待，较早的 cleanup 不会覆盖或删除较新的交接。
上游 chunk 或代码锚点变化会明确报错，必须审核后更新变换；不修改 `_upstream/`。

## 字号比例 scale()

存储键 `oc.settings.terminalFontScale`，与 oc-host.js 的 `ocTerminalScale()` 同一契约：

1. 先读保存值 —— **所有屏幕**都优先采用用户手动存的偏好；
2. 合法数值 clamp 回 `[0.5, 1]`（手改 localStorage 也画不歪）；
3. 没有保存值、或值非法（NaN / ≤ 0 / localStorage 抛错）时，才按 `767px` 断点取
   fallback：窄屏 `0.65`，宽屏 `1`。

即宽屏不会因为"屏幕够大"就丢弃已保存的字号偏好。

## 键盘 fit / bottom frame

- `window.resize`、`oc:insets`、`visualViewport.resize|scroll`，以及
  `document.fonts.ready` / `fonts.loadingdone` / `visibilitychange` / `pageshow`
  全部汇到一个 `scheduleFit()`，用 `requestAnimationFrame` 合并，避免一帧内多次 fit。
  网格是按像素量的，晚到的 webfont 会让首次 fit 拿到的 cell 尺寸失效。xterm 不监听
  FontFaceSet，fit 读取缓存的尺寸，因此字体 `ready` 与 `loadingdone` 先通过公共字号
  option 的同步更新触发重测，再重新 fit；保存的字号和最终字号均不变。
  `visibilitychange` 只在 `visible` 时 fit（旧 WebView 拿不到该状态时按可见处理），
  `pageshow` 覆盖 bfcache 恢复。
- `fit()` 先量 container 的 `getBoundingClientRect()`，`width` 或 `height` 为 0 就直接
  返回：隐藏 / 尚未布局的容器量出 0x0，硬 fit 会把 PTY 缩成 1x1 并改写子 shell 的窗口尺寸。
- `FitAddon.proposeDimensions()` 临时把 frame 撑到 `100%` 再量尺寸，量完恢复，
  所以测量永远针对整屏高度而不是被压缩过的 frame。
- `FitAddon.observeResize()` 用 `ResizeObserver` 观察 container 并转成 `scheduleFit()`。
  它不是 dead code：打包后的面板挂载时显式调用 `u2.observeResize()`
  （见 `research/tmp/terminal-panel-readable.js:430`），所以这是活的兜底路径。
- `layoutFrame()` 让短输出**贴底**：只在 `normal` buffer 且 `baseY === 0`（没有回滚）
  时收缩到实际用到的行数；满屏或带回滚时保留完整网格，绝不改动 cursor 地址或 PTY 尺寸。
  "用到的行数"经 public buffer cell API 判断，不只查文本：ANSI 背景色（palette 与
  RGB 都算）、inverse、下划线、删除线都会在空 cell 上真的画出来，只看
  `translateToString()` 会把彩色块（选中的行、进度条空尾、高亮提示符）裁出 frame。
  扫描复用同一个 cell 对象，零分配。
- textarea `focus` 时**不**无条件 `scrollToBottom()`：只做 `window.scrollTo(0, 0)` +
  重新 fit。正在读 scrollback 的用户点开键盘，位置必须留在原地；`fit()` 先记下
  `viewportY >= baseY`，只有**原本就贴底**时才继续跟随最新输出。`this.textarea` 的
  focus 被刻意放在可信手势内触发，Android IME 才能正常接管。

## 触摸走 public `scrollLines`

拖动 / 惯性滚动只调用 **public** 的 `scrollLines()`（经 `scrollPixels()` 换算像素→行），
不碰 `buffer.ydisp`、不伪造 wheel 事件。这样在 vim / less / tmux 打开鼠标上报
（DECSET 1000 + SGR 1006）时，滑动仍然是浏览 scrollback，不会被当成终端鼠标输入。
`touchstart/move/end` 全部 `preventDefault` + `stopImmediatePropagation`，拦掉 xterm 自带
触摸处理和浏览器整页 pan；`pointerType` 为 mouse 的事件不进入手势分支。

## 构建

```
cd frontend/terminal
npm ci
npm test
npm run build
```

`build.mjs` 用 esbuild 打成单文件 ESM，输出到
`webapp/src/main/assets/www/oc-terminal.js`，并应用上面的面板生命周期修正与
`frontend/embedded-ui` 的无内建服务器配置、共享分隔线 Pointer Events 修复。
终端、面板、入口 JS 和入口 CSS 四个生成文件都是 APK 资产，需重新打包才会生效；
CI 校验其与源码一致。

## 验证

### 完整 SPA（Chromium 模拟 Android WebView）

```
node scripts/verify-android-terminal-release.mjs
VERIFY_TERMINAL_FILTER=drag  node scripts/verify-android-terminal-release.mjs
VERIFY_TERMINAL_NO_SHOTS=1   node scripts/verify-android-terminal-release.mjs
VERIFY_TERMINAL_MODE=boot    node scripts/verify-android-terminal-release.mjs
```

真实托管 `ui/index.html` 与其 `/_assets/*` chunk，仅在 HTTP 路由层把 Ghostty 槽位换成
`oc-terminal.js`，完整注入 oc-host.js，触摸经 CDP `Input.dispatchTouchEvent` 以可信输入下发。
退出码：`0` 全通过 / `1` 有检查失败 / `2` 替换模块缺失。
**结果标注为 `chromium-emulated`** —— 它没有真机、没有 Android System WebView、没有真 IME。

发布验收一律用 `verify-android-terminal-release.mjs`：它是修好真实手机页签进入的那一份
（打包后的 shell 会渲染不止一条 tab strip，session mobile-view tabs、off-canvas 抽屉与竖排
tab 列表并存，只按旧选择器点击会点到被 `transform` 移出屏幕的元素，判定为
`opened via mobile-view-tab` 而截图仍是 session composer；runner 现在只接受
`getBoundingClientRect()` 真正在视口内、且点击前后 tab/engine 状态可观测的候选）。

`scripts/verify-android-terminal.mjs` **不要删**：设备脚本
`verify-android-terminal-device.mjs` 仍从它 import `startBackend` / `seedScript` /
`base64url`，它是依赖而不是遗留副本。两个 runner 的环境变量同名同义
（`VERIFY_TERMINAL_FILTER` / `_NO_SHOTS` / `_MODE` / `_TRACE` / `_MODULE` / `_PLAYWRIGHT` /
`_CHROMIUM` / `_DEVICE`），只有默认输出目录不同（release 版写
`research/tmp/verify-android-terminal-release-ready/<UTC时间戳>/`）。每次保留独立报告，
不覆盖旧失败证据。`history-restore` 分别验证页签切换、折叠/重开保留实例，以及整页
刷新后新 realm、新实例和后端实际 PTY marker 重放；`adapter-lifecycle` 单独验证
序列化、销毁、重建后的文本、ANSI 属性、光标和滚动位置，不冒充 SPA 或原生验收。

### Android 模拟器上的真实 IME / 键盘

```
node scripts/verify-android-terminal-device.mjs
node scripts/verify-android-terminal-device.mjs --inspect --label=<name>
node scripts/verify-android-terminal-device.mjs --verify-native-input --label=<name>
```

不替换原生 bridge、资产、activity 或 HTTP host，只自备 mock PTY 后端。用 adb 真按 BACK
收软键盘（不合成 insets），覆盖 IME 弹出/收起 3 个循环、frame 是否始终在 IME 之上、
视口高度与行列是否复原、短输出是否贴底。脚本**默认从真实 ADB 的 Terminal 页签进入**，
只有该页签不存在时才退回 CDP 快捷键。行列复原用**当前渲染行高下的容量**判定
（`.xterm-rows > div` 真实行盒 × rows 必须填满去 padding 的容器高度，且再多一行放不下），
而不是比较 rows 数量：`cellHeight()` 是 `screen.height / rows` 的派生值，它的任何变化
都不能证明 webfont 落定。

### `--verify-native-input`：真实 Gboard 输入

首个循环键盘已弹出时，额外用 `adb shell input tap` 真按 Gboard 上的
**`q`、`w`、`e`、空格**四个键，校验：

- 收到的 trusted `input`/`beforeinput`/`compositionend` 事件最终送进 PTY 的字节**正好是 `qwe `**；
- 这些事件全部 `isTrusted === true`（非脚本派发）。

**覆盖范围有限，别过度引用**：只覆盖这 4 个键，依赖 1080x2400 模拟器上 Gboard 的固定坐标；
不回车、不测中文组字 / 候选栏 / 长按 / 符号层 / 手写 / 剪贴板 / 语音输入。
脚本会先断言设备为 `com.google.android.inputmethod.latin/` + 1080x2400，否则整段跳过。

### 冷启动验收不能拿 warm 覆盖顶替

字体度量相关的结论（首轮 `rows`、`renderedCellHeight`、容量是否稳定）**必须在冷进程上验证**：
先 `am force-stop`，再 `am start` 真实冷启，等新的 PID / WebSocket devtools socket，再跑脚本。
热进程里字体已经落地，`fonts.ready` / `loadingdone` 不会再触发，warm 通过
**不能**替代 cold 验收。

**范围说明：这是 Android 模拟器，不是真机。** 键盘覆盖是真实的 Android 系统 IME 与
`InsetsHost`，`physicalWidth/physicalHeight` 指的是模拟器窗口的物理像素 —— 但不要把它
当成真机验证来引用。**物理设备未测**：真机 IME、厂商键盘、系统栏与手势区、
WebView 版本差异都没有覆盖过。
