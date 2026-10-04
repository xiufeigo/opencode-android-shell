// W1 — WebView 宿主模块（骨架）。
// 独立 applicationId，与现有原生壳 `com.xiufeigo.opencodeshell[.debug]` 并存，
// 便于同一台设备上 A/B 对比：两个 launcher 图标、两个 pm 条目、互不覆盖。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiufeigo.opencodeshell.web"
    compileSdk = 35

    defaultConfig {
        // `.debug` 由 buildTypes 的 applicationIdSuffix 拼出，debug 构建的最终 id 是
        // `com.xiufeigo.opencodeshell.web.debug`（与原生壳的 debug id 也不撞）。
        applicationId = "com.xiufeigo.opencodeshell.web"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-webhost"
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
            applicationIdSuffix = ".debug"
        }
        // E1 发布工程：release 变体**对齐原生壳约定**（app/build.gradle.kts 的 release 块）——
        // 同一份 `sharedDebug` 签名 + `applicationIdSuffix = ".debug"`，
        // 于是 web 壳的 release 是个"可安装、与官方壳互不覆盖"的包，而不是只装得上的调试包。
        //
        // R8 是**开关式**的：默认关（release 产物与非 R8 完全一致），
        // 要实验产物时显式 `-Pwebapp.r8=true`，`isShrinkResources` 一并跟随。
        // 用开关而不是新建 `releaseR8` buildType，是为了不把变体集合撑大
        // （会多出 lintVitalReleaseR8 / testReleaseR8 之类的任务），
        // 也免得"哪份是发布件"在变体列表里出现两个候选。
        release {
            val r8 = providers.gradleProperty("webapp.r8").orNull == "true"
            isMinifyEnabled = r8
            isShrinkResources = r8
            signingConfig = signingConfigs.getByName("sharedDebug")
            applicationIdSuffix = ".debug"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    // EncryptedSharedPreferences（getSecret/setSecret 不许明文落盘）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // R1 回环代理的实际上游客户端。与 :app 同版本 4.12.0 → 已在本地 Gradle 缓存里。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
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
        "src/main/assets/www/index.html",
        "build.gradle.kts",
        "src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt",
    ).forEach { rel ->
        inputs.file(layout.projectDirectory.file(rel))
            .withPropertyName("contractAsset_$rel")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
}
