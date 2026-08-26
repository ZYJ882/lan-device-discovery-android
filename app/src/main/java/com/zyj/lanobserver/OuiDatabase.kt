package com.zyj.lanobserver

import android.content.Context
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地 MAC 厂商前缀数据库。
 *
 * 数据只保存在应用私有目录；同步仅下载 IEEE 公开注册表，不上传设备 MAC、IP 或扫描结果。
 * 数据库用于“网卡厂商（OUI）”辅助证据，绝不作为设备具体型号证据。
 */
class OuiDatabase(context: Context) {
    private val appContext = context.applicationContext
    private val registryFile = File(appContext.filesDir, "ieee_oui_registry.tsv")
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    private var cachedEntries: Map<String, String>? = null

    fun status(): OuiDatabaseStatus {
        val entries = loadEntries()
        return OuiDatabaseStatus(
            available = entries.isNotEmpty(),
            entryCount = entries.size,
            lastSyncedAt = preferences.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L },
            sourceLabel = if (entries.isEmpty()) "尚未同步" else "IEEE MA-L / MA-M / MA-S 公开注册表"
        )
    }

    fun lookup(macAddress: String?): OuiLookupResult {
        val normalized = macAddress.orEmpty().filter(Char::isLetterOrDigit).uppercase()
        if (normalized.length != 12 || normalized.any { it !in '0'..'9' && it !in 'A'..'F' }) {
            return OuiLookupResult(macAddress = macAddress, vendor = null, locallyAdministered = false, databaseAvailable = status().available)
        }
        val firstOctet = normalized.substring(0, 2).toIntOrNull(16) ?: 0
        val locallyAdministered = (firstOctet and 0x02) != 0
        if (locallyAdministered) {
            return OuiLookupResult(macAddress = macAddress, vendor = null, locallyAdministered = true, databaseAvailable = status().available)
        }
        val entries = loadEntries()
        val matched = (9 downTo 6).firstNotNullOfOrNull { length ->
            entries[normalized.take(length)]?.let { vendor -> OuiMatch(vendor, length) }
        }
        return OuiLookupResult(
            macAddress = macAddress,
            vendor = matched?.vendor,
            locallyAdministered = false,
            databaseAvailable = entries.isNotEmpty(),
            prefixLength = matched?.prefixLength
        )
    }

    /** 用户在设置页主动点击后才下载；不包含任何后台计划或自动更新。 */
    suspend fun sync(network: Network?, onProgress: (String) -> Unit = {}): OuiSyncResult = withContext(Dispatchers.IO) {
        val temporaryFile = File(appContext.filesDir, "ieee_oui_registry.tsv.tmp")
        runCatching {
            val seen = HashSet<String>()
            var count = 0
            temporaryFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                IEEE_REGISTRIES.forEachIndexed { index, registry ->
                    onProgress("正在下载 ${registry.label}（${index + 1}/${IEEE_REGISTRIES.size}）…")
                    val downloaded = downloadRegistry(registry, network, writer, seen)
                    if (downloaded == 0) throw IllegalStateException("${registry.label} 未返回可用注册表记录")
                    count += downloaded
                    onProgress("${registry.label} 已导入 $downloaded 条记录，正在继续…")
                }
            }
            if (count == 0) throw IllegalStateException("IEEE 注册表未返回可用记录")
            if (!temporaryFile.renameTo(registryFile)) {
                throw IllegalStateException("无法安全替换本地 OUI 数据库，已保留旧数据库")
            }
            cachedEntries = null
            preferences.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            val entryCount = loadEntries().size
            onProgress("同步完成：本地数据库共 $entryCount 条记录")
            OuiSyncResult(success = true, entryCount = entryCount, message = "已同步 $count 条 IEEE 厂商前缀记录")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            temporaryFile.delete()
            OuiSyncResult(
                success = false,
                entryCount = status().entryCount,
                message = "同步失败：${error.message ?: "无法访问 IEEE 注册表"}"
            )
        }
    }

    private fun downloadRegistry(
        registry: OuiRegistry,
        network: Network?,
        writer: java.io.BufferedWriter,
        seen: MutableSet<String>
    ): Int {
        val connection = (network?.openConnection(URL(registry.url)) ?: URL(registry.url).openConnection()) as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "text/csv")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val reason = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(220) }
                    ?.replace(Regex("\\s+"), " ")
                    ?.takeIf { it.isNotBlank() }
                val rateLimitHint = if (responseCode == 429 || responseCode == 403) "；IEEE 公开下载通常限制为每天一次，请稍后再试" else ""
                throw IllegalStateException("${registry.label} 返回 HTTP $responseCode${reason?.let { "：$it" }.orEmpty()}$rateLimitHint")
            }
            var added = 0
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.drop(1).forEach { line ->
                    val fields = parseCsvLine(line)
                    if (fields.size < 3) return@forEach
                    val prefix = fields[1].filter(Char::isLetterOrDigit).uppercase()
                    val vendor = fields[2].trim().replace(Regex("\\s+"), " ")
                    if (prefix.length !in 6..9 || vendor.isBlank() || !prefix.all { it in '0'..'9' || it in 'A'..'F' }) return@forEach
                    if (seen.add(prefix)) {
                        writer.append(prefix).append('\t').append(vendor).append('\n')
                        added += 1
                    }
                }
            }
            added
        } finally {
            connection.disconnect()
        }
    }

    private fun loadEntries(): Map<String, String> {
        cachedEntries?.let { return it }
        return synchronized(this) {
            cachedEntries ?: buildMap {
                if (registryFile.canRead()) {
                    registryFile.useLines { lines ->
                        lines.forEach { line ->
                            val separator = line.indexOf('\t')
                            if (separator in 6..9) {
                                val prefix = line.substring(0, separator)
                                val vendor = line.substring(separator + 1).trim()
                                if (vendor.isNotBlank()) put(prefix, vendor)
                            }
                        }
                    }
                }
            }.also { cachedEntries = it }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 1
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private data class OuiMatch(val vendor: String, val prefixLength: Int)
    private data class OuiRegistry(val label: String, val url: String)

    private companion object {
        const val PREFERENCES = "oui_database"
        const val KEY_LAST_SYNC = "last_sync"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val USER_AGENT = "LanDeviceDiscovery/2.3.1 (Android; manual IEEE OUI sync)"
        val IEEE_REGISTRIES = listOf(
            OuiRegistry("MA-L", "https://standards-oui.ieee.org/oui/oui.csv"),
            OuiRegistry("MA-M", "https://standards-oui.ieee.org/oui28/mam.csv"),
            OuiRegistry("MA-S", "https://standards-oui.ieee.org/oui36/oui36.csv")
        )
    }
}

data class OuiDatabaseStatus(
    val available: Boolean,
    val entryCount: Int,
    val lastSyncedAt: Long?,
    val sourceLabel: String
)

data class OuiLookupResult(
    val macAddress: String?,
    val vendor: String?,
    val locallyAdministered: Boolean,
    val databaseAvailable: Boolean,
    val prefixLength: Int? = null
)

data class OuiSyncResult(
    val success: Boolean,
    val entryCount: Int,
    val message: String
)
