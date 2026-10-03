# v71 — worktree 行尾 × 的命中区（emulator-5556，density 160，`wm size 1366x768`）

被测的是 5556 上已装的 v70 构建（`lastUpdateTime 2026-09-29 11:43`，与测试员
`research/v70_verify/5556` 同一份）。探针脚本：`probe.py`（每次探测前先把 chip 复位到
Local，保证几何一致；`uiautomator dump` 在 popup 打开时只 dump popup 那个窗口，
所以 pre-dump 里看不到 chip，post-dump 才看得到）。原始 dump：`dd.xml`（下拉的节点）、
`pre2.xml`/`post2.xml`（点行体前后）。

## 几何（chip = Local 时的稳定状态）

| 节点 | bounds |
|---|---|
| 菜单 popup（= `Column(width = 200.dp)`） | x 641..841 |
| worktree 行（`WorkspaceMenuRow` 的 clickable） | x 641..841，y 501..529（200×28） |
| × 的 a11y 节点（desc=Delete worktree） | x 803..841，y 501..529（38×28） |

× 的 16 dp 像素盒 = x 819..835（= 行右缘 841 − 6 dp 内边距 − 16 dp），
其手指环 = 盒 ±16 dp = **x 803..851、y 491..539**（Compose 按
`minimumTouchTargetSize = 48 dp` 在 16 dp 盒外自己补的环，与
`ocMinTouchTarget(visual = 16.dp)` 的 48 dp 节点重合）。

## 实测（点 (x, 515)，除注明外）

| 点 | 结果 |
|---|---|
| 700,515 | 行选中（chip → lucky-planet）——行体正常 |
| 803 / 808 / 813 / 817 / 819 / 826 / 834 / 836 / 839,515 | **删除确认弹窗**（`Delete worktree?`） |
| 841,515 | 关菜单（未选中）——popup 窗口的右边界 |
| 844,515 | 关菜单（未选中） |
| 826,503 / 826,527 | 删除确认弹窗 |
| 826,533（行下方 4 px，落在 "Refresh worktrees" 行内） | **删除确认弹窗**（× 的环赢了相邻行） |
| 826,497（行上方 4 px，"Worktrees" 分组标签） | 无反应（菜单仍开着） |

## 结论

1. **× 的接线是对的**：点 16 dp 盒（以及盒外环内）一律打开删除确认，
   `WorktreeDeleteConfirmDialog` 正常出现。v70 报告的「× 点击永远触发行选中」
   **不复现**——环落在行体上时是 × 赢（子节点在手势竞争里先拿那一击），行体没有抢。
2. 真正的缺陷是几何：**环的右半（x 841..851）落在 popup 自己的边界之外**，
   那一带进的是 scrim，菜单只会关掉，删除确认永远不出现。
   测试员那批坐标（845/854/858/870/875/895/910）全在这条带子或更外侧，
   所以「下拉收起」——而 `34_x_clicked`/`39_xtries` 里 chip 还是 Local（**没有选中**），
   「标签切到该 worktree」是更早那次刻意点行（`41_row_click`）留下的 chip。
   同一带子的竖直邻居也一样：环还盖住 "Refresh worktrees" 行的上沿 10 px。
3. 修法：行尾内边距留出整个环溢出（`WORKSPACE_ROW_END = touchTargetRingOverflow(16.dp)`
   = 16 dp，上游 `padding-inline-end: 6px` 是鼠标尺寸），环的右缘落在菜单自己的边上。
   预测（**本轮无 APK 可装，未上机复验**）：793..839 全部 → 删除确认，841+ → 关菜单。
