package com.focus.android.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DnsQuery(
    val transactionId: Int,
    val domain: String,
    val rawQuery: ByteArray,
)

object DnsPacketHandler {

    private const val IP_HEADER_MIN_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val DNS_PORT = 53

    fun extractDnsQuery(packet: ByteArray, length: Int): DnsQuery? {
        if (length < IP_HEADER_MIN_LENGTH + UDP_HEADER_LENGTH + 12) return null

        val buffer = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.BIG_ENDIAN)

        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IP_HEADER_MIN_LENGTH || length < ihl + UDP_HEADER_LENGTH + 12) return null

        val protocol = buffer.get(ihl - 8).toInt() and 0xFF
        if (protocol != 17) return null // UDP only

        val srcPort = readUint16(buffer, ihl)
        val dstPort = readUint16(buffer, ihl + 2)
        if (srcPort != DNS_PORT && dstPort != DNS_PORT) return null

        val udpLength = readUint16(buffer, ihl + 4)
        val dnsOffset = ihl + UDP_HEADER_LENGTH
        val dnsLength = minOf(udpLength - UDP_HEADER_LENGTH, length - dnsOffset)
        if (dnsLength < 12) return null

        val dnsData = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = parseDomainName(dnsData, 12) ?: return null
        val txId = readUint16(ByteBuffer.wrap(dnsData).order(ByteOrder.BIG_ENDIAN), 0)

        return DnsQuery(
            transactionId = txId,
            domain = domain,
            rawQuery = dnsData,
        )
    }

    fun buildNxDomainResponse(query: DnsQuery): ByteArray {
        val response = query.rawQuery.copyOf()
        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)

        // Set QR=1 (response), RCODE=3 (NXDOMAIN)
        buffer.put(2, (buffer.get(2).toInt() or 0x80).toByte())
        buffer.put(3, (buffer.get(3).toInt() or 0x03).toByte())

        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        buffer.putShort(6, 0)
        buffer.putShort(8, 0)
        buffer.putShort(10, 0)

        return response
    }

    fun wrapDnsResponse(originalPacket: ByteArray, length: Int, dnsResponse: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(originalPacket, 0, length).order(ByteOrder.BIG_ENDIAN)
        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        val ihl = (versionAndIhl and 0x0F) * 4
        val dnsOffset = ihl + UDP_HEADER_LENGTH

        val totalLength = dnsOffset + dnsResponse.size
        val out = ByteArray(totalLength)
        System.arraycopy(originalPacket, 0, out, 0, ihl)

        val outBuffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)

        // Swap IP src/dst
        swapIpAddresses(outBuffer, ihl)

        // Swap UDP ports
        val srcPort = readUint16(outBuffer, ihl)
        val dstPort = readUint16(outBuffer, ihl + 2)
        outBuffer.putShort(ihl, dstPort.toShort())
        outBuffer.putShort(ihl + 2, srcPort.toShort())

        val udpLen = UDP_HEADER_LENGTH + dnsResponse.size
        outBuffer.putShort(ihl + 4, udpLen.toShort())

        // Copy DNS response
        System.arraycopy(dnsResponse, 0, out, dnsOffset, dnsResponse.size)

        // Recalculate IP total length and checksum; zero checksum first
        outBuffer.putShort(2, totalLength.toShort())
        outBuffer.putShort(10, 0)
        val ipChecksum = ipChecksum(out, ihl)
        outBuffer.putShort(10, ipChecksum.toShort())

        // UDP checksum optional for IPv4 (0 = disabled)
        outBuffer.putShort(ihl + 6, 0)

        return out
    }

    fun buildForwardPacket(originalPacket: ByteArray, length: Int): ByteArray {
        val copy = originalPacket.copyOf(length)
        val buffer = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)
        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        val ihl = (versionAndIhl and 0x0F) * 4

        // Route DNS to upstream 8.8.8.8
        buffer.putInt(16, ipToInt("8.8.8.8"))
        buffer.putShort(ihl + 2, DNS_PORT.toShort())

        buffer.putShort(2, length.toShort())
        buffer.putShort(10, 0)
        buffer.putShort(10, ipChecksum(copy, ihl).toShort())
        buffer.putShort(ihl + 6, 0)

        return copy
    }

    fun buildResponseFromUpstream(originalPacket: ByteArray, upstreamResponse: ByteArray, upstreamLength: Int): ByteArray? {
        val query = extractDnsQuery(originalPacket, originalPacket.size) ?: return null
        val dnsOffset = findDnsOffset(originalPacket) ?: return null
        val dnsResponse = upstreamResponse.copyOfRange(dnsOffset, upstreamLength)
        return wrapDnsResponse(originalPacket, originalPacket.size, dnsResponse)
    }

    private fun findDnsOffset(packet: ByteArray): Int? {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val ihl = (buffer.get(0).toInt() and 0x0F) * 4
        if (packet.size < ihl + UDP_HEADER_LENGTH) return null
        return ihl + UDP_HEADER_LENGTH
    }

    private fun parseDomainName(data: ByteArray, offset: Int): String? {
        val labels = mutableListOf<String>()
        var pos = offset
        var jumps = 0

        while (pos < data.size && jumps < 10) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= data.size) return null
                val pointer = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos = pointer
                jumps++
                continue
            }
            pos++
            if (pos + len > data.size) return null
            labels.add(String(data, pos, len, Charsets.US_ASCII))
            pos += len
        }

        return labels.joinToString(".").ifEmpty { null }
    }

    private fun swapIpAddresses(buffer: ByteBuffer, ihl: Int) {
        val src = ByteArray(4)
        val dst = ByteArray(4)
        buffer.position(12)
        buffer.get(src)
        buffer.get(dst)
        buffer.position(12)
        buffer.put(dst)
        buffer.put(src)
    }

    private fun readUint16(buffer: ByteBuffer, offset: Int): Int {
        return buffer.getShort(offset).toInt() and 0xFFFF
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        return ((parts[0].toInt() and 0xFF) shl 24) or
            ((parts[1].toInt() and 0xFF) shl 16) or
            ((parts[2].toInt() and 0xFF) shl 8) or
            (parts[3].toInt() and 0xFF)
    }

    private fun ipChecksum(data: ByteArray, headerLength: Int): Int {
        var sum = 0
        var i = 0
        while (i < headerLength) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
