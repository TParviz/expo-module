/**
 * audio-recorder-helper
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
 * 
 * НЕ управляет записью - только мониторинг и события.
 * Используйте вместе с expo-audio-recorder.
 */

import { EventEmitter, NativeModulesProxy } from 'expo-modules-core';

import type {
  AudioRecorderHelperModuleEvents,
  BluetoothState,
  InterruptionEndEvent,
  InterruptionInfo,
  InterruptionSource,
  MicrophoneInfo,
  PhoneCallEvent,
  Subscription
} from './AudioRecorderHelper.types';
import AudioRecorderHelperModule from './AudioRecorderHelperModule';

export * from './AudioRecorderHelper.types';

// Нативный модуль
const ExpoAudioInterruption = NativeModulesProxy.ExpoAudioInterruption;

const emitter = new EventEmitter<AudioRecorderHelperModuleEvents>(AudioRecorderHelperModule);

// ============================================================
// МОНИТОРИНГ
// ============================================================

/**
 * Начать мониторинг прерываний
 * 
 * Запрашивает аудио фокус и начинает отслеживать прерывания.
 * 
 * @example
 * ```ts
 * await startMonitoring();
 * 
 * addInterruptionListener((info) => {
 *   if (info.policy === 'PAUSE') {
 *     await pauseRecording();
 *   }
 * });
 * ```
 */
export async function startMonitoring(): Promise<void> {
  return await ExpoAudioInterruption.startMonitoring();
}

/**
 * Остановить мониторинг прерываний
 */
export async function stopMonitoring(): Promise<void> {
  return await ExpoAudioInterruption.stopMonitoring();
}

/**
 * Проверить активен ли мониторинг
 */
export async function isMonitoring(): Promise<boolean> {
  return await ExpoAudioInterruption.isMonitoring();
}

// ============================================================
// АУДИО ФОКУС
// ============================================================

/**
 * Запросить аудио фокус
 * 
 * @returns true если фокус получен
 */
export async function requestAudioFocus(): Promise<boolean> {
  return await ExpoAudioInterruption.requestAudioFocus();
}

/**
 * Освободить аудио фокус
 */
export async function abandonAudioFocus(): Promise<void> {
  return await ExpoAudioInterruption.abandonAudioFocus();
}

/**
 * Проверить есть ли аудио фокус
 */
export async function hasAudioFocus(): Promise<boolean> {
  return await ExpoAudioInterruption.hasAudioFocus();
}

// ============================================================
// BLUETOOTH
// ============================================================

/**
 * Получить состояние Bluetooth аудио
 */
export async function getBluetoothState(): Promise<BluetoothState> {
  return await ExpoAudioInterruption.getBluetoothState();
}

/**
 * Проверить подключены ли Bluetooth наушники
 */
export async function hasBluetoothHeadset(): Promise<boolean> {
  const state = await getBluetoothState();
  return state.isHeadset;
}

// ============================================================
// МИКРОФОНЫ
// ============================================================

/**
 * Получить список доступных микрофонов
 */
export async function getAvailableMicrophones(): Promise<MicrophoneInfo[]> {
  return await ExpoAudioInterruption.getAvailableMicrophones();
}

// ============================================================
// УТИЛИТЫ
// ============================================================

/**
 * Проверить идёт ли телефонный звонок
 */
export async function isInPhoneCall(): Promise<boolean> {
  return await ExpoAudioInterruption.isInPhoneCall();
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
 * 
 * @example
 * ```ts
 * const subscription = addInterruptionListener((info) => {
 *   console.log(`Interruption: ${info.source}, policy: ${info.policy}`);
 *   
 *   if (info.policy === 'PAUSE') {
 *     // Рекомендуется приостановить запись
 *     await pauseRecording();
 *   } else if (info.policy === 'CONTINUE') {
 *     // Можно продолжить, но стоит уведомить пользователя
 *     showNotification(info.message);
 *   }
 * });
 * 
 * // Отписаться
 * subscription.remove();
 * ```
 */
export function addInterruptionListener(
  callback: (info: InterruptionInfo) => void
): Subscription {
  return emitter.addListener('onInterruption', callback);
}

/**
 * Подписаться на окончание прерываний
 * 
 * @example
 * ```ts
 * addInterruptionEndListener((event) => {
 *   console.log(`Interruption ended: ${event.source}`);
 *   
 *   // Можно возобновить запись
 *   if (shouldPauseRecording(event.source)) {
 *     await resumeRecording();
 *   }
 * });
 * ```
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

// ============================================================
// ХЕЛПЕРЫ ДЛЯ ИНТЕГРАЦИИ С РЕКОРДЕРОМ
// ============================================================

/**
 * Создать обработчик прерываний для рекордера
 * 
 * @example
 * ```ts
 * import * as Recorder from 'expo-audio-recorder-core';
 * import * as Interruption from 'expo-audio-interruption';
 * 
 * const handler = Interruption.createRecorderHandler({
 *   onPause: () => Recorder.pauseRecording(),
 *   onResume: () => Recorder.resumeRecording(),
 *   onNotify: (message) => showToast(message),
 * });
 * 
 * // Подключить
 * handler.start();
 * 
 * // Отключить
 * handler.stop();
 * ```
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
        if (info.policy === 'PAUSE') {
          pausedBy = info.source;
          await options.onPause();
        } else if (info.policy === 'CONTINUE' && options.onNotify) {
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