package com.phonecontroller.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.roundToInt

/**
 * Owns the Android sensor listeners and exposes their latest values as a small,
 * allocation-free mailbox. [GyroMapper] runs on the Compose tick coroutine and
 * reads this mailbox, so no sensor callback ever touches input state.
 *
 * Registration uses the gyro at ~200 Hz and the fused orientation vector at
 * ~100 Hz. Values are copied under a lock because some OEMs deliver callbacks
 * on a different looper than the one that registered the listener.
 */
class AndroidGyroSource(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Game rotation vector avoids magnetometer noise; full vector is fallback. */
    private val orientationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val hasGyro: Boolean get() = gyroSensor != null
    val hasOrientation: Boolean get() = orientationSensor != null

    /** Human-readable source for diagnostics. */
    val orientationLabel: String
        get() = when {
            orientationSensor == null -> "unavailable"
            orientationSensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR -> "game rotation vector"
            else -> "rotation vector"
        }

    private val lock = Any()
    private val gyroValues = FloatArray(3)
    private val quaternionValues = FloatArray(4)
    private var gyroTimestamp = 0L
    private var quaternionTimestamp = 0L

    private var started = false
    private var sensorThread: HandlerThread? = null
    private var gyroSamples = 0L
    private var orientationSamples = 0L
    private var rateWindowStartNs = 0L
    private var rateGyroStart = 0L
    private var rateOrientationStart = 0L
    private var lastGyroHz = 0
    private var lastOrientationHz = 0

    fun start() {
        if (started) return
        started = true
        synchronized(lock) {
            gyroTimestamp = 0L
            quaternionTimestamp = 0L
            gyroSamples = 0L
            orientationSamples = 0L
            rateWindowStartNs = 0L
            rateGyroStart = 0L
            rateOrientationStart = 0L
            lastGyroHz = 0
            lastOrientationHz = 0
        }
        // Keep 300 Hz of callbacks off the main looper. SENSOR_DELAY_GAME is
        // 20 ms nominally; shorter periods are requests and devices clamp to
        // their supported rate.
        val thread = HandlerThread("control-deck-gyro").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        gyroSensor?.let {
            sensorManager.registerListener(this, it, GYRO_PERIOD_US, handler)
        }
        orientationSensor?.let {
            sensorManager.registerListener(this, it, ORIENTATION_PERIOD_US, handler)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
    }

    /** Copies the latest gyro values; returns their timestamp or 0 if none. */
    fun readGyro(out: FloatArray): Long = synchronized(lock) {
        if (gyroTimestamp == 0L) return 0L
        gyroValues.copyInto(out, 0, 0, 3)
        gyroTimestamp
    }

    /** Copies the latest quaternion (Android x,y,z,w); returns its timestamp. */
    fun readQuaternion(out: FloatArray): Long = synchronized(lock) {
        if (quaternionTimestamp == 0L) return 0L
        quaternionValues.copyInto(out, 0, 0, 4)
        quaternionTimestamp
    }

    /**
     * Samples-per-second since the previous call, for the diagnostics HUD.
     * Call about once per second.
     */
    fun takeSampleRatesHz(nowNs: Long): Pair<Int, Int> = synchronized(lock) {
        if (rateWindowStartNs == 0L) {
            rateWindowStartNs = nowNs
            rateGyroStart = gyroSamples
            rateOrientationStart = orientationSamples
            return@synchronized lastGyroHz to lastOrientationHz
        }
        val elapsed = (nowNs - rateWindowStartNs) / 1_000_000_000.0
        if (elapsed <= 0.05) return@synchronized lastGyroHz to lastOrientationHz
        lastGyroHz = ((gyroSamples - rateGyroStart) / elapsed).roundToInt()
        lastOrientationHz = ((orientationSamples - rateOrientationStart) / elapsed).roundToInt()
        rateWindowStartNs = nowNs
        rateGyroStart = gyroSamples
        rateOrientationStart = orientationSamples
        lastGyroHz to lastOrientationHz
    }

    override fun onSensorChanged(event: SensorEvent) {
        // A callback already queued on the sensor thread can arrive just after
        // stop(); dropping it keeps a restart from ingesting a stale sample.
        if (!started) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> synchronized(lock) {
                event.values.copyInto(gyroValues, 0, 0, 3)
                gyroTimestamp = event.timestamp
                gyroSamples++
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR,
            -> synchronized(lock) {
                event.values.copyInto(quaternionValues, 0, 0, 4)
                quaternionTimestamp = event.timestamp
                orientationSamples++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val GYRO_PERIOD_US = 5_000
        const val ORIENTATION_PERIOD_US = 10_000
    }
}
