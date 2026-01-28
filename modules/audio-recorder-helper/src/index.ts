/**
 * expo-audio-interruption
 * 
 * Модуль для обработки аудио прерываний в Expo/React Native.
 * 
 * Отслеживает:
 * - Телефонные звонки
 * - VoIP звонки (WhatsApp, Telegram, Zoom)
 * - Голосовые ассистенты (Google Assistant, Siri)
 * - Музыку и видео
 * - Навигацию
 * - Другие приложения использующие микрофон
 * - Ограничение времени записи
 * 
 * НЕ управляет записью - только мониторинг и события.
 * Используйте вместе с expo-audio-recorder-core.
 */

import { EventEmitter } from 'expo-modules-core';
import AudioRecorderHelper from './AudioRecorderHelperModule';

import type {
  AudioFocusEvent,
  AudioRecorderHelperEvents,
  AudioState,
  BluetoothPermissionStatus,
  BluetoothState,
  InterruptionEndEvent,
  InterruptionInfo,
  InterruptionSource,
  MicrophoneInfo,
  MicrophoneSelectionResult,
  PhoneCallEvent,
  RecordingTimeLimitOptions,
  RecordingTimeLimitReachedEvent,
  RecordingTimerStatus,
  RecordingTimerTickEvent,
  RecordingTimeWarningEvent,
  Subscription
} from './AudioRecorderHelper.types';

export * from './AudioRecorderHelper.types';

// Event emitter
const emitter = new EventEmitter<AudioRecorderHelperEvents>(AudioRecorderHelper);

// ============================================================
// МОНИТОРИНГ
// ============================================================

/**
 * Начать мониторинг прерываний
 */
export async function startMonitoring(): Promise<void> {
  return await AudioRecorderHelper.startMonitoring();
}

/**
 * Остановить мониторинг прерываний
 */
export async function stopMonitoring(): Promise<void> {
  return await AudioRecorderHelper.stopMonitoring();
}

/**
 * Проверить активен ли мониторинг
 */
export async function isMonitoring(): Promise<boolean> {
  return await AudioRecorderHelper.isMonitoring();
}

// ============================================================
// АУДИО ФОКУС
// ============================================================

/**
 * Запросить аудио фокус
 */
export async function requestAudioFocus(): Promise<boolean> {
  return await AudioRecorderHelper.requestAudioFocus();
}

/**
 * Освободить аудио фокус
 */
export async function abandonAudioFocus(): Promise<void> {
  return await AudioRecorderHelper.abandonAudioFocus();
}

/**
 * Проверить есть ли аудио фокус
 */
export async function hasAudioFocus(): Promise<boolean> {
  return await AudioRecorderHelper.hasAudioFocus();
}

/**
 * Получить текущее состояние аудио системы
 */
export async function getAudioState(): Promise<AudioState> {
  return await AudioRecorderHelper.getAudioState();
}

// ============================================================
// BLUETOOTH
// ============================================================

/**
 * Получить состояние Bluetooth аудио
 */
export async function getBluetoothState(): Promise<BluetoothState> {
  return await AudioRecorderHelper.getBluetoothState();
}

/**
 * Проверить подключены ли Bluetooth наушники
 */
export async function hasBluetoothHeadset(): Promise<boolean> {
  const state = await getBluetoothState();
  return state.isHeadset;
}

/**
 * Проверить наличие разрешений Bluetooth
 */
export async function hasBluetoothPermission(): Promise<boolean> {
  return await AudioRecorderHelper.hasBluetoothPermission();
}

/**
 * Получить статус разрешений Bluetooth
 * 
 * @returns объект с hasPermission и списком отсутствующих разрешений
 * 
 * @example
 * ```ts
 * const status = await getBluetoothPermissionStatus();
 * if (!status.hasPermission) {
 *   console.log('Missing:', status.missingPermissions);
 *   // Запросить разрешения через PermissionsAndroid или expo-permissions
 * }
 * ```
 */
export async function getBluetoothPermissionStatus(): Promise<BluetoothPermissionStatus> {
  return await AudioRecorderHelper.getBluetoothPermissionStatus();
}

/**
 * Получить список необходимых разрешений для Bluetooth
 * 
 * На Android 12+ возвращает: ['android.permission.BLUETOOTH_CONNECT', 'android.permission.BLUETOOTH_SCAN']
 * На Android 11 и ниже: ['android.permission.BLUETOOTH', 'android.permission.BLUETOOTH_ADMIN']
 */
export async function getBluetoothRequiredPermissions(): Promise<string[]> {
  return await AudioRecorderHelper.getBluetoothRequiredPermissions();
}

/**
 * Инициализировать Bluetooth после получения разрешений
 * 
 * Вызывать после успешного запроса разрешений через PermissionsAndroid
 * 
 * @example
 * ```ts
 * const permissions = await getBluetoothRequiredPermissions();
 * const results = await PermissionsAndroid.requestMultiple(permissions);
 * 
 * const allGranted = Object.values(results).every(r => r === 'granted');
 * if (allGranted) {
 *   await initializeBluetoothAfterPermission();
 *   // Теперь Bluetooth функции будут работать
 * }
 * ```
 */
export async function initializeBluetoothAfterPermission(): Promise<void> {
  return await AudioRecorderHelper.initializeBluetoothAfterPermission();
}

// ============================================================
// МИКРОФОНЫ
// ============================================================

/**
 * Получить список доступных микрофонов
 */
export async function getAvailableMicrophones(): Promise<MicrophoneInfo[]> {
  return await AudioRecorderHelper.getAvailableMicrophones();
}

/**
 * Получить активный микрофон (выбранный или автоматически определённый)
 */
export async function getActiveMicrophone(): Promise<MicrophoneInfo | null> {
  return await AudioRecorderHelper.getActiveMicrophone();
}

/**
 * Получить вручную выбранный микрофон (null если автовыбор)
 */
export async function getSelectedMicrophone(): Promise<MicrophoneInfo | null> {
  return await AudioRecorderHelper.getSelectedMicrophone();
}

/**
 * Выбрать микрофон по ID
 * 
 * @param id - ID микрофона или null для автовыбора
 * @returns результат выбора
 * 
 * @example
 * ```ts
 * // Выбрать конкретный микрофон
 * const mics = await getAvailableMicrophones();
 * const result = await selectMicrophone(mics[1].id);
 * 
 * // Сбросить на автовыбор
 * await selectMicrophone(null);
 * ```
 */
export async function selectMicrophone(id: number | null): Promise<MicrophoneSelectionResult> {
  return await AudioRecorderHelper.selectMicrophone(id);
}

/**
 * Выбрать микрофон по типу
 * 
 * @param type - тип микрофона (используйте MicrophoneTypes)
 * @returns результат выбора
 * 
 * @example
 * ```ts
 * import { selectMicrophoneByType, MicrophoneTypes } from 'audio-recorder-helper';
 * 
 * // Выбрать Bluetooth микрофон
 * await selectMicrophoneByType(MicrophoneTypes.BLUETOOTH_SCO);
 * 
 * // Выбрать встроенный микрофон
 * await selectMicrophoneByType(MicrophoneTypes.BUILTIN_MIC);
 * ```
 */
export async function selectMicrophoneByType(type: number): Promise<MicrophoneSelectionResult> {
  return await AudioRecorderHelper.selectMicrophoneByType(type);
}

/**
 * Сбросить выбор микрофона на автоматический
 * 
 * @returns результат (автоматически выбранный микрофон)
 */
export async function resetMicrophoneSelection(): Promise<MicrophoneSelectionResult> {
  return await AudioRecorderHelper.resetMicrophoneSelection();
}

/**
 * Проверить выбран ли микрофон вручную
 */
export async function isManualMicrophoneSelection(): Promise<boolean> {
  return await AudioRecorderHelper.isManualMicrophoneSelection();
}

/**
 * Проверить доступен ли Bluetooth микрофон
 */
export async function hasBluetoothMicrophone(): Promise<boolean> {
  const mics = await getAvailableMicrophones();
  return mics.some(m => m.typeName === 'BLUETOOTH_SCO' || m.type === 7);
}

/**
 * Проверить доступен ли проводной микрофон
 */
export async function hasWiredMicrophone(): Promise<boolean> {
  const mics = await getAvailableMicrophones();
  return mics.some(m => 
    m.typeName === 'WIRED_HEADSET' || 
    m.typeName === 'USB_HEADSET' ||
    m.type === 3 || 
    m.type === 22
  );
}

// ============================================================
// RECORDING TIME LIMIT
// ============================================================

/**
 * Запустить таймер ограничения записи
 * 
 * @param options - параметры таймера
 * 
 * @example
 * ```ts
 * // Ограничить запись 5 минутами, предупредить за 30 секунд
 * await startRecordingTimer({
 *   maxDurationSeconds: 300,
 *   warningBeforeEndSeconds: 30
 * });
 * 
 * // Подписаться на события
 * addRecordingTimeLimitListener((event) => {
 *   console.log('Время записи истекло!', event.elapsedSeconds);
 *   await stopRecording(); // Остановить запись
 * });
 * ```
 */
export async function startRecordingTimer(options: RecordingTimeLimitOptions): Promise<void> {
  return await AudioRecorderHelper.startRecordingTimer(
    options.maxDurationSeconds,
    options.warningBeforeEndSeconds ?? 0
  );
}

/**
 * Остановить таймер записи
 * 
 * Вызывается автоматически при достижении лимита,
 * но можно вызвать вручную при остановке записи
 */
export async function stopRecordingTimer(): Promise<void> {
  return await AudioRecorderHelper.stopRecordingTimer();
}

/**
 * Пауза таймера (при паузе записи)
 * 
 * Время на паузе не учитывается в лимите
 */
export async function pauseRecordingTimer(): Promise<void> {
  return await AudioRecorderHelper.pauseRecordingTimer();
}

/**
 * Возобновить таймер после паузы
 */
export async function resumeRecordingTimer(): Promise<void> {
  return await AudioRecorderHelper.resumeRecordingTimer();
}

/**
 * Получить текущий статус таймера
 */
export async function getRecordingTimerStatus(): Promise<RecordingTimerStatus> {
  return await AudioRecorderHelper.getRecordingTimerStatus();
}

/**
 * Проверить активен ли таймер
 */
export async function isRecordingTimerActive(): Promise<boolean> {
  return await AudioRecorderHelper.isRecordingTimerActive();
}

// ============================================================
// УТИЛИТЫ
// ============================================================

/**
 * Проверить идёт ли телефонный звонок
 */
export async function isInPhoneCall(): Promise<boolean> {
  return await AudioRecorderHelper.isInPhoneCall();
}

/**
 * Проверить есть ли активное воспроизведение медиа
 */
export async function hasActiveMediaPlayback(): Promise<boolean> {
  return await AudioRecorderHelper.hasActiveMediaPlayback();
}

/**
 * Определить нужно ли паузить запись для данного источника
 */
export function shouldPauseRecording(source: InterruptionSource): boolean {
  return source === 'PHONE_CALL' ||
         source === 'VOIP_CALL' ||
         source === 'VOICE_ASSISTANT' ||
         source === 'VOICE_RECORDER';
}

/**
 * Определить можно ли продолжать запись
 */
export function canContinueRecording(source: InterruptionSource): boolean {
  return source === 'MUSIC_PLAYER' ||
         source === 'VIDEO_PLAYER' ||
         source === 'GAME' ||
         source === 'NAVIGATION' ||
         source === 'NOTIFICATION' ||
         source === 'UNKNOWN';
}

// ============================================================
// СОБЫТИЯ
// ============================================================

/**
 * Подписаться на прерывания
 */
export function addInterruptionListener(
  callback: (info: InterruptionInfo) => void
): Subscription {
  return emitter.addListener('onInterruption', callback);
}

/**
 * Подписаться на окончание прерываний
 */
export function addInterruptionEndListener(
  callback: (event: InterruptionEndEvent) => void
): Subscription {
  return emitter.addListener('onInterruptionEnd', callback);
}

/**
 * Подписаться на события телефонных звонков
 */
export function addPhoneCallListener(
  callback: (event: PhoneCallEvent) => void
): Subscription {
  return emitter.addListener('onPhoneCall', callback);
}

/**
 * Подписаться на изменения аудио фокуса
 */
export function addAudioFocusListener(
  callback: (event: AudioFocusEvent) => void
): Subscription {
  return emitter.addListener('onAudioFocusChanged', callback);
}

/**
 * Подписаться на изменения микрофона
 */
export function addMicrophoneChangedListener(
  callback: (info: MicrophoneInfo) => void
): Subscription {
  return emitter.addListener('onMicrophoneChanged', callback);
}

/**
 * Подписаться на достижение лимита времени записи
 * 
 * ВАЖНО: При получении этого события запись должна быть остановлена!
 * 
 * @example
 * ```ts
 * const sub = addRecordingTimeLimitListener(async (event) => {
 *   console.log(`Записано ${event.elapsedSeconds} секунд`);
 *   await stopRecording(); // Остановить запись
 * });
 * ```
 */
export function addRecordingTimeLimitListener(
  callback: (event: RecordingTimeLimitReachedEvent) => void
): Subscription {
  return emitter.addListener('onRecordingTimeLimitReached', callback);
}

/**
 * Подписаться на предупреждение о скором окончании времени
 * 
 * @example
 * ```ts
 * const sub = addRecordingTimeWarningListener((event) => {
 *   showToast(`Осталось ${event.remainingSeconds} секунд`);
 * });
 * ```
 */
export function addRecordingTimeWarningListener(
  callback: (event: RecordingTimeWarningEvent) => void
): Subscription {
  return emitter.addListener('onRecordingTimeWarning', callback);
}

/**
 * Подписаться на тики таймера (каждую секунду)
 * 
 * Полезно для отображения оставшегося времени в UI
 * 
 * @example
 * ```ts
 * const sub = addRecordingTimerTickListener((event) => {
 *   setRemainingTime(event.remainingSeconds);
 * });
 * ```
 */
export function addRecordingTimerTickListener(
  callback: (event: RecordingTimerTickEvent) => void
): Subscription {
  return emitter.addListener('onRecordingTimerTick', callback);
}

/**
 * Создать обработчик прерываний для рекордера
 */
export function createRecorderHandler(options: {
  onPause: () => void | Promise<void>;
  onResume: () => void | Promise<void>;
  onNotify?: (message: string) => void;
}): { start: () => void; stop: () => void } {
  let interruptionSub: Subscription | null = null;
  let endSub: Subscription | null = null;
  let pausedBy: InterruptionSource | null = null;

  return {
    start: () => {
      interruptionSub = addInterruptionListener(async (info) => {
        if (info.policy === 'PAUSE_AUTO') {
          pausedBy = info.source;
          await options.onPause();
        } else if (info.policy === 'CONTINUE_NOTIFY' && options.onNotify) {
          options.onNotify(info.message);
        }
      });

      endSub = addInterruptionEndListener(async (event) => {
        if (pausedBy === event.source) {
          pausedBy = null;
          await options.onResume();
        }
      });

      startMonitoring();
    },

    stop: () => {
      interruptionSub?.remove();
      endSub?.remove();
      interruptionSub = null;
      endSub = null;
      pausedBy = null;
      stopMonitoring();
    },
  };
}

/**
 * Создать обработчик записи с ограничением времени
 * 
 * Удобный хелпер объединяющий прерывания и лимит времени
 * 
 * @example
 * ```ts
 * const handler = createTimeLimitedRecorderHandler({
 *   maxDurationSeconds: 300, // 5 минут
 *   warningBeforeEndSeconds: 30,
 *   onPause: () => recorder.pause(),
 *   onResume: () => recorder.resume(),
 *   onStop: () => recorder.stop(),
 *   onWarning: (remaining) => toast(`Осталось ${remaining} сек`),
 *   onTick: (elapsed, remaining) => updateUI(elapsed, remaining),
 * });
 * 
 * // При старте записи
 * handler.start();
 * 
 * // При остановке записи
 * handler.stop();
 * ```
 */
export function createTimeLimitedRecorderHandler(options: {
  maxDurationSeconds: number;
  warningBeforeEndSeconds?: number;
  onPause: () => void | Promise<void>;
  onResume: () => void | Promise<void>;
  onStop: () => void | Promise<void>;
  onWarning?: (remainingSeconds: number) => void;
  onTick?: (elapsedSeconds: number, remainingSeconds: number) => void;
  onNotify?: (message: string) => void;
}): { 
  start: () => Promise<void>; 
  stop: () => Promise<void>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
} {
  let interruptionSub: Subscription | null = null;
  let endSub: Subscription | null = null;
  let limitSub: Subscription | null = null;
  let warningSub: Subscription | null = null;
  let tickSub: Subscription | null = null;
  let pausedBy: InterruptionSource | null = null;

  return {
    start: async () => {
      // Подписки на прерывания
      interruptionSub = addInterruptionListener(async (info) => {
        if (info.policy === 'PAUSE_AUTO') {
          pausedBy = info.source;
          await pauseRecordingTimer(); // Пауза таймера
          await options.onPause();
        } else if (info.policy === 'CONTINUE_NOTIFY' && options.onNotify) {
          options.onNotify(info.message);
        }
      });

      endSub = addInterruptionEndListener(async (event) => {
        if (pausedBy === event.source) {
          pausedBy = null;
          await resumeRecordingTimer(); // Возобновить таймер
          await options.onResume();
        }
      });

      // Подписки на таймер
      limitSub = addRecordingTimeLimitListener(async (event) => {
        await options.onStop();
      });

      if (options.onWarning) {
        warningSub = addRecordingTimeWarningListener((event) => {
          options.onWarning!(event.remainingSeconds);
        });
      }

      if (options.onTick) {
        tickSub = addRecordingTimerTickListener((event) => {
          options.onTick!(event.elapsedSeconds, event.remainingSeconds);
        });
      }

      // Старт мониторинга и таймера
      await startMonitoring();
      await startRecordingTimer({
        maxDurationSeconds: options.maxDurationSeconds,
        warningBeforeEndSeconds: options.warningBeforeEndSeconds
      });
    },

    stop: async () => {
      // Отписки
      interruptionSub?.remove();
      endSub?.remove();
      limitSub?.remove();
      warningSub?.remove();
      tickSub?.remove();
      interruptionSub = null;
      endSub = null;
      limitSub = null;
      warningSub = null;
      tickSub = null;
      pausedBy = null;

      // Стоп
      await stopRecordingTimer();
      await stopMonitoring();
    },

    pause: async () => {
      await pauseRecordingTimer();
    },

    resume: async () => {
      await resumeRecordingTimer();
    }
  };
}

/**
 * Получить человекочитаемое описание источника прерывания
 */
export function getSourceDescription(source: InterruptionSource): string {
  switch (source) {
    case 'PHONE_CALL': return 'Телефонный звонок';
    case 'VOIP_CALL': return 'VoIP звонок';
    case 'VOICE_ASSISTANT': return 'Голосовой ассистент';
    case 'VOICE_RECORDER': return 'Другой диктофон';
    case 'MUSIC_PLAYER': return 'Музыкальный плеер';
    case 'VIDEO_PLAYER': return 'Видео';
    case 'GAME': return 'Игра';
    case 'NAVIGATION': return 'Навигация';
    case 'NOTIFICATION': return 'Уведомление';
    default: return 'Неизвестно';
  }
}

/**
 * Получить человекочитаемое название типа микрофона
 */
export function getMicrophoneTypeName(type: number): string {
  switch (type) {
    case 15: return 'Встроенный микрофон';
    case 7: return 'Bluetooth гарнитура';
    case 3: return 'Проводная гарнитура';
    case 22: return 'USB гарнитура';
    case 11: return 'USB устройство';
    case 18: return 'Телефония';
    default: return 'Неизвестный';
  }
}

/**
 * Форматировать секунды в строку MM:SS
 */
export function formatDuration(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
}