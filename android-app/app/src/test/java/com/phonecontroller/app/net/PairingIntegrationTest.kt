package com.phonecontroller.app.net

import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the real [PhoneConnection] against a minimal in-process receiver
 * that follows docs/PROTOCOL.md. This catches wiring bugs that pure codec
 * tests cannot: socket lifetime, handshake order, TCP framing, key
 * derivation and the UDP send pipeline.
 */
class PairingIntegrationTest {

    @Test(timeout = 20_000)
    fun pairsSendsDeltaAndSnapshotToReferenceServer() = runBlocking {
        val pin = "246810"
        val sessionId = 0x0BAD_F00DL
        val serverNonce = ByteArray(16) { 0x22 }

        val udp = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val deltaQueue = LinkedBlockingQueue<InputDelta>()
        val snapshot = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)

        val serverThread = thread(name = "fake-pc") {
            try {
                serverSocket.accept().use { tcp ->
                    tcp.soTimeout = 8_000
                    val input = DataInputStream(BufferedInputStream(tcp.getInputStream()))
                    val output = tcp.getOutputStream()

                    // ---- HELLO -> HELLO_REPLY ----
                    val (helloHdr, helloPayload) = readTcpPacket(input)
                    assertEquals(Protocol.KIND_HELLO, helloHdr.kind)
                    val hello = Hello.decode(helloPayload)
                    assertEquals("jvm-test", hello.deviceName)

                    val key = Crypto.deriveSessionKey(pin, hello.clientNonce, serverNonce)
                    val name = "fake-pc".toByteArray()
                    val reply = ByteArray(7 + name.size + 18)
                    putU16le(Protocol.PROTOCOL_VERSION, reply, 0)
                    putU32le(sessionId, reply, 2)
                    reply[6] = name.size.toByte()
                    System.arraycopy(name, 0, reply, 7, name.size)
                    var off = 7 + name.size
                    reply[off] = 1 // pin_required
                    off += 1
                    reply[off] = 0 // reserved
                    off += 1
                    System.arraycopy(serverNonce, 0, reply, off, 16)
                    output.write(buildPacket(Protocol.KIND_HELLO_REPLY, 0, reply))
                    output.flush()

                    // ---- client proof, then server proof ----
                    val (proofHdr, proof) = readTcpPacket(input)
                    assertEquals(Protocol.KIND_AUTH_PROOF, proofHdr.kind)
                    assertArrayEquals(
                        Crypto.authProof(key, hello.clientNonce, serverNonce, sessionId, true),
                        proof,
                    )
                    output.write(
                        buildPacket(
                            Protocol.KIND_AUTH_PROOF, 0,
                            Crypto.authProof(key, hello.clientNonce, serverNonce, sessionId, false),
                        ),
                    )
                    output.flush()

                    // ---- UDP input plane ----
                    val crypto = SessionCrypto(key)
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + 8_000
                    var lastSeq = 0L
                    var gotSnapshot = false
                    while (System.currentTimeMillis() < deadline && (deltaQueue.isEmpty() || !gotSnapshot)) {
                        udp.soTimeout = 500
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            udp.receive(pkt)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val hdr = PacketHeader.parseHeader(buf, 0)
                        assertTrue("UDP seq must increase", hdr.seq > lastSeq)
                        lastSeq = hdr.seq
                        val plain = crypto.open(
                            Crypto.NONCE_CLIENT_TO_SERVER,
                            hdr.toBytes(),
                            hdr.seq,
                            buf.copyOfRange(Protocol.HEADER_LEN, pkt.length),
                        )
                        when (hdr.kind) {
                            Protocol.KIND_INPUT_DELTA -> deltaQueue.put(InputDelta.decode(plain))
                            Protocol.KIND_INPUT_SNAPSHOT -> {
                                snapshot.set(plain)
                                gotSnapshot = true
                            }
                            else -> error("unexpected UDP kind 0x%02X".format(hdr.kind))
                        }
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }

        var conn: PhoneConnection? = null
        try {
            conn = PhoneConnection.pair(
                host = "127.0.0.1",
                tcpPort = serverSocket.localPort,
                udpPort = udp.localPort,
                pin = pin,
                deviceName = "jvm-test",
                scope = this,
            )
            assertEquals(sessionId, conn.sessionId)
            assertEquals("fake-pc", conn.pcName)

            conn.sendDelta(
                listOf(
                    DeltaEvent.keyDown(17),
                    DeltaEvent.mouseRelQ16(98304, -65536),
                ),
            )
            conn.sendSnapshot(setOf(30, 17), mouseBits = 0b00001)

            assertTrue("reference server did not finish", done.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("reference server failed: ${it.message}", it) }

            val delta = deltaQueue.poll(2, TimeUnit.SECONDS)
                ?: error("INPUT_DELTA never reached the server")
            assertEquals(sessionId, delta.sessionId)
            assertEquals(
                listOf(
                    DeltaEvent(Protocol.OP_KEY_DOWN, 17, 0, 0),
                    DeltaEvent(Protocol.OP_MOUSE_REL, 0, 98304, -65536),
                ),
                delta.events,
            )

            val snap = snapshot.get() ?: error("INPUT_SNAPSHOT never reached the server")
            assertEquals(sessionId, getU32le(snap, 0))
            assertEquals(1L, getU32le(snap, 4)) // base_seq = first delta
            assertEquals(1, snap[8].toInt() and 0xFF)
            assertEquals(2, getU16le(snap, 9))
            assertEquals(17, getU16le(snap, 11))
            assertEquals(30, getU16le(snap, 13))
        } finally {
            conn?.close()
            serverSocket.close()
            udp.close()
            serverThread.join(2_000)
        }
    }

    private fun readTcpPacket(input: DataInputStream): Pair<PacketHeader, ByteArray> {
        val header = ByteArray(Protocol.HEADER_LEN)
        input.readFully(header)
        val hdr = PacketHeader.parseHeader(header)
        var wireLen = hdr.payloadLen
        if (hdr.kind == Protocol.KIND_SECURE_FRAME) wireLen += Protocol.TAG_LEN
        val payload = ByteArray(wireLen)
        if (wireLen > 0) input.readFully(payload)
        return hdr to payload
    }
}
