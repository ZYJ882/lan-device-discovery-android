package com.zyj.lanobserver

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 面向已发现单台设备的受控连通性检查。
 *
 * 该组件只尝试对固定的常见服务端口建立 TCP 连接，不发送协议载荷、不进行身份验证，
 * 也不支持 CIDR、端口范围或任意目标输入。结果应仅用于用户有权管理的本地网络。
 */
class DeviceMonitoringEngine {
    suspend fun scanCommonPorts(
        device: LanDevice,
        network: Network?,
        onProgress: (completed: Int, total: Int) -> Unit
    ): DevicePortScanResult = coroutineScope {
        val address = device.primaryIpv4Address()
            ?: return@coroutineScope DevicePortScanResult.invalidTarget(device)
        val progress = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val openPorts = PORT_PROFILE.map { service ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val isOpen = isTcpPortOpen(network, address, service.port, PORT_SCAN_TIMEOUT_MILLIS)
                    onProgress(progress.incrementAndGet(), PORT_PROFILE.size)
                    service.takeIf { isOpen }
                }
            }
        }.awaitAll().filterNotNull()
        DevicePortScanResult(
            targetIp = address.hostAddress.orEmpty(),
            checkedServices = PORT_PROFILE,
            openServices = openPorts,
            completedAt = System.currentTimeMillis(),
            errorMessage = null
        )
    }

    suspend fun checkOnline(device: LanDevice, network: Network?): DeviceOnlineResult = withContext(Dispatchers.IO) {
        val address = device.primaryIpv4Address()
            ?: return@withContext DeviceOnlineResult.invalidTarget()
        val candidates = (device.ports + HEALTH_CHECK_PORTS).distinct().take(MAX_HEALTH_PORTS)
        val responsivePort = candidates.firstOrNull { port ->
            isTcpPortOpen(network, address, port, HEALTH_CHECK_TIMEOUT_MILLIS)
        }
        if (responsivePort != null) {
            DeviceOnlineResult(
                status = DeviceOnlineStatus.Online,
                checkedAt = System.currentTimeMillis(),
                responsivePort = responsivePort,
                detail = "端口 $responsivePort 可建立 TCP 连接"
            )
        } else {
            DeviceOnlineResult(
                status = DeviceOnlineStatus.Unconfirmed,
                checkedAt = System.currentTimeMillis(),
                responsivePort = null,
                detail = "常见服务端口未响应；不代表设备一定离线"
            )
        }
    }

    private fun LanDevice.primaryIpv4Address(): InetAddress? = addresses
        .asSequence()
        .mapNotNull { rawAddress -> runCatching { InetAddress.getByName(rawAddress) }.getOrNull() }
        .firstOrNull { address -> address is Inet4Address && !address.isLoopbackAddress }

    private fun isTcpPortOpen(network: Network?, address: InetAddress, port: Int, timeoutMillis: Int): Boolean = runCatching {
        Socket().use { socket ->
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            true
        }
    }.getOrDefault(false)

    companion object {
        const val MONITOR_INTERVAL_MILLIS = 15_000L
        private const val PORT_SCAN_TIMEOUT_MILLIS = 420
        private const val HEALTH_CHECK_TIMEOUT_MILLIS = 600
        private const val SCAN_CONCURRENCY = 4
        private const val MAX_HEALTH_PORTS = 8
        private val HEALTH_CHECK_PORTS = listOf(80, 443, 22, 445, 554, 631, 9100)
        val PORT_PROFILE = listOf(
            DeviceServicePort(21, "FTP"),
            DeviceServicePort(22, "SSH"),
            DeviceServicePort(23, "Telnet"),
            DeviceServicePort(53, "DNS"),
            DeviceServicePort(80, "HTTP"),
            DeviceServicePort(443, "HTTPS"),
            DeviceServicePort(445, "SMB"),
            DeviceServicePort(554, "RTSP"),
            DeviceServicePort(631, "IPP"),
            DeviceServicePort(1883, "MQTT"),
            DeviceServicePort(3389, "RDP"),
            DeviceServicePort(8080, "HTTP Alternate"),
            DeviceServicePort(8443, "HTTPS Alternate"),
            DeviceServicePort(9100, "JetDirect")
        )
    }
}

data class DeviceServicePort(val port: Int, val label: String)

data class DevicePortScanResult(
    val targetIp: String,
    val checkedServices: List<DeviceServicePort>,
    val openServices: List<DeviceServicePort>,
    val completedAt: Long,
    val errorMessage: String?
) {
    companion object {
        fun invalidTarget(device: LanDevice) = DevicePortScanResult(
            targetIp = device.addresses.firstOrNull().orEmpty(),
            checkedServices = emptyList(),
            openServices = emptyList(),
            completedAt = System.currentTimeMillis(),
            errorMessage = "未获取到可用的 IPv4 地址"
        )
    }
}

enum class DeviceOnlineStatus(val label: String) {
    Unknown("未检查"),
    Online("在线"),
    Unconfirmed("未确认")
}

data class DeviceOnlineResult(
    val status: DeviceOnlineStatus,
    val checkedAt: Long,
    val responsivePort: Int?,
    val detail: String
) {
    companion object {
        fun invalidTarget() = DeviceOnlineResult(
            status = DeviceOnlineStatus.Unconfirmed,
            checkedAt = System.currentTimeMillis(),
            responsivePort = null,
            detail = "未获取到可用的 IPv4 地址"
        )
    }
}
