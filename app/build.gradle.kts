plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nobi.nobistream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nobi.nobistream"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.2.0"
        // Telegram API credentials are injected ONLY from CI secrets at build
        // time (env NOBISTREAM_API_ID / NOBISTREAM_API_HASH). They are never
        // written in source. Without them the fields are 0 / empty and the
        // user enters the credentials in the app's Settings screen instead.
        val telegramApiId = System.getenv("NOBISTREAM_API_ID")?.toIntOrNull() ?: 0
        val telegramApiHash = System.getenv("NOBISTREAM_API_HASH") ?: ""
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField(
            "String",
            "TELEGRAM_API_HASH",
            "\"" + telegramApiHash.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        )
    }

    signingConfigs {
        getByName("debug") {
            val keystorePath = System.getenv("NOBISTREAM_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                val keystoreFile = file(keystorePath)
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storeType = "PKCS12"
                    storePassword = System.getenv("NOBISTREAM_KEYSTORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("NOBISTREAM_KEY_ALIAS") ?: "nobistream"
                    keyPassword = System.getenv("NOBISTREAM_KEY_PASSWORD") ?: ""
                }
            }
        }
        create("release") {
            val keystorePath = System.getenv("NOBISTREAM_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                val keystoreFile = file(keystorePath)
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storeType = "PKCS12"
                    storePassword = System.getenv("NOBISTREAM_KEYSTORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("NOBISTREAM_KEY_ALIAS") ?: "nobistream"
                    keyPassword = System.getenv("NOBISTREAM_KEY_PASSWORD") ?: ""
                }
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    // Media3 (ExoPlayer) for video playback.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    // Room for playback positions, saved groups and cached video metadata.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore for settings, EncryptedSharedPreferences for api_id / api_hash.
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Image loading for video thumbnails from local files.
    implementation(libs.coil)

    // List item animations (wasabeef recyclerview-animators, from the
    // awesome-android-ui list Sam picked).
    implementation(libs.recyclerview.animators)

    implementation(libs.kotlinx.coroutines.android)

    // TDLib (MTProto) prebuilt AARs from Maven Central.
    // TDLib 1.8.64, all 4 ABIs, 16KB page-size ready. No local NDK build needed.
    implementation(libs.tdlib.core)
    implementation(libs.tdlib.ktx)

    testImplementation(libs.junit)
}
