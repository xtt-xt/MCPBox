// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xtt.mcpbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xtt.mcpbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.6.4"
        resourceConfigurations.add("zh")
    }

    signingConfigs {
        create("release") {
            // 优先用环境变量（CI / 别人的机器），没有就回退到仓库外的本地密钥
            val ksPath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/keystore/release.keystore"
            val ksFile = file(ksPath)
            if (ksFile.exists() && ksFile.length() > 100) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "mcpbox2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "mcpbox"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "mcpbox2026"
            }
        }
    }

    buildTypes {
        release {
            // 没有密钥时就出未签名的包，不至于让构建直接失败
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":mcpcore"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Shizuku：用 ADB shell 身份执行命令（需要用户在 Shizuku 里授权）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
