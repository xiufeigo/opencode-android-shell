plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xiufeigo.opencodeshell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiufeigo.opencodeshell"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.3.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Shared debug key so local and CI APKs carry the same signature and
        // install over each other. The keystore file itself is never committed:
        // locally it is ~/.android/debug.keystore, CI restores it from Secrets
        // to $GITHUB_WORKSPACE/.ci-debug.keystore (see .github/workflows).
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
        }
        release {
            isMinifyEnabled = false
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    androidResources {
        noCompress += listOf("woff", "woff2", "wasm")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // `collectAsStateWithLifecycle` (used by the Compose root for theme + nav
    // state). Already cached locally; M2 had deferred it, M3/M4 code needs it.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // M5 phase 2 — vendored termux VT kernel (`research/06_terminal_selection.md` §1.6 A′):
    // `terminal-view` carries `terminal-emulator` transitively (`api`). Sources live in
    // `app/src/thirdparty/termux/` so they are copied together with the app module; the
    // project has no `externalNativeBuild`, hence no `libtermux.so` and no 16 KB page risk.
    implementation(project(":terminal-view"))
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    // Jetpack Compose (M0 native frontend foundation; OC2 theme in ui/theme).
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-web:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
