plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.typezero.resound"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.typezero.resound"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "0.8.0-dev.13"

        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/MikereDD/Resound/main/releases/update.json\"",
        )
        // Development channel for release-signed "-dev" builds (stable URL above is unchanged).
        buildConfigField(
            "String",
            "UPDATE_DEV_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/MikereDD/Resound/main/releases/development/release-manifest.json\"",
        )
        // Public SHA-256 of the Resound release signing certificate; updates must carry exactly it.
        buildConfigField(
            "String",
            "UPDATE_RELEASE_CERT_SHA256",
            "\"3aaba0c794a652511c65272f7cf9210432a23069f5a27bd2d700c06891bed499\"",
        )

        // Public output folder under Music/ and test-variant switch (overridden by devtest).
        buildConfigField("String", "OUTPUT_SUBDIR", "\"Resound\"")
        buildConfigField("boolean", "IS_DEVTEST", "false")

        // FFmpeg ships large native libs — restrict ABIs to keep the APK sane.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        // Isolated device-test variant: separate package, label, icon colour, public folder.
        create("devtest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devtest"
            isDebuggable = true
            matchingFallbacks += "debug"
            buildConfigField("String", "OUTPUT_SUBDIR", "\"Resound DEV TEST\"")
            buildConfigField("boolean", "IS_DEVTEST", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        compose = true
        buildConfig = true
    }

    // Kotlin sources live under src/main/kotlin per repo convention.
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // Host-side unit tests (JUnit 4.13.2, already in the local Gradle cache).
    testImplementation("junit:junit:4.13.2")
    // Real org.json for host tests of the update-manifest parser (Android provides it at runtime).
    testImplementation("org.json:json:20250517")

    // ── FFmpeg engine ───────────────────────────────────────────────────────
    // ffmpeg-kit was retired by arthenica and pulled from Maven Central. This is
    // a community republish rebuilt for the 16KB page size that Android 15 / API
    // 35 requires (the old 4KB-aligned 6.0 binaries SIGBUS on modern devices).
    // It keeps the original com.arthenica.ffmpegkit wrapper API and pulls
    // smart-exception transitively. Swap the coordinate if you prefer another
    // republish (e.g. io.github.maitrungduc1410:ffmpeg-kit-audio:6.0.1).
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")
}
