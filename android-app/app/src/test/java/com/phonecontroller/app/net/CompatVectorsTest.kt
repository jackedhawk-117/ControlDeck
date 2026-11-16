package com.phonecontroller.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Byte-level compatibility gate between the Kotlin phone implementation and
 * the normative Rust `pc-protocol` crate.
 *
 * Every expected hex string below is printed by:
 *
 *   cd pc-receiver && cargo run -p pc-protocol --example compat_vectors
 *
 * If any of these fail, the two sides cannot talk; do not "fix" the constant
 * without changing the Rust side and docs/PROTOCOL.md too.
 */
class CompatVectorsTest {

    private val pin = "123456"
    private val clientNonce = ByteArray(16) { 1 }
    private val serverNonce = ByteArray(16) { 2 }
    private val sessionId = 0x3B9AL

    private fun key(): ByteArray = Crypto.deriveSessionKey(pin, clientNonce, serverNonce)

    @Test
    fun sessionKeyMatchesRustVector() {
        assertEquals(
            "ACBD960345E8B6302610BE6D67B6ACC5E22EEAFB44352FFEF2F21C86D5E0C703",
            key().toHex(),
        )
    }

    @Test
    fun authProofMatchesRustVector() {
        assertEquals(
            "67ADE28B7C84F5DBFCC691D34CB475ED000DA8F38C937E94A099BC0509EB761A",
            Crypto.authProof(key(), clientNonce, serverNonce, sessionId, fromClient = true).toHex(),
        )
    }

    @Test
    fun nonceLayoutMatchesRustVector() {
        assertEquals(
            "000100000000000000000000",
            Crypto.buildNonce(Crypto.NONCE_CLIENT_TO_SERVER, 1).toHex(),
        )
    }

    @Test
    fun inputDeltaPlaintextMatchesRustVector() {
        assertEquals(
            "9A3B0000020000110000000000000000000400000080010000000000E5651C00",
            vectorDelta().encode().toHex(),
        )
    }

    @Test
    fun sealedDeltaMatchesRustVector() {
        val plain = vectorDelta().encode()
        val header = PacketHeader(Protocol.KIND_INPUT_DELTA, plain.size, 1)
        val ct = SessionCrypto(key()).seal(
            Crypto.NONCE_CLIENT_TO_SERVER,
            1,
            header.toBytes(),
            plain,
        )
        assertEquals(
            "EE23731458154DC7B9E8AD80817BB503FFDD185E1AC91662EE21A6E8662EF209" +
                "C2C191BFE78B25C445AA8770DB6DE070",
            ct.toHex(),
        )
        // ... and the Rust-sealed bytes open with the Kotlin implementation.
        assertArrayEquals(
            plain,
            SessionCrypto(key()).open(Crypto.NONCE_CLIENT_TO_SERVER, header.toBytes(), 1, ct),
        )
    }

    @Test
    fun helloPayloadMatchesRustVector() {
        assertEquals(
            "01000C766563746F722D70686F6E650001010101010101010101010101010101",
            Hello("vector-phone", ByteArray(16) { 1 }).encode().toHex(),
        )
    }

    @Test
    fun discoverPacketMatchesRustVector() {
        assertEquals(
            "50430101000000000000",
            buildPacket(Protocol.KIND_DISCOVER, 0, ByteArray(0)).toHex(),
        )
    }

    private fun vectorDelta(): InputDelta = InputDelta(
        sessionId,
        listOf(
            DeltaEvent.keyDown(17),                       // KEY_W
            DeltaEvent.mouseRelQ16(65536 + 32768, 0),     // 1.5 px
        ),
        0x1C_65_E5L,
    )
}

// ---------------------------------------------------------------------------
// cryptographic behaviour
// ---------------------------------------------------------------------------

class CryptoBehaviourTest {

    private val key = Crypto.deriveSessionKey(
        "123456",
        ByteArray(16) { 1 },
        ByteArray(16) { 2 },
    )
    private val crypto = SessionCrypto(key)

    @Test
    fun gcmRoundTrip() {
        val header = PacketHeader(Protocol.KIND_INPUT_DELTA, 8, 7).toBytes()
        val plain = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val ct = crypto.seal(Crypto.NONCE_CLIENT_TO_SERVER, 7, header, plain)
        assertEquals(plain.size + Protocol.TAG_LEN, ct.size)
        assertArrayEquals(
            plain,
            crypto.open(Crypto.NONCE_CLIENT_TO_SERVER, header, 7, ct),
        )
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val header = PacketHeader(Protocol.KIND_INPUT_DELTA, 8, 7).toBytes()
        val ct = crypto.seal(Crypto.NONCE_CLIENT_TO_SERVER, 7, header, ByteArray(8) { 9 })
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        assertThrows(Crypto.AuthFailedException::class.java) {
            crypto.open(Crypto.NONCE_CLIENT_TO_SERVER, header, 7, ct)
        }
    }

    @Test
    fun tamperedAadIsRejected() {
        val plain = ByteArray(8) { 9 }
        val header = PacketHeader(Protocol.KIND_INPUT_DELTA, plain.size, 7).toBytes()
        val ct = crypto.seal(Crypto.NONCE_CLIENT_TO_SERVER, 7, header, plain)
        val other = PacketHeader(Protocol.KIND_INPUT_SNAPSHOT, plain.size, 7).toBytes()
        assertThrows(Crypto.AuthFailedException::class.java) {
            crypto.open(Crypto.NONCE_CLIENT_TO_SERVER, other, 7, ct)
        }
    }

    @Test
    fun wrongSequenceIsRejected() {
        val header = PacketHeader(Protocol.KIND_INPUT_DELTA, 8, 7).toBytes()
        val ct = crypto.seal(Crypto.NONCE_CLIENT_TO_SERVER, 7, header, ByteArray(8) { 9 })
        assertThrows(Crypto.AuthFailedException::class.java) {
            crypto.open(Crypto.NONCE_CLIENT_TO_SERVER, header, 8, ct)
        }
    }

    @Test
    fun wrongDirectionIsRejected() {
        val header = PacketHeader(Protocol.KIND_STATS, 8, 7).toBytes()
        val ct = crypto.seal(Crypto.NONCE_SERVER_TO_CLIENT, 7, header, ByteArray(8) { 9 })
        assertThrows(Crypto.AuthFailedException::class.java) {
            crypto.open(Crypto.NONCE_CLIENT_TO_SERVER, header, 7, ct)
        }
    }

    @Test
    fun shortPayloadIsRejected() {
        val header = PacketHeader(Protocol.KIND_INPUT_DELTA, 0, 7).toBytes()
        assertThrows(Crypto.AuthFailedException::class.java) {
            crypto.open(Crypto.NONCE_CLIENT_TO_SERVER, header, 7, ByteArray(4))
        }
    }

    @Test
    fun hkdfMatchesRfc5869TestVector() {
        // RFC 5869 test case 1 (SHA-256).
        val ikm = ByteArray(22) { 0x0B }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xF0 + it).toByte() }
        val okm = Crypto.hkdfSha256(salt, ikm, info, 42)
        assertEquals(
            "3CB25F25FAACD57A90434F64D0362F2A" +
                "2D2D0A90CF1A5A4C5DB02D56ECC4C5BF" +
                "34007208D5B887185865",
            okm.toHex(),
        )
    }
}

// ---------------------------------------------------------------------------
// replay watermark
// ---------------------------------------------------------------------------

class WatermarkTest {

    @Test
    fun acceptsOnlyStrictlyIncreasingNonZeroValues() {
        val wm = Watermark(0)
        assertFalse(wm.accept(0))
        assertTrue(wm.accept(1))
        assertFalse(wm.accept(1))
        assertTrue(wm.accept(2))
        assertFalse(wm.accept(1))
        assertEquals(2, wm.current())
    }

    @Test
    fun respectsFullUnsignedU32Range() {
        // Rust's Watermark is AtomicU32; seq 0x8000_0000 must beat small
        // values even though Kotlin's Int is signed.
        val wm = Watermark(0)
        assertTrue(wm.accept(5))
        assertTrue(wm.accept(0x8000_0000L.toInt()))
        assertFalse(wm.accept(6))
        assertFalse(wm.accept(0x7FFF_FFFF))
        assertTrue(wm.accept(0xFFFF_FFFFL.toInt()))
        assertEquals(-1, wm.current())
    }

    @Test
    fun startsFromProvidedInitialValue() {
        val wm = Watermark(100)
        assertFalse(wm.accept(100))
        assertFalse(wm.accept(99))
        assertTrue(wm.accept(101))
    }
}

// ---------------------------------------------------------------------------
// protocol codecs
// ---------------------------------------------------------------------------

class ProtocolCodecTest {

    @Test
    fun headerRoundTrip() {
        val h = PacketHeader(Protocol.KIND_INPUT_DELTA, 0x1234, 0xDEAD_BEEFL)
        val parsed = PacketHeader.parseHeader(h.toBytes())
        assertEquals(Protocol.KIND_INPUT_DELTA, parsed.kind)
        assertEquals(0x1234, parsed.payloadLen)
        assertEquals(0xDEAD_BEEFL, parsed.seq)
    }

    @Test
    fun headerRejectsBadMagic() {
        val bytes = PacketHeader(Protocol.KIND_DISCOVER, 0, 1).toBytes()
        bytes[0] = 'X'.code.toByte()
        assertThrows(Protocol.ParseException::class.java) { PacketHeader.parseHeader(bytes) }
    }

    @Test
    fun headerRejectsBadVersion() {
        val bytes = PacketHeader(Protocol.KIND_DISCOVER, 0, 1).toBytes()
        bytes[2] = 2
        assertThrows(Protocol.ParseException::class.java) { PacketHeader.parseHeader(bytes) }
    }

    @Test
    fun headerRejectsTruncation() {
        assertThrows(Protocol.ParseException::class.java) {
            PacketHeader.parseHeader(ByteArray(Protocol.HEADER_LEN - 1))
        }
    }

    @Test
    fun announceRoundTrip() {
        val name = "living-room-pc".toByteArray()
        val payload = ByteArray(6 + name.size)
        putU16le(1, payload, 0)
        putU16le(41000, payload, 2)
        payload[4] = name.size.toByte()
        System.arraycopy(name, 0, payload, 5, name.size)
        payload[5 + name.size] = 1 // requires_pin

        val a = Announce.decode(payload)
        assertEquals(1, a.protoVersion)
        assertEquals(41000, a.tcpPort)
        assertEquals("living-room-pc", a.name)
        assertTrue(a.requiresPin)
    }

    @Test
    fun announceRejectsTruncatedName() {
        val payload = ByteArray(7)
        putU16le(1, payload, 0)
        putU16le(41000, payload, 2)
        payload[4] = 10
        assertThrows(Protocol.ParseException::class.java) { Announce.decode(payload) }
    }

    @Test
    fun helloRoundTrip() {
        val nonce = ByteArray(16) { (it * 3).toByte() }
        val decoded = Hello.decode(Hello("pixel-8", nonce).encode())
        assertEquals("pixel-8", decoded.deviceName)
        assertArrayEquals(nonce, decoded.clientNonce)
    }

    @Test
    fun helloDecodeRejectsShortNonce() {
        val payload = ByteArray(4 + 3 + 15)
        putU16le(1, payload, 0)
        payload[2] = 3
        assertThrows(Protocol.ParseException::class.java) { Hello.decode(payload) }
    }

    @Test
    fun helloReplyDecode() {
        val name = "gaming-pc".toByteArray()
        val nonce = ByteArray(16) { 0x5A }
        val payload = ByteArray(7 + name.size + 18)
        putU16le(1, payload, 0)
        putU32le(0xDEAD_BEEFL, payload, 2)
        payload[6] = name.size.toByte()
        System.arraycopy(name, 0, payload, 7, name.size)
        var off = 7 + name.size
        payload[off] = 1 // pin_required
        off += 1
        payload[off] = 0 // reserved
        off += 1
        System.arraycopy(nonce, 0, payload, off, 16)

        val reply = HelloReply.decode(payload)
        assertEquals(1, reply.protoVersion)
        assertEquals(0xDEAD_BEEFL, reply.sessionId)
        assertEquals("gaming-pc", reply.name)
        assertTrue(reply.pinRequired)
        assertArrayEquals(nonce, reply.serverNonce)
    }

    @Test
    fun helloReplyRejectsTruncation() {
        val payload = ByteArray(6)
        putU16le(1, payload, 0)
        putU32le(1, payload, 2)
        assertThrows(Protocol.ParseException::class.java) { HelloReply.decode(payload) }
    }

    @Test
    fun errorDecode() {
        val msg = "bad PIN".toByteArray()
        val payload = ByteArray(3 + msg.size)
        putU16le(Protocol.ERROR_AUTH_FAILED, payload, 0)
        payload[2] = msg.size.toByte()
        System.arraycopy(msg, 0, payload, 3, msg.size)
        val e = ErrorPayload.decode(payload)
        assertEquals(Protocol.ERROR_AUTH_FAILED, e.code)
        assertEquals("bad PIN", e.message)
    }

    @Test
    fun statsDecode() {
        val payload = ByteArray(Stats.WIRE_SIZE)
        putU32le(77, payload, 0)
        putU32le(120, payload, 4)
        putU32le(3, payload, 8)
        putU32le(0xFFFF_FFF0L, payload, 12)
        val s = Stats.decode(payload)
        assertEquals(77, s.lastSeqProcessed)
        assertEquals(120, s.eventsPerSec)
        assertEquals(3, s.packetsDroppedTotal)
        assertEquals(0xFFFF_FFF0L, s.rttProbeEcho)
    }

    @Test
    fun inputDeltaRoundTripWithNegativeQ16() {
        val delta = InputDelta(
            0x0102_0304L,
            listOf(
                DeltaEvent.mouseRelQ16(-65536, 98304),
                DeltaEvent.mouseDown(Protocol.MOUSE_LEFT),
                DeltaEvent.keyUp(30),
                DeltaEvent(Protocol.OP_SCROLL, 0, -3, 0),
            ),
            0xFFFF_FF00L,
        )
        assertEquals(delta, InputDelta.decode(delta.encode()))
    }

    @Test
    fun inputDeltaRejectsBadLength() {
        val delta = InputDelta(1, listOf(DeltaEvent.keyDown(17)), 1)
        val bytes = delta.encode()
        assertThrows(Protocol.ParseException::class.java) {
            InputDelta.decode(bytes.copyOf(bytes.size - 1))
        }
    }

    @Test
    fun inputDeltaRejectsTooManyEvents() {
        val payload = ByteArray(6 + 33 * Protocol.EVENT_WIRE_SIZE + 4)
        putU32le(1, payload, 0)
        putU16le(33, payload, 4)
        assertThrows(Protocol.ParseException::class.java) { InputDelta.decode(payload) }
    }

    @Test
    fun byePayloadRoundTrip() {
        assertEquals(Protocol.BYE_REPLACED, parseByePayload(byePayload(Protocol.BYE_REPLACED)))
        assertThrows(IOException::class.java) { parseByePayload(ByteArray(0)) }
    }
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

private fun ByteArray.toHex(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }
