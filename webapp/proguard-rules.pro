# ─────────────────────────────────────────────────────────────────────────────
# webapp R8 keep 规则（F11 试产轮落盘）
#
# 依据：research/web/R8_ATTEMPT_F11.md §2「12 项风险 → keep 规则映射表」，
#       风险清单本体来自 research/web/WEB_RELEASE_BUILD.md §5.2。
# 落盘人：F11（R8 试产 + 设备验证）。写权限：本文件属本轮允许新建/修改的范围。
#
# 【本文件不做什么】刻意**不**用「整包 -dontshrink / -dontobfuscate」把混淆整体关掉：
# F11 的硬要求是「不许因为构建过不去就把混淆放宽到等于没混淆」。
# 下面每条规则都对应风险清单里的**具体一项**，且都是**定向**的（按注解 / 按类 / 按属性），
# 没有一条是全局关闭优化。
# ─────────────────────────────────────────────────────────────────────────────

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #1 / #4 —— WebView @JavascriptInterface 桥（最高频的静默故障）
#
# 为什么坏：`WebView.addJavascriptInterface` 走的是 **native 侧按方法名反射**：
# JS 侧 `window.OcHost.xxx()` 是**字符串查表**，不是虚方法表。R8 一旦把方法名
# 改掉或把方法整个删掉，**编译期与 JVM 单测都测不出来**，只在设备上表现为
# "桥调不通 / 返回 undefined"。这是一条典型的「装得上、跑起来静默死」的路。
#
# 对应规则：本文件下面的 `-keepclassmembers ... @JavascriptInterface <methods>;`
# ═══════════════════════════════════════════════════════════════════════════
-keepclassmembers class com.xiufeigo.opencodeshell.web.** {
    @android.webkit.JavascriptInterface <methods>;
}

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #1 / #2 / #4 / #5 / #11 / #12 —— 本模块自己的 11 个 Kotlin 文件
#
# 为什么 keep 整包（而不是全删掉这条规则）：
#   · 本模块**全部业务代码只有 11 个 .kt**（E1 §4.1 实测 assets/www 仅 25,366 B / 0.25%），
#     也就是说**自己的代码本来就几乎不占体积** —— 保留它的**收益损失接近于零**，
#     却一次性消灭了 #1 桥、#2 组件、#4 INJECT_NAME、#5 各 Host、#11 ::class.java、
#     #12 enum/data class 整整 6 项风险。
#   · **R8 的收益并不因此消失**：占 dex 94.31% 的是**依赖侧**（okhttp / kotlin-stdlib /
#     coroutines / androidx / tink），它们**不在**这个包名下，仍然被正常缩与混淆。
#     这不是「放宽到等于没混淆」，而是「用零成本的定向保留买下 6 项风险的确定性」。
#
# 风险 #12 补充：enum（ImeMode / Kind / StreamingAction / RoundStreamSignal）与
# `valueOf`/`values()` 都在本包内 ⇒ 被本条覆盖。
# ═══════════════════════════════════════════════════════════════════════════
-keep class com.xiufeigo.opencodeshell.web.** { *; }

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #7（🔴 最高风险）—— Tink / EncryptedSharedPreferences 静默降级
#
# 为什么坏：SecretStore.kt:63-71 用 MasterKey + EncryptedSharedPreferences 存口令。
#   Tink 内部**基于反射装配** keyset 与 AEAD primitive（Registry 装配、protobuf 解析、
#   注解处理器生成的 `*Proto` 类），混淆后典型表现**不是崩**，而是
#   `SecretStore.kt:76` 的 `Log.w("EncryptedSharedPreferences unavailable, falling back
#   to in-memory only")` 分支被触发 ⇒ **静默降级** ⇒ 口令不落盘 ⇒ 杀进程/重启后
#   要重新输密码。**这是 R8 最阴险的一种坏法：它不报错，只是悄悄换了行为。**
#   静态检查与单测都测不出来，**只有实机「杀进程重开还能免密」能测出来**。
#
# 对应规则：整包 keep Tink + androidx.security.crypto，**并保留 Signature/InnerClasses/
#   EnclosingMethod/Annotation 属性**（AOSP crypto 靠这些属性做反射装配）。
# ═══════════════════════════════════════════════════════════════════════════
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn androidx.security.crypto.**

# 反射装配需要的属性。默认的 proguard-android-optimize.txt 只保留部分属性，
# 这里显式补齐 Signature / InnerClasses / EnclosingMethod（安全库反射路径必需）。
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #7 附属 —— Tink/AOSP crypto 在无 BouncyCastle/Conscrypt 设备上的可选依赖
#
# 为什么写：okhttp 与 AOSP crypto 都会引用 org.conscrypt / org.bouncycastle /
#   org.openjsse / javax.annotation 等**可选**平台类；缺类时 R8 会**因为警告而失败**。
#   `-dontwarn` 只压制"缺可选类的警告"，**不改变任何已用代码的混淆/裁剪行为**，
#   因此**不属于**「放宽到等于没混淆」。
# ═══════════════════════════════════════════════════════════════════════════
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn sun.misc.**
-dontwarn com.android.org.conscrypt.**

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #8 / #9 —— okhttp 与 kotlinx-coroutines
#
# 两者**自带 consumer proguard 规则**（随 AAR/jar 打进 META-INF），主路径安全；
# 上面 #7 附属块里的 `-dontwarn okhttp3.internal.platform.**` 是**唯一**的补充
# （覆盖 platform provider 的缺失告警）。**不加** `-keep class okhttp3.** { *; }` ——
# 那是本模块最大的可缩体积之一，全 keep 等于把 R8 的收益直接扔掉。
# ═══════════════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #3 —— assets/www 注入层（oc-host.js / oc-perf.js / oc-preflight.js）
#
# 为什么不需要规则：**R8 与 shrinkResources 都不处理 `assets/`**（只处理 dex/ 与 res/）。
# `WebViewCompat.addDocumentStartJavaScript` 注入的是**字符串**，由 `assets.open()` 读文件。
# 风险 #3 的正确处置是「**不加任何规则**」—— 加了反而是错。
# ═══════════════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #10 —— res/ 收缩（isShrinkResources = true）
#
# 实测 res 树只有 7 个文件（drawable/ic_launcher_web_foreground.xml、
# mipmap-anydpi{,-v26}/ic_launcher_web.xml、values/{colors,strings,themes}.xml、
# xml/network_security_config.xml）。manifest 显式引用 @mipmap/ic_launcher_web、
# @string/app_name、@style/Theme.OcWebHost ⇒ shrinkResources 依据 manifest
# 引用图会**自动保留**这三项及其传递依赖。
# ⚠️ network_security_config 由 manifest `<network-security-config>` 引用，同样保留。
# ⚠️ 不加 `tools:keep` 兜底 —— 若真被裁掉，**设备上会看到（SetupDialog 文案/图标缺失）**，
#    本轮用实机截图证伪，而不是用规则掩盖。
# ═══════════════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════════════
# 风险 #6 —— androidx.webkit（WebViewAssetLoader / WebViewCompat / WebViewFeature）
# 自带 consumer proguard 规则；`isFeatureSupported` 是**常规静态调用**不是反射。
# 同 okhttp：**不加**整包 keep。
#
# 风险 #ServiceLoader —— 全量 grep `webapp/src/main` 无任何 `ServiceLoader` /
#   `Class.forName` 使用点（见 F11 §2 表末行）⇒ **不需要**任何 ServiceLoader 规则。
#   注解处理器：本模块**无** Dagger/Room/Glide 等注解处理器；Tink 的 `*Proto` 类
#   是**打包期**由上游 AAR 带入的既有产物，由上面的 Tink 整包 keep 覆盖。
#   kotlin-reflect：**依赖里没有** `kotlin-reflect`（build.gradle.kts:83-96 逐行可查），
#   故无"kotlin-reflect 使用点"需保留；Kotlin 运行时元数据靠 `-keepattributes` 与
#   本包整包 keep 覆盖。
# ═══════════════════════════════════════════════════════════════════════════
