package com.phonecontroller.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DiscoveryTest {

    private fun v4(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test
    fun `directed broadcast for a 24 bit subnet`() {
        val broadcast = Discovery.directedBroadcast(v4(192, 168, 43, 1), 24)
        assertEquals("192.168.43.255", broadcast?.hostAddress)
    }

    @Test
    fun `directed broadcast for a 16 bit subnet`() {
        val broadcast = Discovery.directedBroadcast(v4(10, 0, 12, 34), 16)
        assertEquals("10.0.255.255", broadcast?.hostAddress)
    }

    @Test
    fun `a host route has no directed broadcast`() {
        assertNull(Discovery.directedBroadcast(v4(192, 168, 43, 7), 32))
    }

    @Test
    fun `lan interfaces are candidates and virtual links are not`() {
        assertTrue(
            Discovery.isCandidateInterface("wlan0", InetAddress.getByName("192.168.43.1")),
        )
        assertTrue(
            Discovery.isCandidateInterface("eth0", InetAddress.getByName("192.168.1.20")),
        )
        assertFalse(
            Discovery.isCandidateInterface("lo", InetAddress.getByName("127.0.0.1")),
        )
        assertFalse(
            Discovery.isCandidateInterface("rmnet_data0", InetAddress.getByName("10.20.30.40")),
        )
        assertFalse(
            Discovery.isCandidateInterface("tun0", InetAddress.getByName("10.8.0.2")),
        )
        assertFalse(
            Discovery.isCandidateInterface("wlan0", InetAddress.getByName("::1")),
        )
    }

    @Test
    fun `hotspot interfaces and gateway addresses are labelled`() {
        assertTrue(Discovery.looksLikeHotspot("ap0", InetAddress.getByName("192.168.100.1")))
        assertTrue(
            Discovery.looksLikeHotspot("wlan0", InetAddress.getByName("192.168.43.1")),
        )
        assertTrue(
            Discovery.looksLikeHotspot("wlan0", InetAddress.getByName("192.168.137.1")),
        )
        assertFalse(
            Discovery.looksLikeHotspot("wlan0", InetAddress.getByName("192.168.29.50")),
        )
    }
}
