// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xtt

package com.xtt.mcpbox.core

import java.net.Inet4Address
import java.net.NetworkInterface

/** 本机局域网地址（纯 JVM 实现，Android 上同样可用）。 */
object LocalNet {

    /** 所有非回环、已启用的 IPv4 地址。 */
    fun ipv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    /** 最像"局域网主地址"的那个（优先 192.168.x / 10.x）。 */
    fun primary(): String? {
        val all = ipv4()
        return all.firstOrNull { it.startsWith("192.168.") }
            ?: all.firstOrNull { it.startsWith("10.") }
            ?: all.firstOrNull { it.startsWith("172.") }
            ?: all.firstOrNull()
    }

    fun urls(port: Int): List<String> {
        val list = mutableListOf("http://127.0.0.1:$port")
        ipv4().forEach { list.add("http://$it:$port") }
        return list
    }
}
