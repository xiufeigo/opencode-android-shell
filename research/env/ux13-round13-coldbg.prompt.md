# 轮次：UX13-P13 —— 冷启后首次后台通知失效：带完整日志的复现 + 闸门读数环缓冲定位（必要时修复）

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：`UX13_P12_PARITY.md` §5：force-stop→冷启→进会话→发 ping→HOME→等 27s：**Pong 落地但无通知**；权限 granted ✅、hasOpenTab ✅；P10（安装后首后台）同场景**成功过**。差异只在"冷启重进"。**本轮一次跑完：非侵入复现+定位；若根因是明确的宿主 bug 且修复小，直接修+重建+复验。**

**先建报告 `research/web/UX13_P13_COLD_BG_NOTIFY.md`，每步写盘；单条命令 + 立即读输出；真实点击；注入只读打点并标注。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（P11 批）；后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）已在跑**不许起停**；AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）；纪律同前（只关自建、不 kill-server、不碰 gradle——除非进入修复段）。

## 阶段一：非侵入复现（只验证）
1. 起机 → **install -r** → pm clear → am start → 连 4095（UI 登记+Default）→ 进会话 → 发消息→弹权限窗→真点 Allow。
2. **force-stop** → am start（冷启重进，应恢复 4095 上下文）→ 进会话。
3. **后台前**注入"闸门读数环缓冲"（只读打点，不碰后台窗口）：
   b64：
   ```
   window.__gateLog=[]; var vs=document.visibilityState; Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){var v=window.__ocBg?'hidden':'visible'; window.__gateLog.push(['vs',v,Date.now()]); return v;}}); var hf=document.hasFocus.bind(document); document.hasFocus=function(){var f=!window.__ocBg; window.__gateLog.push(['hf',f,Date.now()]); return f;}; "armed"
   ```
   ⚠️ 注意：此打点以 `window.__ocBg` 为真值源（不覆盖 P10 shim 的 ocBackgrounded 闭包——两者同值；若 `window.__ocBg` 不存在则打点无效，先读 `typeof window.__ocSetBackground` 确认并在报告记录）。
   （更稳妥：直接复用 shim 的 getter 包装——先把上面的 defineProperty 去掉，改为只包一层计数。二选一，写清选择。）
4. 发 `ping` → **立刻 HOME** → **全程 logcat 落盘**（`adb logcat -v threadtime` 后台 job，至少覆盖 40s）→ 30s 后回前台（am start 会拉前台，正常）。
5. 回前台后注入读回：`JSON.stringify({gateLog:window.__gateLog, ocBg:window.__ocBg, perm:window.Notification.permission})`；logcat 停采并 grep：`P7 onPause|round end|event stream|notifyPerm|oc_turns|Notification`。
6. 判定表：
   - onPause 走了 keepalive 分支吗（"round active -> keep SSE alive" 日志在不在）？
   - 后台窗口有 `round end session.execution.succeeded` 吗？
   - `__gateLog` 里 succeeded 时刻前后的 vs/hf 读数是什么？（上游若读了，值必须是 hidden/false）
   - oc_turns 时间戳 >0 吗？

## 阶段二：视根因修复（仅当根因明确且修法在授权内）
按定位结果三选一：
- **若 onPause 走了 pagehide 分支**（roundActive 误判）⇒ 查 `RoundActivityTracker.onRoundStart` 谁在冷启后没触发（读代码定位），修法必须在宿主 Kotlin、最小 diff、补绊线。
- **若 keepalive 走了但 succeeded 没到** ⇒ 查 SSE 为何被掐（日志里 event stream CLOSE 时刻），修宿主断流逻辑。
- **若 succeeded 到了、闸门读了 vs/hf 但值不对** ⇒ 查 `__ocSetBackground(true)` 是否在冷启后的 onPause 真的执行了（evaluateJs 判空/runnable 顺序），修派发。
修复后：锁协议（Microsoft JDK21、内联 ANDROID_HOME、脱离会话+轮询）跑 `:webapp:testDebugUnitTest` 全绿 + assemble 三件套 + 覆盖 `research/deliver/` + 包内自查（ui=1613、oc-host.js md5 登记）；然后**同轮设备复验**（重做阶段一流程，判定通知出/不出）。
若根因不明或超出宿主侧 ⇒ 不许硬修，把证据写全，留待产品/上游裁决。

## 收尾（无论哪段）
emu kill、reverse --remove、后端 pid 15096 未变+200、列 serial。

## 汇报（最后一行）
定位结论（onPause 分支/succeeded 送达/闸门读数 三格表）· 修复与否+改点 · （若修）用例数/产物 md5/oc-host.js md5 · 复验通知 出/未出 · 报告路径 · 碰过的 serial。
