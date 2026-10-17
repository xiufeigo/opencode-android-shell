// W1 — WebView 宿主模块。
// 2026-10-07（第 3 次改）**全变体统一为正式包名 `com.xiufeigo.opencodeshell`**。
// 产品负责人拍板："包依旧是 `com.xiufeigo.opencodeshell` 这个哈。只有这个，
// 只有当前[构建]是 debug 时，才打 debug 包。" 定稿形态：
//   · debug / release / releaseR8 **都不带 `.debug` 后缀** ⇒ 设备上永远是同一个 App；
//   · CI 只发 **release 这一个**（`.github/workflows/build.yml`）；debug 包只在该构建
//     就是 debug 时产出（本地 `:webapp:assembleDebug`），**永不进 Release**；
//   · 签名沿用 secrets 里的 sharedDebug keystore —— **历史上的 GitHub Release 就是这么发的**：
//     老 CI 跑的是 `:app:assembleDebug`，而 `:app` 的 debug 变体没有后缀 ⇒ 包名同样是
//     `com.xiufeigo.opencodeshell`。所以"从 GitHub 装过包"的设备，这一版是**原地升级**。
//   · ⚠️ 本机自建的 APK 用本机 `~/.android/debug.keystore`（`220657df…`），与 CI 那把
//     （`760dc05f…`）**不是同一把** ⇒ 装到 CI 签名的设备上会 UPDATE_INCOMPATIBLE。
//     那是 keystore 的事、不是包名的事：要让两边互换，得让 `DEBUG_KEYSTORE_PATH`
//     指向同一份 keystore。
// 注意：namespace / Kotlin 包名仍是 `.web`（与 Android applicationId 无关，无需改代码）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiufeigo.opencodeshell.web"
    compileSdk = 35

    defaultConfig {
        // 全变体共用这一个包名（= 历史发版包的包名）。
        applicationId = "com.xiufeigo.opencodeshell"
        minSdk = 26
        targetSdk = 35
        // 27 原生壳版本线（1.4.0/26）续号；28 幻影 4173；29 弹层键盘；30 窄屏终端列数；
        // 31 终端字号设置项；32 IME 视口兜底；33 应用名；34 终端触摸滚动+resize 合并；
        // 35 触摸防 IME 聚焦、稳定滚动目标及手机/平板统一字符度量；
        // 36 自有 xterm 终端、历史滚动及键盘隐藏时即时恢复布局。
        // 37 多服务器管理、官方右侧栏入口及平板触摸分隔线修复。
        // 46 手机端 xterm 滚动条滑块视觉宽度缩窄，保留触摸命中区与列宽计算。
        // 47 官方 UI 升到 2.0.29，补齐其新增中文文案，并修正终端拖动“只滚动、不抢焦点、也不关键盘”的焦点规则。
        // 48 系统深浅色跟随：宿主把 uiMode 真值转交给页面依赖的 `prefers-color-scheme`，修真机切深色不换肤。
        versionCode = 48
        // 保持正式包名和原 CI 签名，允许从 v2.0.8 原地升级。
        versionName = "2.0.20"
    }

    signingConfigs {
        // 与 :app 用同一份 sharedDebug keystore，两个 APK 都能覆盖安装自己。
        create("sharedDebug") {
            val ksPath =
                System.getenv("DEBUG_KEYSTORE_PATH")
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            storeFile = File(ksPath)
            storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDebug")
            // 🔴 刻意**不加** `applicationIdSuffix = ".debug"`：包名必须与正式包一致
            //    （产品负责人："只有这个"）。代价是 debug 包与正式包互相覆盖 —— 装哪个是哪个，
            //    这正是要的语义。debug 包只在"该构建就是 debug"时产出，不进 CI 发布。
        }
        // 正式发布变体：**无 `applicationIdSuffix`** ⇒ 包名 = `com.xiufeigo.opencodeshell`。
        //
        // 🔴 与 E1（`.debug` 后缀）的差别，逐条记账：
        //   · E1 的理由是"与官方壳互不覆盖，可并存"；产品负责人 2026-10-07 两次改判：
        //     ① 要正式包名（GitHub 下载件必须是被安装应用的一次更新，而不是又一个新 App）；
        //     ② "只有这个"⇒ debug 变体也不再带后缀，全仓一个包名。
        //   · 于是设备上只会有一个 OpenCode；从 GitHub 装的旧包（1.4.0 / versionCode 26，
        //     同一把 sharedDebug 签名）会被这一版**原地升级**，数据不丢。
        //   · 想并存做 A/B 的老做法（`.debug` 子包）已退役；要并存只能在本地临时改
        //     `applicationIdSuffix`，别把改动提交回来。
        //
        // 🔴 F11（R8 试产轮）**故意收紧了这一块**：删掉 E1 留下的
        // `-Pwebapp.r8=true` 开关，改为**无条件非 R8**。
        // 理由（硬要求："不能因为试产把已验收的交付物换掉"）：
        // 开关式的隐患是**同一个输出路径** `outputs/apk/release/webapp-release.apk`
        // 在带不带 `-P` 时**静默变成两份不同的包**——只要有人（或 CI）顺手带上那个
        // 开关，**已验收的分发物就被 R8 版顶替**了，而且从变体列表上看不出来。
        // 写成常量之后，这条路在**结构上**就不存在。
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("sharedDebug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // F11：R8 试产变体，**独立 buildType**（不是开关）。
        // · 独立 ⇒ 输出路径 `outputs/apk/releaseR8/webapp-releaseR8.apk`，
        //   与 release 的产物**永不互相覆盖**，"哪份是发布件"在变体列表里也不会混淆。
        // · 继承 release ⇒ applicationId 同样**无后缀**（`com.xiufeigo.opencodeshell`），
        //   两版**互相覆盖安装**，这正是同机同轮 A/B 对比需要的。
        // · 真正的混淆规则在 `proguard-rules.pro`（F11 已按 12 项风险逐条落盘）。
        // ⚠️ 不加 `isDebuggable = true`：C1 契约（`DebugEvalChannelTest`）要求
        //   release 侧 debug 通道保持关闭，`releaseR8` 同样保持关闭。
        // ⚠️ R8 变体**不进 CI 发布**（只有 release 进）——它是本地 A/B 用的试产品。
        create("releaseR8") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    // 上游 `@opencode-ai/app` 的 dist 带 woff/woff2/wasm（xterm 等），
    // 与 :app 同样不压缩，否则 asset 会被 deflate 两次。
    androidResources {
        noCompress += listOf("woff", "woff2", "wasm")
    }
}

dependencies {
    // 全部与 :app 同版本 → 已在本地 Gradle 缓存里，不引入新的下载依赖。
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // WebViewAssetLoader + WebViewCompat.addDocumentStartJavaScript
    implementation("androidx.webkit:webkit:1.12.1")
    // EncryptedSharedPreferences（宿主偏好不许明文落盘）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // UX6：回环代理已删，OkHttp 不再是依赖（WebView 自己直连后端）。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

// IME1 绊线（IME_OCCLUSION_FIX.md §5）：`ImeOcclusionTest` 的「资产契约层」直接读
// `src/main/assets/www/oc-host.js`，断言 IME 抬升修法真的在注入层里。
//
// **为什么必须显式声明**：单测任务默认不把 `src/main/assets` 当输入。
// 只改注入层、不改任何 .kt 时，`testDebugUnitTest` 会被判为 UP-TO-DATE **整段跳过**，
// 绊线于是"假绿"——本轮真跑验证过：撤回修法后 gradle 仍然 BUILD SUCCESSFUL。
// 声明成输入后，注入层一改，测试必重跑；撤回修法 ⇒ 必红。
//
// C1 绊线（`CLEANUP_C1.md` §1/§2）：同样是「资产/构建脚本契约」型断言，必须显式声明 ——
//  `FallbackPageTouchTargetTest` 直接读 `assets/www/index.html`；
//  `DebugEvalChannelTest` 直接读 `build.gradle.kts` + `WebHostActivity.kt`。
//  不声明的话，撤回 `min-height: 48px` 或改 buildTypes 都会让任务被判 UP-TO-DATE 而"假绿"。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf(
        "src/main/assets/www/oc-host.js",
        "src/main/assets/www/oc-terminal.js",
        "../frontend/terminal/terminal.js",
        "src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt",
        "src/main/assets/www/index.html",
        "build.gradle.kts",
        "src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt",
        "src/main/java/com/xiufeigo/opencodeshell/web/WebHostApp.kt",
        // F18 绊线：`PhoneFormDefectTest` 的「资产契约层」要读 InsetsHost.kt
        // （断言 replay() 存在）与新纯函数件本身。撤回任一处修法都必须能反向变红。
        "src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt",
        "src/main/java/com/xiufeigo/opencodeshell/web/host/PhoneFormDefect.kt",
        // UX4 绊线：`Ux4ThemeTripwireTest` 断言面板不硬编码深色（oc-host.js 已在上列）
        // 且窗口背景按模式给（values/colors.xml + values-night/colors.xml 成对存在）。
        "src/main/res/values/colors.xml",
        "src/main/res/values-night/colors.xml",
    ).forEach { rel ->
        inputs.file(layout.projectDirectory.file(rel))
            .withPropertyName("contractAsset_$rel")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
}
