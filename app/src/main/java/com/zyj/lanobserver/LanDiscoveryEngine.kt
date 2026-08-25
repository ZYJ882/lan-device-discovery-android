package com.zyj.lanobserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 仅用于当前活动局域网的只读设备发现。
 *
 * 设备结果来自三类公开可见信号：DNS-SD/mDNS 服务公告、UPnP/SSDP 响应，以及少量常见
 * 服务端口的 TCP 建连成功。该类不会尝试登录设备、读取文件、执行命令或探测漏洞。
 */
class LanDiscoveryEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.applicationContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val identityResolver = DeviceIdentityResolver()
    private val ippIdentityResolver = IppIdentityResolver()
    private val upnpIdentityCache = ConcurrentHashMap<String, PublicDeviceIdentity>()

    /**
     * 优先返回本机移动热点的下游接口；热点未开启时，返回应用的活动局域网接口。
     *
     * 普通应用不能可靠读取系统 DHCP 客户端表，因此热点客户端仍通过其公开服务响应来发现。
     */
    fun networkSnapshot(): LanNetworkSnapshot? {
        val activeNetwork = connectivityManager.activeNetwork
        val activeProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
        val activeCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val activeSnapshot = activeProperties?.let { properties ->
            val localAddress = properties.linkAddresses
                .firstOrNull { address -> address.address is Inet4Address && !address.address.isLoopbackAddress }
                ?: return@let null
            val address = localAddress.address as Inet4Address
            val subnet = Ipv4Subnet.from(address, localAddress.prefixLength)
            val gateway = properties.routes
                .firstOrNull { route -> route.destination.prefixLength == 0 && route.gateway is Inet4Address }
                ?.gateway
                ?.hostAddress
            LanNetworkSnapshot(
                localIp = address.hostAddress.orEmpty(),
                gateway = gateway,
                interfaceName = properties.interfaceName.orEmpty(),
                transport = when {
                    activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
                    activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
                    activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                    else -> "当前网络"
                },
                actualCidr = subnet.cidrLabel,
                scanCidr = subnet.scanCidrLabel,
                subnet = subnet,
                isHotspot = false,
                hasVpn = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            )
        }
        return hotspotSnapshot(activeSnapshot, activeCapabilities) ?: activeSnapshot
    }

    private fun hotspotSnapshot(
        activeSnapshot: LanNetworkSnapshot?,
        activeCapabilities: NetworkCapabilities?
    ): LanNetworkSnapshot? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        val activeIsCellular = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        var bestCandidate: HotspotInterfaceCandidate? = null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) continue
            val name = networkInterface.name.orEmpty().lowercase()
            val address = networkInterface.interfaceAddresses
                .firstOrNull { item -> item.address is Inet4Address && item.address.isSiteLocalAddress }
                ?: continue
            val score = hotspotCandidateScore(name, activeSnapshot?.interfaceName, activeIsCellular)
            if (score <= 0) continue
            val candidate = HotspotInterfaceCandidate(networkInterface.name, address.address as Inet4Address, address.networkPrefixLength, score)
            if (bestCandidate == null || candidate.score > bestCandidate.score) bestCandidate = candidate
        }
        val candidate = bestCandidate ?: return null
        val subnet = Ipv4Subnet.from(candidate.address, candidate.prefixLength.toInt())
        return LanNetworkSnapshot(
            localIp = candidate.address.hostAddress.orEmpty(),
            gateway = candidate.address.hostAddress,
            interfaceName = candidate.interfaceName,
            transport = "移动热点",
            actualCidr = subnet.cidrLabel,
            scanCidr = subnet.scanCidrLabel,
            subnet = subnet,
            isHotspot = true,
            hasVpn = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        )
    }

    private fun hotspotCandidateScore(
        interfaceName: String,
        activeInterfaceName: String?,
        activeIsCellular: Boolean
    ): Int = when {
        interfaceName.contains("softap") || interfaceName.startsWith("ap") || interfaceName.contains("tether") -> 100
        activeIsCellular && (interfaceName.startsWith("wlan") || interfaceName.startsWith("wifi")) -> 80
        activeIsCellular && interfaceName != activeInterfaceName?.lowercase() -> 60
        else -> 0
    }

    private data class HotspotInterfaceCandidate(
        val interfaceName: String,
        val address: Inet4Address,
        val prefixLength: Short,
        val score: Int
    )

    suspend fun scan(
        snapshot: LanNetworkSnapshot,
        onDevice: (LanDevice) -> Unit,
        onProgress: (LanScanProgress) -> Unit
    ): LanScanSummary = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val registry = DeviceRegistry(onDevice)
        registry.upsert(
            LanDevice(
                id = "local:${snapshot.localIp}",
                displayName = if (snapshot.isHotspot) "本机热点" else "本机",
                hostname = null,
                addresses = setOf(snapshot.localIp),
                ports = emptySet(),
                services = setOf(if (snapshot.isHotspot) "热点网关" else "本机"),
                sources = setOf(if (snapshot.isHotspot) "本机热点网络信息" else "本机网络信息"),
                manufacturer = null,
                deviceHint = "Android 设备",
                details = mapOf(
                    "网络接口" to snapshot.interfaceName,
                    "网络模式" to if (snapshot.isHotspot) "移动热点" else snapshot.transport
                ),
                lastSeenAt = startedAt
            )
        )

        // 热点下游流量可能被系统或 VPN 中间层处理。对整个子网做裸 TCP connect 会把
        // 中间层接受的连接误判为目标设备开放端口，因此热点和 VPN 场景只采用真实广播响应。
        val canSweepTcp = !snapshot.isHotspot && !snapshot.hasVpn
        val tcpHostCount = if (canSweepTcp) snapshot.subnet.scanHosts().size else 0
        val multicastLock = acquireMulticastLock()
        try {
            onProgress(
                LanScanProgress(
                    when {
                        snapshot.isHotspot -> "正在查找热点客户端公开的 mDNS 与 UPnP 服务"
                        snapshot.hasVpn -> "检测到 VPN，已跳过 TCP 子网扫掠以避免误报"
                        else -> "正在查找 mDNS、UPnP 及常见网络服务"
                    },
                    0,
                    tcpHostCount
                )
            )
            val mdns = async(Dispatchers.IO) {
                discoverMdns(registry)
            }
            val ssdp = async(Dispatchers.IO) {
                discoverSsdp(registry)
            }
            val tcp = async(Dispatchers.IO) {
                if (canSweepTcp) probeCommonServices(snapshot, registry, onProgress)
            }
            awaitAll(mdns, ssdp, tcp)
            LanScanSummary(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                discoveredCount = registry.snapshot().size,
                scannedHostCount = tcpHostCount
            )
        } finally {
            multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        wifiManager?.createMulticastLock("lan-device-discovery").also { lock ->
            lock?.setReferenceCounted(false)
            lock?.acquire()
        }
    }.getOrNull()

    private suspend fun discoverMdns(registry: DeviceRegistry) {
        // 采用常见、公开的 DNS-SD 服务类型；只处理设备自己广播出的元数据。
        val serviceTypes = listOf(
            "_http._tcp.",
            "_https._tcp.",
            "_ipp._tcp.",
            "_printer._tcp.",
            "_airplay._tcp.",
            "_googlecast._tcp.",
            "_ssh._tcp.",
            "_smb._tcp."
        )
        serviceTypes.forEach { serviceType ->
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                discoverOneServiceType(serviceType, registry)
            }
        }
    }

    private suspend fun discoverOneServiceType(serviceType: String, registry: DeviceRegistry) {
        suspendCancellableCoroutine { continuation ->
            var started = false
            var stopRequested = false
            lateinit var listener: NsdManager.DiscoveryListener

            fun requestStop() {
                if (stopRequested) return
                stopRequested = true
                if (started) {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                } else if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    started = true
                    mainHandler.postDelayed({ requestStop() }, MDNS_WINDOW_MILLIS)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    resolveMdnsService(serviceInfo, registry)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.removeCallbacksAndMessages(null)
                if (started) runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            runCatching {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    private fun resolveMdnsService(service: NsdServiceInfo, registry: DeviceRegistry) {
        runCatching {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    val address = host?.hostAddress
                    val attributes = serviceInfo.attributes
                        .mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8) }
                        .filterValues { it.isNotBlank() }
                    val serviceLabel = serviceInfo.serviceType.removePrefix("_").removeSuffix("._tcp.")
                    val publicIdentity = MdnsIdentityNormalizer.normalize(attributes, serviceInfo.serviceName)
                    registry.upsert(
                        LanDevice(
                            id = address?.let { "ip:$it" } ?: "mdns:${serviceInfo.serviceName}:${serviceInfo.serviceType}",
                            displayName = publicIdentity.friendlyName ?: serviceInfo.serviceName,
                            hostname = host?.hostName,
                            addresses = address?.let { setOf(it) } ?: emptySet(),
                            ports = serviceInfo.port.takeIf { it > 0 }?.let { setOf(it) } ?: emptySet(),
                            services = setOf(serviceLabel),
                            sources = setOf("mDNS"),
                            manufacturer = publicIdentity.manufacturer,
                            deviceHint = publicIdentity.model ?: classifyService(serviceLabel),
                            details = attributes + mapOf("服务实例" to serviceInfo.serviceName) + publicIdentity.asDetails(),
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                    if (serviceLabel == "ipp" && host != null && serviceInfo.port > 0 && address != null) {
                        val ippResourcePath = attributes.entries.firstOrNull { (key, _) -> key.equals("rp", ignoreCase = true) }?.value
                        resolveIppIdentityAsync(host, serviceInfo.port, ippResourcePath, address, serviceInfo.serviceName, registry)
                    }
                }
            })
        }
    }

    private fun resolveIppIdentityAsync(
        host: InetAddress,
        port: Int,
        resourcePath: String?,
        address: String,
        fallbackName: String,
        registry: DeviceRegistry
    ) {
        Thread({
            val identity = ippIdentityResolver.resolve(host, port, resourcePath) ?: return@Thread
            registry.upsert(
                LanDevice(
                    id = "ip:$address",
                    displayName = identity.name ?: identity.makeAndModel ?: fallbackName,
                    hostname = host.hostName,
                    addresses = setOf(address),
                    ports = setOf(port),
                    services = setOf("IPP"),
                    sources = setOf("IPP 标准属性"),
                    manufacturer = null,
                    deviceHint = identity.makeAndModel ?: "网络打印设备",
                    details = identity.asDetails(),
                    lastSeenAt = System.currentTimeMillis()
                )
            )
        }, "ipp-identity").start()
    }

    private suspend fun discoverSsdp(registry: DeviceRegistry) = withContext(Dispatchers.IO) {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 800
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))
                val deadline = System.currentTimeMillis() + SSDP_WINDOW_MILLIS
                while (System.currentTimeMillis() < deadline && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val buffer = ByteArray(4096)
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                        val headers = parseHeaders(String(response.data, 0, response.length, StandardCharsets.UTF_8))
                        val address = response.address.hostAddress.orEmpty()
                        val server = headers["server"]
                        val type = headers["st"] ?: headers["nt"] ?: "UPnP 设备"
                        val location = headers["location"]
                        val cacheKey = "$address|${location.orEmpty()}"
                        val identity = location?.let { publicLocation ->
                            upnpIdentityCache[cacheKey] ?: identityResolver
                                .resolveUpnpDescription(publicLocation, address)
                                ?.also { resolved -> upnpIdentityCache[cacheKey] = resolved }
                        }
                        registry.upsert(
                            LanDevice(
                                id = "ip:$address",
                                displayName = identity?.friendlyName ?: identity?.modelName ?: server ?: type,
                                hostname = null,
                                addresses = setOf(address),
                                ports = setOf(SSDP_PORT),
                                services = setOf("UPnP / SSDP"),
                                sources = setOf("UPnP"),
                                manufacturer = identity?.manufacturer,
                                deviceHint = identity?.modelName ?: identity?.modelDescription ?: classifySsdp(type, server),
                                details = headers.filterKeys { it in SSDP_DETAIL_KEYS } + (identity?.asDetails().orEmpty()),
                                lastSeenAt = System.currentTimeMillis()
                            )
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        // 到达短超时后继续等待，直至 SSDP 窗口结束。
                    }
                }
            }
        }
    }

    private suspend fun probeCommonServices(
        snapshot: LanNetworkSnapshot,
        registry: DeviceRegistry,
        onProgress: (LanScanProgress) -> Unit
    ) = coroutineScope {
        val hosts = snapshot.subnet.scanHosts().filter { it.hostAddress != snapshot.localIp }
        val semaphore = Semaphore(TCP_CONCURRENCY)
        hosts.mapIndexed { index, address ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val openPorts = COMMON_TCP_SERVICES.filter { service ->
                        isTcpPortOpen(address, service.port)
                    }
                    if (openPorts.isNotEmpty()) {
                        val ip = address.hostAddress.orEmpty()
                        registry.upsert(
                            LanDevice(
                                id = "ip:$ip",
                                displayName = "设备 · $ip",
                                hostname = null,
                                addresses = setOf(ip),
                                ports = openPorts.map { it.port }.toSet(),
                                services = openPorts.map { it.label }.toSet(),
                                sources = setOf("常见服务探测"),
                                manufacturer = null,
                                deviceHint = classifyPorts(openPorts.map { it.port }.toSet()),
                                details = mapOf("探测范围" to "仅常见服务端口"),
                                lastSeenAt = System.currentTimeMillis()
                            )
                        )
                    }
                    onProgress(LanScanProgress("正在检查当前子网的常见服务", index + 1, hosts.size))
                }
            }
        }.awaitAll()
    }

    private fun isTcpPortOpen(address: InetAddress, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TCP_CONNECT_TIMEOUT_MILLIS)
            true
        }
    }.getOrDefault(false)

    private fun parseHeaders(message: String): Map<String, String> = message
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val index = line.indexOf(':')
            if (index > 0) line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim() else null
        }
        .toMap()

    private fun classifyService(label: String): String = when (label.lowercase()) {
        "ipp", "printer" -> "网络打印设备"
        "airplay", "googlecast" -> "媒体播放设备"
        "ssh" -> "远程管理设备"
        "smb" -> "文件共享设备"
        "http", "https" -> "提供 Web 服务的设备"
        else -> "局域网服务设备"
    }

    private fun classifySsdp(type: String, server: String?): String = when {
        type.contains("MediaRenderer", ignoreCase = true) -> "媒体播放设备"
        type.contains("MediaServer", ignoreCase = true) -> "媒体服务器"
        server?.contains("router", ignoreCase = true) == true -> "网络网关设备"
        else -> "UPnP 设备"
    }

    private fun classifyPorts(ports: Set<Int>): String = when {
        631 in ports && 9100 in ports -> "可能的打印设备（服务特征）"
        445 in ports -> "可能的文件共享设备（服务特征）"
        22 in ports -> "可能的远程管理设备（服务特征）"
        80 in ports || 443 in ports -> "提供 Web 服务的设备（服务特征）"
        else -> "响应常见网络服务的设备（服务特征）"
    }

    private class DeviceRegistry(private val publish: (LanDevice) -> Unit) {
        private val devices = ConcurrentHashMap<String, LanDevice>()

        fun upsert(incoming: LanDevice) {
            val primaryKey = incoming.addresses.firstOrNull()?.let { "ip:$it" } ?: incoming.id
            val merged = devices.compute(primaryKey) { _, existing ->
                if (existing == null) incoming.copy(id = primaryKey) else existing.merge(incoming).copy(id = primaryKey)
            } ?: incoming
            publish(merged)
        }

        fun snapshot(): List<LanDevice> = devices.values.toList()
    }

    companion object {
        private const val MDNS_WINDOW_MILLIS = 900L
        private const val SSDP_WINDOW_MILLIS = 3_000L
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val TCP_CONNECT_TIMEOUT_MILLIS = 220
        private const val TCP_CONCURRENCY = 24
        private val SSDP_DETAIL_KEYS = setOf("server", "st", "usn", "location", "cache-control")
        private val COMMON_TCP_SERVICES = listOf(
            TcpService(80, "HTTP"),
            TcpService(443, "HTTPS"),
            TcpService(22, "SSH"),
            TcpService(445, "SMB"),
            TcpService(631, "IPP"),
            TcpService(9100, "JetDirect")
        )
    }
}

data class LanNetworkSnapshot(
    val localIp: String,
    val gateway: String?,
    val interfaceName: String,
    val transport: String,
    val actualCidr: String,
    val scanCidr: String,
    val subnet: Ipv4Subnet,
    val isHotspot: Boolean = false,
    val hasVpn: Boolean = false
)

data class LanScanProgress(
    val message: String,
    val completedHosts: Int,
    val totalHosts: Int
)

data class LanScanSummary(
    val startedAt: Long,
    val finishedAt: Long,
    val discoveredCount: Int,
    val scannedHostCount: Int
)

data class LanDevice(
    val id: String,
    val displayName: String,
    val hostname: String?,
    val addresses: Set<String>,
    val ports: Set<Int>,
    val services: Set<String>,
    val sources: Set<String>,
    val manufacturer: String?,
    val deviceHint: String,
    val details: Map<String, String>,
    val lastSeenAt: Long
) {
    fun merge(other: LanDevice): LanDevice = copy(
        displayName = preferredName(displayName, other.displayName),
        hostname = hostname ?: other.hostname,
        addresses = addresses + other.addresses,
        ports = ports + other.ports,
        services = services + other.services,
        sources = sources + other.sources,
        manufacturer = manufacturer ?: other.manufacturer,
        deviceHint = preferredHint(deviceHint, other.deviceHint, other.sources),
        details = details + other.details,
        lastSeenAt = maxOf(lastSeenAt, other.lastSeenAt)
    )

    private fun preferredName(current: String, candidate: String): String = when {
        current.startsWith("设备 · ") && !candidate.startsWith("设备 · ") -> candidate
        current == "UPnP 设备" && candidate != "UPnP 设备" -> candidate
        else -> current
    }

    private fun preferredHint(current: String, candidate: String, candidateSources: Set<String>): String = when {
        candidateSources.any { it == "IPP 标准属性" } -> candidate
        current == "局域网服务设备" -> candidate
        current.contains("服务特征") && !candidate.contains("服务特征") -> candidate
        else -> current
    }
}

data class TcpService(val port: Int, val label: String)

/** IPv4 子网计算被限制为最多扫描本机所在 /24，避免对大网段发起过量连接。 */
data class Ipv4Subnet private constructor(
    private val addressValue: Int,
    private val prefixLength: Int,
    private val effectivePrefixLength: Int
) {
    val cidrLabel: String get() = "${toAddress(networkAddress(prefixLength)).hostAddress}/$prefixLength"
    val scanCidrLabel: String get() = "${toAddress(networkAddress(effectivePrefixLength)).hostAddress}/$effectivePrefixLength"

    fun scanHosts(): List<InetAddress> {
        val hostBits = 32 - effectivePrefixLength
        val count = if (hostBits >= 31) 0 else (1 shl hostBits) - 2
        val network = networkAddress(effectivePrefixLength)
        return (1..count).map { offset -> toAddress(network + offset) }
    }

    private fun networkAddress(prefix: Int): Int {
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return addressValue and mask
    }

    private fun toAddress(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    )

    companion object {
        fun from(address: Inet4Address, prefixLength: Int): Ipv4Subnet {
            val value = address.address.fold(0) { accumulator, byte -> (accumulator shl 8) or (byte.toInt() and 0xff) }
            val sanitizedPrefix = prefixLength.coerceIn(1, 30)
            // 对 /16、/20 等大型家庭或办公网络，仅检查本机所在的 /24；小网段保持原始边界。
            val effectivePrefix = maxOf(sanitizedPrefix, 24)
            return Ipv4Subnet(value, sanitizedPrefix, effectivePrefix)
        }
    }
}
