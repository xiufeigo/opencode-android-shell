# 进度快照(2026-09-21 晚,用户暂停,明早继续)

## 当前状态

- **工作树编译状态:RED** —— 唯一原因是 agent-8(Wave 1 协议层)的中途态重构
  (`v2/SessionTransport.kt`、`V2Capability.kt` 语法错;`V1Transport/V2Transport/V2Api`、
  `ChatStore.kt` 未解析引用)。**恢复 agent-8 即可继续**(上下文全保留):
  `Agent(resume="agent-8")`,恢复指令已下发过:先保编译绿、砍范围也要绿、余量写 `wave1-notes.md`。
- 除协议层外全部波次完成;协议层前的测试基线:**1165 tests / 0 failures**。

## 已完成(全部经构建/测试门槛)

| 波次 | 内容 | 关键产出 |
|---|---|---|
| Wave 0 | v2 前端 5 路审计 | `research/v2_audit/{README,A1..A5}.md` |
| Wave 2.1 | 设计 token 按 v2 oc-2 校准 | `OcTheme.kt` 10 新字段、双 scheme 语法色、动效值;`colors_oc.xml` 双 scheme Markwon 色(主 agent 改) |
| Wave 2.2a | markdown 表格+Prism4j 高亮+代码块 chrome | `ui/markdown/*`,21 新测试 |
| Wave 2.2b | 工具卡/timeline 12 项(真 hunk diff、ToolErrorCard、ANSI 剥离等) | `ui/session/timeline/*`,30 新测试 |
| Wave 2.3a | 设置/调色板/终端 14 项(Providers 连接、Models 可见性、41 行快捷键表、palette 25 命令) | `ui/{settings,palette,terminal}/*`,24 新测试;注意它动了 `SettingsStore.kt` 和 4 个手写字符串(已记录) |
| Wave 2.3b | Home/导航壳(移动全标签抽屉、行菜单、搜索、继承) | `ui/{home,nav}/*`,17 新测试 |
| Wave 2.3c | i18n 从 v2 重采 | en1870/zh1411/zht1411,`research/_i18n_v2/` + `strings_oc_i18n.xml` |
| 集成 | App.kt 接线 I-1…I-6、palette 装配、ModelPicker 可见性过滤 | agent-17,6 新测试;share 键确认上游 v2 已删(走 legacy shim,合理) |
| 修复 | 6 个失败测试(agent-16)、表格零宽 bug(agent-18,`TableLayout.kt` 新算法) | agent-18 的真机截图验证**待补**(被协议层红挡) |

## 真机冒烟结论(Tablet @5556,v1 4096)

通过:Home 分组、时间线、composer、流式对话(muse-spark-1.3-contributor)、路由恢复。
bug:纯表格消息零宽(已修待拍图)。技巧:Gboard 中文模式下 `input text` 会被拼音吃掉,逐词 `text`+ENTER 可输入但 ENTER 会触发发送(composer 回车即发送,与官方一致)。adb 截图用 `exec-out screencap -p > f.png`;Git Bash 下 adb 参数记得 `MSYS_NO_PATHCONV=1` 或 `//sdcard/`。

## 明早续作顺序

1. `Agent(resume="agent-8")` 继续协议层 → 落地后全量 `testDebugUnitTest + assembleDebug`(assemble 若 lint-gradle 报错则**不要** `--offline`)。
2. 双机 `install -r` + 重加 `adb reverse tcp:4095/4096` → 补拍 `research/v2_verify_shots/fix_table_render.png`(android-e2e 会话应有 3×3 表格)。
3. 协议层遗留从 `research/v2_audit/wave1-notes.md` 接力(**下一个新 agent 用 command-code**,上一个是 go-response,保持 1:1 分流;模型别名 `opencode-go-response/muse-spark-1.3-contributor` 已验证可用)。
4. Wave 2.4:composer queue/steer + 结构化 @mention(依赖协议层 inbox/prompt delivery)。
5. Wave 4:双机(Fold 5554 + Tablet 5556)× v1(4096)+ v2(4095,Basic opencode/opencode)交互验收,与 v2 官方前端并排比对(dev server 需重启:`cd _upstream/packages/app && bun dev --port 4444`,依赖已装好;或直接用 4095 内嵌 SPA)。

## 环境备忘

- 构建:`JAVA_HOME='/c/Program Files/Android/Android Studio/jbr'`(JBR 21;系统默认 JDK25 会崩 Kotlin daemon)。
- serve:v1 @4096(1.18.31,无认证)、v2 @4095(2.0.11,Basic opencode/opencode,无 v1 端点)。
- v2 探测信号:`GET /api/info` JSON;`/api/health` 是 404,勿用。
- `_upstream` sparse-checkout 已扩到全 `/packages/` + `/patches/`(bun install 需要);曾被 Git Bash 路径转换毁过一次,勿用 `git sparse-checkout set` 带 `/` 开头的参数。
- 模拟器:5554=Fold 内屏 2076x2152@390(桌面分支),5556=Tablet 2560x1600@320(桌面分支);768dp 门。
