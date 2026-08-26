package com.zyj.lanobserver

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LanDiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val discoveryEngine = LanDiscoveryEngine(application)
    private val modelResolver = DeviceModelResolver()
    private val ouiDatabase = OuiDatabase(application)
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private var scanJob: Job? = null
    private var modelRecognitionJob: Job? = null
    private var ouiSyncJob: Job? = null

    var uiState by mutableStateOf(LanDiscoveryUiState())
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRefresh()

        override fun onLost(network: Network) = scheduleNetworkRefresh()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = scheduleNetworkRefresh()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = scheduleNetworkRefresh()
    }

    init {
        refreshNetwork()
        refreshOuiDatabaseStatus()
        runCatching {
            // 监听所有实际存在的网络；VPN、移动网络和热点状态也需要刷新主页卡片。
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun scheduleNetworkRefresh() {
        viewModelScope.launch { refreshNetwork() }
    }

    fun refreshNetwork() {
        val networks = discoveryEngine.networkStatuses().map { it.toUi() }
        val selectedNetwork = networks.firstOrNull { it.id == uiState.selectedNetworkId && it.scanSnapshot != null }
            ?: networks.firstOrNull { it.scanSnapshot != null }
        val localHost = discoveryEngine.localHostInfo().toUi()
        val localDevice = if (selectedNetwork == null) localHost.toLanDevice() else null
        uiState = uiState.copy(
            networks = networks,
            network = selectedNetwork,
            selectedNetworkId = selectedNetwork?.id,
            localHost = localHost,
            devices = if (selectedNetwork == null) listOfNotNull(localDevice) else uiState.devices,
            selectedDeviceId = uiState.selectedDeviceId?.takeIf { selectedId -> selectedNetwork != null || selectedId == localDevice?.id },
            message = when {
                selectedNetwork == null && networks.isEmpty() -> "无网络连接。请连接 Wi‑Fi、以太网或开启个人热点后重试。"
                selectedNetwork == null && localHost.localIp != null -> "当前仅检测到不支持局域网设备扫描的网络。本机接口已作为“本机设备”显示。"
                selectedNetwork == null -> "当前网络不支持局域网设备扫描；请连接 Wi‑Fi、以太网或开启个人热点。"
                uiState.isScanning -> uiState.message
                selectedNetwork.isHotspot -> "已识别本机移动热点。扫描仅采用客户端实际公开的邻居、mDNS 与 UPnP 证据。"
                else -> "请选择网络卡片中的“扫描此网络”，只收集设备 IP 与公开服务证据。"
            }
        )
    }

    fun startScan(networkId: String) {
        if (uiState.isScanning) return
        val networkUi = uiState.networks.firstOrNull { it.id == networkId }
        val snapshot = networkUi?.scanSnapshot
        if (snapshot == null) {
            uiState = uiState.copy(message = "该网络仅用于状态展示，不支持局域网设备扫描。")
            return
        }
        uiState = uiState.copy(
            network = networkUi,
            selectedNetworkId = networkUi.id,
            isScanning = true,
            devices = emptyList(),
            selectedDeviceId = null,
            progress = LanScanProgress("正在准备 IP 与公开服务发现", 0, 0),
            lastScanLabel = null,
                                message = if (snapshot.isHotspot) {
                        "正在从热点子网 ${snapshot.scanCidr} 的邻居缓存、mDNS 与 UPnP 识别设备 IP。"
                    } else {
                        "正在发现 ${snapshot.actualCidr} 中公开广播的设备 IP；不会发送登录请求、读取文件或执行远程命令。"
                    }

        )
        val discoveryNetworkLabel = networkUi.discoveryNetworkLabel()
        val discoverySubnet = networkUi.cidr?.takeIf { it.isNotBlank() }
        scanJob = viewModelScope.launch {
            try {
                val summary = discoveryEngine.scan(
                    snapshot = snapshot,
                    onDevicesChanged = { devices -> publishDevices(devices, discoveryNetworkLabel, discoverySubnet) },
                    onProgress = { progress ->
                        uiState = uiState.copy(progress = progress)
                    }
                )
                val finishedLabel = dateFormat.format(Date(summary.finishedAt))
                uiState = uiState.copy(
                    isScanning = false,
                    progress = null,
                    lastScanLabel = "上次扫描：$finishedLabel · ${networkUi.title}",
                    scannedDeviceCounts = uiState.scannedDeviceCounts + (networkUi.id to (summary.discoveredCount - 1).coerceAtLeast(0)),
                    message = if (snapshot.isHotspot) {
                        val publicServiceCount = (summary.discoveredCount - 1 - summary.hotspotNeighborCount).coerceAtLeast(0)
                        when {
                            summary.hotspotNeighborCount > 0 -> "热点扫描完成：邻居缓存观测到 ${summary.hotspotNeighborCount} 台当前或近期通信的客户端；其中 $publicServiceCount 台公开了网络服务。"
                            summary.hotspotNeighborCacheReadable -> "热点扫描完成：邻居缓存暂未观测到客户端，公开服务识别到 $publicServiceCount 台。系统设置中的已连接设备未必会公开服务或产生可读缓存记录。"
                            else -> "热点扫描完成：系统限制了热点邻居缓存读取，公开服务识别到 $publicServiceCount 台。系统设置中的完整客户端列表仅系统应用可见。"
                        }
                    } else {
                        val arp = summary.diagnostics.sourceStats["ARP"]?.observations ?: 0
                        val mdns = summary.diagnostics.sourceStats["mDNS"]?.observations ?: 0
                        val ssdp = summary.diagnostics.sourceStats["SSDP"]?.observations ?: 0
                        "扫描完成：多源原始证据 ARP $arp、mDNS $mdns、SSDP $ssdp；去重后 ${summary.discoveredCount} 台。结果仅来自邻居缓存与公开服务证据。"
                    }
                )
            } catch (exception: SecurityException) {
                uiState = uiState.copy(
                    isScanning = false,
                    progress = null,
                    message = "系统阻止了本地网络访问。请在系统设置中允许此应用访问网络或附近设备后重试。"
                )
            } catch (exception: CancellationException) {
                uiState = uiState.copy(isScanning = false, progress = null)
                throw exception
            } catch (exception: Exception) {
                uiState = uiState.copy(
                    isScanning = false,
                    progress = null,
                    message = "扫描未能完成：${exception.message ?: "网络暂不可用"}。请检查连接后重试。"
                )
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        uiState = uiState.copy(
            isScanning = false,
            progress = null,
            message = "已停止扫描。当前已发现的设备仍会保留在列表中。"
        )
    }

    fun selectDevice(deviceId: String?) {
        uiState = uiState.copy(selectedDeviceId = deviceId, selectedOuiLookup = null)
        val device = uiState.devices.firstOrNull { it.id == deviceId } ?: return
        val macAddress = device.details["MAC 地址"]?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val lookup = withContext(Dispatchers.IO) { ouiDatabase.lookup(macAddress) }
            if (uiState.selectedDeviceId == device.id) {
                uiState = uiState.copy(selectedOuiLookup = lookup)
            }
        }
    }

    /** 只在详情页点击后，依据已发现的协议证据发起只读型号识别。 */
    fun identifyDeviceModel(deviceId: String) {
        val device = uiState.devices.firstOrNull { it.id == deviceId } ?: return
        if (modelRecognitionJob?.isActive == true) return
        updateModelRecognition(deviceId) { ModelRecognitionUiState(isRunning = true, result = ModelRecognitionResult.running()) }
        modelRecognitionJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { modelResolver.identifyPublic(device, uiState.network?.scanSnapshot?.network) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                ModelRecognitionResult.unavailable("识别失败：${exception.message ?: "网络暂不可用"}")
            }
            updateModelRecognition(deviceId) { ModelRecognitionUiState(isRunning = false, result = result) }
        }
    }

    /** ONVIF 仅在用户明确提交凭据后调用只读 GetDeviceInformation；凭据不会保存。 */
    fun identifyDeviceWithOnvif(deviceId: String, username: String, password: String) {
        val device = uiState.devices.firstOrNull { it.id == deviceId } ?: return
        if (modelRecognitionJob?.isActive == true) return
        updateModelRecognition(deviceId) { ModelRecognitionUiState(isRunning = true, result = ModelRecognitionResult.running()) }
        modelRecognitionJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    modelResolver.identifyOnvif(device, uiState.network?.scanSnapshot?.network, OnvifCredentials(username, password))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                ModelRecognitionResult.unavailable("ONVIF 识别失败：${exception.message ?: "网络暂不可用"}")
            }
            updateModelRecognition(deviceId) { ModelRecognitionUiState(isRunning = false, result = result) }
        }
    }

    fun syncOuiDatabase() {
        if (ouiSyncJob?.isActive == true) return
        val (network, networkLabel) = selectOuiSyncNetwork()
        uiState = uiState.copy(
            isOuiSyncing = true,
            ouiSyncMessage = "将通过${networkLabel}下载 IEEE MA-L、MA-M、MA-S 注册表；不会上传 MAC 地址。"
        )
        ouiSyncJob = viewModelScope.launch {
            val result = try {
                withTimeout(OUI_SYNC_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) {
                        ouiDatabase.sync(network) { progress ->
                            viewModelScope.launch {
                                if (ouiSyncJob?.isActive == true) {
                                    uiState = uiState.copy(ouiSyncMessage = "$networkLabel：$progress")
                                }
                            }
                        }
                    }
                }
            } catch (exception: kotlinx.coroutines.TimeoutCancellationException) {
                OuiSyncResult(
                    success = false,
                    entryCount = ouiDatabase.status().entryCount,
                    message = "同步超时：120 秒内未完成 IEEE 下载。请确认可访问互联网，或稍后重试。"
                )
            } catch (exception: Exception) {
                OuiSyncResult(
                    success = false,
                    entryCount = ouiDatabase.status().entryCount,
                    message = "同步失败：${exception.message ?: "网络暂不可用"}"
                )
            }
            uiState = uiState.copy(
                isOuiSyncing = false,
                ouiDatabaseStatus = ouiDatabase.status(),
                ouiSyncMessage = result.message
            )
            refreshSelectedOuiLookup()
        }
    }

    /** OUI 下载不沿用用户选择的局域网扫描目标，始终优先使用系统当前可联网网络。 */
    private fun selectOuiSyncNetwork(): Pair<Network?, String> {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeNetwork != null && activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            return activeNetwork to "系统当前网络"
        }
        val validatedNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
        return if (validatedNetwork != null) {
            validatedNetwork to "已验证互联网网络"
        } else {
            null to "系统默认网络"
        }
    }

    fun refreshOuiDatabaseStatus() {
        uiState = uiState.copy(ouiDatabaseStatus = ouiDatabase.status())
    }

    fun filter(query: String) {
        uiState = uiState.copy(searchQuery = query)
    }

    override fun onCleared() {
        scanJob?.cancel()
        modelRecognitionJob?.cancel()
        ouiSyncJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    private fun updateModelRecognition(deviceId: String, transform: (ModelRecognitionUiState) -> ModelRecognitionUiState) {
        val current = uiState.modelRecognitionStates[deviceId] ?: ModelRecognitionUiState()
        uiState = uiState.copy(modelRecognitionStates = uiState.modelRecognitionStates + (deviceId to transform(current)))
    }

    private fun refreshSelectedOuiLookup() {
        val selected = uiState.selectedDevice ?: return
        val macAddress = selected.details["MAC 地址"]?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val lookup = withContext(Dispatchers.IO) { ouiDatabase.lookup(macAddress) }
            if (uiState.selectedDeviceId == selected.id) uiState = uiState.copy(selectedOuiLookup = lookup)
        }
    }

    /** 设备归属只记录本次用户明确点击的发现网络，不从地址范围或接口名称反向猜测。 */
    private fun publishDevices(
        devices: List<LanDevice>,
        discoveryNetworkLabel: String,
        discoverySubnet: String?
    ) {
        val taggedDevices = devices.map { device ->
            val networkDetails = buildMap {
                put("发现网络", discoveryNetworkLabel)
                discoverySubnet?.let { put("发现子网", it) }
            }
            device.copy(details = device.details + networkDetails)
        }
        uiState = uiState.copy(
            devices = taggedDevices.sortedWith(
                compareByDescending<LanDevice> { it.id.startsWith("local:") }
                    .thenBy { it.displayName.lowercase(Locale.getDefault()) }
            )
        )
    }

    private fun LanNetworkUi.discoveryNetworkLabel(): String {
        val name = displayName?.takeIf { it.isNotBlank() }
        return listOfNotNull(title, name).joinToString(" · ")
    }

    private fun LocalHostUi.toLanDevice(): LanDevice? {
        val address = localIp ?: return null
        return LanDevice(
            id = "local:$address",
            displayName = "本机设备",
            hostname = null,
            addresses = setOf(address),
            ports = emptySet(),
            services = setOf("本机接口"),
            sources = setOf("本机网络信息"),
            manufacturer = null,
            deviceHint = "本机 IPv4（未检测到可扫描局域网）",
            details = buildMap {
                interfaceName?.let { put("网络接口", it) }
                cidr?.let { put("本机地址范围", it) }
                put("本机网络状态", detail)
                put("名称来源", "本机网络信息")
            },
            lastSeenAt = System.currentTimeMillis()
        )
    }

    private fun LocalHostInfo.toUi() = LocalHostUi(
        localIp = localIp,
        interfaceName = interfaceName,
        cidr = cidr,
        detail = detail
    )

    private fun LanNetworkStatus.toUi() = LanNetworkUi(
        id = id,
        kind = kind,
        title = title,
        displayName = displayName,
        interfaceName = interfaceName,
        localIpv4 = localIpv4,
        localIpv6 = localIpv6,
        gateway = gateway,
        cidr = cidr,
        subnetMask = subnetMask,
        carrierName = carrierName,
        dnsServers = dnsServers,
        hasInternet = hasInternet,
        isValidated = isValidated,
        isScanTarget = isScanTarget,
        isVpn = isVpn,
        detail = detail,
        scanSnapshot = scanSnapshot
    )

    private companion object {
        val dateFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.CHINA)
        const val OUI_SYNC_TIMEOUT_MILLIS = 120_000L
    }
}

data class LanDiscoveryUiState(
    val networks: List<LanNetworkUi> = emptyList(),
    val network: LanNetworkUi? = null,
    val selectedNetworkId: String? = null,
    val scannedDeviceCounts: Map<String, Int> = emptyMap(),
    val localHost: LocalHostUi = LocalHostUi(),
    val isScanning: Boolean = false,
    val progress: LanScanProgress? = null,
    val devices: List<LanDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val searchQuery: String = "",
    val lastScanLabel: String? = null,
    val message: String = "正在读取网络状态。",
    val modelRecognitionStates: Map<String, ModelRecognitionUiState> = emptyMap(),
    val ouiDatabaseStatus: OuiDatabaseStatus = OuiDatabaseStatus(false, 0, null, "尚未同步"),
    val isOuiSyncing: Boolean = false,
    val ouiSyncMessage: String? = null,
    val selectedOuiLookup: OuiLookupResult? = null
) {
    val visibleDevices: List<LanDevice>
        get() {
            val query = searchQuery.trim().lowercase(Locale.getDefault())
            if (query.isBlank()) return devices
            return devices.filter { device ->
                listOf(
                    device.displayName,
                    device.hostname.orEmpty(),
                    device.deviceHint,
                    device.addresses.joinToString(),
                    device.services.joinToString(),
                    device.manufacturer.orEmpty()
                ).any { value -> value.lowercase(Locale.getDefault()).contains(query) }
            }
        }

    val selectedDevice: LanDevice?
        get() = devices.firstOrNull { it.id == selectedDeviceId }
}

data class LocalHostUi(
    val localIp: String? = null,
    val interfaceName: String? = null,
    val cidr: String? = null,
    val detail: String = "正在读取本机网络接口。"
)

data class LanNetworkUi(
    val id: String,
    val kind: LanNetworkKind,
    val title: String,
    val displayName: String?,
    val interfaceName: String,
    val localIpv4: String?,
    val localIpv6: String?,
    val gateway: String?,
    val cidr: String?,
    val subnetMask: String?,
    val carrierName: String?,
    val dnsServers: List<String>,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isScanTarget: Boolean,
    val isVpn: Boolean,
    val detail: String,
    val scanSnapshot: LanNetworkSnapshot?
) {
    val isHotspot: Boolean get() = kind == LanNetworkKind.HOTSPOT
}
