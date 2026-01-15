package expo.modules.audiorecorder

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Управляет состоянием телефона для обработки входящих/исходящих звонков
 */
class PhoneStateManager(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit
) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var isListening = false
    private var wasRecordingBeforeCall = false

    // Для Android 12+ (API 31+)
    private var telephonyCallback: Any? = null

    // Для Android 11 и ниже
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChange(state)
        }
    }

    private fun handleCallStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Входящий звонок (звонит)
                android.util.Log.w("PhoneState", "Incoming call - pausing recording")
                wasRecordingBeforeCall = true
                onCallStarted()
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Разговор начался (трубка снята)
                android.util.Log.w("PhoneState", "Call started - pausing recording")
                wasRecordingBeforeCall = true
                onCallStarted()
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                // Звонок завершен
                if (wasRecordingBeforeCall) {
                    android.util.Log.i("PhoneState", "Call ended - can resume recording")
                    wasRecordingBeforeCall = false
                    onCallEnded()
                }
            }
        }
    }

    /**
     * Начать отслеживание состояния телефона
     */
    fun startListening() {
        if (isListening) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+)
                startListeningModern()
            } else {
                // Android 11 и ниже
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE
                )
            }

            isListening = true
            android.util.Log.i("PhoneState", "Started listening to phone state (API ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            android.util.Log.e("PhoneState", "Failed to start listening", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startListeningModern() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }

        telephonyCallback = callback
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
    }

    /**
     * Остановить отслеживание
     */
    fun stopListening() {
        if (!isListening) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopListeningModern()
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }

            isListening = false
            wasRecordingBeforeCall = false
            android.util.Log.i("PhoneState", "Stopped listening to phone state")
        } catch (e: Exception) {
            android.util.Log.e("PhoneState", "Failed to stop listening", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopListeningModern() {
        (telephonyCallback as? TelephonyCallback)?.let {
            telephonyManager.unregisterTelephonyCallback(it)
        }
        telephonyCallback = null
    }

    /**
     * Проверяет идет ли сейчас звонок
     */
    fun isInCall(): Boolean {
        return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
    }
}