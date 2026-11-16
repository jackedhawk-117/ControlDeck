package com.phonecontroller.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRttTest {

    @Test
    fun `normal echo yields elapsed milliseconds`() {
        assertEquals(50, rttMillisFromEcho(999_950L, 1_000_000L))
    }

    @Test
    fun `u32 timestamp wrap is handled`() {
        // Echo at 0xFFFF_FFF0, reply arrives at 0x0000_0020 (48 ms later).
        assertEquals(48, rttMillisFromEcho(0xFFFF_FFF0L, 0x20L))
    }

    @Test
    fun `stale echo is rejected instead of reported as huge latency`() {
        assertEquals(0, rttMillisFromEcho(900_000L, 1_000_000L))
    }

    @Test
    fun `exactly five seconds is still accepted`() {
        assertEquals(5000, rttMillisFromEcho(1_000L, 6_000L))
    }
}
