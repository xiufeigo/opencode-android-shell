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
        versionCode = 26
        versionName = "1.4.0"
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

    packaging {
        resources {
            // Both `com.github.mwiede:jsch` and `org.bouncycastle:bcprov-jdk18on`
            // are multi-release jars that ship the *same* OSGi manifest under
            // META-INF/versions/N, so `:app:mergeDebugJavaResource` aborts on the
            // duplicate. Those entries are pure OSGi build metadata — Android
            // neither reads them nor D8 ever sees them — so dropping them is
            // behaviour-neutral. The bouncycastle *classes* stay in the APK:
            // F14b (jsch ed25519) runs on bcprov's Ed25519Signer.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // Round 2: AGP aborts on the *next* same-named entry it walks into.
            excludes += "META-INF/versions/11/OSGI-INF/MANIFEST.MF"
            // Round 3: same story for the Java 15 OSGi manifest.
            excludes += "META-INF/versions/15/OSGI-INF/MANIFEST.MF"
            // v97 (④ cold start): bcprov also ships the Picnic post-quantum
            // KEM constant tables as *resources*
            // (`org/bouncycastle/pqc/crypto/picnic/lowmcL{1,3,5}.bin.properties`,
            // 1.21 MB raw / 1.21 MB stored) — pure data, no code. They are read
            // only by `org.bouncycastle.pqc.crypto.picnic.*`, which this app
            // never reaches: the launcher never registers a JCE provider
            // (no `Security.addProvider` / `BouncyCastleProvider` anywhere), and
            // the one bcprov path we use is jsch's ed25519 signature, which
            // resolves through `org.bouncycastle.crypto.signers.Ed25519Signer`
            // into `crypto.**` / `math.**` / `util.**` — never `pqc.**`.
            // Resource-only exclusion: no class is removed, so dex count, class
            // count and start-up behaviour are unchanged; this only shrinks the
            // APK (install/transfer), it does not speed up the first frame.
            // ⚠ If anyone ever adds a PQC algorithm, these tables must come back
            //   or the failure surfaces at runtime as a missing resource.
            excludes += "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties"
            excludes += "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties"
            excludes += "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties"
        }
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
    // OpenCode native capabilities: QR device pairing (`servers/connect/scanner`)
    // and SSH server connections (`servers/ssh/*`). zxing:core is decode+encode
    // only (no camera UI — the system scanner intent covers capture); jsch is the
    // SSH client behind `SshDialog`'s connection half.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.mwiede:jsch:0.2.24")
    // F14b: jsch's stock `ssh-ed25519` signing route points at its jce class,
    // whose jar *base* entry is a Java15+ stub (the real impl lives only in
    // META-INF/versions/15, which D8 never packs) — on device every ed25519
    // signature resolved to a dead stub and the public key was silently never
    // offered. `net.i2p.crypto:eddsa` is NOT the answer (no jsch 0.2.24 class
    // references net/i2p; its POM declares no deps): the runnable ed25519
    // implementation is jsch's `bc.*` family, which delegates to bcprov's
    // Ed25519Signer. Pinned by SshEd25519IdentityF14Test.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
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
