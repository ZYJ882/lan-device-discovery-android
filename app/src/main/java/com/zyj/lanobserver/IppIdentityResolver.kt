package com.zyj.lanobserver

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 仅针对已由 mDNS 发现的 _ipp._tcp 服务，发送 IPP Get-Printer-Attributes 只读请求。
 * 不探测任意路径、不提交打印任务、不跟随重定向，也不处理认证挑战。
 */
class IppIdentityResolver {
    fun resolve(host: InetAddress, port: Int, resourcePath: String?): IppPrinterIdentity? {
        if (port !in 1..65535) return null
        val path = resourcePath.normalizedIppPath() ?: return null
        val hostAddress = host.hostAddress ?: return null
        val hostForUrl = if (hostAddress.contains(':')) "[$hostAddress]" else hostAddress
        val printerUri = "ipp://$hostForUrl:$port$path"
        val payload = createGetPrinterAttributes(printerUri)
        val body = runCatching {
            val connection = (URL("http://$hostForUrl:$port$path").openConnection() as? HttpURLConnection) ?: return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/ipp")
            connection.setRequestProperty("Accept", "application/ipp")
            connection.setRequestProperty("User-Agent", "LanDeviceDiscovery/1.4")
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBounded(MAX_RESPONSE_BYTES) }
        }.getOrNull() ?: return null
        return parseIppAttributes(body)
    }

    private fun createGetPrinterAttributes(printerUri: String): ByteArray = ByteArrayOutputStream().apply {
        // IPP 2.0, Get-Printer-Attributes (0x000B), request-id 1.
        write(0x02)
        write(0x00)
        writeShort(OPERATION_GET_PRINTER_ATTRIBUTES)
        writeInt(1)
        write(TAG_OPERATION_ATTRIBUTES)
        writeAttribute(TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttribute(TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeAttribute(TAG_URI, "printer-uri", printerUri)
        REQUESTED_ATTRIBUTES.forEachIndexed { index, attribute ->
            writeAttribute(TAG_KEYWORD, if (index == 0) "requested-attributes" else "", attribute)
        }
        write(TAG_END_OF_ATTRIBUTES)
    }.toByteArray()

    private fun parseIppAttributes(bytes: ByteArray): IppPrinterIdentity? {
        if (bytes.size < IPP_HEADER_BYTES) return null
        val statusCode = bytes.readUnsignedShort(2)
        if (statusCode !in SUCCESS_STATUS_CODES) return null
        var offset = IPP_HEADER_BYTES
        var currentName = ""
        val values = linkedMapOf<String, String>()
        while (offset < bytes.size) {
            val tag = bytes[offset++].toInt() and 0xFF
            if (tag == TAG_END_OF_ATTRIBUTES) break
            if (tag in GROUP_TAG_RANGE) continue
            if (offset + 4 > bytes.size) break
            val nameLength = bytes.readUnsignedShort(offset)
            offset += 2
            if (offset + nameLength + 2 > bytes.size) break
            val name = if (nameLength > 0) bytes.decodeUtf8(offset, nameLength) else currentName
            offset += nameLength
            val valueLength = bytes.readUnsignedShort(offset)
            offset += 2
            if (offset + valueLength > bytes.size) break
            val value = bytes.decodeUtf8(offset, valueLength).cleanValue()
            offset += valueLength
            if (nameLength > 0) currentName = name
            if (name in REQUESTED_ATTRIBUTES && value != null) values.putIfAbsent(name, value)
        }
        val identity = IppPrinterIdentity(
            makeAndModel = values["printer-make-and-model"],
            name = values["printer-name"],
            info = values["printer-info"],
            uuid = values["printer-uuid"],
            location = values["printer-location"],
            deviceId = values["printer-device-id"]
        )
        return identity.takeIf { it.hasIdentity }
    }

    private fun ByteArrayOutputStream.writeAttribute(tag: Int, name: String, value: String) {
        write(tag)
        writeUtf8WithLength(name)
        writeUtf8WithLength(value)
    }

    private fun ByteArrayOutputStream.writeUtf8WithLength(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArray.readUnsignedShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun ByteArray.decodeUtf8(offset: Int, length: Int): String =
        String(this, offset, length, StandardCharsets.UTF_8)

    private fun String?.normalizedIppPath(): String? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.contains("://") || raw.contains('?') || raw.contains('#') || raw.contains("..")) return null
        val clean = raw.trimStart('/')
        return clean.takeIf { it.isNotBlank() }?.let { "/$it" }
    }

    private fun String.cleanValue(): String? = trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_FIELD_LENGTH)
        .takeIf { it.isNotBlank() }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val read = read(buffer, offset, limit - offset)
            if (read <= 0) break
            offset += read
        }
        return buffer.copyOf(offset)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 700
        const val READ_TIMEOUT_MILLIS = 1_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_FIELD_LENGTH = 180
        const val IPP_HEADER_BYTES = 8
        const val OPERATION_GET_PRINTER_ATTRIBUTES = 0x000B
        const val TAG_OPERATION_ATTRIBUTES = 0x01
        const val TAG_END_OF_ATTRIBUTES = 0x03
        const val TAG_CHARSET = 0x47
        const val TAG_NATURAL_LANGUAGE = 0x48
        const val TAG_URI = 0x45
        const val TAG_KEYWORD = 0x44
        val GROUP_TAG_RANGE = 0x01..0x0F
        val SUCCESS_STATUS_CODES = setOf(0x0000, 0x0001)
        val REQUESTED_ATTRIBUTES = listOf(
            "printer-make-and-model",
            "printer-name",
            "printer-info",
            "printer-uuid",
            "printer-location",
            "printer-device-id"
        )
    }
}

data class IppPrinterIdentity(
    val makeAndModel: String?,
    val name: String?,
    val info: String?,
    val uuid: String?,
    val location: String?,
    val deviceId: String?
) {
    val hasIdentity: Boolean
        get() = listOf(makeAndModel, name, info, uuid, location, deviceId).any { !it.isNullOrBlank() }

    fun asDetails(): Map<String, String> = buildMap {
        put("型号识别证据", "IPP 标准只读属性")
        makeAndModel?.let { put("IPP 厂商与型号", it) }
        name?.let { put("IPP 打印机名称", it) }
        info?.let { put("IPP 打印机说明", it) }
        uuid?.let { put("IPP 打印机 UUID", it) }
        location?.let { put("IPP 打印机位置", it) }
        deviceId?.let { put("IPP 设备标识", it) }
    }
}
