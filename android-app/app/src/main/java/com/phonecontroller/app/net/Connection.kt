package com.phonecontroller.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** A local IPv4 interface candidates are discovered on. */
data class LocalNetwork(
    val interfaceName: String,
    val address: InetAddress,
    val broadcast: InetAddress?,
    val prefixLength: Int,
) {
    /** Best-effort label for the phone's Wi-Fi hotspot interface. */
    val isHotspot: Boolean get() = Discovery.looksLikeHotspot(interfaceName, address)

    val label: String get() = if (isHotspot) "Phone hotspot" else interfaceName
}

/** A PC discovered via UDP broadcast, plus the interface that reached it. */
data class DiscoveredPc(
    val address: InetAddress,
    val announce: Announce,
    val via: LocalNetwork? = null,
) {
    override fun toString(): String =
        "${announce.name} @ ${address.hostAddress} (tcp ${announce.tcpPort})" +
            (via?.let { " via ${it.label}" } ?: "")
}

/** Result of one discovery scan, including the interfaces that were probed. */
data class DiscoveryResult(
    val pcs: List<DiscoveredPc>,
    val networks: List<LocalNetwork>,
)

/**
 * UDP discovery (docs/PROTOCOL.md §4.2): broadcast DISCOVER, collect ANNOUNCE
 * replies for `timeoutMs`.
 *
 * Sends from **every** local IPv4 interface, not just the default route. When
 * mobile data is active and the PC is attached to the phone's Wi-Fi hotspot,
 * the default route points at cellular while the PC is only reachable through
 * `wlan0`/`ap0`. Binding one socket per interface and using the interface's
 * directed broadcast address keeps discovery working in both normal Wi-Fi and
 * hotspot topologies.
 */
object Discovery {
    suspend fun discover(
        timeoutMs: Long = 1500,
        udpPort: Int = Protocol.DEFAULT_UDP_PORT,
    ): List<DiscoveredPc> = discoverDetailed(timeoutMs, udpPort).pcs

    suspend fun discoverDetailed(
        timeoutMs: Long = 1500,
        udpPort: Int = Protocol.DEFAULT_UDP_PORT,
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val networks = localNetworks()
        val sockets = ArrayList<Pair<DatagramSocket, LocalNetwork?>>()

        try {
            if (networks.isEmpty()) {
                createDiscoverySocket(null)?.let { sockets += it to null }
            } else {
                for (network in networks) {
                    createDiscoverySocket(network.address)?.let { sockets += it to network }
                }
            }
            if (sockets.isEmpty()) {
                return@withContext DiscoveryResult(emptyList(), networks)
            }

            val discover = buildPacket(Protocol.KIND_DISCOVER, 0, ByteArray(0))
            val globalBroadcast = InetAddress.getByName("255.255.255.255")
            for ((socket, network) in sockets) {
                val targets = linkedSetOf(globalBroadcast)
                network?.broadcast?.let { targets += it }
                for (target in targets) {
                    runCatching {
                        socket.send(DatagramPacket(discover, discover.size, target, udpPort))
                    }
                }
            }

            val found = LinkedHashMap<String, DiscoveredPc>()
            val buf = ByteArray(Protocol.MAX_UDP_PACKET + 64)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                var received: Pair<DatagramPacket, LocalNetwork?>? = null
                for ((socket, network) in sockets) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    received = pkt to network
                    break
                }
                val (pkt, network) = received ?: continue
                val n = pkt.length
                if (n <= Protocol.HEADER_LEN) continue
                val header = try {
                    PacketHeader.parseHeader(buf, 0)
                } catch (e: IOException) {
                    continue
                }
                if (header.kind != Protocol.KIND_ANNOUNCE) continue
                val announce = try {
                    Announce.decode(buf.copyOfRange(Protocol.HEADER_LEN, n))
                } catch (e: IOException) {
                    continue
                }
                if (announce.protoVersion != Protocol.PROTOCOL_VERSION) continue
                found[pkt.address.hostAddress!!] =
                    DiscoveredPc(pkt.address, announce, network)
            }
            DiscoveryResult(found.values.toList(), networks)
        } finally {
            for ((socket, _) in sockets) {
                runCatching { socket.close() }
            }
        }
    }

    private fun createDiscoverySocket(localAddress: InetAddress?): DatagramSocket? =
        runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(
                    if (localAddress != null) InetSocketAddress(localAddress, 0)
                    else InetSocketAddress(0),
                )
                broadcast = true
                soTimeout = 100
            }
        }.getOrNull()

    /** All usable non-loopback IPv4 interfaces, deduplicated by address. */
    internal fun localNetworks(): List<LocalNetwork> {
        val result = LinkedHashMap<String, LocalNetwork>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val isUp = runCatching { ni.isUp }.getOrDefault(false)
                val isLoopback = runCatching { ni.isLoopback }.getOrDefault(true)
                if (!isUp || isLoopback) continue
                val name = runCatching { ni.name }.getOrNull() ?: continue
                val interfaceAddresses =
                    runCatching { ni.interfaceAddresses }.getOrDefault(emptyList())
                for (ia in interfaceAddresses) {
                    val address = ia.address
                    if (!isCandidateInterface(name, address)) continue
                    val prefix = ia.networkPrefixLength.toInt().coerceIn(0, 32)
                    val broadcast = ia.broadcast
                        ?: directedBroadcast(address.address, prefix)
                    val key = address.hostAddress ?: continue
                    result.putIfAbsent(
                        key,
                        LocalNetwork(name, address, broadcast, prefix),
                    )
                }
            }
        } catch (_: Exception) {
            // Interface enumeration must never break a scan.
        }
        return result.values.toList()
    }

    /** Directed broadcast address for an IPv4 address/prefix, or null for /32. */
    internal fun directedBroadcast(address: ByteArray, prefixLength: Int): InetAddress? {
        if (address.size != 4 || prefixLength !in 1..31) return null
        var value = 0L
        for (byte in address) {
            value = (value shl 8) or (byte.toLong() and 0xFF)
        }
        val hostMask = (1L shl (32 - prefixLength)) - 1
        val broadcast = value or hostMask
        return InetAddress.getByAddress(
            byteArrayOf(
                ((broadcast ushr 24) and 0xFF).toByte(),
                ((broadcast ushr 16) and 0xFF).toByte(),
                ((broadcast ushr 8) and 0xFF).toByte(),
                (broadcast and 0xFF).toByte(),
            ),
        )
    }

    /** Skip loopback, cellular, VPN and other non-LAN interfaces. */
    internal fun isCandidateInterface(name: String, address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) {
            return false
        }
        val normalized = name.lowercase()
        return listOf("lo", "rmnet", "ccmni", "tun", "tap", "ppp", "dummy", "sit")
            .none { normalized.startsWith(it) }
    }

    /**
     * Heuristic for UI labelling only. Android hotspot gateways are commonly
     * 192.168.43.1 / 192.168.49.1 / 192.168.137.1, but some OEMs use wlan0 as
     * the AP interface, so the address check matters as much as the name.
     */
    internal fun looksLikeHotspot(name: String, address: InetAddress): Boolean {
        val normalized = name.lowercase()
        if (normalized.startsWith("ap") ||
            normalized.contains("softap") ||
            normalized.startsWith("swlan")
        ) {
            return true
        }
        return address.hostAddress in HOTSPOT_GATEWAYS
    }

    private val HOTSPOT_GATEWAYS = setOf(
        "192.168.43.1",
        "192.168.49.1",
        "192.168.137.1",
        "192.168.232.1",
        "172.20.10.1",
    )
}

/**
 * RTT from a TCP PING/PONG echo. Both timestamps are phone-local 32-bit
 * milliseconds, so unsigned subtraction is wrap-safe. Anything older than
 * 5 s is treated as a stale echo rather than a real latency reading.
 */
internal fun rttMillisFromEcho(echoedMs: Long, nowMs: Long): Int {
    val diff = (nowMs - echoedMs) and 0xFFFF_FFFFL
    return if (diff <= 5000) diff.toInt() else 0
}

/** Connection lifecycle states surfaced to the UI. */
sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Paired : ConnState
    data class Failed(val message: String) : ConnState
    data class Disconnected(val reason: String) : ConnState
}

/** Message handed to the single UDP sender coroutine. */
private sealed interface UdpMessage {
    class Delta(val plaintext: ByteArray) : UdpMessage
    class Snapshot(val keys: IntArray, val mouseBits: Int) : UdpMessage
}

/**
 * Read one TCP packet; the payload is ciphertext+tag for SECURE_FRAME.
 * Takes the session's single buffered reader so no bytes are lost between
 * frames.
 */
private fun readPacket(input: DataInputStream): Pair<PacketHeader, ByteArray> {
    val header = ByteArray(Protocol.HEADER_LEN)
    input.readFully(header)
    val hdr = PacketHeader.parseHeader(header)
    var wireLen = hdr.payloadLen
    if (hdr.kind == Protocol.KIND_SECURE_FRAME) wireLen += Protocol.TAG_LEN
    if (wireLen > 4096) throw IOException("payload too large")
    val payload = ByteArray(wireLen)
    if (wireLen > 0) input.readFully(payload)
    return hdr to payload
}

/**
 * An established, paired phone→PC session. Owns the TCP control connection,
 * the UDP input socket, the session crypto and both sequence streams.
 *
 * Wire reminders (docs/PROTOCOL.md):
 *  - TCP: plaintext handshake packets, then SECURE_FRAME
 *    (header.payload_len counts the INNER plaintext; the frame payload is
 *    ciphertext + 16-byte tag).
 *  - UDP: datagram = header(kind, plaintext_len, seq) || ciphertext+tag —
 *    payload_len counts the plaintext in BOTH directions.
 */
class PhoneConnection private constructor(
    private val tcp: Socket,
    /**
     * The single buffered reader created before HELLO. It must be shared with
     * the session loop: a second reader would lose any bytes the handshake
     * read ahead (e.g. a heartbeat frame coalesced with AUTH_PROOF).
     */
    private val tcpInput: DataInputStream,
    private val udp: DatagramSocket,
    private val crypto: SessionCrypto,
    val sessionId: Long,
    val pcName: String,
    /** PC UDP input port (protocol default 41000; the ANNOUNCE carries TCP only). */
    private val udpPort: Int,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val tcpWriteMutex = Mutex()
    private val tcpTxSeq = AtomicInteger(0)
    private val udpTxSeq = AtomicInteger(0)
    private val udpRxWatermark = Watermark(0)
    private val tcpRxWatermark = Watermark(0)

    /**
     * All input datagrams go through one FIFO channel so sequence numbers are
     * assigned in wire order. Sealing/sending directly from UI coroutines can
     * reorder two packets and make the receiver drop the older one.
     *
     * UNLIMITED (rather than BUFFERED) lets the UI enqueue press/release
     * events synchronously: a full buffer must never drop a key-up or make a
     * caller fall back to a separate coroutine that can overtake an earlier
     * event. The sender drains continuously and datagrams are tiny, so the
     * queue only grows if the socket blocks.
     */
    private val udpOut = Channel<UdpMessage>(Channel.UNLIMITED)

    @Volatile
    private var lastDeltaSeq: Int = 0

    private val _state = MutableStateFlow<ConnState>(ConnState.Paired)
    val state: StateFlow<ConnState> = _state

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats

    @Volatile
    private var rttMsValue: Int = 0

    /** Latest round-trip time (ms) from STATS echo, 0 when unknown/stale. */
    val rttMs: Int
        get() = rttMsValue

    // -------------------------------------------------------------------
    // pairing
    // -------------------------------------------------------------------

    companion object {
        /**
         * Pair with a PC: TCP connect → HELLO/HELLO_REPLY → key derivation →
         * AUTH_PROOF both ways. Throws [IOException] with an actionable
         * message on any failure.
         */
        suspend fun pair(
            host: String,
            tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
            udpPort: Int = Protocol.DEFAULT_UDP_PORT,
            pin: String,
            deviceName: String = "android",
            scope: kotlinx.coroutines.CoroutineScope,
        ): PhoneConnection = withContext(Dispatchers.IO) {
            val tcp = Socket()
            try {
                tcp.connect(InetSocketAddress(host, tcpPort), 3000)
                tcp.tcpNoDelay = true
                tcp.soTimeout = 5000

                // Streams live for the whole socket lifetime: closing either
                // one closes the socket, so no `use {}` here.
                val input = DataInputStream(BufferedInputStream(tcp.getInputStream()))
                val output = tcp.getOutputStream()

                val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val hello = buildPacket(
                    Protocol.KIND_HELLO, 0,
                    Hello(deviceName, clientNonce).encode(),
                )
                output.write(hello)
                output.flush()

                // ---- HELLO_REPLY (or ERROR) ----
                val (replyHdr, replyPayload) = readPacket(input)
                if (replyHdr.kind == Protocol.KIND_ERROR) {
                    val e = ErrorPayload.decode(replyPayload)
                    throw IOException(describeError(e))
                }
                require(replyHdr.kind == Protocol.KIND_HELLO_REPLY) {
                    "expected HELLO_REPLY, got kind 0x%02X".format(replyHdr.kind)
                }
                val reply = HelloReply.decode(replyPayload)
                if (reply.protoVersion != Protocol.PROTOCOL_VERSION) {
                    throw IOException("PC speaks protocol v${reply.protoVersion}")
                }
                val key = Crypto.deriveSessionKey(pin, clientNonce, reply.serverNonce)
                val crypto = SessionCrypto(key)

                // ---- client AUTH_PROOF ----
                val proof = Crypto.authProof(key, clientNonce, reply.serverNonce, reply.sessionId, true)
                output.write(buildPacket(Protocol.KIND_AUTH_PROOF, 0, proof))
                output.flush()

                // ---- server AUTH_PROOF (or ERROR) ----
                val (proofHdr, proofPayload) = readPacket(input)
                if (proofHdr.kind == Protocol.KIND_ERROR) {
                    val e = ErrorPayload.decode(proofPayload)
                    throw IOException(describeError(e))
                }
                require(proofHdr.kind == Protocol.KIND_AUTH_PROOF) {
                    "expected AUTH_PROOF, got kind 0x%02X".format(proofHdr.kind)
                }
                val expected = Crypto.authProof(key, clientNonce, reply.serverNonce, reply.sessionId, false)
                if (!proofPayload.contentEquals(expected)) {
                    throw IOException("PC failed proof verification (bad PIN?)")
                }

                tcp.soTimeout = 10_000
                val udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                val conn = PhoneConnection(
                    tcp, input, udp, crypto, reply.sessionId, reply.name, udpPort, scope,
                )
                conn.startLoops()
                conn
            } catch (e: Exception) {
                runCatching { tcp.close() }
                throw e
            }
        }

        private fun describeError(e: ErrorPayload): String = when (e.code) {
            Protocol.ERROR_AUTH_FAILED -> "wrong PIN"
            Protocol.ERROR_VERSION_MISMATCH -> "protocol version mismatch: ${e.message}"
            Protocol.ERROR_SERVER_BUSY -> "PC busy: ${e.message}"
            else -> "PC error ${e.code}: ${e.message}"
        }
    }

    // -------------------------------------------------------------------
    // framed TCP I/O
    // -------------------------------------------------------------------

    /**
     * Seal and write one SECURE_FRAME carrying the plaintext inner packet.
     * The sequence number is assigned inside the write lock so wire order and
     * seq order always agree (the receiver drops non-increasing seqs).
     */
    private suspend fun sendSecure(innerKind: Int, innerPayload: ByteArray) =
        withContext(Dispatchers.IO) {
            tcpWriteMutex.withLock {
                val seq = tcpTxSeq.incrementAndGet().toLong()
                val inner = buildPacket(innerKind, 0, innerPayload)
                val oHdr = PacketHeader(Protocol.KIND_SECURE_FRAME, inner.size, seq).toBytes()
                val ct = crypto.seal(Crypto.NONCE_CLIENT_TO_SERVER, seq, oHdr, inner)
                tcp.getOutputStream().apply {
                    write(oHdr)
                    write(ct)
                    flush()
                }
            }
        }

    // -------------------------------------------------------------------
    // loops
    // -------------------------------------------------------------------

    private fun startLoops() {
        // Single UDP sender: seq assignment, sealing and send order are one
        // atomic step from the receiver's point of view.
        scope.launch(Dispatchers.IO) {
            try {
                for (msg in udpOut) {
                    // Snapshot payloads are built here so base_seq includes
                    // every delta enqueued before them.
                    val (kind, plaintext) = when (msg) {
                        is UdpMessage.Delta -> Protocol.KIND_INPUT_DELTA to msg.plaintext
                        is UdpMessage.Snapshot -> Protocol.KIND_INPUT_SNAPSHOT to
                            encodeSnapshot(msg.keys, msg.mouseBits, lastDeltaSeq)
                    }
                    val seq = udpTxSeq.incrementAndGet().toLong()
                    val header = PacketHeader(kind, plaintext.size, seq).toBytes()
                    val ct = crypto.seal(Crypto.NONCE_CLIENT_TO_SERVER, seq, header, plaintext)
                    val datagram = header + ct
                    udp.send(DatagramPacket(datagram, datagram.size, tcp.inetAddress, udpPort))
                    if (kind == Protocol.KIND_INPUT_DELTA) lastDeltaSeq = seq.toInt()
                }
            } catch (e: IOException) {
                // Socket closed or network unreachable; the TCP control loop
                // reports disconnects to the UI.
            }
        }

        // TCP control loop: heartbeat replies, BYE/RESET/ERROR handling.
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val (hdr, wire) = readPacket(tcpInput)
                    if (hdr.kind != Protocol.KIND_SECURE_FRAME) continue
                    if (!tcpRxWatermark.accept(hdr.seq.toInt())) continue
                    val plain = try {
                        crypto.open(Crypto.NONCE_SERVER_TO_CLIENT, hdr.toBytes(), hdr.seq, wire)
                    } catch (e: Crypto.AuthFailedException) {
                        _state.value = ConnState.Disconnected("control frame failed to decrypt")
                        break
                    }
                    val inner = try {
                        val ih = PacketHeader.parseHeader(plain)
                        ih to plain.copyOfRange(Protocol.HEADER_LEN, plain.size)
                    } catch (e: IOException) {
                        continue
                    }
                    when (inner.first.kind) {
                        Protocol.KIND_PING -> sendSecure(Protocol.KIND_PONG, inner.second)
                        Protocol.KIND_PONG -> {
                            // RTT probe: the PC echoes the low 32 bits of our
                            // send timestamp verbatim. Both timestamps come
                            // from this phone's clock, so no clock sync is
                            // needed; u32 subtraction handles the 49-day wrap.
                            if (inner.second.size >= 4) {
                                val echoed = getU32le(inner.second, 0)
                                val now = System.currentTimeMillis() and 0xFFFF_FFFFL
                                rttMsValue = rttMillisFromEcho(echoed, now)
                            }
                        }
                        Protocol.KIND_BYE -> {
                            val reason = runCatching { parseByePayload(inner.second) }.getOrDefault(-1)
                            _state.value = ConnState.Disconnected("PC sent BYE (reason $reason)")
                            break
                        }
                        Protocol.KIND_RESET -> {
                            _state.value = ConnState.Disconnected("PC reset the session")
                            break
                        }
                        else -> {}
                    }
                }
            } catch (e: EOFException) {
                _state.value = ConnState.Disconnected("PC closed the connection")
            } catch (e: SocketTimeoutException) {
                _state.value = ConnState.Disconnected("connection timed out")
            } catch (e: IOException) {
                if (_state.value is ConnState.Paired) {
                    _state.value = ConnState.Disconnected("connection lost: ${e.message}")
                }
            } finally {
                // The session is over (BYE, EOF, timeout or explicit close):
                // stop the sender and release both sockets. `close()` is
                // idempotent, so this also covers the normal disconnect path.
                udpOut.close()
                runCatching { tcp.close() }
                runCatching { udp.close() }
            }
        }

        // Active RTT probe. STATS echoes the timestamp of the last input
        // delta, so it stops being a valid RTT as soon as the user pauses;
        // a TCP PING/PONG every 2 s is independent of input traffic and
        // also keeps the server's TCP idle watchdog satisfied.
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    delay(2000)
                    if (_state.value !is ConnState.Paired) break
                    val probe = System.currentTimeMillis() and 0xFFFF_FFFFL
                    sendSecure(Protocol.KIND_PING, pingPayloadMs(probe))
                }
            } catch (e: IOException) {
                // Session closed; the TCP control loop reports the reason.
            }
        }

        // UDP stats listener: decrypt STATS packets and surface counters.
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(Protocol.MAX_UDP_PACKET + 64)
            while (true) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    udp.receive(pkt)
                } catch (e: IOException) {
                    break
                }
                val n = pkt.length
                if (n <= Protocol.HEADER_LEN) continue
                val hdr = try {
                    PacketHeader.parseHeader(buf, 0)
                } catch (e: IOException) {
                    continue
                }
                if (hdr.kind != Protocol.KIND_STATS) continue
                if (!udpRxWatermark.accept(hdr.seq.toInt())) continue
                val plain = try {
                    crypto.open(
                        Crypto.NONCE_SERVER_TO_CLIENT, hdr.toBytes(), hdr.seq,
                        buf.copyOfRange(Protocol.HEADER_LEN, n),
                    )
                } catch (e: Crypto.AuthFailedException) {
                    continue
                }
                val decoded = runCatching { Stats.decode(plain) }.getOrNull()
                if (decoded != null) {
                    _stats.value = decoded
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // input plane
    // -------------------------------------------------------------------

    /**
     * Send one INPUT_DELTA datagram:
     * header(INPUT_DELTA, plaintext_len, seq) || AES-GCM(plaintext, AAD=header).
     *
     * Enqueues synchronously on the UDP FIFO. Callers on the UI thread can
     * therefore guarantee that a release is ordered after the matching press
     * without spawning a coroutine that may run in a different order.
     */
    fun enqueueDelta(events: List<DeltaEvent>) {
        if (events.isEmpty()) return
        val probe = System.currentTimeMillis().toInt() // u32 ms wrap is fine
        val delta = InputDelta(sessionId, events, probe.toLong() and 0xFFFF_FFFFL)
        udpOut.trySend(UdpMessage.Delta(delta.encode()))
    }

    /** Suspend variant retained for tests and non-UI callers. */
    suspend fun sendDelta(events: List<DeltaEvent>) {
        enqueueDelta(events)
    }

    /**
     * 1 Hz INPUT_SNAPSHOT so the receiver can reconcile after any packet loss.
     * It is enqueued on the same FIFO as deltas, so it observes a coherent
     * `base_seq` and held-key set.
     */
    suspend fun sendSnapshot(heldKeys: Set<Int>, mouseBits: Int) {
        udpOut.send(UdpMessage.Snapshot(heldKeys.sorted().toIntArray(), mouseBits))
    }

    suspend fun sendSnapshot(heldKeys: IntArray, mouseBits: Int) {
        udpOut.send(UdpMessage.Snapshot(heldKeys, mouseBits))
    }

    /** INPUT_SNAPSHOT payload (§5.6). Keys must be sorted ascending. */
    private fun encodeSnapshot(keys: IntArray, mouseBits: Int, baseSeq: Int): ByteArray {
        val out = ByteArray(11 + keys.size * 2)
        putU32le(sessionId, out, 0)
        putU32le(baseSeq.toLong() and 0xFFFF_FFFFL, out, 4)
        out[8] = mouseBits.toByte()
        putU16le(keys.size, out, 9)
        var off = 11
        for (k in keys) {
            putU16le(k, out, off)
            off += 2
        }
        return out
    }

    /** Inform the receiver to release all held keys and mouse buttons (e.g. profile switch). */
    suspend fun sendReset() {
        sendSecure(Protocol.KIND_RESET, ByteArray(0))
    }

    /** Inform the receiver of the active profile name (logging / diagnostics). */
    suspend fun sendProfileInfo(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        val len = minOf(bytes.size, 255)
        val payload = ByteArray(1 + len)
        payload[0] = len.toByte()
        System.arraycopy(bytes, 0, payload, 1, len)
        sendSecure(Protocol.KIND_PROFILE_INFO, payload)
    }

    /** Graceful disconnect: BYE, then drop the sockets and loops. */
    suspend fun close(reason: Int = Protocol.BYE_NORMAL) {
        _state.value = ConnState.Idle
        runCatching { sendSecure(Protocol.KIND_BYE, byePayload(reason)) }
        udpOut.close()
        runCatching { tcp.close() }
        runCatching { udp.close() }
    }
}
