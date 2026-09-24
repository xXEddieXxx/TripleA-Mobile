package org.triplea.mobile.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibrations for events the player might otherwise miss while the AI plays. */
object Haptics {
    private fun vibrate(effect: VibrationEffect) {
        runCatching {
            val context = AppServices.appContext
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                // Android 10 and 11: the vibrator manager does not exist yet
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(effect)
        }
    }

    /** A battle with one of the player's nations begins. */
    fun battle() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 60, 60), -1))

    /** It is the player's turn. */
    fun yourTurn() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40, 80, 120), -1))
}
