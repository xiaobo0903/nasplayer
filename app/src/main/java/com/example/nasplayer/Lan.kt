package com.example.nasplayer

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class LanHost(val ip: String, val name: String)

private fun portOpen(ip: String, port: Int = 445): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(ip, port), 500); true }
} catch (e: Exception) { false }

/** 扫描本机所在 /24 网段中开放 SMB(445) 端口的设备；每发现一台就在主线程回调。 */
suspend fun scanLan(ctx: Context, onFound: (LanHost) -> Unit) = withContext(Dispatchers.IO) {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val self = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }?.address?.address ?: throw IllegalStateException("未连接到局域网")
    val prefix = "${self[0].toInt() and 255}.${self[1].toInt() and 255}.${self[2].toInt() and 255}"
    val me = self[3].toInt() and 255
    val sem = Semaphore(64)
    coroutineScope {
        for (i in 1..254) {
            if (i == me) continue
            launch {
                sem.withPermit {
                    val ip = "$prefix.$i"
                    if (portOpen(ip)) {
                        // 反向 DNS 取设备名，超时则只显示 IP
                        val name = withTimeoutOrNull(1500) { async { InetAddress.getByName(ip).canonicalHostName }.await() }
                            ?.takeIf { it != ip } ?: ip
                        withContext(Dispatchers.Main) { onFound(LanHost(ip, name)) }
                    }
                }
            }
        }
    }
}
