package com.zyj.lanobserver

import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 只读取 SSDP 响应设备自身公开的 UPnP 描述文档，用于补充友好名称、厂商与型号。
 *
 * 为防止将局域网发现变成任意 URL 请求：仅接受 http、响应地址相同的主机、固定大小与短超时。
 */
class DeviceIdentityResolver {
    fun resolveUpnpDescription(location: String, responderAddress: String): PublicDeviceIdentity? {
        val url = runCatching { URL(location) }.getOrNull() ?: return null
        if (url.protocol.lowercase() != "http") return null
        val responderIp = runCatching { InetAddress.getByName(responderAddress).hostAddress }.getOrNull() ?: return null
        val locationIp = runCatching { InetAddress.getByName(url.host).hostAddress }.getOrNull() ?: return null
        if (responderIp != locationIp) return null

        val payload = runCatching {
            (url.openConnection() as? HttpURLConnection)?.let { connection ->
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "LanDeviceDiscovery/1.3")
                connection.inputStream.use { input -> input.readBounded(MAX_DESCRIPTION_BYTES) }
            }
        }.getOrNull() ?: return null
        if (payload.isEmpty()) return null
        return parsePublicIdentity(payload)
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
        val identity = PublicDeviceIdentity(
            friendlyName = document.textOf("friendlyName"),
            manufacturer = document.textOf("manufacturer"),
            modelName = document.textOf("modelName"),
            modelNumber = document.textOf("modelNumber"),
            modelDescription = document.textOf("modelDescription"),
            deviceType = document.textOf("deviceType")
        )
        identity.takeIf { it.hasPublicIdentity }
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
        const val CONNECT_TIMEOUT_MILLIS = 900
        const val READ_TIMEOUT_MILLIS = 1_200
        const val MAX_DESCRIPTION_BYTES = 64 * 1024
        const val MAX_FIELD_LENGTH = 160
    }
}

data class PublicDeviceIdentity(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val modelDescription: String?,
    val deviceType: String?
) {
    val hasPublicIdentity: Boolean
        get() = listOf(friendlyName, manufacturer, modelName, modelNumber, modelDescription, deviceType).any { !it.isNullOrBlank() }

    fun asDetails(): Map<String, String> = buildMap {
        friendlyName?.let { put("UPnP 设备名", it) }
        manufacturer?.let { put("公开厂商", it) }
        modelName?.let { put("公开型号", it) }
        modelNumber?.let { put("型号编号", it) }
        modelDescription?.let { put("设备描述", it) }
        deviceType?.let { put("UPnP 设备类型", it) }
    }
}
