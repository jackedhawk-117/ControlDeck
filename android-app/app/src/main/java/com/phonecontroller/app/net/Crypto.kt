package com.phonecontroller.app.net

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session cryptography — Kotlin mirror of pc-protocol/src/crypto.rs
 * (docs/PROTOCOL.md §6). Byte-level compatibility is pinned by test vectors
 * produced by the Rust implementation (see CryptoCompatTest).
 *
 *  - Key: HKDF-SHA256(salt = zeroed, ikm, info = "pc session key", 32 bytes)
 *    with ikm = "phonecontroller-v1" || 0x00 || PIN || client_nonce || server_nonce
 *  - Nonce (12 bytes): direction(1) || seq LE (4) || zeros(7)
 *  - Cipher: AES-256-GCM, AAD = the 10-byte packet header, output ct||tag.
 *
 * Documented limitation (matching the Rust side): the PIN-based key is
 * vulnerable to offline brute force by an active attacker who records one
 * handshake; SPAKE2 is the planned upgrade.
 */
object Crypto {
    /** Nonce direction byte: 0x00 = phone→pc, 0x01 = pc→phone. */
    const val NONCE_CLIENT_TO_SERVER = 0x00
    const val NONCE_SERVER_TO_CLIENT = 0x01

    private const val KDF_LABEL = "phonecontroller-v1"
    private const val KDF_INFO = "pc session key"

    class AuthFailedException(message: String) : Exception(message)

    // -----------------------------------------------------------------------
    // HKDF-SHA256 (RFC 5869)
    // -----------------------------------------------------------------------

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 extract step. salt = null means 32 zero bytes. */
    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val realSalt = salt ?: ByteArray(32)
        return hmacSha256(realSalt, ikm)
    }

    /** RFC 5869 expand step, producing `outLen` bytes. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen <= 255 * 32) { "HKDF output too long" }
        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < outLen) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            val n = minOf(32, outLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hkdfSha256(salt: ByteArray?, ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, outLen)

    // -----------------------------------------------------------------------
    // session key + handshake proof
    // -----------------------------------------------------------------------

    /**
     * Session key from the pairing PIN and both nonces.
     * ikm = "phonecontroller-v1" || 0x00 || PIN || client_nonce || server_nonce
     */
    fun deriveSessionKey(pin: String, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray {
        val label = KDF_LABEL.toByteArray(Charsets.UTF_8)
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        require(clientNonce.size == 16 && serverNonce.size == 16)
        val ikm = ByteArray(label.size + 1 + pinBytes.size + 32)
        var off = 0
        System.arraycopy(label, 0, ikm, off, label.size); off += label.size
        ikm[off++] = 0x00
        System.arraycopy(pinBytes, 0, ikm, off, pinBytes.size); off += pinBytes.size
        System.arraycopy(clientNonce, 0, ikm, off, 16); off += 16
        System.arraycopy(serverNonce, 0, ikm, off, 16)
        return hkdfSha256(null, ikm, KDF_INFO.toByteArray(Charsets.UTF_8), 32)
    }

    /**
     * AUTH_PROOF tag:
     * tag = HMAC-SHA256(key, client_nonce || server_nonce || session_id_le || dir)
     * dir: 0x00 client→pc, 0x01 pc→client.
     */
    fun authProof(
        key: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        sessionId: Long,
        fromClient: Boolean,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(clientNonce)
        mac.update(serverNonce)
        mac.update(ByteArray(4).also { putU32le(sessionId, it, 0) })
        mac.update(byteArrayOf(if (fromClient) 0 else 1))
        return mac.doFinal()
    }

    /** 12-byte GCM nonce: direction(1) || seq LE (4) || zeros(7). */
    fun buildNonce(direction: Int, seq: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = direction.toByte()
        putU32le(seq, nonce, 1)
        return nonce
    }
}

/**
 * Per-session AES-256-GCM cipher with AAD = the 10-byte packet header.
 * Stateless w.r.t. sequence numbers: replay protection is per stream
 * ([Watermark]) and lives with the session state.
 */
class SessionCrypto(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")

    /** Seal plaintext (payload only). Returns ciphertext || 16-byte tag. */
    fun seal(direction: Int, seq: Long, header: ByteArray, plaintext: ByteArray): ByteArray {
        require(header.size == Protocol.HEADER_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, Crypto.buildNonce(direction, seq)))
        cipher.updateAAD(header)
        return cipher.doFinal(plaintext)
    }

    /** Open an on-wire encrypted payload (ciphertext || tag). */
    fun open(direction: Int, header: ByteArray, seq: Long, wirePayload: ByteArray): ByteArray {
        require(header.size == Protocol.HEADER_LEN)
        if (wirePayload.size < Protocol.TAG_LEN) throw Crypto.AuthFailedException("payload too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, Crypto.buildNonce(direction, seq)))
        cipher.updateAAD(header)
        return try {
            cipher.doFinal(wirePayload)
        } catch (e: Exception) {
            throw Crypto.AuthFailedException("GCM authentication failed")
        }
    }
}
