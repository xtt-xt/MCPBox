// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SSH / SFTP 服务端（内置 SSH 工作区）。纯 Java 实现，能跑在 Android 上，
    // 也让 harness 能起一个真的 SSH 服务器做端到端测试。
    implementation("org.apache.sshd:sshd-core:2.12.1")
    implementation("org.apache.sshd:sshd-sftp:2.12.1")
    // MINA 的日志门面（2.x 只依赖 slf4j-api，不给绑定会往 stderr 刷告警）
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    // Ed25519：Android 的 JCE 没有 EdDSA，用这个纯 Java 实现补上
    // （MINA 会自动发现它并注册成 provider，不需要动系统的 BouncyCastle）
    implementation("net.i2p.crypto:eddsa:0.3.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
