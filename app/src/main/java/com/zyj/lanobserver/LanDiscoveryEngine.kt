package com.zyj.lanobserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 只读、多发现源的局域网设备发现引擎。
 *
 * 默认发现不扫描 TCP 端口；结果仅来自 ARP/邻居、mDNS、SSDP/UPnP 等可直接提供设备 IP 的证据。
 * 端口扫描只在用户进入一台已发现设备详情后手动执行。所有可控的网络请求均绑定到明确选择的 Wi‑Fi Network。
 */
class LanDiscoveryEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> mainHandler.post(command) }
    private val identityResolver = DeviceIdentityResolver()
    private val ippIdentityResolver = IppIdentityResolver()

    /** 明确选择实际承载 IPv4 的 Wi‑Fi Network，不将 VPN 作为扫描目标。 */
    fun networkSnapshot(): LanNetworkSnapshot? {
        val allNetworks = connectivityManager.allNetworks.toList()
        val vpnPresent = allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val wifiSnapshots = allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            val properties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            snapshotFromWifi(network, properties, vpnPresent, capabilities)
        }
        return wifiSnapshots.maxByOrNull { snapshot ->
            val caps = snapshot.network?.let(connectivityManager::getNetworkCapabilities)
            when {
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> 3
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true -> 2
                else -> 1
            }
        } ?: hotspotSnapshot(vpnPresent)
    }

    private fun snapshotFromWifi(
        network: Network,
        properties: LinkProperties,
        vpnPresent: Boolean,
        capabilities: NetworkCapabilities
    ): LanNetworkSnapshot? {
        val linkAddress = properties.linkAddresses.firstOrNull { address ->
            address.address is Inet4Address && !address.address.isLoopbackAddress
        } ?: return null
        val address = linkAddress.address as Inet4Address
        val subnet = Ipv4Subnet.from(address, linkAddress.prefixLength)
        val gateway = properties.routes
            .firstOrNull { route -> route.destination.prefixLength == 0 && route.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
        return LanNetworkSnapshot(
            network = network,
            localIp = address.hostAddress.orEmpty(),
            gateway = gateway,
            interfaceName = properties.interfaceName.orEmpty(),
            transport = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Wi‑Fi" else "本地 Wi‑Fi",
            actualCidr = subnet.cidrLabel,
            scanCidr = subnet.scanCidrLabel,
            subnet = subnet,
            isHotspot = false,
            hasVpn = vpnPresent
        )
    }

    /** 仅在未能获取 Wi‑Fi Network 时尝试识别本机热点下游接口；热点没有公开的 Network 句柄。 */
    private fun hotspotSnapshot(vpnPresent: Boolean): LanNetworkSnapshot? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        var candidate: HotspotInterfaceCandidate? = null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) continue
            val name = networkInterface.name.orEmpty().lowercase()
            if (!(name.contains("softap") || name.startsWith("ap") || name.contains("tether"))) continue
            val address = networkInterface.interfaceAddresses
                .firstOrNull { item -> item.address is Inet4Address && item.address.isSiteLocalAddress }
                ?: continue
            candidate = HotspotInterfaceCandidate(networkInterface.name, address.address as Inet4Address, address.networkPrefixLength)
            break
        }
        val selected = candidate ?: return null
        val subnet = Ipv4Subnet.from(selected.address, selected.prefixLength.toInt())
        return LanNetworkSnapshot(
            network = null,
            localIp = selected.address.hostAddress.orEmpty(),
            gateway = selected.address.hostAddress,
            interfaceName = selected.interfaceName,
            transport = "移动热点",
            actualCidr = subnet.cidrLabel,
            scanCidr = subnet.scanCidrLabel,
            subnet = subnet,
            isHotspot = true,
            hasVpn = vpnPresent
        )
    }

    suspend fun scan(
        snapshot: LanNetworkSnapshot,
        onDevicesChanged: (List<LanDevice>) -> Unit,
        onProgress: (LanScanProgress) -> Unit
    ): LanScanSummary = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val diagnostics = DiscoveryDiagnostics(snapshot)
        diagnostics.scanStarted()
        val registry = DeviceRegistry({ devices -> mainHandler.post { onDevicesChanged(devices) } }, diagnostics)
        registry.upsert(localDevice(snapshot, startedAt))

        val neighborRead = readNeighbors(snapshot, diagnostics)
        neighborRead.entries.forEach { registry.upsert(it.toLanDevice(startedAt)) }

        val multicastLock = acquireMulticastLock()
        diagnostics.multicastLock(multicastLock?.isHeld == true)
        try {
            onProgress(
                LanScanProgress(
                    message = "正在通过 ARP、mDNS 与 SSDP 识别已公开设备 IP；不会扫描端口",
                    completedHosts = 0,
                    totalHosts = 0
                )
            )
            val mdns = async(Dispatchers.IO) { discoverMdns(snapshot, registry, diagnostics, this@coroutineScope) }
            val ssdp = async(Dispatchers.IO) { discoverSsdp(snapshot, registry, diagnostics) }
            awaitAll(mdns, ssdp)
            val finalDevices = registry.snapshot()
            val finalDiagnostics = diagnostics.finish(
                rawObservations = registry.rawObservationCount(),
                deduplicatedDevices = finalDevices.size,
                scannedHosts = 0
            )
            LanScanSummary(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                discoveredCount = finalDevices.size,
                scannedHostCount = 0,
                hotspotNeighborCount = if (snapshot.isHotspot) neighborRead.entries.size else 0,
                hotspotNeighborCacheReadable = neighborRead.cacheReadable,
                diagnostics = finalDiagnostics
            )
        } finally {
            multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        }
    }

    private fun localDevice(snapshot: LanNetworkSnapshot, timestamp: Long) = LanDevice(
        id = "local:${snapshot.localIp}",
        displayName = if (snapshot.isHotspot) "本机热点" else "本机",
        hostname = null,
        addresses = setOf(snapshot.localIp),
        ports = emptySet(),
        services = setOf(if (snapshot.isHotspot) "热点网关" else "本机网络"),
        sources = setOf("本机网络信息"),
        manufacturer = null,
        deviceHint = "Android 设备",
        details = mapOf(
            "网络接口" to snapshot.interfaceName,
            "网络模式" to snapshot.transport,
            "名称来源" to "本机网络信息"
        ),
        lastSeenAt = timestamp
    )

    /** 只读当前接口的 ARP 邻居缓存。受平台限制时记录失败，不伪造设备。 */
    private fun readNeighbors(snapshot: LanNetworkSnapshot, diagnostics: DiscoveryDiagnostics): NeighborRead {
        val arp = File("/proc/net/arp")
        if (!arp.canRead()) {
            diagnostics.neighborCache(false, 0)
            return NeighborRead(false, emptyList())
        }
        return runCatching {
            val entries = arp.useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val values = line.trim().split(Regex("\\s+"))
                    if (values.size < 6 || values[2] != "0x2") return@mapNotNull null
                    val ip = runCatching { InetAddress.getByName(values[0]) as? Inet4Address }.getOrNull() ?: return@mapNotNull null
                    val mac = values[3].takeUnless { it.equals("00:00:00:00:00:00", ignoreCase = true) } ?: return@mapNotNull null
                    val interfaceName = values[5]
                    val correctInterface = interfaceName == snapshot.interfaceName || (snapshot.isHotspot && snapshot.subnet.contains(ip))
                    if (!correctInterface || ip.hostAddress == snapshot.localIp || !snapshot.subnet.contains(ip)) return@mapNotNull null
                    NeighborEntry(ip.hostAddress.orEmpty(), mac.lowercase(), interfaceName)
                }.distinctBy { it.ipAddress }.toList()
            }
            diagnostics.neighborCache(true, entries.size)
            NeighborRead(true, entries)
        }.getOrElse {
            diagnostics.neighborCache(false, 0)
            NeighborRead(false, emptyList())
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        wifiManager.createMulticastLock("lan-device-discovery").also { lock ->
            lock.setReferenceCounted(false)
            lock.acquire()
        }
    }.getOrNull()

    private suspend fun discoverMdns(
        snapshot: LanNetworkSnapshot,
        registry: DeviceRegistry,
        diagnostics: DiscoveryDiagnostics,
        scanScope: CoroutineScope
    ) = coroutineScope {
        val types = MDNS_SERVICE_TYPES
        val semaphore = Semaphore(MDNS_PARALLEL_DISCOVERIES)
        types.map { type ->
            async {
                semaphore.withPermit { discoverOneServiceType(type, snapshot.network, registry, diagnostics, scanScope) }
            }
        }.awaitAll()
    }

    private suspend fun discoverOneServiceType(
        serviceType: String,
        network: Network?,
        registry: DeviceRegistry,
        diagnostics: DiscoveryDiagnostics,
        scanScope: CoroutineScope
    ) {
        diagnostics.sourceStarted("mDNS")
        suspendCancellableCoroutine { continuation ->
            var started = false
            var stopRequested = false
            lateinit var listener: NsdManager.DiscoveryListener
            fun finish() {
                if (continuation.isActive) continuation.resume(Unit)
            }
            fun requestStop() {
                if (stopRequested) return
                stopRequested = true
                if (started) runCatching { nsdManager.stopServiceDiscovery(listener) }.onFailure { finish() } else finish()
            }
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    started = true
                    mainHandler.postDelayed({ requestStop() }, MDNS_WINDOW_MILLIS)
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    diagnostics.sourceResponse("mDNS", "type=$serviceType instance=${serviceInfo.serviceName}")
                    resolveMdnsService(serviceInfo, registry, diagnostics, scanScope, network)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(type: String) = finish()
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    diagnostics.sourceFailure("mDNS", "start type=$type code=$errorCode")
                    finish()
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                    diagnostics.sourceFailure("mDNS", "stop type=$type code=$errorCode")
                    finish()
                }
            }
            continuation.invokeOnCancellation {
                if (started) runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && network != null) {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, network, callbackExecutor, listener)
                } else {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            }.onFailure {
                diagnostics.sourceFailure("mDNS", "request type=$serviceType reason=${it.javaClass.simpleName}")
                finish()
            }
        }
    }

    private fun resolveMdnsService(
        service: NsdServiceInfo,
        registry: DeviceRegistry,
        diagnostics: DiscoveryDiagnostics,
        scanScope: CoroutineScope,
        network: Network?
    ) {
        runCatching {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    diagnostics.sourceFailure("mDNS", "resolve instance=${serviceInfo.serviceName} code=$errorCode")
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    val address = host?.hostAddress
                    val attributes = serviceInfo.attributes
                        .mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8) }
                        .filterValues { it.isNotBlank() }
                    val label = serviceLabel(serviceInfo.serviceType)
                    val publicIdentity = MdnsIdentityNormalizer.normalize(attributes, serviceInfo.serviceName)
                    val hostname = host?.hostName?.removeSuffix(".local.")?.removeSuffix(".local")?.takeIf { it.isNotBlank() }
                    val model = publicIdentity.model
                    val manufacturerModel = listOfNotNull(publicIdentity.manufacturer, model).joinToString(" ").takeIf { it.isNotBlank() }
                    val displayName = hostname ?: publicIdentity.friendlyName ?: manufacturerModel ?: "未知设备"
                    val nameSource = when {
                        hostname != null -> "mDNS hostname"
                        publicIdentity.friendlyName != null -> "mDNS 服务名称"
                        manufacturerModel != null -> "mDNS 厂商与型号"
                        else -> "未知"
                    }
                    registry.upsert(
                        LanDevice(
                            id = "mdns:${serviceInfo.serviceName}:${serviceInfo.serviceType}",
                            displayName = displayName,
                            hostname = hostname,
                            addresses = address?.let { setOf(it) } ?: emptySet(),
                            ports = serviceInfo.port.takeIf { it > 0 }?.let { setOf(it) } ?: emptySet(),
                            services = setOf(label),
                            sources = setOf("mDNS / DNS-SD"),
                            manufacturer = publicIdentity.manufacturer,
                            deviceHint = model ?: classifyService(label),
                            details = attributes + publicIdentity.asDetails() + mapOf(
                                "mDNS 服务" to label,
                                "mDNS 服务实例" to serviceInfo.serviceName,
                                "mDNS 服务类型" to serviceInfo.serviceType,
                                "名称来源" to nameSource
                            ),
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                    diagnostics.observation("mDNS", "ip=${address ?: "none"} hostname=${hostname ?: "none"} service=$label")
                    if (label == "IPP" && host != null && serviceInfo.port > 0 && address != null) {
                        val resourcePath = attributes.entries.firstOrNull { (key, _) -> key.equals("rp", true) }?.value
                        scanScope.launch(Dispatchers.IO) {
                            resolveIppIdentity(host, serviceInfo.port, resourcePath, address, serviceInfo.serviceName, network, registry, diagnostics)
                        }
                    }
                }
            })
        }.onFailure { diagnostics.sourceFailure("mDNS", "resolve request reason=${it.javaClass.simpleName}") }
    }

    private fun resolveIppIdentity(
        host: InetAddress,
        port: Int,
        resourcePath: String?,
        address: String,
        fallbackName: String,
        network: Network?,
        registry: DeviceRegistry,
        diagnostics: DiscoveryDiagnostics
    ) {
        val identity = ippIdentityResolver.resolve(host, port, resourcePath, network) ?: return
        registry.upsert(
            LanDevice(
                id = "ip:$address",
                displayName = identity.name ?: identity.makeAndModel ?: fallbackName,
                hostname = host.hostName?.removeSuffix(".local."),
                addresses = setOf(address),
                ports = setOf(port),
                services = setOf("IPP"),
                sources = setOf("IPP 标准属性"),
                manufacturer = null,
                deviceHint = identity.makeAndModel ?: "网络打印设备",
                details = identity.asDetails() + mapOf("名称来源" to "IPP 打印机名称"),
                lastSeenAt = System.currentTimeMillis()
            )
        )
        diagnostics.observation("IPP", "ip=$address model=${identity.makeAndModel ?: "none"}")
    }

    private suspend fun discoverSsdp(snapshot: LanNetworkSnapshot, registry: DeviceRegistry, diagnostics: DiscoveryDiagnostics) = withContext(Dispatchers.IO) {
        diagnostics.sourceStarted("SSDP")
        val request = SSDP_REQUEST.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                snapshot.network?.bindSocket(socket)
                socket.soTimeout = SSDP_RECEIVE_TIMEOUT_MILLIS
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))
                val deadline = System.currentTimeMillis() + SSDP_WINDOW_MILLIS
                while (System.currentTimeMillis() < deadline && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val buffer = ByteArray(4096)
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                        val headers = parseHeaders(String(response.data, 0, response.length, StandardCharsets.UTF_8))
                        val address = response.address.hostAddress.orEmpty()
                        diagnostics.sourceResponse("SSDP", "ip=$address usn=${headers["usn"] ?: "none"} st=${headers["st"] ?: "none"}")
                        if (address.isBlank()) continue
                        val location = headers["location"]
                        val identity = location?.let { identityResolver.resolveUpnpDescription(it, address, snapshot.network, diagnostics) }
                        val type = headers["st"] ?: headers["nt"] ?: "UPnP 设备"
                        val displayName = upnpDisplayName(identity)
                        val aliases = buildMap {
                            headers["usn"]?.let { put("SSDP USN", it) }
                            headers["server"]?.let { put("SSDP SERVER", it) }
                            headers["st"]?.let { put("SSDP ST", it) }
                            headers["cache-control"]?.let { put("SSDP CACHE-CONTROL", it) }
                            headers["location"]?.let { put("SSDP LOCATION", it) }
                        }
                        registry.upsert(
                            LanDevice(
                                id = "ssdp:${headers["usn"] ?: address}",
                                displayName = displayName.first,
                                hostname = null,
                                addresses = setOf(address),
                                ports = setOf(SSDP_PORT),
                                services = setOf("UPnP / SSDP"),
                                sources = setOf("SSDP / UPnP"),
                                manufacturer = identity?.manufacturer,
                                deviceHint = identity?.bestModel() ?: classifySsdp(type),
                                details = aliases + (identity?.asDetails().orEmpty()) + mapOf(
                                    "名称来源" to displayName.second,
                                    "UPnP 设备类型" to (identity?.deviceType ?: type)
                                ),
                                lastSeenAt = System.currentTimeMillis()
                            )
                        )
                        diagnostics.observation("SSDP", "ip=$address name=${displayName.first} type=$type")
                    } catch (_: java.net.SocketTimeoutException) {
                        // 在完整监听窗口内持续接收；短超时仅用于检查取消条件。
                    }
                }
            }
        }.onFailure { diagnostics.sourceFailure("SSDP", "request reason=${it.javaClass.simpleName}") }
    }

    /** 保证 SERVER 只保留为详情字段，绝不作为用户可见名称。 */
    private fun upnpDisplayName(identity: PublicDeviceIdentity?): Pair<String, String> {
        identity?.bestFriendlyName()?.let { return it to "UPnP friendlyName" }
        val manufacturerModel = listOfNotNull(identity?.manufacturer, identity?.modelName).joinToString(" ").takeIf { it.isNotBlank() }
        if (manufacturerModel != null) return manufacturerModel to "UPnP 厂商与型号"
        val manufacturerType = listOfNotNull(identity?.manufacturer, identity?.deviceType).joinToString(" ").takeIf { it.isNotBlank() }
        if (manufacturerType != null) return manufacturerType to "UPnP 厂商与设备类型"
        return "未知设备" to "未知"
    }

    private fun parseHeaders(message: String): Map<String, String> = message
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator > 0) line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim() else null
        }
        .toMap()

    private fun serviceLabel(type: String): String = when (type.removePrefix("_").removeSuffix("._tcp.").lowercase()) {
        "ipp" -> "IPP"
        "printer" -> "打印服务"
        "airplay" -> "AirPlay"
        "raop" -> "RAOP"
        "googlecast" -> "Google Cast"
        "ssh" -> "SSH"
        "smb" -> "SMB"
        "http" -> "HTTP"
        "https" -> "HTTPS"
        "hap" -> "HomeKit"
        else -> type
    }

    private fun classifyService(label: String): String = when (label) {
        "IPP", "打印服务" -> "网络打印设备"
        "AirPlay", "RAOP", "Google Cast" -> "媒体播放设备"
        "SSH" -> "远程管理设备"
        "SMB" -> "文件共享设备"
        else -> "局域网服务设备"
    }

    private fun classifySsdp(type: String): String = when {
        type.contains("MediaRenderer", true) -> "媒体播放设备"
        type.contains("MediaServer", true) -> "媒体服务器"
        type.contains("InternetGatewayDevice", true) -> "网络网关设备"
        else -> "UPnP 设备"
    }

    private class DeviceRegistry(
        private val publishSnapshot: (List<LanDevice>) -> Unit,
        private val diagnostics: DiscoveryDiagnostics
    ) {
        private val devices = linkedMapOf<String, LanDevice>()
        private val aliasIndex = mutableMapOf<String, String>()
        private val rawObservations = AtomicInteger(0)
        private var serial = 0

        @Synchronized
        fun upsert(incoming: LanDevice) {
            rawObservations.incrementAndGet()
            val aliases = aliasesFor(incoming)
            val matches = aliases.mapNotNull(aliasIndex::get).distinct().filter(devices::containsKey)
            val canonicalId = matches.firstOrNull() ?: "device:${++serial}"
            var merged = incoming.copy(id = canonicalId)
            matches.forEach { id ->
                val existing = devices.remove(id) ?: return@forEach
                merged = merge(existing, merged).copy(id = canonicalId)
            }
            devices[canonicalId] = merged
            aliasesFor(merged).forEach { aliasIndex[it] = canonicalId }
            diagnostics.devicePublished(merged)
            publishSnapshot(snapshot())
        }

        @Synchronized fun snapshot(): List<LanDevice> = devices.values.toList()
        fun rawObservationCount(): Int = rawObservations.get()

        private fun aliasesFor(device: LanDevice): Set<String> = buildSet {
            device.addresses.filter { it.isNotBlank() }.forEach { add("ip:${it.lowercase()}") }
            device.details["MAC 地址"]?.takeIf { it.isNotBlank() }?.let { add("mac:${it.lowercase()}") }
            device.details["UPnP UDN"]?.takeIf { it.isNotBlank() }?.let { add("udn:${it.lowercase()}") }
            device.details["UPnP serialNumber"]?.takeIf { it.isNotBlank() }?.let { add("serial:${it.lowercase()}") }
            device.details["SSDP USN"]?.takeIf { it.isNotBlank() }?.let { add("usn:${it.lowercase()}") }
            val instance = device.details["mDNS 服务实例"]
            val type = device.details["mDNS 服务类型"]
            if (!instance.isNullOrBlank() && !type.isNullOrBlank()) add("mdns:${instance.lowercase()}|${type.lowercase()}")
            if (device.id.startsWith("local:")) add(device.id)
        }

        private fun merge(first: LanDevice, second: LanDevice): LanDevice {
            val preferred = if (namePriority(second) > namePriority(first)) second else first
            val other = if (preferred === second) first else second
            val mergedDetails = first.details + second.details + mapOf("名称来源" to (preferred.details["名称来源"] ?: "未知"))
            return preferred.copy(
                hostname = preferred.hostname ?: other.hostname,
                addresses = first.addresses + second.addresses,
                ports = first.ports + second.ports,
                services = first.services + second.services,
                sources = first.sources + second.sources,
                manufacturer = preferred.manufacturer ?: other.manufacturer,
                deviceHint = preferredHint(first.deviceHint, second.deviceHint),
                details = mergedDetails,
                lastSeenAt = maxOf(first.lastSeenAt, second.lastSeenAt)
            )
        }

        private fun namePriority(device: LanDevice): Int = when (device.details["名称来源"]) {
            "UPnP friendlyName" -> 700
            "mDNS hostname" -> 600
            "DHCP hostname" -> 500
            "mDNS 服务名称" -> 450
            "mDNS 厂商与型号", "UPnP 厂商与型号" -> 400
            "UPnP 厂商与设备类型" -> 300
            else -> if (device.displayName == "未知设备") 0 else 100
        }

        private fun preferredHint(first: String, second: String): String = when {
            second.contains("型号") && !first.contains("型号") -> second
            first == "局域网服务设备" || first.contains("低置信度") -> second
            else -> first
        }
    }

    private companion object {
        const val MDNS_WINDOW_MILLIS = 1_800L
        const val MDNS_PARALLEL_DISCOVERIES = 3
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val SSDP_WINDOW_MILLIS = 4_500L
        const val SSDP_RECEIVE_TIMEOUT_MILLIS = 600
        val MDNS_SERVICE_TYPES = listOf(
            "_http._tcp.", "_https._tcp.", "_ssh._tcp.", "_smb._tcp.", "_ipp._tcp.",
            "_printer._tcp.", "_airplay._tcp.", "_raop._tcp.", "_googlecast._tcp.", "_hap._tcp."
        )
        const val SSDP_REQUEST = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ssdp:all\r\n\r\n"
    }
}

data class LanNetworkSnapshot(
    val network: Network?,
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

data class LanScanProgress(val message: String, val completedHosts: Int, val totalHosts: Int)

data class LanScanSummary(
    val startedAt: Long,
    val finishedAt: Long,
    val discoveredCount: Int,
    val scannedHostCount: Int,
    val hotspotNeighborCount: Int = 0,
    val hotspotNeighborCacheReadable: Boolean = false,
    val diagnostics: LanDiscoveryDiagnostics
)

private data class HotspotInterfaceCandidate(val interfaceName: String, val address: Inet4Address, val prefixLength: Short)
private data class NeighborRead(val cacheReadable: Boolean, val entries: List<NeighborEntry>)
private data class NeighborEntry(val ipAddress: String, val macAddress: String, val interfaceName: String) {
    fun toLanDevice(timestamp: Long) = LanDevice(
        id = "ip:$ipAddress",
        displayName = "未知设备",
        hostname = null,
        addresses = setOf(ipAddress),
        ports = emptySet(),
        services = setOf("邻居缓存"),
        sources = setOf("ARP / 邻居缓存"),
        manufacturer = null,
        deviceHint = "本地邻居缓存观测",
        details = mapOf(
            "MAC 地址" to macAddress,
            "邻居接口" to interfaceName,
            "邻居证据" to "本机 ARP/邻居缓存记录",
            "名称来源" to "未知"
        ),
        lastSeenAt = timestamp
    )
}

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
)

/** IPv4 扫描范围最多限制到本机 /24；显示实际 CIDR 与受限扫描 CIDR。 */
data class Ipv4Subnet private constructor(
    private val addressValue: Int,
    private val prefixLength: Int,
    private val effectivePrefixLength: Int
) {
    val cidrLabel: String get() = "${toAddress(networkAddress(prefixLength)).hostAddress}/$prefixLength"
    val scanCidrLabel: String get() = "${toAddress(networkAddress(effectivePrefixLength)).hostAddress}/$effectivePrefixLength"

    fun contains(address: Inet4Address): Boolean {
        val value = address.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xff) }
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (value and mask) == (addressValue and mask)
    }

    private fun networkAddress(prefix: Int): Int {
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return addressValue and mask
    }

    private fun toAddress(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    )

    companion object {
        fun from(address: Inet4Address, prefixLength: Int): Ipv4Subnet {
            val value = address.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xff) }
            val actualPrefix = prefixLength.coerceIn(1, 30)
            return Ipv4Subnet(value, actualPrefix, maxOf(actualPrefix, 24))
        }
    }
}
