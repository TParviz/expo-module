package audio.recorder.helper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Менеджер таймера записи с ограничением по времени
 *
 * Особенности:
 * - Отсчёт времени с поддержкой паузы
 * - Событие при достижении лимита
 * - Предупреждение за N секунд до конца
 * - Тики каждую секунду для UI
 * - Корректная работа с паузой (время на паузе не считается)
 */
class RecordingTimer(
    private val onTick: (elapsedSeconds: Int, remainingSeconds: Int, maxDurationSeconds: Int) -> Unit,
    private val onWarning: (remainingSeconds: Int, elapsedSeconds: Int, maxDurationSeconds: Int) -> Unit,
    private val onLimitReached: (elapsedSeconds: Int, maxDurationSeconds: Int) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    // Конфигурация
    private var maxDurationSeconds: Int = 0
    private var warningBeforeEndSeconds: Int = 0

    // Состояние
    private var isRunning = false
    private var isPaused = false
    private var elapsedSeconds: Int = 0

    // Время для корректного подсчёта
    private var startTimeMs: Long = 0
    private var pauseStartTimeMs: Long = 0
    private var totalPausedMs: Long = 0

    // Флаг отправки предупреждения
    private var warningWasSent = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || isPaused) return

            // Вычисляем прошедшее время с учётом пауз
            val currentTimeMs = SystemClock.elapsedRealtime()
            val activeTimeMs = currentTimeMs - startTimeMs - totalPausedMs
            elapsedSeconds = (activeTimeMs / 1000).toInt()

            val remainingSeconds = maxDurationSeconds - elapsedSeconds

            // Отправляем тик
            onTick(elapsedSeconds, remainingSeconds, maxDurationSeconds)

            // Проверяем предупреждение
            if (!warningWasSent &&
                warningBeforeEndSeconds > 0 &&
                remainingSeconds <= warningBeforeEndSeconds &&
                remainingSeconds > 0
            ) {
                warningWasSent = true
                onWarning(remainingSeconds, elapsedSeconds, maxDurationSeconds)
            }

            // Проверяем достижение лимита
            if (remainingSeconds <= 0) {
                // Лимит достигнут - останавливаем и отправляем событие
                stopInternal()
                onLimitReached(elapsedSeconds, maxDurationSeconds)
                return
            }

            // Планируем следующий тик
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Запустить таймер
     *
     * @param maxDurationSeconds максимальная длительность в секундах
     * @param warningBeforeEndSeconds предупредить за X секунд до конца (0 = без предупреждения)
     */
    fun start(maxDurationSeconds: Int, warningBeforeEndSeconds: Int = 0) {
        if (isRunning) {
            stop()
        }

        this.maxDurationSeconds = maxDurationSeconds
        this.warningBeforeEndSeconds = warningBeforeEndSeconds

        // Сброс состояния
        elapsedSeconds = 0
        totalPausedMs = 0
        warningWasSent = false
        isPaused = false

        // Запоминаем время старта
        startTimeMs = SystemClock.elapsedRealtime()

        isRunning = true

        // Первый тик сразу
        onTick(0, maxDurationSeconds, maxDurationSeconds)

        // Запускаем тики
        handler.postDelayed(tickRunnable, 1000)
    }

    /**
     * Остановить таймер
     */
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        isRunning = false
        isPaused = false
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Пауза таймера
     * Время на паузе не учитывается в лимите
     */
    fun pause() {
        if (!isRunning || isPaused) return

        isPaused = true
        pauseStartTimeMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Возобновить после паузы
     */
    fun resume() {
        if (!isRunning || !isPaused) return

        // Добавляем время паузы к общему времени пауз
        totalPausedMs += SystemClock.elapsedRealtime() - pauseStartTimeMs

        isPaused = false

        // Возобновляем тики
        handler.post(tickRunnable)
    }

    /**
     * Получить текущий статус
     */
    fun getStatus(): RecordingTimerStatus {
        return RecordingTimerStatus(
            isActive = isRunning,
            isPaused = isPaused,
            elapsedSeconds = elapsedSeconds,
            remainingSeconds = if (isRunning) maxDurationSeconds - elapsedSeconds else 0,
            maxDurationSeconds = maxDurationSeconds
        )
    }

    /**
     * Проверить активен ли таймер
     */
    fun isActive(): Boolean = isRunning

    /**
     * Проверить на паузе ли таймер
     */
    fun isPaused(): Boolean = isPaused
}

/**
 * Статус таймера записи
 */
data class RecordingTimerStatus(
    val isActive: Boolean,
    val isPaused: Boolean,
    val elapsedSeconds: Int,
    val remainingSeconds: Int,
    val maxDurationSeconds: Int
)