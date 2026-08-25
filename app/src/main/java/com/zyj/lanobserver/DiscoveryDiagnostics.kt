package com.zyj.lanobserver

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 每次发现任务的结构化诊断；所有内容同时输出到 Logcat 的 LanDiscovery 标签。 */
class DiscoveryDiagnostics(private val snapshot: LanNetworkSnapshot) {
    private val sourceStats = ConcurrentHashMap<String, MutableSourceStats>()
    private val events = ConcurrentLinkedQueue<String>()
    private val multicastLockHeld = AtomicBoolean(false)
    private val neighborCacheReadable = AtomicBoolean(false)
    private val startedAt = System.currentTimeMillis()

    fun scanStarted() {
        record(
            "scan.start network=${snapshot.network?.toString() ?: "none"} interface=${snapshot.interfaceName} " +
                "ipv4=${snapshot.localIp} cidr=${snapshot.actualCidr} scanCidr=${snapshot.scanCidr} " +
                "gateway=${snapshot.gateway ?: "none"} vpn=${snapshot.hasVpn} hotspot=${snapshot.isHotspot}"
        )
    }

    fun multicastLock(acquired: Boolean) {
        multicastLockHeld.set(acquired)
        record("multicast.lock acquired=$acquired")
    }

    fun neighborCache(readable: Boolean, count: Int) {
        neighborCacheReadable.set(readable)
        source("ARP").apply {
            if (readable) started.incrementAndGet() else failures.incrementAndGet()
            observations.addAndGet(count)
        }
        record("arp.cache readable=$readable entries=$count interface=${snapshot.interfaceName}")
    }

    fun sourceStarted(name: String) {
        source(name).started.incrementAndGet()
        record("source.start name=$name")
    }

    fun sourceResponse(name: String, detail: String) {
        source(name).responses.incrementAndGet()
        record("source.response name=$name $detail")
    }

    fun observation(name: String, detail: String) {
        source(name).observations.incrementAndGet()
        record("source.observation name=$name $detail")
    }

    fun sourceFailure(name: String, detail: String) {
        source(name).failures.incrementAndGet()
        record("source.failure name=$name $detail")
    }

    fun devicePublished(device: LanDevice) {
        record(
            "device.publish id=${device.id} ip=${device.addresses.joinToString()} mac=${device.details["MAC 地址"] ?: "none"} " +
                "hostname=${device.hostname ?: "none"} mdns=${device.details["mDNS 服务"] ?: "none"} " +
                "ssdpUsn=${device.details["SSDP USN"] ?: "none"} ssdpServer=${device.details["SSDP SERVER"] ?: "none"} " +
                "upnpName=${device.details["UPnP friendlyName"] ?: "none"} manufacturer=${device.manufacturer ?: "none"} " +
                "model=${device.details["公开型号"] ?: device.deviceHint} ports=${device.ports.sorted()} sources=${device.sources.joinToString()}"
        )
    }

    fun finish(rawObservations: Int, deduplicatedDevices: Int, scannedHosts: Int): LanDiscoveryDiagnostics {
        val finishedAt = System.currentTimeMillis()
        val snapshot = LanDiscoveryDiagnostics(
            networkDescription = this.snapshot.network?.toString() ?: "未绑定 Network",
            interfaceName = this.snapshot.interfaceName,
            localIp = this.snapshot.localIp,
            gateway = this.snapshot.gateway,
            actualCidr = this.snapshot.actualCidr,
            scanCidr = this.snapshot.scanCidr,
            vpnPresent = this.snapshot.hasVpn,
            multicastLockHeld = multicastLockHeld.get(),
            neighborCacheReadable = neighborCacheReadable.get(),
            sourceStats = sourceStats.mapValues { (_, value) -> value.snapshot() }.toSortedMap(),
            rawObservations = rawObservations,
            deduplicatedDevices = deduplicatedDevices,
            scannedHostCount = scannedHosts,
            startedAt = startedAt,
            finishedAt = finishedAt,
            events = events.toList()
        )
        record(
            "scan.finish rawObservations=$rawObservations deduplicatedDevices=$deduplicatedDevices " +
                "scannedHosts=$scannedHosts durationMs=${finishedAt - startedAt}"
        )
        return snapshot.copy(events = events.toList())
    }

    private fun source(name: String): MutableSourceStats = sourceStats.getOrPut(name) { MutableSourceStats() }

    private fun record(message: String) {
        if (events.size < MAX_EVENTS) events.add(message)
        Log.i(TAG, message)
    }

    private class MutableSourceStats {
        val started = AtomicInteger(0)
        val responses = AtomicInteger(0)
        val observations = AtomicInteger(0)
        val failures = AtomicInteger(0)

        fun snapshot() = DiscoverySourceStats(started.get(), responses.get(), observations.get(), failures.get())
    }

    private companion object {
        const val TAG = "LanDiscovery"
        const val MAX_EVENTS = 160
    }
}

data class DiscoverySourceStats(
    val started: Int,
    val responses: Int,
    val observations: Int,
    val failures: Int
)

data class LanDiscoveryDiagnostics(
    val networkDescription: String,
    val interfaceName: String,
    val localIp: String,
    val gateway: String?,
    val actualCidr: String,
    val scanCidr: String,
    val vpnPresent: Boolean,
    val multicastLockHeld: Boolean,
    val neighborCacheReadable: Boolean,
    val sourceStats: Map<String, DiscoverySourceStats>,
    val rawObservations: Int,
    val deduplicatedDevices: Int,
    val scannedHostCount: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val events: List<String>
)
