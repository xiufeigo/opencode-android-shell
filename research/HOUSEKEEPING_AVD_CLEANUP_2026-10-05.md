# AVD 清理记录（2026-10-05）

产品负责人指令：「清了那个 avd，然后提交并推送到远端，同时触发打包，再把 debug 的包提供给我」

## 删除范围：只删我们自己建的、且 F17 建议可删的 10 个
判据（写死，不是临时判断）：
- 只允许 `OC_*` 前缀或 `Medium_Desktop_B`（脚本里有硬闸，出现别的名字就直接抛错中止）；
- 正在运行的实例一个都不许在删除名单里（跑前逐台查 AVD 名，命中的话直接中止）。

删除前实测：**正在跑的只有 `emulator-5672` = `T91_Phone`（产品负责人的）**，不在名单内 ⇒ 安全。

## 已删除（10 个，逐个确认"目录已消失 + 已不在 list-avds"）
| AVD | 回收 |
|---|---|
| OC_Phone_Test | 6.55 GiB |
| OC_Phone_Test_B | 8.35 GiB |
| OC_Phone_Test_C | 11.58 GiB |
| OC_Preflight_V3 | 5.25 GiB |
| OC_Preflight_WebView83 | 8.60 GiB |
| OC_Perf_Test | 6.61 GiB |
| OC_Narrow_Test | 5.61 GiB |
| OC_FF_F4 | 5.30 GiB |
| OC_Notify_Test | 5.25 GiB |
| OC_NotifyRC | 5.23 GiB |
| **合计** | **68.33 GiB** |

方式：`avdmanager delete avd -n <name>`（标准路径，未手改任何 `.ini`）。

## 保留（2 个，理由见 F17 §5.1）
- `OC_Deliver_R2`（7.35 GiB）—— 交付验证用，被多轮报告引用最多；
- `Medium_Desktop_B`（6.77 GiB）—— 桌面形态对照用。

若这两个也要删，一句话即可，合计再回收 14.12 GiB。

## 未动的东西（硬边界）
产品负责人的 32 个实例（`MCRemote_*` / `DSH_*` / `Pixel_*` / `T*` / `Medium_Desktop` / `Small_Desktop` 等，221.96 GiB）**一个都没碰**；正在跑的 `T91_Phone` 未受影响。
