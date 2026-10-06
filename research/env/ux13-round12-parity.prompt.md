# 轮次：UX13-P12 —— 综合设备轮：B-2 定性 / D5·D3 首启界面 / 重进 App 状态恢复 / 通知重载自愈

你是本仓库本轮的执行 agent。仓库根：`D:\XiaoZhuTouTou\opencode-android-shell`。
背景：P11 批已把"页面重载后通知权限重推"修复打进包。本轮一次设备轮把四个剩余项跑完。**只验证，不改代码。**
**先建报告 `research/web/UX13_P12_PARITY.md`，每步写盘；单条命令 + 立即读输出；真实点击/按键；注入只读打点并标注。**

## 环境
- 包 `research/deliver/opencode-shell-web.apk`（P11 批，装前 Get-FileHash 记录）。
- 后端 `127.0.0.1:4095`（opencode/opencode，v2.0.24）已在跑，**不许起停**。
- AVD `Medium_Phone_API_37.0`，端口 **6790**（先查占用）；`adb reverse tcp:4095 tcp:4095`（收尾 remove）。
- 注入 `am start --es oc_debug_eval "b64:..."`（不能 async）；logcat grep `oc-pow|OcPerfJS`。
- 可达路径（复用前面各轮结论，简写）。
- 纪律：只关自建 emulator-6790；不 kill-server；不改代码；不碰 gradle。

## 0. 起机装包（**install -r**，别漏）→ pm clear → am start → 5s
连服务器 `http://127.0.0.1:4095`+opencode（等≥14s）→ 切上下文 → 进会话。全程截图。

## 1. B-2：输入框 → 到行尾后焦点是否跳出到边栏
1. 会话 composer 真实点入，注入记录 `document.activeElement` 标签；
2. `input text "hello world"` 后，连续 `input keyevent 22`（→ 键）≥15 次（确保过行尾）；
3. 每 3 次 → 注入读一次 `document.activeElement`；
4. 判定：焦点始终在 composer/textarea ⇒ **B-2 不复现（当前构建已修好或上游已修）**；焦点跳到侧边栏某元素 ⇒ 复现，记录跳到哪个元素（截图+注入读 outerHTML 摘要），分类为"上游键位/焦点管理"或"我方注入"（看触发点是否与我们注入的监听有关——对照 oc-host.js 里 keydown 相关代码，只读）。
5. 顺带把历史"其它快捷键仍能用"抽查一条：终端里 ctrl+alt+t 不好真按，改为发消息 Enter 发送正常即可。

## 2. D5：冷启首屏缺「New session」按钮？
1. `pm clear` → force-stop → am start → 等首屏稳定（≥8s），截图 `ux13p12_coldfirst.png`；
2. 注入扫首屏可点元素清单（buttons/links 文本数组）；
3. 按 BACK 再回 Home，再注入扫一次；
4. 判定：两次清单对比——首屏缺、返回后有 ⇒ D5 复现（上游首帧行为）；首屏就有 ⇒ D5 不复现。记录并与 V13/V14 历史结论对照。

## 3. D3：Tab 条挤压（360dp）
手机档 1080 宽@420≈360dp。会话内顶部 会话/更改/文件/终端 标签条截图，量 `Terminal` 与 `≡` 之间的净空 px（用 uiautomator bounds 算）。对照 V14 记录（≈75px）。判定：与历史一致 ⇒ 维持观察（上游布局）；明显更糟 ⇒ 记录差异。

## 4. 重进 App 状态恢复（桌面端语义）
1. 在 4095 上下文某会话内 → `am force-stop` → 静置 5s → am start；
2. 观察 10s：落在哪个路由（注入读 `location.pathname`+页面标题）、服务器上下文是否仍是 4095（绿点是否还在/列表是否直接渲染）、4173 红点是否还在；
3. 判定：直接回到上次会话/4095 上下文 ⇒ 与桌面端"重开恢复"一致 ✅；回 Home 且需手动重切 ⇒ 如实记录差异（上游 last-route 恢复是否生效）。
4. 顺带记录：重进后 `opencode.settings.dat:defaultServerUrl` 是否有值（注入读 localStorage）。

## 5. 通知重载自愈（P11 验收）
1. 先发一条消息触发权限弹窗（若已被 P11 重推则跳过弹窗）→ 真点允许；
2. force-stop → am start（页面重载）→ 注入读 `Notification.permission`（应为 granted——P11 重推的验收点）；
3. 进会话 → 发消息 → 立刻 HOME → 25s → `dumpsys notification` 应有本包通知（重载后第一次后台回合）；
4. 判定：出 ⇒ P11 闭环 ✅；不出 ⇒ 记录闸门四值（perm/vis/focus/hasOpenTab）复盘。

## 6. 收尾（同前纪律）

## 汇报（最后一行）
B-2 复现/不复现 · D5 复现/不复现 · D3 净空px · 重进恢复 恢复/未恢复+defaultServerUrl 有无 · 通知重载自愈 出/未出 · 报告路径 · 碰过的 serial。
