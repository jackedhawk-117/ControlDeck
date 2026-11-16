package com.phonecontroller.app.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Cross-language E2E: this is the exact Kotlin client used by the Android app
 * pairing with the real Rust receiver and delivering real input events.
 *
 * Automated mode (runs on a Linux host with a writable /dev/uinput):
 *   1. cargo build --release -p pc-receiver
 *   2. ./gradlew testDebugUnitTest --tests '*RealReceiverE2ETest*'
 *
 * The test is skipped when the receiver binary or /dev/uinput is absent, so
 * plain `./gradlew test` works on machines without the Rust build.
 */
class RealReceiverE2ETest {

    @Test(timeout = 40_000)
    fun kotlinClientDrivesRealRustReceiver() = runBlocking {
        val receiver = findReceiverBinary()
        assumeTrue("receiver binary not built (skipping cross-language E2E)", receiver != null)
        assumeTrue("/dev/uinput is not writable (skipping cross-language E2E)", File("/dev/uinput").canWrite())

        // Reserve two ephemeral ports, then release them for the receiver.
        val tcpPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val udpPort = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val pin = "246810"

        val process = ProcessBuilder(
            receiver!!.absolutePath,
            "--pin", pin,
            "--port", tcpPort.toString(),
            "--udp-port", udpPort.toString(),
            "--name", "kotlin-e2e",
            "--timeout-ms", "5000",
        ).redirectErrorStream(true).start()

        val ansi = Regex("\\x1B\\[[0-9;]*[A-Za-z]")
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val clean = line.replace(ansi, "")
                    synchronized(output) { output.appendLine(clean) }
                }
            } catch (_: IOException) {
                // process killed
            }
        }.apply {
            isDaemon = true
            start()
        }

        var conn: PhoneConnection? = null
        try {
            // Wait for the listener; TCP connect during startup is refused.
            val pairDeadline = System.currentTimeMillis() + 15_000
            while (conn == null && System.currentTimeMillis() < pairDeadline) {
                conn = try {
                    PhoneConnection.pair(
                        host = "127.0.0.1",
                        tcpPort = tcpPort,
                        udpPort = udpPort,
                        pin = pin,
                        deviceName = "jvm-e2e",
                        scope = this,
                    )
                } catch (e: IOException) {
                    if (!process.isAlive) break
                    delay(200)
                    null
                }
            }
            assertNotNull("client could not pair with the real receiver:\n${dump(output)}", conn)

            val c = conn!!
            c.sendDelta(
                listOf(
                    DeltaEvent.keyDown(17),                    // W down
                    DeltaEvent.mouseRelQ16(98304, -65536),      // +1.5px, -1px
                    DeltaEvent.keyUp(17),                      // W up
                ),
            )
            delay(400) // let the datagram be processed
            c.close()

            val logDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < logDeadline && !dump(output).contains("session closed")) {
                delay(100)
            }
            val log = dump(output)
            assertTrue("receiver never logged session teardown:\n$log", log.contains("session closed"))
            assertTrue("receiver did not process all 3 events:\n$log", log.contains("events=3"))
            assertTrue("receiver dropped packets:\n$log", log.contains("dropped=0"))
        } finally {
            conn?.close()
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(1_000)
        }
    }

    /** Walk up from the test working directory looking for a built receiver. */
    private fun findReceiverBinary(): File? {
        val names = listOf(
            "target/release/phonecontroller-receiver",
            "target/debug/phonecontroller-receiver",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return@repeat
            for (name in names) {
                val f = File(base, "pc-receiver/$name")
                if (f.canExecute()) return f
            }
            dir = base.parentFile
        }
        return null
    }

    private fun dump(sb: StringBuilder): String = synchronized(sb) { sb.toString() }
}
