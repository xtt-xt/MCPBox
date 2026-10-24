// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":mcpcore"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 测试客户端：用一个真的 SSH 客户端去连内置 SSH 服务端
    implementation("com.github.mwiede:jsch:0.2.20")
}

kotlin {
    jvmToolchain(17)
}

tasks.register<JavaExec>("e2e") {
    group = "verification"
    description = "Run the MCP server end-to-end test on the JVM"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.xtt.mcpbox.harness.MainKt")
    standardInput = System.`in`
}
