package com.xtt.mcpbox

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class Endpoint(
    val label: String,
    val host: String,
    val url: String
)

object NetUtil {

    /** 所有可用的本机 IPv4 地址（局域网地址排在前面）。 */
    fun localAddresses(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>() // ip to label
        runCatching {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: ""
                if (name.startsWith("rmnet") || name.startsWith("dummy")) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val label = when {
                            name.startsWith("wlan") -> "Wi-Fi"
                            name.startsWith("eth") -> "有线/热点"
                            name.startsWith("ap") || name.contains("softap") -> "热点"
                            ip.startsWith("192.168.") || ip.startsWith("10.") ||
                                ip.startsWith("172.") -> "局域网"
                            else -> name
                        }
                        result.add(ip to label)
                    }
                }
            }
        }
        return result.sortedBy { if (it.first.startsWith("192.168.")) 0 else 1 }
    }

    /** AI 客户端要用的连接地址。 */
    fun endpoints(port: Int, token: String?, preferLan: Boolean): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        val lan = localAddresses()
        lan.forEach { (ip, label) ->
            out.add(Endpoint(label, ip, buildUrl("http", ip, port, token)))
        }
        out.add(Endpoint("本机", "127.0.0.1", buildUrl("http", "127.0.0.1", port, token)))
        return if (preferLan) out else out.sortedBy { it.host }
    }

    fun buildUrl(scheme: String, host: String, port: Int, token: String?): String {
        val base = "$scheme://$host:$port/mcp"
        return if (token.isNullOrBlank()) base else "$base?token=$token"
    }

    fun consoleUrl(host: String, port: Int, token: String?): String {
        val base = "http://$host:$port/"
        return if (token.isNullOrBlank()) base else "$base?token=$token"
    }

    fun localIpv4(): String? = localAddresses().firstOrNull()?.first
}
