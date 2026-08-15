package com.durendal.droneagent.companion.commissioning.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class IpLiteral(
    val family: Family,
    val canonical: String,
    val inetAddress: InetAddress,
) {
    enum class Family(val maximumPrefixLength: Int) {
        IPV4(32),
        IPV6(128),
    }

    val isProhibitedTarget: Boolean
        get() =
            inetAddress.isAnyLocalAddress ||
                inetAddress.isLoopbackAddress ||
                inetAddress.isLinkLocalAddress ||
                inetAddress.isMulticastAddress ||
                (family == Family.IPV4 && canonical == "255.255.255.255")

    val isPrivateIpv4: Boolean
        get() = family == Family.IPV4 && inetAddress.isSiteLocalAddress

    val isLinkLocal: Boolean
        get() = inetAddress.isLinkLocalAddress

    val isLoopback: Boolean
        get() = inetAddress.isLoopbackAddress

    fun hasValidPrefix(prefixLength: Int): Boolean =
        prefixLength in 0..family.maximumPrefixLength

    fun isCanonicalNetwork(prefixLength: Int): Boolean {
        if (!hasValidPrefix(prefixLength)) return false
        val bytes = inetAddress.address
        val wholeBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        if (remainingBits > 0) {
            val hostMask = 0xff ushr remainingBits
            if ((bytes[wholeBytes].toInt() and 0xff and hostMask) != 0) return false
        }
        val firstHostByte = wholeBytes + if (remainingBits > 0) 1 else 0
        return (firstHostByte until bytes.size).all { index -> bytes[index].toInt() == 0 }
    }

    fun prefixIsContainedWithinLinkLocal(prefixLength: Int): Boolean =
        isLinkLocal &&
            when (family) {
                Family.IPV4 -> prefixLength in 16..32
                Family.IPV6 -> prefixLength in 10..128
            }

    fun prefixIsContainedWithinLoopback(prefixLength: Int): Boolean =
        isLoopback &&
            when (family) {
                Family.IPV4 -> prefixLength in 8..32
                Family.IPV6 -> prefixLength == 128
            }

    fun ipv4Network(prefixLength: Int): Int? {
        if (family != Family.IPV4 || prefixLength !in 0..32) return null
        val value = ipv4Int() ?: return null
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return value and mask
    }

    fun ipv4Int(): Int? {
        if (family != Family.IPV4) return null
        return inetAddress.address.fold(0) { result, octet ->
            (result shl 8) or (octet.toInt() and 0xff)
        }
    }

    fun sameAddress(other: IpLiteral): Boolean =
        family == other.family && inetAddress.address.contentEquals(other.inetAddress.address)

    companion object {
        fun parse(raw: String): IpLiteral? {
            if (raw.isEmpty() || raw != raw.trim()) return null
            return if (':' in raw) parseIpv6(raw) else parseIpv4(raw)
        }

        private fun parseIpv4(raw: String): IpLiteral? {
            if (raw.any { !it.isDigit() && it != '.' }) return null
            val components = raw.split('.')
            if (components.size != 4 || components.any { it.isEmpty() || it.length > 3 }) return null
            val octets =
                components.map { component ->
                    component.toIntOrNull()?.takeIf { value ->
                        value in 0..255 && component == value.toString()
                    } ?: return null
                }
            val bytes = ByteArray(4) { index -> octets[index].toByte() }
            val address = InetAddress.getByAddress(bytes) as Inet4Address
            return IpLiteral(Family.IPV4, octets.joinToString("."), address)
        }

        private fun parseIpv6(raw: String): IpLiteral? {
            // Requiring a colon and this closed character set prevents hostname/DNS resolution.
            if (raw.length > 45 || raw.any { it !in "0123456789abcdefABCDEF:." }) return null
            val address = runCatching { InetAddress.getByName(raw) }.getOrNull() as? Inet6Address ?: return null
            return IpLiteral(Family.IPV6, address.hostAddress.substringBefore('%'), address)
        }
    }
}
