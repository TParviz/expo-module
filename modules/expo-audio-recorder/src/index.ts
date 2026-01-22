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
  PermissionResponse,
  RecordingEvent,
  RecordingOptions,
  RecordingResult,
  RecordingStateChangedEvent,
  RecordingStatus,
  Subscription
} from './ExpoAudioRecorder.types';
import ExpoAudioRecorderModule from './ExpoAudioRecorderModule';

export * from './ExpoAudioRecorder.types';

// Event emitter
const emitter = new EventEmitter<ExpoAudioRecorderModuleEvents>(ExpoAudioRecorderModule);

// ============================================================
// РАЗРЕШЕНИЯ
// ============================================================

/**
 * Запросить разрешение на использование микрофона
 */
export async function requestPermissions(): Promise<PermissionResponse> {
  return await ExpoAudioRecorder.requestPermissions();
}

// ============================================================
// ЗАПИСЬ
// ============================================================

/**
 * Начать запись
 * 
 * @param options - Параметры записи
 * @returns Путь к файлу записи
 * 
 * @example
 * ```ts
 * const filePath = await startRecording({
 *   sampleRate: 44100,
 *   bitRate: 128000,
 *   channels: 1,
 * });
 * ```
 */
export async function startRecording(options: RecordingOptions = {}): Promise<string> {
  return await ExpoAudioRecorder.startRecording(options);
}

/**
 * Остановить запись и получить результат
 * 
 * @returns Результат записи с путём к файлу, длительностью и размером
 * 
 * @example
 * ```ts
 * const result = await stopRecording();
 * console.log(`Записано ${result.duration} секунд в ${result.filePath}`);
 * ```
 */
export async function stopRecording(): Promise<RecordingResult> {
  return await ExpoAudioRecorder.stopRecording();
}

/**
 * Приостановить запись
 */
export async function pauseRecording(): Promise<void> {
  return await ExpoAudioRecorder.pauseRecording();
}

/**
 * Возобновить запись
 */
export async function resumeRecording(): Promise<void> {
  return await ExpoAudioRecorder.resumeRecording();
}

/**
 * Отменить запись без сохранения
 */
export async function cancelRecording(): Promise<void> {
  return await ExpoAudioRecorder.cancelRecording();
}

/**
 * Получить текущий статус записи
 */
export async function getStatus(): Promise<RecordingStatus> {
  return await ExpoAudioRecorder.getStatus();
}

// ============================================================
// RECOVERY
// ============================================================

/**
 * Проверить есть ли незавершённая запись после crash/force-kill
 */
export async function hasUnfinishedRecording(): Promise<boolean> {
  return await ExpoAudioRecorder.hasUnfinishedRecording();
}

/**
 * Восстановить незавершённую запись
 * 
 * @returns Результат восстановленной записи или null если нечего восстанавливать
 */
export async function recoverUnfinishedRecording(): Promise<RecordingResult | null> {
  return await ExpoAudioRecorder.recoverUnfinishedRecording();
}

// ============================================================
// СОБЫТИЯ
// ============================================================

/**
 * Подписаться на изменения состояния записи
 * 
 * @example
 * ```ts
 * const subscription = addRecordingStateListener((status) => {
 *   console.log(`State: ${status.state}, Duration: ${status.duration}s`);
 * });
 * 
 * // Отписаться
 * subscription.remove();
 * ```
 */
export function addRecordingStateListener(
  callback: (event: RecordingStateChangedEvent) => void
): Subscription {
  return emitter.addListener('onRecordingStateChanged', callback);
}

/**
 * Подписаться на аудио чанки (для real-time обработки)
 * 
 * Требует `enableChunking: true` в опциях записи.
 * 
 * @example
 * ```ts
 * await startRecording({ enableChunking: true, chunkDuration: 500 });
 * 
 * const subscription = addAudioChunkListener((chunk) => {
 *   // chunk.data - Float32 PCM данные
 *   // chunk.sampleRate - обычно 16000
 *   processAudio(chunk.data);
 * });
 * ```
 */
export function addAudioChunkListener(
  callback: (chunk: AudioChunk) => void
): Subscription {
  return emitter.addListener('onAudioChunk', callback);
}

/**
 * Подписаться на события записи (completed, canceled, error)
 */
export function addRecordingEventListener(
  callback: (event: RecordingEvent) => void
): Subscription {
  return emitter.addListener('onRecordingEvent', callback);
}

// ============================================================
// УТИЛИТЫ
// ============================================================

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