package com.phonecontroller.app.input

import android.content.Context

/**
 * Persists touchpad tuning across app restarts. Deliberately small: full
 * per-profile settings arrive with the versioned profile format in
 * Milestone 4, at which point this becomes the global fallback.
 */
class TouchpadSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TouchpadConfig {
        val defaults = TouchpadConfig()
        return TouchpadConfig(
            sensitivityX = prefs.getFloat(KEY_SENS_X, defaults.sensitivityX),
            sensitivityY = prefs.getFloat(KEY_SENS_Y, defaults.sensitivityY),
            acceleration = prefs.getFloat(KEY_ACCEL, defaults.acceleration),
            smoothing = prefs.getFloat(KEY_SMOOTH, defaults.smoothing),
            invertX = prefs.getBoolean(KEY_INVERT_X, defaults.invertX),
            invertY = prefs.getBoolean(KEY_INVERT_Y, defaults.invertY),
            widthFraction = prefs.getFloat(KEY_WIDTH, defaults.widthFraction),
            tapToClick = prefs.getBoolean(KEY_TAP, defaults.tapToClick),
        ).sanitized()
    }

    fun save(config: TouchpadConfig) {
        val clean = config.sanitized()
        prefs.edit()
            .putFloat(KEY_SENS_X, clean.sensitivityX)
            .putFloat(KEY_SENS_Y, clean.sensitivityY)
            .putFloat(KEY_ACCEL, clean.acceleration)
            .putFloat(KEY_SMOOTH, clean.smoothing)
            .putBoolean(KEY_INVERT_X, clean.invertX)
            .putBoolean(KEY_INVERT_Y, clean.invertY)
            .putFloat(KEY_WIDTH, clean.widthFraction)
            .putBoolean(KEY_TAP, clean.tapToClick)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "touchpad-settings"
        const val KEY_SENS_X = "sens_x"
        const val KEY_SENS_Y = "sens_y"
        const val KEY_ACCEL = "accel"
        const val KEY_SMOOTH = "smoothing"
        const val KEY_INVERT_X = "invert_x"
        const val KEY_INVERT_Y = "invert_y"
        const val KEY_WIDTH = "width_fraction"
        const val KEY_TAP = "tap_to_click"
    }
}
