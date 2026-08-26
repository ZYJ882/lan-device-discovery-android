package com.zyj.lanobserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val LanBlue: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF90CAF9) else Color(0xFF185ABC)
private val LanBlueDark: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE1F0FF) else Color(0xFF0B356F)
private val LanSky: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF19324F) else Color(0xFFEAF2FF)
private val LanSuccess: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF75DAB2) else Color(0xFF0B7654)
private val LanSuccessBg: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF153C32) else Color(0xFFE7F7F0)
private val LanMuted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB9C4D0) else Color(0xFF5F6673)
private val LanLine: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF344656) else Color(0xFFE4E7EC)
private val LanPage: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF101720) else Color(0xFFF7F9FC)
private val LanCard: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF18232F) else Color.White

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanDiscoveryScreen(
    state: LanDiscoveryUiState,
    onStartScan: (String) -> Unit,
    onCancelScan: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onFilter: (String) -> Unit,
    onSelectDevice: (String?) -> Unit,
    onScanDevicePorts: (String) -> Unit,
    onCancelPortScan: () -> Unit,
    onStartMonitoring: (String) -> Unit,
    onStopMonitoring: () -> Unit,
    onScanLocalHostPorts: () -> Unit,
    onCancelLocalHostPortScan: () -> Unit,
    onIdentifyDeviceModel: (String) -> Unit,
    onIdentifyDeviceWithOnvif: (String, String, String) -> Unit,
    onSyncOuiDatabase: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var selectedNetworkDetailsId by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = LanPage,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("局域网设备", fontWeight = FontWeight.Bold, color = LanBlueDark)
                        Text("LAN DISCOVERY", fontSize = 10.sp, color = LanMuted, letterSpacing = 1.5.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "OUI 数据库设置", tint = LanBlueDark)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LanPage)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                NetworkStatusSection(
                    networks = state.networks,
                    scannedDeviceCounts = state.scannedDeviceCounts,
                    selectedNetworkId = state.selectedNetworkId,
                    isScanning = state.isScanning,
                    progress = state.progress,
                    onRefreshNetwork = onRefreshNetwork,
                    onScanNetwork = onStartScan,
                    onCancelScan = onCancelScan,
                    onOpenDetails = { selectedNetworkDetailsId = it }
                )
            }
            item {
                ScanNotice(message = state.message, lastScanLabel = state.lastScanLabel)
            }
            item {
                DeviceToolbar(
                    count = state.devices.size,
                    query = state.searchQuery,
                    onFilter = onFilter
                )
            }
            when {
                state.visibleDevices.isEmpty() && state.isScanning -> item { SearchingPlaceholder() }
                state.visibleDevices.isEmpty() && state.devices.isNotEmpty() -> item { EmptyFilterPlaceholder() }
                state.visibleDevices.isEmpty() -> item { EmptyDevicePlaceholder() }
                else -> items(state.visibleDevices, key = { it.id }) { device ->
                    DeviceCard(device = device, onClick = { onSelectDevice(device.id) })
                }
            }
            item { PrivacyFootnote() }
        }
    }

    state.selectedDevice?.let { device ->
        val isStandaloneLocalHost = state.network == null && device.id == state.localHost.localIp?.let { "local:$it" }
        DeviceDetailDialog(
            device = device,
            portScan = if (isStandaloneLocalHost) state.localPortScan else state.portScanStates[device.id],
            onlineResult = state.onlineStates[device.id],
            isMonitoring = state.monitoredDeviceId == device.id,
            modelRecognition = state.modelRecognitionStates[device.id],
            ouiLookup = state.selectedOuiLookup,
            isStandaloneLocalHost = isStandaloneLocalHost,
            onScanPorts = {
                if (isStandaloneLocalHost) onScanLocalHostPorts() else onScanDevicePorts(device.id)
            },
            onCancelPortScan = {
                if (isStandaloneLocalHost) onCancelLocalHostPortScan() else onCancelPortScan()
            },
            onStartMonitoring = { onStartMonitoring(device.id) },
            onStopMonitoring = onStopMonitoring,
            onIdentifyModel = { onIdentifyDeviceModel(device.id) },
            onIdentifyWithOnvif = { username, password -> onIdentifyDeviceWithOnvif(device.id, username, password) },
            onDismiss = { onSelectDevice(null) }
        )
    }

    state.networks.firstOrNull { it.id == selectedNetworkDetailsId }?.let { network ->
        NetworkDetailDialog(network = network, onDismiss = { selectedNetworkDetailsId = null })
    }

    if (showSettings) {
        OuiSettingsDialog(
            status = state.ouiDatabaseStatus,
            isSyncing = state.isOuiSyncing,
            message = state.ouiSyncMessage,
            onSync = onSyncOuiDatabase,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun NetworkStatusSection(
    networks: List<LanNetworkUi>,
    scannedDeviceCounts: Map<String, Int>,
    selectedNetworkId: String?,
    isScanning: Boolean,
    progress: LanScanProgress?,
    onRefreshNetwork: () -> Unit,
    onScanNetwork: (String) -> Unit,
    onCancelScan: () -> Unit,
    onOpenDetails: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("网络状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = LanBlueDark)
            Spacer(Modifier.weight(1f))
            Text("${networks.size} 个网络", color = LanMuted, fontSize = 13.sp)
        }
        if (networks.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = LanCard), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("无网络连接", color = LanBlueDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("当前设备没有可用的网络连接。请连接 Wi‑Fi、以太网或开启个人热点。", color = LanMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onRefreshNetwork, modifier = Modifier.fillMaxWidth()) { Text("重新检测网络") }
                }
            }
        } else {
            networks.forEach { network ->
                NetworkStatusCard(
                    network = network,
                    discoveredCount = scannedDeviceCounts[network.id],
                    isSelected = network.id == selectedNetworkId,
                    isScanning = isScanning && network.id == selectedNetworkId,
                    progress = progress,
                    onScan = { onScanNetwork(network.id) },
                    onCancelScan = onCancelScan,
                    onOpenDetails = { onOpenDetails(network.id) }
                )
            }
            OutlinedButton(onClick = onRefreshNetwork, modifier = Modifier.fillMaxWidth()) { Text("刷新网络状态") }
        }
    }
}

@Composable
private fun NetworkStatusCard(
    network: LanNetworkUi,
    discoveredCount: Int?,
    isSelected: Boolean,
    isScanning: Boolean,
    progress: LanScanProgress?,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = LanCard),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetails)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetworkTypeMark(network.kind)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(network.title, color = LanBlueDark, fontWeight = FontWeight.Bold)
                    network.displayName?.let { Text(it, color = LanMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                StatusBadge(if (network.isValidated || network.isScanTarget) "已连接" else "已存在", network.isValidated || network.isScanTarget)
            }
            network.localIpv4?.let { NetworkValueRow("IPv4", it) }
            network.carrierName?.let { NetworkValueRow("运营商", it) }
            network.gateway?.let { NetworkValueRow("网关", it) }
            network.cidr?.let { NetworkValueRow("子网", it) }
            discoveredCount?.let { NetworkValueRow("局域网设备", "$it 台") }
            Spacer(Modifier.height(5.dp))
            Text(network.detail, color = if (network.isScanTarget) LanSuccess else LanMuted, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenDetails) { Text("网络详情") }
                Spacer(Modifier.weight(1f))
                if (network.isScanTarget) {
                    if (isScanning) {
                        OutlinedButton(onClick = onCancelScan) { Text("停止扫描") }
                    } else {
                        Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = LanBlue)) { Text("扫描此网络") }
                    }
                }
            }
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LanBlue, trackColor = LanSky)
                Spacer(Modifier.height(5.dp))
                Text(progress?.let { if (it.totalHosts > 0) "${it.message} · ${it.completedHosts}/${it.totalHosts}" else it.message } ?: "正在准备扫描", color = LanMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun NetworkTypeMark(kind: LanNetworkKind) {
    val label = when (kind) {
        LanNetworkKind.WIFI -> "Wi"
        LanNetworkKind.HOTSPOT -> "热"
        LanNetworkKind.ETHERNET -> "网"
        LanNetworkKind.CELLULAR -> "移"
        LanNetworkKind.VPN -> "VPN"
        LanNetworkKind.BLUETOOTH -> "蓝"
        LanNetworkKind.OTHER -> "其"
    }
    val background = when (kind) {
        LanNetworkKind.CELLULAR -> Color(0xFFF1EBFF)
        LanNetworkKind.VPN -> Color(0xFFFFF4E5)
        LanNetworkKind.HOTSPOT -> LanSuccessBg
        else -> LanSky
    }
    Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Text(label, color = LanBlueDark, fontSize = if (label.length > 2) 9.sp else 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun NetworkDetailDialog(network: LanNetworkUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("${network.title}详情", color = LanBlueDark, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                network.displayName?.let { DeviceDetailRow("名称", it) }
                DeviceDetailRow("状态", if (network.isValidated || network.isScanTarget) "已连接" else "已存在")
                network.localIpv4?.let { DeviceDetailRow("IPv4", it) }
                network.localIpv6?.let { DeviceDetailRow("IPv6", it) }
                network.gateway?.let { DeviceDetailRow("默认网关", it) }
                network.subnetMask?.let { DeviceDetailRow("子网掩码", it) }
                network.cidr?.let { DeviceDetailRow("CIDR", it) }
                network.carrierName?.let { DeviceDetailRow("运营商", it) }
                if (network.interfaceName.isNotBlank()) DeviceDetailRow("网络接口", network.interfaceName)
                if (network.dnsServers.isNotEmpty()) DeviceDetailRow("DNS", network.dnsServers.joinToString("\n"))
                DeviceDetailRow("互联网", if (network.isValidated) "已验证可访问" else if (network.hasInternet) "已配置，尚未验证" else "未声明互联网能力")
                DeviceDetailRow("扫描资格", if (network.isScanTarget) "可独立扫描此局域网" else "仅状态展示，不作为局域网扫描目标")
                Spacer(Modifier.height(8.dp))
                Surface(color = LanSky, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(network.detail, color = LanMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }
    )
}

@Composable
private fun NetworkValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LanMuted, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        Text(value, color = LanBlueDark, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusBadge(text: String, active: Boolean) {
    val background = if (active) LanSuccessBg else Color(0xFFFFF1F0)
    val foreground = if (active) LanSuccess else Color(0xFFB42318)
    Surface(shape = CircleShape, color = background) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScanNotice(message: String, lastScanLabel: String?) {
    Surface(color = LanSky, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("扫描说明", fontWeight = FontWeight.Bold, color = LanBlueDark, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(message, color = LanMuted, fontSize = 12.sp, lineHeight = 18.sp)
            lastScanLabel?.let { label ->
                Spacer(Modifier.height(5.dp))
                Text(label, color = LanBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun DeviceToolbar(count: Int, query: String, onFilter: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发现的设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = LanBlueDark)
            Spacer(Modifier.weight(1f))
            Text("$count 台", color = LanMuted, fontSize = 13.sp)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onFilter,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("按名称、IP、服务或类型筛选") },
            label = { Text("筛选设备") }
        )
    }
}

@Composable
private fun DeviceCard(device: LanDevice, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = LanCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            DeviceMonogram(device)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.displayName, color = LanBlueDark, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (device.id.startsWith("local:")) StatusBadge("本机", true)
                }
                Spacer(Modifier.height(3.dp))
                Text(device.deviceHint, color = LanMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                device.details["型号识别证据"]?.let { evidence ->
                    Spacer(Modifier.height(5.dp))
                    Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFFE8F5EE)) {
                        Text("公开型号 · $evidence", modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp, color = LanSuccess)
                    }
                }
                val addresses = device.addresses.joinToString(" · ")
                if (addresses.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(addresses, color = LanBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (device.services.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    DeviceTagRow(device.services.toList())
                }
            }
        }
    }
}

@Composable
private fun DeviceMonogram(device: LanDevice) {
    val value = when {
        device.id.startsWith("local:") -> "我"
        device.services.any { it.contains("打印") || it.contains("IPP") || it.contains("JetDirect") } -> "印"
        device.services.any { it.contains("媒体") || it.contains("Air") || it.contains("Cast") } -> "播"
        device.services.any { it.contains("HTTP") || it.contains("HTTPS") } -> "网"
        else -> "设"
    }
    Box(
        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(LanSky),
        contentAlignment = Alignment.Center
    ) { Text(value, color = LanBlue, fontWeight = FontWeight.ExtraBold) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceTagRow(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        tags.take(4).forEach { tag ->
            Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFFF1F5FB)) {
                Text(tag, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp, color = LanBlueDark)
            }
        }
    }
}

@Composable
private fun SearchingPlaceholder() {
    Surface(color = LanCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在等待设备响应", fontWeight = FontWeight.Bold, color = LanBlueDark)
            Spacer(Modifier.height(5.dp))
            Text("mDNS、UPnP 与常见服务会陆续显示在这里。", color = LanMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyFilterPlaceholder() {
    Surface(color = LanCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text("没有设备与当前筛选条件匹配。", modifier = Modifier.padding(22.dp), color = LanMuted, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyDevicePlaceholder() {
    Surface(color = LanCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("尚未开始扫描", fontWeight = FontWeight.Bold, color = LanBlueDark)
            Spacer(Modifier.height(5.dp))
            Text("点击“开始发现设备”以查找当前局域网中主动响应的设备。", color = LanMuted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun PrivacyFootnote() {
    Text(
        "设备信息仅在本次扫描中保留。结果取决于网络隔离、防火墙及设备是否公开广播服务；未显示不代表设备不存在。",
        color = LanMuted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun DeviceDetailDialog(
    device: LanDevice,
    portScan: DevicePortScanUiState?,
    onlineResult: DeviceOnlineResult?,
    isMonitoring: Boolean,
    modelRecognition: ModelRecognitionUiState?,
    ouiLookup: OuiLookupResult?,
    isStandaloneLocalHost: Boolean,
    onScanPorts: () -> Unit,
    onCancelPortScan: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onIdentifyModel: () -> Unit,
    onIdentifyWithOnvif: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scanState = portScan ?: DevicePortScanUiState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(device.displayName, color = LanBlueDark, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(device.deviceHint, color = LanMuted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                DeviceDetailRow("IP 地址", device.addresses.joinToString("\n").ifBlank { "未获取" })
                device.hostname?.let { DeviceDetailRow("主机名", it) }
                DeviceDetailRow("发现方式", device.sources.joinToString("、"))
                if (device.services.isNotEmpty()) DeviceDetailRow("公开服务", device.services.joinToString("、"))
                if (device.ports.isNotEmpty()) DeviceDetailRow("发现时响应端口", device.ports.sorted().joinToString(", "))
                device.manufacturer?.let { DeviceDetailRow("公开厂商", it) }
                if (!isStandaloneLocalHost) {
                    val identityEvidence = device.details["型号识别证据"]
                    val publicModel = device.details.entries
                        .firstOrNull { (key, value) -> key != "型号识别证据" && key.contains("型号") && value.isNotBlank() }
                        ?.value
                    if (publicModel != null) DeviceDetailRow("公开型号", publicModel)
                    DeviceDetailRow("型号识别", identityEvidence ?: "未发现设备公开的型号字段")
                }
                device.details.toSortedMap().forEach { (key, value) ->
                    if (key != "型号识别证据" && !key.contains("型号") && value.isNotBlank()) DeviceDetailRow(key, value)
                }
                OuiLookupSection(ouiLookup)

                if (!isStandaloneLocalHost) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = LanLine)
                    Spacer(Modifier.height(14.dp))
                    ModelRecognitionSection(
                        state = modelRecognition ?: ModelRecognitionUiState(),
                        onIdentify = onIdentifyModel,
                        onIdentifyWithOnvif = onIdentifyWithOnvif
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = LanLine)
                Spacer(Modifier.height(14.dp))
                Text(if (isStandaloneLocalHost) "本机端口检测" else "端口扫描", color = LanBlueDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isStandaloneLocalHost) "仅检查此本机 IPv4 的 14 个固定常见 TCP 服务端口；不会检查外部地址、端口范围或发送协议载荷。"
                    else "仅检查此已发现设备的 14 个常见服务端口；不发送协议载荷或认证请求。",
                    color = LanMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                if (scanState.isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LanBlue, trackColor = LanSky)
                    Spacer(Modifier.height(6.dp))
                    Text("${scanState.message ?: "正在扫描"} · ${scanState.completedPorts}/${scanState.totalPorts}", color = LanMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancelPortScan, modifier = Modifier.fillMaxWidth()) { Text("停止端口扫描") }
                } else {
                    Button(
                        onClick = onScanPorts,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = LanBlue)
                    ) { Text(if (isStandaloneLocalHost) "检测本机 14 个常见端口" else "扫描 14 个常见端口") }
                }
                scanState.result?.let { result ->
                    Spacer(Modifier.height(7.dp))
                    result.errorMessage?.let { DeviceDetailRow("扫描状态", it) }
                    if (result.errorMessage == null) {
                        val open = result.openServices.joinToString("、") { "${it.label} (${it.port})" }
                        DeviceDetailRow("开放端口", open.ifBlank { "未检测到开放的常见服务端口" })
                        DeviceDetailRow("扫描范围", result.checkedServices.joinToString("、") { it.port.toString() })
                    }
                }

                if (!isStandaloneLocalHost) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = LanLine)
                    Spacer(Modifier.height(14.dp))
                    Text("在线状态监测", color = LanBlueDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(onlineResult?.status?.label ?: DeviceOnlineStatus.Unknown.label, onlineResult?.status == DeviceOnlineStatus.Online)
                        Spacer(Modifier.width(8.dp))
                        Text(onlineResult?.detail ?: "尚未检查此设备的连通性", color = LanMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("开启后仅在应用前台每 15 秒检查一次已知常见服务端口；无响应不代表设备一定离线。", color = LanMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(9.dp))
                    if (isMonitoring) {
                        OutlinedButton(onClick = onStopMonitoring, modifier = Modifier.fillMaxWidth()) { Text("停止在线监测") }
                    } else {
                        Button(
                            onClick = onStartMonitoring,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = LanSuccess)
                        ) { Text("开始在线监测") }
                    }
                }
            }
        }
    )
}

@Composable
private fun OuiLookupSection(lookup: OuiLookupResult?) {
    lookup ?: return
    val value = when {
        lookup.locallyAdministered -> "随机/本地管理 MAC，不进行 OUI 匹配"
        !lookup.databaseAvailable -> "尚未同步 IEEE OUI 数据库"
        !lookup.vendor.isNullOrBlank() -> "${lookup.vendor}（${lookup.prefixLength?.times(4) ?: 0} 位前缀）"
        else -> "IEEE 本地数据库未匹配到注册厂商"
    }
    DeviceDetailRow("网卡厂商（OUI）", value)
    Text(
        "OUI 仅表示 MAC 前缀的注册网卡厂商，不代表设备厂商或具体型号；匹配只在本机完成。",
        color = LanMuted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 1.dp, bottom = 6.dp)
    )
}

@Composable
private fun ModelRecognitionSection(
    state: ModelRecognitionUiState,
    onIdentify: () -> Unit,
    onIdentifyWithOnvif: (String, String) -> Unit
) {
    var onvifUser by remember { mutableStateOf("") }
    var onvifPassword by remember { mutableStateOf("") }
    Text("型号识别", color = LanBlueDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Spacer(Modifier.height(4.dp))
    Text(
        "仅依据已经发现的 UPnP、IPP、mDNS 或 WS-Discovery 证据进行只读查询；不会扫描端口，也不会把 OUI、端口或 Server 头当作型号。",
        color = LanMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
    Spacer(Modifier.height(10.dp))
    if (state.isRunning) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LanBlue, trackColor = LanSky)
        Spacer(Modifier.height(7.dp))
        Text(state.result.detail, color = LanMuted, fontSize = 12.sp)
    } else {
        Button(
            onClick = onIdentify,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = LanBlue)
        ) { Text("识别型号") }
    }

    if (state.result.level != ModelRecognitionLevel.Idle && state.result.level != ModelRecognitionLevel.Running) {
        Spacer(Modifier.height(9.dp))
        val (label, foreground, background) = when (state.result.level) {
            ModelRecognitionLevel.Confirmed -> Triple("已确认型号", LanSuccess, LanSuccessBg)
            ModelRecognitionLevel.PublicDeclared -> Triple("公开声明型号", LanBlue, LanSky)
            ModelRecognitionLevel.CategoryOnly -> Triple("仅识别设备类别", Color(0xFF9A6700), Color(0xFFFFF4E5))
            ModelRecognitionLevel.Unavailable -> Triple("未能确认型号", Color(0xFFB42318), Color(0xFFFFF1F0))
            ModelRecognitionLevel.NeedsCredentials -> Triple("需要 ONVIF 凭据", Color(0xFF9A6700), Color(0xFFFFF4E5))
            else -> Triple("型号识别", LanMuted, Color(0xFFF1F5FB))
        }
        Surface(shape = RoundedCornerShape(10.dp), color = background, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(11.dp)) {
                Text(label, color = foreground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                state.result.model?.let { Text(it, color = LanBlueDark, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                state.result.manufacturer?.let { Text("公开厂商：$it", color = LanMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
                state.result.category?.let { Text("设备类别：$it", color = LanBlueDark, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                state.result.evidence?.let { Text("证据：$it", color = LanMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
                Text(state.result.detail, color = LanMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (state.result.level == ModelRecognitionLevel.NeedsCredentials) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = onvifUser,
            onValueChange = { onvifUser = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("ONVIF 用户名") }
        )
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = onvifPassword,
            onValueChange = { onvifPassword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("ONVIF 密码") }
        )
        Spacer(Modifier.height(7.dp))
        OutlinedButton(
            onClick = { onIdentifyWithOnvif(onvifUser, onvifPassword) },
            enabled = onvifUser.isNotBlank() && onvifPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("使用本次输入的凭据识别") }
        Text(
            "仅本次请求使用，应用不会保存、上传或复用凭据。",
            color = LanMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun OuiSettingsDialog(
    status: OuiDatabaseStatus,
    isSyncing: Boolean,
    message: String?,
    onSync: () -> Unit,
    onDismiss: () -> Unit
) {
    val lastSync = status.lastSyncedAt?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.CHINA).format(Date(timestamp))
    } ?: "从未同步"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("OUI 厂商数据库", color = LanBlueDark, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("数据来源", color = LanMuted, fontSize = 11.sp)
                Text(status.sourceLabel, color = LanBlueDark, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("上次同步", color = LanMuted, fontSize = 11.sp)
                Text(lastSync, color = LanBlueDark, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("本地记录数", color = LanMuted, fontSize = 11.sp)
                Text(String.format(Locale.CHINA, "%,d 条", status.entryCount), color = LanBlueDark, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "同步仅在您手动点击后从 IEEE 官方 MA-L、MA-M、MA-S 公共注册表下载。下载失败会保留现有数据库；扫描期间不会联网查询或上传完整 MAC 地址。IEEE 对下载频率设有限制，请勿频繁同步。",
                    color = LanMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))
                if (isSyncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LanBlue, trackColor = LanSky)
                    Spacer(Modifier.height(7.dp))
                } else {
                    Button(onClick = onSync, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = LanBlue)) {
                        Text("同步 OUI 数据库")
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = LanMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    )
}

@Composable
private fun DeviceDetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = LanMuted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = LanBlueDark, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(7.dp))
        HorizontalDivider(color = LanLine)
    }
}
