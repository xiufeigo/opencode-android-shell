# 本地 Android 签名

用户指定本机打包使用以下文件：

- 密钥：`C:/sensorsdata/main/packages/debug.keystore`。
- 同一密钥的备份：`C:/sensorsdata/main/packages/android-debug-keystore-cursor.zip`；其中的 `README.txt` 提供 alias、storepass 和 keypass。

后续本机 Gradle 打包必须通过现有的 `DEBUG_KEYSTORE_PATH`、`DEBUG_KEYSTORE_PASSWORD`、`DEBUG_KEY_ALIAS`、`DEBUG_KEY_PASSWORD` 环境变量使用这份密钥。凭据从压缩包说明读取，仅传给本次 Gradle 进程；不得输出凭据、提交密钥或密码，也不要修改用户全局环境或 `~/.android/debug.keystore`。

预期证书 SHA-256：`760dc05fb4d1a125ed82cf9bff8eee622c7244d44b6ddc82a4c34e0b37b820e6`，与现有 GitHub Release 的 CI 签名一致。文件缺失或证书不匹配时明确报错，不自行生成或改用另一把密钥。CI 继续使用仓库已有的签名 Secrets。

产品打包入口为 `:webapp:assembleRelease`；只有明确需要 debug 构建时使用 `:webapp:assembleDebug`。打包后用 `apksigner verify --print-certs` 核对证书。
