/**
 * expo-audio-recorder-core
 * 
 * Чистый аудио рекордер для Expo/React Native.
 * Записывает аудио в M4A формат с поддержкой паузы, стриминга и recovery.
 * 
 * НЕ содержит логики обработки прерываний - только запись.
 * Для обработки прерываний используйте модуль expo-audio-interruption.
 */
import { EventEmitter } from 'expo-modules-core';
import ExpoAudioRecorder from './ExpoAudioRecorderModule';

import type {
  AudioChunk,
  ExpoAudioRecorderModuleEvents,
  MicrophoneInfo,
  MicrophoneType,
  PermissionResponse,
  RecordingConfig,
  RecordingEvent,
  RecordingEventType,
  RecordingResult,
  RecordingState,
  RecordingStatus,
  RecoveryResult,
  Subscription
} from './ExpoAudioRecorder.types';

// Re-export types
export type {
  AudioChunk,
  MicrophoneInfo,
  MicrophoneType,
  PermissionResponse,
  RecordingConfig,
  RecordingEvent,
  RecordingEventType,
  RecordingResult,
  RecordingState,
  RecordingStatus,
  RecoveryResult,
  Subscription
};

// Event emitter
const emitter = new EventEmitter<ExpoAudioRecorderModuleEvents>(ExpoAudioRecorder);

// ==================== Permissions ====================

/**
 * Запросить разрешение на запись аудио
 */
export async function requestPermissions(): Promise<PermissionResponse> {
  return ExpoAudioRecorder.requestPermissions();
}

// ==================== Core Recording ====================

/**
 * Начать запись
 * 
 * @param config Конфигурация записи
 * @returns Путь к файлу записи
 */
export async function startRecording(config: RecordingConfig = {}): Promise<string> {
  return ExpoAudioRecorder.startRecording(config);
}

/**
 * Остановить запись и сохранить файл
 * 
 * @returns Результат записи (путь, длительность, размер)
 */
export async function stopRecording(): Promise<RecordingResult> {
  return ExpoAudioRecorder.stopRecording();
}

/**
 * Отменить запись без сохранения
 */
export async function cancelRecording(): Promise<void> {
  return ExpoAudioRecorder.cancelRecording();
}

/**
 * Поставить запись на паузу
 */
export async function pauseRecording(): Promise<void> {
  return ExpoAudioRecorder.pauseRecording();
}

/**
 * Возобновить запись
 */
export async function resumeRecording(): Promise<void> {
  return ExpoAudioRecorder.resumeRecording();
}

/**
 * Получить текущий статус записи
 */
export async function getStatus(): Promise<RecordingStatus> {
  return ExpoAudioRecorder.getStatus();
}

// ==================== Recovery ====================

/**
 * Проверить есть ли незавершённая запись
 * 
 * Вызывайте при старте приложения для показа UI восстановления
 */
export async function hasUnfinishedRecording(): Promise<boolean> {
  return ExpoAudioRecorder.hasUnfinishedRecording();
}

/**
 * Восстановить незавершённую запись
 * 
 * Конвертирует сохранённые PCM данные в M4A файл
 * 
 * @returns Результат восстановления или null
 */
export async function recoverUnfinishedRecording(): Promise<RecoveryResult | null> {
  return ExpoAudioRecorder.recoverUnfinishedRecording();
}

// ==================== Microphones ====================

/**
 * Получить список всех доступных микрофонов
 * 
 * Возвращает информацию о:
 * - Встроенном микрофоне
 * - Bluetooth гарнитуре (SCO)
 * - Проводной гарнитуре
 * - USB микрофоне
 */
export async function getAvailableMicrophones(): Promise<MicrophoneInfo[]> {
  return ExpoAudioRecorder.getAvailableMicrophones();
}

/**
 * Получить текущий активный микрофон
 * 
 * Возвращает микрофон с наивысшим приоритетом:
 * Bluetooth SCO > Wired > USB > Built-in
 */
export async function getActiveMicrophone(): Promise<MicrophoneInfo | null> {
  return ExpoAudioRecorder.getActiveMicrophone();
}

// ==================== Utilities ====================

/**
 * Проверить находимся ли в grace period (первая секунда записи)
 */
export async function isInGracePeriod(): Promise<boolean> {
  return ExpoAudioRecorder.isInGracePeriod();
}

// ==================== Event Listeners ====================

/**
 * Подписаться на изменения состояния записи
 */
export function addRecordingStateListener(
  listener: (status: RecordingStatus) => void
): Subscription {
  return emitter.addListener('onRecordingStateChanged', listener);
}

/**
 * Подписаться на аудио чанки (для стриминга)
 */
export function addAudioChunkListener(
  listener: (chunk: AudioChunk) => void
): Subscription {
  return emitter.addListener('onAudioChunk', listener);
}

/**
 * Подписаться на ошибки записи
 */
export function addRecordingErrorListener(
  listener: (error: { code: string; message: string }) => void
): Subscription {
  return emitter.addListener('onRecordingError', listener);
}

/**
 * Подписаться на все события записи
 * 
 * События:
 * - completed: Запись завершена
 * - canceled: Запись отменена
 * - audioFileError: Ошибка файла
 * - cantHearMicrophone: Микрофон не слышит (тишина > 3 сек)
 * - chunk: Новый аудио чанк
 * - chunkWasLost: Чанк потерян
 * - recoveryCompleted: Восстановление завершено
 * - microphonesDetected: Обнаружены микрофоны
 * - microphoneSelected: Микрофон выбран
 * - microphoneSelectionFailed: Не удалось выбрать микрофон
 */
export function addRecordingEventListener(
  listener: (event: RecordingEvent) => void
): Subscription {
  return emitter.addListener('onRecordingEvent', listener);
}

/**
 * Подписаться на конкретный тип события
 */
export function addRecordingEventListenerByType(
  eventType: RecordingEventType,
  listener: (event: RecordingEvent) => void
): Subscription {
  return emitter.addListener('onRecordingEvent', (event: RecordingEvent) => {
    if (event.type === eventType) {
      listener(event);
    }
  });
}

// ==================== Convenience Helpers ====================

/**
 * Инициализация модуля при старте приложения
 * 
 * Пример использования:
 * ```typescript
 * useEffect(() => {
 *   initializeRecorder().then(recovery => {
 *     if (recovery) {
 *       // Показать UI: "Восстановлена запись X минут"
 *     }
 *   });
 * }, []);
 * ```
 */
export async function initializeRecorder(): Promise<RecoveryResult | null> {
  const hasUnfinished = await hasUnfinishedRecording();
  
  if (hasUnfinished) {
    return recoverUnfinishedRecording();
  }
  
  return null;
}

/**
 * Проверить идёт ли сейчас запись
 */
export async function isRecording(): Promise<boolean> {
  const status = await getStatus();
  return status.isRecording;
}

/**
 * Проверить на паузе ли запись
 */
export async function isPaused(): Promise<boolean> {
  const status = await getStatus();
  return status.isPaused;
}

/**
 * Получить текущую длительность записи в секундах
 */
export async function getDuration(): Promise<number> {
  const status = await getStatus();
  return status.duration;
}

/**
 * Получить текущий уровень шума в dB
 */
export async function getNoiseLevel(): Promise<number> {
  const status = await getStatus();
  return status.noiseLevel;
}