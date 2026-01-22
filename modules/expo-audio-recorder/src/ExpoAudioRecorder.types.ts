/**
 * expo-audio-recorder - TypeScript Types
 * 
 * Чистый рекордер без логики прерываний.
 */

/**
 * Конфигурация записи
 */
export interface RecordingOptions {
  /** Sample rate в Hz (по умолчанию 44100) */
  sampleRate?: number;
  
  /** Bit rate в bps (по умолчанию 128000) */
  bitRate?: number;
  
  /** Количество каналов: 1 (mono) или 2 (stereo) */
  channels?: number;
  
  /** Включить стриминг аудио чанков */
  enableChunking?: boolean;
  
  /** Длительность чанка в мс (по умолчанию 1000) */
  chunkDuration?: number;
}

/**
 * Результат записи
 */
export interface RecordingResult {
  /** Путь к записанному файлу */
  filePath: string;
  
  /** Длительность в секундах */
  duration: number;
  
  /** Размер файла в байтах */
  fileSize: number;
}

/**
 * Текущий статус записи
 */
export interface RecordingStatus {
  /** Состояние: "idle", "recording", "paused" */
  state: 'idle' | 'recording' | 'paused';
  
  /** Путь к файлу (если запись активна) */
  filePath?: string;
  
  /** Текущая длительность в секундах */
  duration: number;
  
  /** Идёт ли запись */
  isRecording: boolean;
  
  /** На паузе ли запись */
  isPaused: boolean;
  
  /** Уровень шума в dB */
  noiseLevel: number;
}

/**
 * Аудио чанк для real-time обработки
 */
export interface AudioChunk {
  /** PCM данные в формате Float32 (-1.0 to 1.0) */
  data: number[];
  
  /** Sample rate чанка (обычно 16000) */
  sampleRate: number;
  
  /** Timestamp в миллисекундах */
  timestamp: number;
}

/**
 * Ответ на запрос разрешений
 */
export interface PermissionResponse {
  /** Разрешение получено */
  granted: boolean;
  
  /** Статус: "granted", "denied", "undetermined" */
  status: 'granted' | 'denied' | 'undetermined';
}

/**
 * Событие изменения состояния записи
 */
export interface RecordingStateChangedEvent {
  state: 'idle' | 'recording' | 'paused';
  filePath?: string;
  duration: number;
  isRecording: boolean;
  isPaused: boolean;
  noiseLevel: number;
}

/**
 * Событие записи
 */
export interface RecordingEvent {
  type: RecordingEventType;
  filePath?: string;
  duration?: number;
  fileSize?: number;
  message?: string;
}

/**
 * Типы событий записи
 */
export type RecordingEventType =
  | 'completed'     // Запись успешно завершена
  | 'canceled'      // Запись отменена
  | 'error';        // Ошибка записи

/**
 * Подписка на событие
 */
export interface Subscription {
  remove(): void;
}

export type ExpoAudioRecorderModuleEvents = {
  // Существующие события
  onRecordingStateChanged: (status: RecordingStatus) => void;
  onAudioChunk: (chunk: AudioChunk) => void;
  onRecordingError: (error: { code: string; message: string }) => void;
  
  // НОВОЕ: Унифицированное событие записи
  onRecordingEvent: (event: RecordingEvent) => void;
};