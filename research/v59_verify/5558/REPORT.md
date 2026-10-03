# v1.4.0 修复验收 — emulator-5558 (Pixel_9a, 1080x2424, density 420)

- 包：com.xiufeigo.opencodeshell（09-29 02:1x 构建）
- 会话前缀：E2ECHK5558（本次实际创建的会话未能改名，见「备注」）
- 模型：仅 space-bunny-free（Space Bunny Free）
- 截图：research/v59_verify/5558/

## 结果汇总

| # | 项目 | 结果 |
|---|------|------|
| 1a | Enter 发送 | PASS |
| 1b | 长按发送键换行（草稿两行可见可编辑） | PASS |
| 1c | 换行后 Enter 完整发送 | PASS |
| 2 | 空内容发送键置灰 / 输入后恢复 | PASS |
| 3 | 权限卡丢字逐字符复核 | PASS（附带条件，见说明） |
| 4 | 短窗 720x1280 一排式 composer | PASS |
| 5 | 模型选择器 / 更多菜单 Esc + 无选中态泄漏 | PASS |
| 5b | Home 长按上下文菜单 Esc | **FAIL** |
| 6 | P0 连建 3 次会话项目路径无长重复段 | PASS |
| 7 | 清理 E2ECHK5558-* 会话 | PASS（4 个自建会话已删） |

## 明细

### 1. 多行草稿（PASS）
- 1a：输入 `LINEABQ1` 后 keyevent 66 → composer 清空、发送键切为「停止」。截图 07。
- 1b：输入 `ALPHA` 后 `input swipe 990 1462 990 1462 800`（长按发送键）→ EditText text = `ALPHA&#10;BRAVO`，两行同屏可见，随后继续输入 `BRAVO` 成功，说明仍可编辑。截图 10 / 10c。
- 1c：换行后 keyevent 66 → composer 清空进入忙碌态；重启 app 后转录区出现该条消息，气泡文本为两行 `ALPHA BRAVO`，右侧跳转列表条目亦为 `ALPHA BRAVO`。截图 15。

### 2. 发送禁用态（PASS）
- 空内容：发送键 `View content-desc=发送 bounds=[927,1399][1053,1525] enabled=false`。截图 04。
- 输入 `LINEABQ1` 后：同一节点 `enabled=true`。截图 06。

### 3. 权限卡丢字复核（PASS，条件受限）
- 发送 `run df /tmp`（空格用 %s 转义），在其进入忙碌/重渲染的瞬间用 10 个独立 keyevent（8..16,7）连打 `1234567890`。
- 结果：EditText text = `1234567890`，10 字符一个不丢。
- 条件说明：本机 shell 的权限请求卡没有出现（`run df /tmp` 被直接以普通命令执行并返回输出，未产生授权卡），所以复核是在「发送态重渲染」这一等价高负载时刻做的，没有覆盖到真·权限卡渲染分支。逐字符不丢，说明历史上怀疑的 adb 批量注入问题本次未复现。

### 4. 短窗 minimal composer（PASS）
- `wm size 720x1280` + `wm density 420`（成对设置），重启 app，点输入框弹键盘。
- 一排式 composer 完整可见：`+` / `Space Bunny Free ▾` / 发送键三者互不遮挡，模型 chip 文字未被截断也未被发送键压住。发送键在空内容时为置灰态。截图 13 / 13c。
- 测完已 `wm size reset` + `wm density reset`，`wm size` 回到 Physical 1080x2424，density 420。

### 5. 弹层不串 + Esc（PASS）
- 模型选择器：tap 模型 chip 打开（15/16），Esc（keyevent 111）一次关闭（17）。
- 关闭后 composer 模型 chip 仍为 `Space Bunny Free`、发送键回到空内容置灰态 → 无选中态泄漏。
- 会话「更多选项」菜单（重命名/新建会话/重开会话/删除/导出/复制会话/移动到项目/新建副本会话/更多设置/清空会话历史）打开后（18），Esc 一次关闭（19），同样无状态残留。

### 5b. Home 长按上下文菜单不响应 Esc（FAIL · 四段式）

1. 现象：Home 会话列表长按某行弹出的上下文菜单（重命名 / 导出会话… / 导入会话 / 删除…），按 Esc（keyevent 111）**两次均不关闭**，菜单持续停留（截图 29 / 30）。同一页面里模型选择器、更多菜单都是一次 Esc 即关，说明是该弹层自身没接 dismiss 回调。
2. 复现步骤：Home → 任意会话行 `input swipe 400 1920 400 1920 800`（长按 800ms）→ 菜单弹出 → `input keyevent 111` ×2 → 菜单仍在。
3. 预期：任意浮层 Esc（keyevent 111）两次内关闭；实际需点击菜单外部才能关。
4. 影响 / 建议：Home 长按菜单属于高频入口（重命名、删除都在这里），键盘/外接键盘用户无法用 Esc 退出，必须盲点击空白处。给该 DropdownMenu/Popup 补 `onDismissRequest`（Compose `Popup` 的 `dismissOnBackPress` / `DropdownMenu` 的 `onDismiss`）并确保 Esc 走同一 dismiss 通道。

### 6. P0 快验（PASS）
- 在 `opencode-android-shell` 项目连建 3 次会话，3 个终端分别 `pwd`：
  - `/mnt/c/sensorsdata/main/program/opencode-android-shell`（三个会话一致，截图 26c / 27c2 / 27c3）
- 路径无 `.../opencode-android-shell/opencode-android-shell/...` 之类长重复段，终端 8 的 cwd 也正确显示为 `opencode-android-shell`（截图 25c）。

### 7. 清理（PASS）
- 本轮自建 4 个会话已全部删除：`LINEABQ1`（v36projC）+ 3 × `Untitled`（opencode-android-shell）。删除后列表仅剩其他测试者的 v36projC 会话。截图 33。
- 全程未按 BACK；wm 已 reset。

## 备注（需产品/开发确认）
1. **会话改名没成功**：按「Home 长按 → Rename」路径，长按确实弹出了带「重命名」的上下文菜单，但执行到「重命名」对话框那一步在本轮没有走通，因此 4 个会话一直保持自动名（Untitled / 首条消息名 LINEABQ1）。清理时是按「项目 + 创建时间 + 内容」逐条确认归属后删除的，只删了自己的 4 条。
2. **发送后转录区不实时刷新**：Enter 发送后，composer 立刻清空并进入「停止」态，但转录区仍显示「构建任何内容」空态；切到终端 tab 再切回、或重启 app 后才看到已发出的 3 条消息。功能没丢（消息确实到达并有 assistant 回复），但用户视角是「发出去了却什么都没出现」，体验上像丢了。建议排查发送后转录列表的乐观插入 / 刷新时机。
