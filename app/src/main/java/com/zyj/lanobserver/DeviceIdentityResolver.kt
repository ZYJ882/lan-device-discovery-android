package com.zyj.lanobserver

import android.net.Network
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 仅读取 SSDP 响应设备自身、同一 IPv4 地址上的 UPnP device description。
 * 请求使用扫描选定的 Wi‑Fi Network，禁止重定向、跨主机和超限读取。
 */
class DeviceIdentityResolver {
    fun resolveUpnpDescription(
        location: String,
        responderAddress: String,
        network: Network?,
        diagnostics: DiscoveryDiagnostics? = null
    ): PublicDeviceIdentity? {
        val url = runCatching { URL(location) }.getOrElse {
            diagnostics?.sourceFailure("SSDP", "invalid LOCATION=$location")
            return null
        }
        if (url.protocol.lowercase() != "http") {
            diagnostics?.sourceFailure("SSDP", "unsupported LOCATION scheme=${url.protocol}")
            return null
        }
        val responderIp = runCatching { InetAddress.getByName(responderAddress).hostAddress }.getOrNull() ?: return null
        val locationIps = runCatching {
            (network?.getAllByName(url.host) ?: InetAddress.getAllByName(url.host)).mapNotNull { it.hostAddress }.toSet()
        }.getOrNull() ?: return null
        if (responderIp !in locationIps) {
            diagnostics?.sourceFailure("SSDP", "LOCATION host does not match responder ip=$responderIp host=${url.host}")
            return null
        }

        val payload = runCatching {
            val connection = ((network?.openConnection(url) ?: url.openConnection()) as? HttpURLConnection) ?: return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "LanDeviceDiscovery/2.0")
            connection.inputStream.use { input -> input.readBounded(MAX_DESCRIPTION_BYTES) }
        }.getOrElse {
            diagnostics?.sourceFailure("SSDP", "UPnP description request failed ip=$responderAddress reason=${it.javaClass.simpleName}")
            return null
        }
        if (payload.isEmpty()) return null
        return parsePublicIdentity(payload)?.also { identity ->
            diagnostics?.observation("SSDP", "upnpXml ip=$responderAddress name=${identity.friendlyName ?: "none"} model=${identity.modelName ?: "none"}")
        }
    }

    private fun parsePublicIdentity(payload: ByteArray): PublicDeviceIdentity? = runCatching {
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
        PublicDeviceIdentity(
            friendlyName = document.textOf("friendlyName"),
            manufacturer = document.textOf("manufacturer"),
            modelName = document.textOf("modelName"),
            modelNumber = document.textOf("modelNumber"),
            modelDescription = document.textOf("modelDescription"),
            serialNumber = document.textOf("serialNumber"),
            udn = document.textOf("UDN"),
            deviceType = document.textOf("deviceType")
        ).takeIf { it.hasPublicIdentity }
    }.getOrNull()

    private fun Document.textOf(tagName: String): String? = getElementsByTagName(tagName)
        .item(0)
        ?.textContent
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() }
        ?.take(MAX_FIELD_LENGTH)

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

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 1_200
        const val READ_TIMEOUT_MILLIS = 1_500
        const val MAX_DESCRIPTION_BYTES = 64 * 1024
        const val MAX_FIELD_LENGTH = 180
    }
}

data class PublicDeviceIdentity(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val modelDescription: String?,
    val serialNumber: String?,
    val udn: String?,
    val deviceType: String?
) {
    val hasPublicIdentity: Boolean
        get() = listOf(friendlyName, manufacturer, modelName, modelNumber, modelDescription, serialNumber, udn, deviceType)
            .any { !it.isNullOrBlank() }

    fun bestFriendlyName(): String? = friendlyName?.takeIf { it.isNotBlank() }

    fun bestModel(): String? = listOf(modelName, modelNumber, modelDescription)
        .firstOrNull { !it.isNullOrBlank() }

    fun asDetails(): Map<String, String> = buildMap {
        put("型号识别证据", "UPnP 描述公开声明")
        friendlyName?.let { put("UPnP friendlyName", it) }
        manufacturer?.let { put("UPnP 厂商", it) }
        modelName?.let { put("公开型号", it) }
        modelNumber?.let { put("UPnP 型号编号", it) }
        modelDescription?.let { put("UPnP 设备描述", it) }
        serialNumber?.let { put("UPnP serialNumber", it) }
        udn?.let { put("UPnP UDN", it) }
        deviceType?.let { put("UPnP deviceType", it) }
    }
}
