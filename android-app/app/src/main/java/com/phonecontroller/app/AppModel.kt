package com.phonecontroller.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonecontroller.app.net.PhoneConnection
import com.phonecontroller.app.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-level connection state. Kept deliberately small: a single live
 * [PhoneConnection], the pairing-in-progress flag and the last error.
 */
class AppModel(val scope: CoroutineScope) {

    var connection by mutableStateOf<PhoneConnection?>(null)
        private set

    var connecting by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun connect(host: String, tcpPort: Int, pin: String) {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                connection = PhoneConnection.pair(
                    host = host,
                    tcpPort = tcpPort,
                    udpPort = Protocol.DEFAULT_UDP_PORT,
                    pin = pin,
                    deviceName = "android",
                    scope = scope,
                )
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                connecting = false
            }
        }
    }

    fun disconnect() {
        val conn = connection ?: return
        connection = null
        scope.launch { conn.close() }
    }

    fun clearError() {
        error = null
    }
}
