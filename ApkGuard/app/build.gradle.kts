import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the version: the VERSION file (CI checks it matches the git tag).
val appVersion = rootProject.file("VERSION").readText().trim()
require(Regex("""\d+\.\d+\.\d+""").matches(appVersion)) { "VERSION must be MAJOR.MINOR.PATCH, got '$appVersion'" }
val (vMajor, vMinor, vPatch) = appVersion.split('.').map { it.toInt() }

android {
    namespace = "com.apkguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apkguard.app"
        minSdk = 28
        targetSdk = 35
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
    }

    // Release signing comes from environment variables so no key is ever committed.
    val ksPath = System.getenv("APKGUARD_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("APKGUARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("APKGUARD_KEY_ALIAS")
                keyPassword = System.getenv("APKGUARD_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key, fall back to the debug key so the build is still installable.
            signingConfig = signingConfigs.getByName(if (ksPath != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin", "kotlin/**")
    }

    dependenciesInfo {
        // Don't embed the encrypted dependency blob; nothing here needs it.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Deliberately no third-party dependencies: smaller attack surface, nothing phones home.
dependencies {
    implementation(project(":core"))
}
