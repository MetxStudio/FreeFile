package com.freefile.share

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * 局域网设备发现（Sprint 5）：jmDNS（mDNS/Bonjour）。
 * 服务类型 _freefile._tcp.local.；同一 WLAN 内自动发现运行 FreeFile 快传的设备。
 *
 * 关键修复：
 *  1. 自连过滤：注册时记录本机 deviceName + 本机所有 IP，serviceResolved 时跳过自身。
 *  2. 关闭卡死：unregisterAllServices / close 移到后台协程，避免主线程阻塞。
 */
object ShareDiscovery {

    private const val TAG = "ShareDiscovery"
    private const val SERVICE_TYPE = "_freefile._tcp.local."
    private const val SERVICE_NAME_PREFIX = "FreeFile-"

    private var jmdns: JmDNS? = null
    private val discovered = LinkedHashMap<String, Pair<String, Int>>() // name → (host, port)

    /** 本机标识（注册时设置），用于过滤自发现。 */
    private var localDeviceName: String? = null
    /** 本机所有 IP（含 IPv4/IPv6 + loopback），用于按地址过滤自连。 */
    private val localAddressSet = mutableSetOf<String>()

    /** 发现回调（在 mDNS 线程触发，UI 自行处理线程切换）。 */
    var onDeviceAdded: ((name: String, host: String, port: Int) -> Unit)? = null
    var onDeviceRemoved: ((name: String) -> Unit)? = null

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // 需要请求解析以获取地址
            jmdns?.requestServiceInfo(SERVICE_TYPE, event.name)
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val name = event.name
            // 过滤自连：本机 deviceName 完全匹配
            if (name == localDeviceName) {
                Log.d(TAG, "skip self device: $name")
                return
            }
            // 过滤自连：本机所有 IP 都排除
            val host = info.inetAddresses.firstOrNull { addr ->
                !addr.isLoopbackAddress && addr.hostAddress !in localAddressSet
            } ?: run {
                Log.d(TAG, "skip self address for device: $name")
                return
            }
            synchronized(discovered) {
                discovered[name] = host.hostAddress to info.port
            }
            onDeviceAdded?.invoke(name, host.hostAddress, info.port)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            synchronized(discovered) { discovered.remove(event.name) }
            onDeviceRemoved?.invoke(event.name)
        }
    }

    /** 注册本机快传服务并开始发现。 */
    fun registerAndDiscover(deviceName: String, port: Int) = withContextIfNeeded {
        // 记录本机标识，用于 serviceResolved 过滤自连
        localDeviceName = deviceName
        localAddressSet.clear()
        // 收集本机所有 IP 地址（loopback + 局域网 IPv4/IPv6）
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .mapNotNull { it.hostAddress }
                .forEach { localAddressSet.add(it) }
        }
        val localIp = ShareClient.localIpAddress()
        Log.d(TAG, "register: name=$deviceName, localIp=$localIp, allLocalIps=${localAddressSet.joinToString { it }}")
        val addr = localIp?.let { InetAddress.getByName(it) } ?: InetAddress.getLocalHost()
        val j = JmDNS.create(addr, deviceName)
        jmdns = j
        val info = ServiceInfo.create(
            SERVICE_TYPE, deviceName, port, "FreeFile LAN Share"
        )
        j.registerService(info)
        j.addServiceListener(SERVICE_TYPE, listener)
    }

    /** 停止服务与发现（后台协程执行，避免主线程阻塞卡死）。 */
    fun unregister() {
        // 在独立线程释放：JmDNS.close() 内部 join 子线程可能阻塞数百毫秒
        Thread {
            try { jmdns?.removeServiceListener(SERVICE_TYPE, listener) } catch (e: Throwable) {
                Log.w(TAG, "removeServiceListener failed", e)
            }
            try { jmdns?.unregisterAllServices() } catch (e: Throwable) {
                Log.w(TAG, "unregisterAllServices failed", e)
            }
            try { jmdns?.close() } catch (e: Throwable) {
                Log.w(TAG, "jmdns.close failed", e)
            }
            jmdns = null
            synchronized(discovered) { discovered.clear() }
            localDeviceName = null
            localAddressSet.clear()
            Log.d(TAG, "unregister done")
        }.apply { isDaemon = true; name = "ShareDiscovery-unregister" }.start()
    }

    /** 当前发现的设备快照。 */
    fun devices(): Map<String, Pair<String, Int>> =
        synchronized(discovered) { LinkedHashMap(discovered) }

    private inline fun withContextIfNeeded(crossinline block: () -> Unit) {
        // mDNS 注册需要网络 IO，交给后台线程（内部自有线程池会阻塞启动，这里独立线程）
        Thread {
            try { block() } catch (e: Throwable) { Log.e(TAG, "registerAndDiscover failed", e) }
        }.apply { isDaemon = true; name = "ShareDiscovery-register" }.start()
    }

    suspend fun registerAndDiscoverSuspend(deviceName: String, port: Int) =
        withContext(Dispatchers.IO) { registerAndDiscover(deviceName, port) }
}
