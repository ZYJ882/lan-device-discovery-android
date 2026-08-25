package com.zyj.lanobserver

import android.net.Network
import android.util.Base64
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 详情页按需运行的型号识别器。
 *
 * 它绝不扫描端口、不会尝试登录、不会把 OUI、开放端口、HTTP Server 或 SSDP SERVER 当作型号。
 * ONVIF 请求只有在用户明确输入凭据后才发送；凭据不会写入磁盘或状态快照。
 */
class DeviceModelResolver {
    private val upnpResolver = DeviceIdentityResolver()
    private val ippResolver = IppIdentityResolver()

    fun needsOnvifCredentials(device: LanDevice): Boolean = onvifEndpoint(device) != null

    fun identifyPublic(device: LanDevice, network: Network?): ModelRecognitionResult {
        resolveUpnp(device, network)?.let { return it }
        resolveIpp(device, network)?.let { return it }
        resolveMdnsDeclaration(device)?.let { return it }
        categoryFromExistingEvidence(device)?.let { category ->
            return ModelRecognitionResult.categoryOnly(category.first, category.second)
        }
        return if (needsOnvifCredentials(device)) {
            ModelRecognitionResult.needsCredentials("已发现 ONVIF / WS-Discovery 线索。ONVIF 的 GetDeviceInformation 需要先由您输入设备凭据。")
        } else {
            ModelRecognitionResult.unavailable("未发现可用于只读识别的 UPnP、IPP、mDNS 或 WS-Discovery 公开元数据。")
        }
    }

    fun identifyOnvif(device: LanDevice, network: Network?, credentials: OnvifCredentials): ModelRecognitionResult {
        val endpoint = onvifEndpoint(device)
            ?: return ModelRecognitionResult.unavailable("此设备没有已发现的 ONVIF / WS-Discovery 服务地址，不能安全地猜测 ONVIF 端点。")
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return ModelRecognitionResult.needsCredentials("请输入 ONVIF 用户名和密码后再发起只读 GetDeviceInformation 请求。")
        }
        val identity = OnvifIdentityResolver().resolve(endpoint, device.addresses, network, credentials)
            ?: return ModelRecognitionResult.unavailable("ONVIF 未返回可用设备信息。请核对凭据、设备权限和已发现的服务地址；凭据未被保存。")
        return when {
            !identity.model.isNullOrBlank() -> ModelRecognitionResult.confirmed(
                model = identity.model,
                manufacturer = identity.manufacturer,
                evidence = "用户授权的 ONVIF GetDeviceInformation"
            )
            !identity.category.isNullOrBlank() -> ModelRecognitionResult.categoryOnly(
                category = identity.category,
                evidence = "用户授权的 ONVIF GetDeviceInformation"
            )
            else -> ModelRecognitionResult.unavailable("ONVIF 请求完成，但设备没有公开具体型号字段。")
        }
    }

    private fun resolveUpnp(device: LanDevice, network: Network?): ModelRecognitionResult? {
        val location = device.details["SSDP LOCATION"] ?: return null
        val address = device.addresses.firstOrNull { runCatching { InetAddress.getByName(it) is Inet4Address }.getOrDefault(false) }
            ?: return null
        val identity = upnpResolver.resolveUpnpDescription(location, address, network) ?: return null
        val model = listOfNotNull(identity.modelName, identity.modelNumber).joinToString(" ").takeIf { it.isNotBlank() }
        return when {
            model != null -> ModelRecognitionResult.confirmed(
                model = model,
                manufacturer = identity.manufacturer,
                evidence = "UPnP 设备描述公开字段（同一 SSDP 响应地址）"
            )
            !identity.modelDescription.isNullOrBlank() -> ModelRecognitionResult.publicDeclared(
                model = identity.modelDescription,
                manufacturer = identity.manufacturer,
                evidence = "UPnP 设备描述公开声明"
            )
            !identity.deviceType.isNullOrBlank() -> ModelRecognitionResult.categoryOnly(
                category = humanizeUpnpType(identity.deviceType),
                evidence = "UPnP deviceType 公开字段"
            )
            else -> null
        }
    }

    private fun resolveIpp(device: LanDevice, network: Network?): ModelRecognitionResult? {
        if (device.services.none { it == "IPP" }) return null
        val address = device.addresses.firstOrNull { runCatching { InetAddress.getByName(it) is Inet4Address }.getOrDefault(false) } ?: return null
        val host = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return null
        val port = device.details["mDNS 端口"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: device.ports.firstOrNull { it in 1..65535 }
            ?: return null
        val path = device.details.entries.firstOrNull { (key, _) -> key.equals("rp", ignoreCase = true) }?.value ?: return null
        val identity = ippResolver.resolve(host, port, path, network) ?: return null
        return when {
            !identity.makeAndModel.isNullOrBlank() -> ModelRecognitionResult.confirmed(
                model = identity.makeAndModel,
                manufacturer = null,
                evidence = "IPP Get-Printer-Attributes 标准只读属性"
            )
            else -> ModelRecognitionResult.categoryOnly("网络打印设备", "IPP 标准只读属性")
        }
    }

    private fun resolveMdnsDeclaration(device: LanDevice): ModelRecognitionResult? {
        val model = device.details["mDNS 公开型号"] ?: return null
        return ModelRecognitionResult.publicDeclared(
            model = model,
            manufacturer = device.details["mDNS 公开厂商"] ?: device.manufacturer,
            evidence = "mDNS TXT 公开声明"
        )
    }

    private fun categoryFromExistingEvidence(device: LanDevice): Pair<String, String>? = when {
        device.details["UPnP 设备类型"] != null -> humanizeUpnpType(device.details.getValue("UPnP 设备类型")) to "UPnP deviceType 公开字段"
        device.details["mDNS 服务类型"] != null -> categoryForMdns(device.services, device.details.getValue("mDNS 服务类型")) to "mDNS 服务类型"
        device.services.any { it == "IPP" || it == "打印服务" } -> "网络打印设备" to "mDNS 服务类型"
        device.services.any { it in setOf("AirPlay", "RAOP", "Google Cast") } -> "媒体播放设备" to "mDNS 服务类型"
        device.services.any { it == "SMB" } -> "文件共享设备" to "mDNS 服务类型"
        else -> null
    }

    private fun onvifEndpoint(device: LanDevice): String? {
        val candidates = listOfNotNull(
            device.details["ONVIF XAddr"],
            device.details["WS-Discovery XAddr"],
            device.details["WS-Discovery XAddrs"]
        ).flatMap { raw -> raw.split(Regex("[\\s,]+")) }
        return candidates.firstOrNull { value ->
            val lower = value.lowercase()
            (lower.startsWith("http://") || lower.startsWith("https://")) && lower.contains("onvif")
        }
    }

    private fun categoryForMdns(services: Set<String>, serviceType: String): String = when {
        services.any { it == "IPP" || it == "打印服务" } || serviceType.contains("ipp", true) -> "网络打印设备"
        services.any { it in setOf("AirPlay", "RAOP", "Google Cast") } -> "媒体播放设备"
        services.any { it == "SMB" } -> "文件共享设备"
        services.any { it == "SSH" } -> "远程管理设备"
        else -> "局域网服务设备"
    }

    private fun humanizeUpnpType(rawType: String): String = when {
        rawType.contains("MediaRenderer", true) -> "媒体播放设备"
        rawType.contains("MediaServer", true) -> "媒体服务器"
        rawType.contains("InternetGatewayDevice", true) -> "网络网关设备"
        rawType.contains("Printer", true) -> "网络打印设备"
        else -> "UPnP 设备"
    }
}

private class OnvifIdentityResolver {
    fun resolve(endpoint: String, allowedAddresses: Set<String>, network: Network?, credentials: OnvifCredentials): OnvifIdentity? {
        val url = runCatching { URL(endpoint) }.getOrNull() ?: return null
        if (url.protocol.lowercase() !in setOf("http", "https")) return null
        val expected = allowedAddresses.mapNotNull { value -> runCatching { InetAddress.getByName(value).hostAddress }.getOrNull() }.toSet()
        if (expected.isEmpty()) return null
        val resolved = runCatching { (network?.getAllByName(url.host) ?: InetAddress.getAllByName(url.host)).mapNotNull { it.hostAddress }.toSet() }.getOrNull() ?: return null
        if (resolved.intersect(expected).isEmpty()) return null
        val payload = soapEnvelope(credentials)
        val body = runCatching {
            val connection = ((network?.openConnection(url) ?: url.openConnection()) as? HttpURLConnection) ?: return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            connection.setRequestProperty("User-Agent", "LanDeviceDiscovery/2.1.0")
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBounded(MAX_RESPONSE_BYTES) }
        }.getOrNull() ?: return null
        return parseIdentity(body)
    }

    private fun soapEnvelope(credentials: OnvifCredentials): ByteArray {
        val nonce = UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)
        val created = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(nonce + created.toByteArray(StandardCharsets.UTF_8) + credentials.password.toByteArray(StandardCharsets.UTF_8))
        val nonceValue = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val digestValue = Base64.encodeToString(digest, Base64.NO_WRAP)
        val xml = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
              <s:Header><wsse:Security><wsse:UsernameToken><wsse:Username>${escapeXml(credentials.username)}</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestValue</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceValue</wsse:Nonce><wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header>
              <s:Body><tds:GetDeviceInformation/></s:Body>
            </s:Envelope>
        """.trimIndent()
        return xml.toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseIdentity(payload: ByteArray): OnvifIdentity? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(payload))
        OnvifIdentity(
            manufacturer = document.textOf("Manufacturer"),
            model = document.textOf("Model"),
            category = document.textOf("HardwareId")?.takeIf { it.isNotBlank() }
        ).takeIf { it.manufacturer != null || it.model != null || it.category != null }
    }.getOrNull()

    private fun Document.textOf(tag: String): String? = getElementsByTagName(tag).item(0)?.textContent
        ?.trim()?.replace(Regex("\\s+"), " ")?.take(180)?.takeIf { it.isNotBlank() }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val count = read(buffer, offset, limit - offset)
            if (count <= 0) break
            offset += count
        }
        return buffer.copyOf(offset)
    }

    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 1_500
        const val READ_TIMEOUT_MILLIS = 2_500
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

data class OnvifCredentials(val username: String, val password: String)

data class OnvifIdentity(val manufacturer: String?, val model: String?, val category: String?)

enum class ModelRecognitionLevel {
    Idle,
    Running,
    Confirmed,
    PublicDeclared,
    CategoryOnly,
    Unavailable,
    NeedsCredentials
}

data class ModelRecognitionResult(
    val level: ModelRecognitionLevel,
    val model: String? = null,
    val manufacturer: String? = null,
    val category: String? = null,
    val evidence: String? = null,
    val detail: String
) {
    companion object {
        fun idle() = ModelRecognitionResult(ModelRecognitionLevel.Idle, detail = "尚未发起型号识别。")
        fun running() = ModelRecognitionResult(ModelRecognitionLevel.Running, detail = "正在根据已发现的协议证据进行只读识别。")
        fun confirmed(model: String, manufacturer: String?, evidence: String) = ModelRecognitionResult(ModelRecognitionLevel.Confirmed, model, manufacturer, evidence = evidence, detail = "协议返回了明确的型号字段。")
        fun publicDeclared(model: String, manufacturer: String?, evidence: String) = ModelRecognitionResult(ModelRecognitionLevel.PublicDeclared, model, manufacturer, evidence = evidence, detail = "型号由设备在公开服务中自行声明，未做跨协议确认。")
        fun categoryOnly(category: String, evidence: String) = ModelRecognitionResult(ModelRecognitionLevel.CategoryOnly, category = category, evidence = evidence, detail = "协议只能确认设备类别，不能确认具体型号。")
        fun unavailable(detail: String) = ModelRecognitionResult(ModelRecognitionLevel.Unavailable, detail = detail)
        fun needsCredentials(detail: String) = ModelRecognitionResult(ModelRecognitionLevel.NeedsCredentials, detail = detail)
    }
}

data class ModelRecognitionUiState(
    val isRunning: Boolean = false,
    val result: ModelRecognitionResult = ModelRecognitionResult.idle()
)
