package com.zyj.lanobserver

/** 将不同厂商公开的 DNS-SD TXT 键规范化为名称、厂商与型号；不基于端口进行猜测。 */
object MdnsIdentityNormalizer {
    fun normalize(attributes: Map<String, String>, fallbackName: String): MdnsPublicIdentity {
        val normalized = attributes
            .mapKeys { (key, _) -> key.trim().lowercase() }
            .mapValues { (_, value) -> value.normalizedValue() }
            .filterValues { it != null }
            .mapValues { (_, value) -> value.orEmpty() }

        val name = normalized.firstValue("fn", "friendlyname", "name", "device_name")
            ?: fallbackName.normalizedValue()
        val model = normalized.firstValue(
            "model", "modelname", "model_name", "modelnumber", "md", "am", "ty", "usb_mdl", "product"
        )
        val manufacturer = normalized.firstValue(
            "manufacturer", "manufacturername", "mfg", "vendor", "brand", "usb_mfg"
        )
        return MdnsPublicIdentity(name, manufacturer, model)
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? = keys
        .asSequence()
        .mapNotNull { this[it]?.normalizedValue() }
        .firstOrNull()

    private fun String?.normalizedValue(): String? = this
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(MAX_FIELD_LENGTH)
        ?.takeIf { it.isNotBlank() }

    private const val MAX_FIELD_LENGTH = 180
}

data class MdnsPublicIdentity(
    val friendlyName: String?,
    val manufacturer: String?,
    val model: String?
) {
    fun asDetails(): Map<String, String> = buildMap {
        put("型号识别证据", "mDNS TXT 公开声明")
        friendlyName?.let { put("mDNS 公开名称", it) }
        manufacturer?.let { put("mDNS 公开厂商", it) }
        model?.let { put("mDNS 公开型号", it) }
    }
}
