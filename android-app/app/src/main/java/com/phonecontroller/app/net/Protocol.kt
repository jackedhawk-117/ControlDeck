package com.phonecontroller.app.net

import java.io.IOException

/**
 * PhoneController protocol v1 — Kotlin mirror of the Rust `pc-protocol`
 * crate (pc-receiver/crates/pc-protocol), which is the normative
 * implementation of docs/PROTOCOL.md.
 *
 * Byte-level compatibility is enforced by pinned test vectors produced by
 * `cargo run -p pc-protocol --example compat_vectors` (see ProtocolCompatTest).
 *
 * No I/O happens here: pure encode/decode only.
 */
object Protocol {
    const val PROTOCOL_VERSION: Int = 1

    /** 0x50 0x43 = "PC", bytes 0 and 1 of every packet. */
    const val MAGIC0: Int = 0x50
    const val MAGIC1: Int = 0x43

    /** Fixed 10-byte packet header length. */
    const val HEADER_LEN: Int = 10

    /** Max UDP datagram: header + plaintext payload (tag adds 16). */
    const val MAX_UDP_PACKET: Int = 1400

    const val DEFAULT_TCP_PORT: Int = 41000
    const val DEFAULT_UDP_PORT: Int = 41000

    /** GCM tag length appended to every encrypted payload. */
    const val TAG_LEN: Int = 16

    // Packet kinds (docs/PROTOCOL.md §4)
    const val KIND_DISCOVER = 0x01
    const val KIND_ANNOUNCE = 0x02
    const val KIND_HELLO = 0x03
    const val KIND_HELLO_REPLY = 0x04
    const val KIND_AUTH_PROOF = 0x05
    const val KIND_SECURE_FRAME = 0x06
    const val KIND_PING = 0x07
    const val KIND_PONG = 0x08
    const val KIND_BYE = 0x09
    const val KIND_RESET = 0x0A
    const val KIND_PROFILE_INFO = 0x0B
    const val KIND_ERROR = 0x0C
    const val KIND_INPUT_DELTA = 0x10
    const val KIND_INPUT_SNAPSHOT = 0x11
    const val KIND_STATS = 0x12

    // ERROR codes
    const val ERROR_VERSION_MISMATCH = 0x0001
    const val ERROR_AUTH_FAILED = 0x0002
    const val ERROR_MALFORMED = 0x0003
    const val ERROR_SERVER_BUSY = 0x0004

    // BYE reasons
    const val BYE_NORMAL = 0
    const val BYE_TIMEOUT = 1
    const val BYE_REPLACED = 2
    const val BYE_ERROR = 3

    // Input event ops (docs/PROTOCOL.md §5.5)
    const val OP_KEY_DOWN = 0
    const val OP_KEY_UP = 1
    const val OP_MOUSE_DOWN = 2
    const val OP_MOUSE_UP = 3
    const val OP_MOUSE_REL = 4
    const val OP_SCROLL = 5

    // Protocol mouse button ids
    const val MOUSE_LEFT = 1
    const val MOUSE_RIGHT = 2
    const val MOUSE_MIDDLE = 3
    const val MOUSE_BACK = 4
    const val MOUSE_FORWARD = 5

    const val EVENT_WIRE_SIZE = 11
    const val MAX_EVENTS_PER_DELTA = 32

    /** Thrown by every decode function on malformed input. */
    class ParseException(message: String) : IOException(message)
}

// ---------------------------------------------------------------------------
// little-endian helpers
// ---------------------------------------------------------------------------

fun putU16le(v: Int, out: ByteArray, off: Int) {
    out[off] = (v and 0xFF).toByte()
    out[off + 1] = ((v shr 8) and 0xFF).toByte()
}

fun putU32le(v: Long, out: ByteArray, off: Int) {
    out[off] = (v and 0xFF).toByte()
    out[off + 1] = ((v shr 8) and 0xFF).toByte()
    out[off + 2] = ((v shr 16) and 0xFF).toByte()
    out[off + 3] = ((v shr 24) and 0xFF).toByte()
}

fun getU16le(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

fun getU32le(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)

// ---------------------------------------------------------------------------
// PacketHeader
// ---------------------------------------------------------------------------

/** Fixed 10-byte header: magic(2) version(1) kind(1) payload_len(2) seq(4). */
data class PacketHeader(val kind: Int, val payloadLen: Int, val seq: Long) {

    fun toBytes(): ByteArray {
        val h = ByteArray(Protocol.HEADER_LEN)
        h[0] = Protocol.MAGIC0.toByte()
        h[1] = Protocol.MAGIC1.toByte()
        h[2] = Protocol.PROTOCOL_VERSION.toByte()
        h[3] = kind.toByte()
        putU16le(payloadLen, h, 4)
        putU32le(seq, h, 6)
        return h
    }

    companion object {
        /** Parse only the fixed header (stream readers fetch the payload separately). */
        fun parseHeader(buf: ByteArray, off: Int = 0): PacketHeader {
            if (buf.size - off < Protocol.HEADER_LEN) {
                throw Protocol.ParseException("truncated header")
            }
            if (buf[off].toInt() and 0xFF != Protocol.MAGIC0 || buf[off + 1].toInt() and 0xFF != Protocol.MAGIC1) {
                throw Protocol.ParseException("bad magic")
            }
            val version = buf[off + 2].toInt() and 0xFF
            if (version != Protocol.PROTOCOL_VERSION) {
                throw Protocol.ParseException("bad protocol version $version")
            }
            val kind = buf[off + 3].toInt() and 0xFF
            val payloadLen = getU16le(buf, off + 4)
            val seq = getU32le(buf, off + 6)
            return PacketHeader(kind, payloadLen, seq)
        }
    }
}

/** Build a full plaintext packet (header + payload). */
fun buildPacket(kind: Int, seq: Long, payload: ByteArray): ByteArray {
    val out = ByteArray(Protocol.HEADER_LEN + payload.size)
    val h = PacketHeader(kind, payload.size, seq).toBytes()
    System.arraycopy(h, 0, out, 0, Protocol.HEADER_LEN)
    System.arraycopy(payload, 0, out, Protocol.HEADER_LEN, payload.size)
    return out
}

// ---------------------------------------------------------------------------
// discovery / handshake payloads
// ---------------------------------------------------------------------------

/** ANNOUNCE payload (§5.1): u16 proto_version, u16 tcp_port, u8 name_len, name, u8 flags. */
data class Announce(val protoVersion: Int, val tcpPort: Int, val name: String, val requiresPin: Boolean) {
    companion object {
        fun decode(payload: ByteArray): Announce {
            if (payload.size < 6) throw Protocol.ParseException("truncated announce")
            val protoVersion = getU16le(payload, 0)
            val tcpPort = getU16le(payload, 2)
            val nameLen = payload[4].toInt() and 0xFF
            if (payload.size < 6 + nameLen) throw Protocol.ParseException("truncated announce name")
            val name = String(payload, 5, nameLen, Charsets.UTF_8)
            val flags = payload[5 + nameLen].toInt() and 0xFF
            return Announce(protoVersion, tcpPort, name, flags and 1 != 0)
        }
    }
}

/** HELLO payload (§5.2): u16 version, u8 name_len, name, u8 reserved(0), [16] client_nonce. */
data class Hello(val deviceName: String, val clientNonce: ByteArray) {
    init {
        require(clientNonce.size == 16) { "client nonce must be 16 bytes" }
    }

    fun encode(): ByteArray {
        val name = deviceName.toByteArray(Charsets.UTF_8)
        require(name.size <= 255)
        val out = ByteArray(2 + 1 + name.size + 1 + 16)
        putU16le(Protocol.PROTOCOL_VERSION, out, 0)
        out[2] = name.size.toByte()
        System.arraycopy(name, 0, out, 3, name.size)
        out[3 + name.size] = 0 // reserved
        System.arraycopy(clientNonce, 0, out, 4 + name.size, 16)
        return out
    }

    companion object {
        fun decode(payload: ByteArray): Hello {
            if (payload.size < 4 + 16) throw Protocol.ParseException("truncated hello")
            val version = getU16le(payload, 0)
            if (version != Protocol.PROTOCOL_VERSION) {
                throw Protocol.ParseException("bad protocol version $version")
            }
            val nameLen = payload[2].toInt() and 0xFF
            if (payload.size < 4 + nameLen + 16) throw Protocol.ParseException("truncated hello body")
            val name = String(payload, 3, nameLen, Charsets.UTF_8)
            val nonce = payload.copyOfRange(4 + nameLen, 4 + nameLen + 16)
            return Hello(name, nonce)
        }
    }
}

/** HELLO_REPLY payload (§5.3): u16 version, u32 session_id, u8 name_len, name, u8 flags, u8 reserved, [16] server_nonce. */
data class HelloReply(
    val protoVersion: Int,
    val sessionId: Long,
    val name: String,
    val pinRequired: Boolean,
    val serverNonce: ByteArray,
) {
    companion object {
        fun decode(payload: ByteArray): HelloReply {
            if (payload.size < 7) throw Protocol.ParseException("truncated hello reply")
            val protoVersion = getU16le(payload, 0)
            val sessionId = getU32le(payload, 2)
            val nameLen = payload[6].toInt() and 0xFF
            if (payload.size < 7 + nameLen + 18) throw Protocol.ParseException("truncated hello reply body")
            val name = String(payload, 7, nameLen, Charsets.UTF_8)
            var off = 7 + nameLen
            val pinRequired = payload[off].toInt() and 1 != 0
            off += 1 // flags
            off += 1 // reserved
            val nonce = payload.copyOfRange(off, off + 16)
            return HelloReply(protoVersion, sessionId, name, pinRequired, nonce)
        }
    }
}

/** ERROR payload: u16 code, u8 msg_len, msg. */
data class ErrorPayload(val code: Int, val message: String) {
    companion object {
        fun decode(payload: ByteArray): ErrorPayload {
            if (payload.size < 3) throw Protocol.ParseException("truncated error")
            val code = getU16le(payload, 0)
            val len = payload[2].toInt() and 0xFF
            if (payload.size < 3 + len) throw Protocol.ParseException("truncated error message")
            return ErrorPayload(code, String(payload, 3, len, Charsets.UTF_8))
        }
    }
}

/** BYE payload: single reason byte. */
fun byePayload(reason: Int): ByteArray = byteArrayOf(reason.toByte())

fun parseByePayload(payload: ByteArray): Int {
    if (payload.isEmpty()) throw Protocol.ParseException("truncated bye")
    return payload[0].toInt() and 0xFF
}

/** PING/PONG payload: u64 milliseconds. */
fun pingPayloadMs(ms: Long): ByteArray = ByteArray(8).also { putU32le(ms and 0xFFFF_FFFFL, it, 0) }

// ---------------------------------------------------------------------------
// input plane (§5.5–5.7)
// ---------------------------------------------------------------------------

/** One input event: op(1) code(2) a(4) b(4). For MOUSE_REL, a/b are Q16.16 dx/dy. */
data class DeltaEvent(val op: Int, val code: Int, val a: Int, val b: Int) {
    fun encodeInto(out: ByteArray, off: Int) {
        out[off] = op.toByte()
        putU16le(code, out, off + 1)
        putU32le(a.toLong(), out, off + 3)
        putU32le(b.toLong(), out, off + 7)
    }

    companion object {
        fun keyDown(evdevCode: Int) = DeltaEvent(Protocol.OP_KEY_DOWN, evdevCode, 0, 0)
        fun keyUp(evdevCode: Int) = DeltaEvent(Protocol.OP_KEY_UP, evdevCode, 0, 0)
        fun mouseDown(button: Int) = DeltaEvent(Protocol.OP_MOUSE_DOWN, button, 0, 0)
        fun mouseUp(button: Int) = DeltaEvent(Protocol.OP_MOUSE_UP, button, 0, 0)
        /** dx/dy in Q16.16 fixed point (65536 = 1 device pixel). */
        fun mouseRelQ16(dxQ16: Int, dyQ16: Int) = DeltaEvent(Protocol.OP_MOUSE_REL, 0, dxQ16, dyQ16)
    }
}

/** INPUT_DELTA payload: u32 session_id, u16 count, events…, u32 probe_ts. */
data class InputDelta(val sessionId: Long, val events: List<DeltaEvent>, val probeTs: Long) {

    fun encode(): ByteArray {
        require(events.size <= Protocol.MAX_EVENTS_PER_DELTA) { "too many events in one delta" }
        val out = ByteArray(4 + 2 + events.size * Protocol.EVENT_WIRE_SIZE + 4)
        putU32le(sessionId, out, 0)
        putU16le(events.size, out, 4)
        var off = 6
        for (e in events) {
            e.encodeInto(out, off)
            off += Protocol.EVENT_WIRE_SIZE
        }
        putU32le(probeTs, out, off)
        return out
    }

    companion object {
        fun decode(payload: ByteArray): InputDelta {
            if (payload.size < 10) throw Protocol.ParseException("truncated input delta")
            val sessionId = getU32le(payload, 0)
            val count = getU16le(payload, 4)
            if (count > Protocol.MAX_EVENTS_PER_DELTA) {
                throw Protocol.ParseException("too many events: $count")
            }
            val expected = 6 + count * Protocol.EVENT_WIRE_SIZE + 4
            if (payload.size != expected) {
                throw Protocol.ParseException("bad delta length ${payload.size}, expected $expected")
            }
            var off = 6
            val events = ArrayList<DeltaEvent>(count)
            repeat(count) {
                events += DeltaEvent(
                    op = payload[off].toInt() and 0xFF,
                    code = getU16le(payload, off + 1),
                    a = getU32le(payload, off + 3).toInt(),
                    b = getU32le(payload, off + 7).toInt(),
                )
                off += Protocol.EVENT_WIRE_SIZE
            }
            return InputDelta(sessionId, events, getU32le(payload, off))
        }
    }
}

/** STATS payload (§5.7), PC → phone: 4×u32. */
data class Stats(
    val lastSeqProcessed: Long,
    val eventsPerSec: Long,
    val packetsDroppedTotal: Long,
    val rttProbeEcho: Long,
) {
    companion object {
        const val WIRE_SIZE = 16

        fun decode(payload: ByteArray): Stats {
            if (payload.size < WIRE_SIZE) throw Protocol.ParseException("truncated stats")
            return Stats(
                getU32le(payload, 0),
                getU32le(payload, 4),
                getU32le(payload, 8),
                getU32le(payload, 12),
            )
        }
    }
}

/** Monotonic per-stream seq tracker; accepts strictly increasing values (0 never). */
class Watermark(initial: Int) {
    private val value = java.util.concurrent.atomic.AtomicInteger(initial)

    /**
     * True if `v` is newer than the current watermark and promotes it.
     * Uses unsigned comparison so the whole u32 wire range behaves exactly
     * like the Rust `Watermark` (seq wraps only after 2^32 packets; the
     * post-wrap packet is rejected by both implementations).
     */
    fun accept(v: Int): Boolean {
        if (v == 0) return false
        while (true) {
            val cur = value.get()
            if (Integer.compareUnsigned(v, cur) <= 0) return false
            if (value.compareAndSet(cur, v)) return true
        }
    }

    fun current(): Int = value.get()
}
