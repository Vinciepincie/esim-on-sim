import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key, from ESIM_* environment variables or else a git-ignored keystore.properties at the repo root (see
// the names below). Through providers, so the configuration cache notices when either changes.
val keystoreProperties = providers.fileContents(layout.settingsDirectory.file("keystore.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) } }
val releaseKey = listOf(
    "ESIM_KEYSTORE" to "storeFile", "ESIM_KEYSTORE_PASSWORD" to "storePassword",
    "ESIM_KEY_ALIAS" to "keyAlias", "ESIM_KEY_PASSWORD" to "keyPassword",
).map { (variable, key) ->
    providers.environmentVariable(variable).orElse(keystoreProperties.map { it.getProperty(key) }).orNull
}

android {
    namespace = "com.esimonsim"
    // Android 17 QPR2, the newest SDK.
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.esimonsim"
        // Android 6: the oldest the current Compose and AndroidX releases support.
        minSdk = 23
        // Android 17: finding Wireless debugging's ports over mDNS needs the Nearby devices permission (asked
        // for just before pairing or connecting); the loopback connections themselves don't.
        targetSdk = 37
        versionCode = 3
        versionName = "0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only: skip the x86/x86_64 copies of the native libs (emulators, Chromebooks).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    androidResources {
        // The UI is English-only; drop the ~80 translations bundled by the libraries.
        localeFilters += "en"
    }

    // Same app ID and signature, so either build installs over the other and keeps the pairing.
    flavorDimensions += "services"
    productFlavors {
        // Scans QR codes with its own camera screen. No Google code: works without Play services.
        create("standalone") {
            dimension = "services"
            isDefault = true
        }
        // Scans with Play services' scanner (no camera permission prompt), falling back to the app's own.
        create("google") {
            dimension = "services"
            versionNameSuffix = "-google"
            proguardFile("proguard-google.pro")
        }
    }

    signingConfigs {
        if (releaseKey.any { it != null }) create("release") {
            // Half a key stops the build: falling back to the debug key would ship APKs that can't update each other.
            val (store, storePass, alias, keyPass) = releaseKey.filterNotNull().also {
                check(it.size == 4) { "Release signing needs ESIM_KEYSTORE, ESIM_KEYSTORE_PASSWORD, ESIM_KEY_ALIAS and " +
                    "ESIM_KEY_PASSWORD, or storeFile, storePassword, keyAlias and keyPassword in keystore.properties." }
            }
            storeFile = layout.settingsDirectory.file(store).asFile
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The debug key is for local builds only: the release workflow refuses to publish with it.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // Sideloaded, so the APK is the download: native libs and dex are both compressed. Android extracts only
        // the phone's own ABI. Compressed dex makes the download about 1.4 MB smaller and the installed size about
        // 1.5 MB larger, as Android then keeps an uncompressed copy in the vdex. Where it keeps that vdex private to
        // the app, as on API 34 after a profile-guided compile, the shell-uid card helper can't map it, so the
        // helper also holds an inflated copy of the dex in about 3 MB of anonymous memory.
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/kotlin/**",
            "DebugProbesKt.bin", "kotlin-tooling-metadata.json",
            // BouncyCastle's data files (~1.2 MB of post-quantum tables, CertPathReviewer messages): libadb
            // only uses its HKDF and AES-GCM classes for pairing, which read none of them.
            "/org/bouncycastle/**/*.properties", "/org/bouncycastle/pqc/**",
        )
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log in tests
    }
    lint {
        // Deliberate, see defaultConfig: phones only (no x86_64/ChromeOS).
        disable += listOf("ChromeOsAbiSupport")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Pinned by hash, with its spake2-android, in gradle/verification-metadata.xml: a bump needs new hashes there.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Android 11 only: the one pairing call with no public API there (org/conscrypt/Conscrypt.java).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Card access through Shizuku, where it's installed. No desugaring needed: see apdu/ShizukuClient.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // QR codes, decoded on the phone with ZXing: from screenshots, and from the app's own camera screen.
    implementation("com.google.zxing:core:3.5.4")
    // The google build scans with Play services instead, when it can. play-services-base (ModuleInstall, to
    // fetch the scanner early) stays on 18.10.1: 18.11 needs Android 7.
    "googleImplementation"("com.google.android.gms:play-services-code-scanner:16.1.0")
    //noinspection GradleDependency: newer releases need Android 7
    "googleImplementation"("com.google.android.gms:play-services-base:18.10.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub on the JVM; the card helper's tests run its real protocol.
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
