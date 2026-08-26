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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LanDiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val discoveryEngine = LanDiscoveryEngine(application)
    private val monitoringEngine = DeviceMonitoringEngine()
    private val modelResolver = DeviceModelResolver()
    private val ouiDatabase = OuiDatabase(application)
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private var scanJob: Job? = null
    private var portScanJob: Job? = null
    private var monitorJob: Job? = null
    private var localPortScanJob: Job? = null
    private var modelRecognitionJob: Job? = null
    private var ouiSyncJob: Job? = null

    var uiState by mutableStateOf(LanDiscoveryUiState())
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()

        override fun onLost(network: Network) = refreshNetwork()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNetwork()
    }

    init {
        refreshNetwork()
        refreshOuiDatabaseStatus()
        runCatching {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    fun refreshNetwork() {
        val snapshot = discoveryEngine.networkSnapshot()
        val localHost = discoveryEngine.localHostInfo().toUi()
        uiState = uiState.copy(
            network = snapshot?.toUi(),
            localHost = localHost,
            message = when {
                snapshot == null && localHost.localIp != null -> "未检测到可用于局域网扫描的 Wi‑Fi 或热点网络。已显示本机 IPv4；可手动检测本机固定常见端口。"
                snapshot == null -> "未检测到可用于局域网扫描的 IPv4 网络，也未获取到本机 IPv4 接口。请连接 Wi‑Fi、开启热点或重新检测。"
                uiState.isScanning -> uiState.message
                snapshot.isHotspot -> "已识别本机移动热点。为避免误报，扫描只采用客户端实际公开的 mDNS 与 UPnP 响应。"
                else -> "已准备就绪。扫描只识别设备 IP 与公开协议证据；端口需在设备详情中手动检查。"
            }
        )
    }

    fun startScan() {
        if (uiState.isScanning) return
        val snapshot = discoveryEngine.networkSnapshot()
        if (snapshot == null) {
            uiState = uiState.copy(message = "无法获取可扫描的局域网。可查看本机 IPv4，并按需检测本机固定常见端口。")
            return
        }
        stopMonitoring()
        portScanJob?.cancel()
        localPortScanJob?.cancel()
        uiState = uiState.copy(
            network = snapshot.toUi(),
            isScanning = true,
            devices = emptyList(),
            selectedDeviceId = null,
            progress = LanScanProgress("正在准备 IP 与公开服务发现", 0, 0),
            lastScanLabel = null,
            portScanStates = emptyMap(),
            onlineStates = emptyMap(),
            message = if (snapshot.isHotspot) {
                "正在从热点子网 ${snapshot.scanCidr} 的邻居缓存、mDNS 与 UPnP 识别设备 IP；不会扫描端口。"
            } else {
                "正在发现 ${snapshot.actualCidr} 中公开广播的设备 IP；不会扫描端口、发送登录请求、读取文件或执行远程命令。"
            }
        )
        scanJob = viewModelScope.launch {
            try {
                val summary = discoveryEngine.scan(
                    snapshot = snapshot,
                    onDevicesChanged = { devices -> publishDevices(devices) },
                    onProgress = { progress ->
                        uiState = uiState.copy(progress = progress)
                    }
                )
                val finishedLabel = dateFormat.format(Date(summary.finishedAt))
                uiState = uiState.copy(
                    isScanning = false,
                    progress = null,
                    lastScanLabel = "上次扫描：$finishedLabel",
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
                        "扫描完成：多源原始证据 ARP $arp、mDNS $mdns、SSDP $ssdp；去重后 ${summary.discoveredCount} 台。默认扫描未检查任何 TCP/UDP 端口；请选择已发现设备后手动扫描可用端口。"
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

    /** 只允许对当前已发现设备的固定常见服务端口进行扫描。 */
    fun scanDevicePorts(deviceId: String) {
        val device = uiState.devices.firstOrNull { it.id == deviceId } ?: return
        if (portScanJob?.isActive == true) return
        updatePortScan(deviceId) {
            DevicePortScanUiState(
                isScanning = true,
                completedPorts = 0,
                totalPorts = DeviceMonitoringEngine.PORT_PROFILE.size,
                result = null,
                message = "正在检查固定的常见服务端口"
            )
        }
        portScanJob = viewModelScope.launch {
            try {
                val result = monitoringEngine.scanCommonPorts(
                    device = device,
                    network = discoveryEngine.networkSnapshot()?.network
                ) { completed, total ->
                    viewModelScope.launch {
                        updatePortScan(deviceId) { current ->
                            current.copy(completedPorts = completed, totalPorts = total)
                        }
                    }
                }
                updatePortScan(deviceId) {
                    DevicePortScanUiState(
                        isScanning = false,
                        completedPorts = result.checkedServices.size,
                        totalPorts = result.checkedServices.size,
                        result = result,
                        message = result.errorMessage ?: "端口扫描完成"
                    )
                }
            } catch (exception: CancellationException) {
                updatePortScan(deviceId) { current -> current.copy(isScanning = false, message = "端口扫描已停止") }
                throw exception
            } catch (exception: Exception) {
                updatePortScan(deviceId) {
                    it.copy(isScanning = false, message = "端口扫描失败：${exception.message ?: "网络暂不可用"}")
                }
            }
        }
    }

    fun cancelPortScan() {
        portScanJob?.cancel()
        portScanJob = null
    }

    /** 无可扫描局域网时，用户可主动检测当前显示的本机 IPv4 固定常见端口。 */
    fun scanLocalHostPorts() {
        val localIp = uiState.localHost.localIp ?: run {
            uiState = uiState.copy(localPortScan = DevicePortScanUiState(message = "未获取到可检测的本机 IPv4 地址"))
            return
        }
        if (localPortScanJob?.isActive == true) return
        uiState = uiState.copy(
            localPortScan = DevicePortScanUiState(
                isScanning = true,
                completedPorts = 0,
                totalPorts = DeviceMonitoringEngine.PORT_PROFILE.size,
                message = "正在检查本机固定的常见服务端口"
            )
        )
        localPortScanJob = viewModelScope.launch {
            try {
                val result = monitoringEngine.scanLocalHostPorts(localIp) { completed, total ->
                    viewModelScope.launch {
                        uiState = uiState.copy(localPortScan = uiState.localPortScan.copy(completedPorts = completed, totalPorts = total))
                    }
                }
                uiState = uiState.copy(
                    localPortScan = DevicePortScanUiState(
                        isScanning = false,
                        completedPorts = result.checkedServices.size,
                        totalPorts = result.checkedServices.size,
                        result = result,
                        message = result.errorMessage ?: "本机端口检测完成"
                    )
                )
            } catch (exception: CancellationException) {
                uiState = uiState.copy(localPortScan = uiState.localPortScan.copy(isScanning = false, message = "本机端口检测已停止"))
                throw exception
            } catch (exception: Exception) {
                uiState = uiState.copy(
                    localPortScan = uiState.localPortScan.copy(
                        isScanning = false,
                        message = "本机端口检测失败：${exception.message ?: "网络暂不可用"}"
                    )
                )
            }
        }
    }

    fun cancelLocalHostPortScan() {
        localPortScanJob?.cancel()
        localPortScanJob = null
    }

    /** 在应用前台中每 15 秒检查一次指定已发现设备的常见服务端口。 */
    fun startMonitoring(deviceId: String) {
        if (uiState.monitoredDeviceId == deviceId && monitorJob?.isActive == true) return
        monitorJob?.cancel()
        uiState = uiState.copy(monitoredDeviceId = deviceId)
        monitorJob = viewModelScope.launch {
            while (isActive) {
                val device = uiState.devices.firstOrNull { it.id == deviceId } ?: break
                val result = try {
                    monitoringEngine.checkOnline(device, discoveryEngine.networkSnapshot()?.network)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    DeviceOnlineResult(
                        status = DeviceOnlineStatus.Unconfirmed,
                        checkedAt = System.currentTimeMillis(),
                        responsivePort = null,
                        detail = "检查失败：${exception.message ?: "网络暂不可用"}"
                    )
                }
                uiState = uiState.copy(onlineStates = uiState.onlineStates + (deviceId to result))
                delay(DeviceMonitoringEngine.MONITOR_INTERVAL_MILLIS)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        uiState = uiState.copy(monitoredDeviceId = null)
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
                withContext(Dispatchers.IO) { modelResolver.identifyPublic(device, discoveryEngine.networkSnapshot()?.network) }
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
                    modelResolver.identifyOnvif(device, discoveryEngine.networkSnapshot()?.network, OnvifCredentials(username, password))
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
        uiState = uiState.copy(isOuiSyncing = true, ouiSyncMessage = "正在从 IEEE 官方 MA-L、MA-M、MA-S 注册表同步；不会上传 MAC 地址。")
        ouiSyncJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ouiDatabase.sync(discoveryEngine.networkSnapshot()?.network) }
            uiState = uiState.copy(
                isOuiSyncing = false,
                ouiDatabaseStatus = ouiDatabase.status(),
                ouiSyncMessage = result.message
            )
            refreshSelectedOuiLookup()
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
        portScanJob?.cancel()
        monitorJob?.cancel()
        localPortScanJob?.cancel()
        modelRecognitionJob?.cancel()
        ouiSyncJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    private fun updatePortScan(deviceId: String, transform: (DevicePortScanUiState) -> DevicePortScanUiState) {
        val current = uiState.portScanStates[deviceId] ?: DevicePortScanUiState()
        uiState = uiState.copy(portScanStates = uiState.portScanStates + (deviceId to transform(current)))
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

    private fun publishDevices(devices: List<LanDevice>) {
        uiState = uiState.copy(
            devices = devices.sortedWith(
                compareByDescending<LanDevice> { it.id.startsWith("local:") }
                    .thenBy { it.displayName.lowercase(Locale.getDefault()) }
            )
        )
    }

    private fun LocalHostInfo.toUi() = LocalHostUi(
        localIp = localIp,
        interfaceName = interfaceName,
        cidr = cidr,
        detail = detail
    )

    private fun LanNetworkSnapshot.toUi() = LanNetworkUi(
        localIp = localIp,
        gateway = gateway,
        transport = transport,
        actualCidr = actualCidr,
        scanCidr = scanCidr,
        isHotspot = isHotspot,
        hasVpn = hasVpn
    )

    private companion object {
        val dateFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.CHINA)
    }
}

data class LanDiscoveryUiState(
    val network: LanNetworkUi? = null,
    val localHost: LocalHostUi = LocalHostUi(),
    val localPortScan: DevicePortScanUiState = DevicePortScanUiState(),
    val isScanning: Boolean = false,
    val progress: LanScanProgress? = null,
    val devices: List<LanDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val searchQuery: String = "",
    val lastScanLabel: String? = null,
    val message: String = "正在读取网络状态。",
    val portScanStates: Map<String, DevicePortScanUiState> = emptyMap(),
    val onlineStates: Map<String, DeviceOnlineResult> = emptyMap(),
    val monitoredDeviceId: String? = null,
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

data class DevicePortScanUiState(
    val isScanning: Boolean = false,
    val completedPorts: Int = 0,
    val totalPorts: Int = 0,
    val result: DevicePortScanResult? = null,
    val message: String? = null
)

data class LocalHostUi(
    val localIp: String? = null,
    val interfaceName: String? = null,
    val cidr: String? = null,
    val detail: String = "正在读取本机网络接口。"
)

data class LanNetworkUi(
    val localIp: String,
    val gateway: String?,
    val transport: String,
    val actualCidr: String,
    val scanCidr: String,
    val isHotspot: Boolean = false,
    val hasVpn: Boolean = false
)
