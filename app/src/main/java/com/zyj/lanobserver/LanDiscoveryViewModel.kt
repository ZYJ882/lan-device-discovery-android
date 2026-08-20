package com.zyj.lanobserver

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LanDiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val discoveryEngine = LanDiscoveryEngine(application)
    private val monitoringEngine = DeviceMonitoringEngine()
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private var scanJob: Job? = null
    private var portScanJob: Job? = null
    private var monitorJob: Job? = null

    var uiState by mutableStateOf(LanDiscoveryUiState())
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()

        override fun onLost(network: Network) = refreshNetwork()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNetwork()
    }

    init {
        refreshNetwork()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    fun refreshNetwork() {
        val snapshot = discoveryEngine.networkSnapshot()
        uiState = uiState.copy(
            network = snapshot?.toUi(),
            message = when {
                snapshot == null -> "未检测到可用于局域网扫描的 IPv4 网络。请连接 Wi‑Fi 或以太网后重试。"
                uiState.isScanning -> uiState.message
                snapshot.isHotspot -> "已识别本机移动热点。扫描将只读取热点客户端公开广播与常见服务连通性。"
                else -> "已准备就绪。扫描只读取设备公开广播与常见服务连通性。"
            }
        )
    }

    fun startScan() {
        if (uiState.isScanning) return
        val snapshot = discoveryEngine.networkSnapshot()
        if (snapshot == null) {
            uiState = uiState.copy(message = "无法获取当前局域网。请确认设备已连接 Wi‑Fi 或以太网。")
            return
        }
        stopMonitoring()
        portScanJob?.cancel()
        uiState = uiState.copy(
            network = snapshot.toUi(),
            isScanning = true,
            devices = emptyList(),
            selectedDeviceId = null,
            progress = LanScanProgress("正在准备发现服务", 0, snapshot.subnet.scanHosts().size),
            lastScanLabel = null,
            portScanStates = emptyMap(),
            onlineStates = emptyMap(),
            message = if (snapshot.isHotspot) {
                "正在扫描热点子网 ${snapshot.scanCidr} 中设备公开的网络服务；未发现不代表设备未连接。"
            } else {
                "正在扫描 ${snapshot.scanCidr}。不会发送登录请求、读取文件或执行远程命令。"
            }
        )
        scanJob = viewModelScope.launch {
            try {
                val summary = discoveryEngine.scan(
                    snapshot = snapshot,
                    onDevice = { device -> publishDevice(device) },
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
                        "热点扫描完成：在 ${summary.scannedHostCount} 个可检查地址中发现 ${summary.discoveredCount} 台响应设备。未显示不代表设备未连接。"
                    } else {
                        "扫描完成：在 ${summary.scannedHostCount} 个可检查地址中发现 ${summary.discoveredCount} 台设备。未响应不代表设备不存在。"
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
                val result = monitoringEngine.scanCommonPorts(device) { completed, total ->
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

    /** 在应用前台中每 15 秒检查一次指定已发现设备的常见服务端口。 */
    fun startMonitoring(deviceId: String) {
        if (uiState.monitoredDeviceId == deviceId && monitorJob?.isActive == true) return
        monitorJob?.cancel()
        uiState = uiState.copy(monitoredDeviceId = deviceId)
        monitorJob = viewModelScope.launch {
            while (isActive) {
                val device = uiState.devices.firstOrNull { it.id == deviceId } ?: break
                val result = try {
                    monitoringEngine.checkOnline(device)
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
        uiState = uiState.copy(selectedDeviceId = deviceId)
    }

    fun filter(query: String) {
        uiState = uiState.copy(searchQuery = query)
    }

    override fun onCleared() {
        scanJob?.cancel()
        portScanJob?.cancel()
        monitorJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    private fun updatePortScan(deviceId: String, transform: (DevicePortScanUiState) -> DevicePortScanUiState) {
        val current = uiState.portScanStates[deviceId] ?: DevicePortScanUiState()
        uiState = uiState.copy(portScanStates = uiState.portScanStates + (deviceId to transform(current)))
    }

    private fun publishDevice(device: LanDevice) {
        val oldDevices = uiState.devices.associateBy { it.id }.toMutableMap()
        oldDevices[device.id] = device
        uiState = uiState.copy(
            devices = oldDevices.values.sortedWith(
                compareByDescending<LanDevice> { it.id.startsWith("local:") }
                    .thenBy { it.displayName.lowercase(Locale.getDefault()) }
            )
        )
    }

    private fun LanNetworkSnapshot.toUi() = LanNetworkUi(
        localIp = localIp,
        gateway = gateway,
        transport = transport,
        actualCidr = actualCidr,
        scanCidr = scanCidr,
        isHotspot = isHotspot
    )

    private companion object {
        val dateFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.CHINA)
    }
}

data class LanDiscoveryUiState(
    val network: LanNetworkUi? = null,
    val isScanning: Boolean = false,
    val progress: LanScanProgress? = null,
    val devices: List<LanDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val searchQuery: String = "",
    val lastScanLabel: String? = null,
    val message: String = "正在读取网络状态。",
    val portScanStates: Map<String, DevicePortScanUiState> = emptyMap(),
    val onlineStates: Map<String, DeviceOnlineResult> = emptyMap(),
    val monitoredDeviceId: String? = null
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

data class LanNetworkUi(
    val localIp: String,
    val gateway: String?,
    val transport: String,
    val actualCidr: String,
    val scanCidr: String,
    val isHotspot: Boolean = false
)
