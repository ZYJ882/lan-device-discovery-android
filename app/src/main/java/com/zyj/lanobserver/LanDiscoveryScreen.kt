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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LanBlue = Color(0xFF185ABC)
private val LanBlueDark = Color(0xFF0B356F)
private val LanSky = Color(0xFFEAF2FF)
private val LanSuccess = Color(0xFF0B7654)
private val LanSuccessBg = Color(0xFFE7F7F0)
private val LanMuted = Color(0xFF5F6673)
private val LanLine = Color(0xFFE4E7EC)
private val LanPage = Color(0xFFF7F9FC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanDiscoveryScreen(
    state: LanDiscoveryUiState,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onFilter: (String) -> Unit,
    onSelectDevice: (String?) -> Unit,
    onScanDevicePorts: (String) -> Unit,
    onCancelPortScan: () -> Unit,
    onStartMonitoring: (String) -> Unit,
    onStopMonitoring: () -> Unit
) {
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
                NetworkOverviewCard(
                    network = state.network,
                    isScanning = state.isScanning,
                    progress = state.progress,
                    onStartScan = onStartScan,
                    onCancelScan = onCancelScan,
                    onRefreshNetwork = onRefreshNetwork
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
        DeviceDetailDialog(
            device = device,
            portScan = state.portScanStates[device.id],
            onlineResult = state.onlineStates[device.id],
            isMonitoring = state.monitoredDeviceId == device.id,
            onScanPorts = { onScanDevicePorts(device.id) },
            onCancelPortScan = onCancelPortScan,
            onStartMonitoring = { onStartMonitoring(device.id) },
            onStopMonitoring = onStopMonitoring,
            onDismiss = { onSelectDevice(null) }
        )
    }
}

@Composable
private fun NetworkOverviewCard(
    network: LanNetworkUi?,
    isScanning: Boolean,
    progress: LanScanProgress?,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onRefreshNetwork: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(LanSky),
                    contentAlignment = Alignment.Center
                ) { Text("LAN", color = LanBlue, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("当前网络", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LanBlueDark)
                    Text(network?.transport ?: "正在等待网络", color = LanMuted, fontSize = 13.sp)
                }
                StatusBadge(if (network == null) "未连接" else "已连接", network != null)
            }
            Spacer(Modifier.height(17.dp))
            if (network == null) {
                Text("请先连接到 Wi‑Fi 或以太网。应用仅在当前局域网内发现设备。", color = LanMuted, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onRefreshNetwork, modifier = Modifier.fillMaxWidth()) { Text("重新检测网络") }
            } else {
                NetworkValueRow("本机 IP", network.localIp)
                NetworkValueRow(if (network.isHotspot) "热点网关" else "默认网关", network.gateway ?: "未获取")
                NetworkValueRow("网络范围", network.actualCidr)
                if (network.isHotspot) {
                    Spacer(Modifier.height(5.dp))
                    Surface(color = LanSuccessBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                                                        "热点模式：仅以客户端实际公开的 mDNS、UPnP 响应识别设备；不会以裸 TCP 建连推断设备存在。"
,
                            color = LanSuccess,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (network.hasVpn) {
                    Spacer(Modifier.height(7.dp))
                    Surface(color = Color(0xFFFFF4E5), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "检测到 VPN：已自动禁用 TCP 子网扫掠，避免代理或中间层导致虚假设备。",
                            color = Color(0xFF9A6700),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                if (network.actualCidr != network.scanCidr) {
                    NetworkValueRow("本次扫描", "${network.scanCidr}（限制为本机 /24）")
                }
                if (isScanning) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LanBlue, trackColor = LanSky)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        progress?.let { item ->
                            if (item.totalHosts > 0) "${item.message} · ${item.completedHosts}/${item.totalHosts}" else item.message
                        } ?: "正在扫描",
                        color = LanMuted,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (isScanning) {
                    OutlinedButton(onClick = onCancelScan, modifier = Modifier.fillMaxWidth()) { Text("停止扫描") }
                } else {
                    Button(
                        onClick = onStartScan,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = LanBlue)
                    ) { Text(if (network.isHotspot) "扫描热点设备" else "开始发现设备") }
                }
            }
        }
    }
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
        colors = CardDefaults.cardColors(containerColor = Color.White),
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
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在等待设备响应", fontWeight = FontWeight.Bold, color = LanBlueDark)
            Spacer(Modifier.height(5.dp))
            Text("mDNS、UPnP 与常见服务会陆续显示在这里。", color = LanMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyFilterPlaceholder() {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text("没有设备与当前筛选条件匹配。", modifier = Modifier.padding(22.dp), color = LanMuted, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyDevicePlaceholder() {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
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
    onScanPorts: () -> Unit,
    onCancelPortScan: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
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
                device.manufacturer?.let { DeviceDetailRow("厂商 / 型号", it) }
                device.details.toSortedMap().forEach { (key, value) ->
                    if (value.isNotBlank()) DeviceDetailRow(key, value)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = LanLine)
                Spacer(Modifier.height(14.dp))
                Text("端口扫描", color = LanBlueDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("仅检查此已发现设备的 14 个常见服务端口；不发送协议载荷或认证请求。", color = LanMuted, fontSize = 12.sp, lineHeight = 17.sp)
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
                    ) { Text("扫描 14 个常见端口") }
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
