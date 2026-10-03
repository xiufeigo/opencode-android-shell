【目标】把 Android 壳工程（仓库 C:\sensorsdata\main\program\opencode-android-shell，包名 com.xiufeigo.opencodeshell）持续推进到与上游 https://github.com/anomalyco/opencode/tree/v2 功能、界面、后端接入三线一致，并对照 https://opencode.ai/v2/docs/api 补齐全部 API 面。已决策不做 V1 兼容，只接 V2 后端（opencode serve 2.0.18 @ http://127.0.0.1:4095，Basic auth opencode:opencode）。

【完成判据】全部成立才算完：
1. 全量闸门 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL，XML 汇总 failures=0 / errors=0，tests≥5368、suites≥595（当前基线，只增不减）。
2. 上游 v2 功能与 opencode.ai/v2/docs/api 逐项对照，无「未实现」项；有意偏离以 research/HANDOFF.md「有意偏离（终审表用，不许改回）」为准，20+ 条保持不回退。
3. 3 台模拟器（5554 Medium_Desktop / 5556 Small_Desktop / 5558 Pixel_9a）全功能真机复测通过：会话改名回滚、设置短窗 Save、420dpi 页头、SSE 自愈、断网保稿、chord、导出、stop 结算等；新 bug 清零，或逐条给出「不修+理由」。
4. research/HANDOFF.md 残留队列清空或逐条结案。

【范围】只动 C:\sensorsdata\main\program\opencode-android-shell（app/、frontend/、scripts/、research/）；_upstream/ 只读对照；不改用户其他项目。禁止：taskkill 杀 qemu、测试时按 BACK、子 agent 批量读截图（每 agent 最多 2-3 张，30 张上限会毒化上下文）。

【每轮循环】
1. `curl -u opencode:opencode http://127.0.0.1:4095/api/info` 探活；后端楔死（TCP accept 但 HTTP 000）先看 /tmp/wedge/watchdog.sh 是否自愈，不行再手动拉起。
2. 并行派多路测试（全部用 `custom_provider:minimax-cn-coding-plan/MiniMax-M3.1-Flash-Preview`，推理强度 default，无限量、无上限并行；按域切，互不重叠）→ 新 bug 记录：现象/复现步骤/预期 vs 实际/截图路径/设备 serial。
3. 分诊剔误报 → 并行派多路修复（拆细，无上限）→ 每个修复必须补/改 JVM 单测 pin（过时 pin 才更新且保强度）→ 主 agent 拿 .agent-gradle-lock 跑全量闸门 → adb install -r 三台 → 复验 → 下一轮。

【环境铁律】
- 后端在 WSL Arch；健康检查只用 GET /api/info。恢复楔死：`wsl.exe -d Arch -- bash -lc "kill -9 <pid>; /tmp/wedge/start_serve.sh 4095 /tmp/oc4095.log"`（wsl --shutdown 会清 /tmp，脚本需重建；看门狗不在就重挂）。
- 模拟器：`/c/Users/xiufe/AppData/Local/Android/Sdk/emulator/emulator.exe -avd <名> -no-snapshot-save -no-boot-anim`，必须后台跑（前台会 SIGPIPE 杀）。5560 Pixel_10_Pro_XL 已放弃（AVD 锁僵尸）。adb=`/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe`。杀单个僵尸先读 hardware-qemu.ini.lock/pid 精准杀。127.0.0.1:5555 是额外 TCP 设备，用前确认归属，别混进 E2E。
- Gradle：`export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`；闸门 `until mkdir .agent-gradle-lock; do sleep 2; done` + `trap 'rmdir .agent-gradle-lock' EXIT`（别用 mkdir -p；强杀后手动 rmdir）。子 agent 只跑精确 `--tests` 逐类名（通配会被 shell glob 吞）；全量闸门只归主 agent，cleanTestDebugUnitTest 必须带。计数从 app/build/test-results XML 汇总。

【测试纪律】会话标题带设备前缀 E2ECHK<serial>，只操作自己前缀；input text 空格必须 %s，长串 ≤8 字符分段 sleep 1.5；uiautomator 可能串台/滞后，以 screencap 为准；Ctrl+K = `keycombination 113 39`（75 是撇号）；导出走 ⋮→Export session（别和 Move to… 目录选择器混淆）；app 内模型用白名单（opencode/space-bunny-free、opencode-go/muse-spark-1.3-contributor、command-code/meta/muse-spark-1.3-contributor 等，403 就换 server default 并标注）；已知误报别当 bug：Gboard 对 / 自动补全、后端楔死时段 500/超时、他人会话干扰。

【模型分工】
- 测试：全部用 `custom_provider:minimax-cn-coding-plan/MiniMax-M3.1-Flash-Preview`（推理强度 default，minimax 无限量，可无上限并行）。
- 修复（按 UTC 时间窗选模型，星期与时刻均按 UTC 判定）：周一至周五 01:00-04:00、06:00-10:00 UTC 全部用 muse-spark——`custom_provider:opencode-go-response/muse-spark-1.3-contributor` + `custom_provider:commandcode/meta/muse-spark-1.3-contributor`（均推理强度 high，必须英文沟通）；其余时间（含周末）全部用 deepseek——`custom_provider:opencode-go/deepseek-v4.1-flash` + `custom_provider:commandcode/deepseek/deepseek-v4.1-flash`（均推理强度 high）。同一时间窗内 opencode 系与 commandcode 两路轮询均衡启动。修复禁止用 minimax。死链别派：`custom_provider:小杰/mimo-v2.6-pro`。测试/修复子 agent 无上限并行。

【接手起点：v85 三修已落地、闸门已绿（595 suites / 5368 tests / 0 fail），第一轮先装机复验】
1. #784 会话改名失败即退编辑+回滚结构化（resolveSave 双答案都关编辑器）+ 草稿回填互斥（takeRestore(key, editorText) 仅空编辑器收）→ 5558 验：改名失败不丢结构、草稿不粘连。
2. #785 chromeImeBand 纯 body 三档（0/48/84dp）→ 5554 验 420dpi 页头/tab 不再整条消失。
3. #786 设置短窗 chrome 折叠（settingsTopChromeCollapsed）+ 骨架错误态（isColumnLoading，ProbePending 不拖 error）→ 5556 验设置短窗表单 Save 可达、骨架出错不卡死。

【低优残留（HANDOFF.md 有全表）】①yieldsToIme 按显示高度，420dpi 起键盘 shell 条占 52dp（blast radius 大）；②外部 POST 建的会话 Home 列表不刷新（SSE session.created 未接）；③坏目录会话搜索不到（新鲜度）；④/mnt/c/x 类坏目录项目条目残留；⑤chip a11y 无模型名（上游同款，不修）；⑥SshAuthenticate/SettingsModalOverlay footer 未钉；⑦saving 永不返回窗口行留编辑态。

【停止规则】后端持续楔死且看门狗救不回、Gradle 锁被占/闸门无法收敛、或上游对照出现无法判定的设计分歧（该不该实现、算不算有意偏离）→ 停下汇报现状与证据，不要硬造通过、不要自行扩大范围。发现上游 v2 有本清单外的新面 → 先列对照清单再动手。
