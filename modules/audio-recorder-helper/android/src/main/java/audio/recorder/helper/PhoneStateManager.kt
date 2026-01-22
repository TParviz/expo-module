package audio.recorder.helper

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Менеджер состояния телефонных звонков
 */
class PhoneStateManager(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit
) {
    companion object {
        private const val TAG = "PhoneStateManager"
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    
    private var isListening = false
    private var wasInCall = false

    // Для API 31+
    private var telephonyCallback: TelephonyCallback? = null

    // Для API < 31
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChange(state)
        }
    }

    /**
     * Начать отслеживание звонков
     */
    fun startListening() {
        if (isListening) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startListeningApi31()
        } else {
            startListeningLegacy()
        }
        
        isListening = true
        Log.d(TAG, "Phone state listening started")
    }

    /**
     * Остановить отслеживание
     */
    fun stopListening() {
        if (!isListening) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }

        isListening = false
        wasInCall = false
        Log.d(TAG, "Phone state listening stopped")
    }

    /**
     * Проверить идёт ли звонок
     */
    fun isInCall(): Boolean {
        return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
    }

    private fun startListeningApi31() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }

        val executor = Executor { it.run() }
        telephonyManager.registerTelephonyCallback(executor, telephonyCallback!!)
    }

    @Suppress("DEPRECATION")
    private fun startListeningLegacy() {
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleCallStateChange(state: Int) {
        Log.d(TAG, "Call state changed: $state")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!wasInCall) {
                    wasInCall = true
                    onCallStarted()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasInCall) {
                    wasInCall = false
                    onCallEnded()
                }
            }
        }
    }
}