// ==================== Recording ====================

export type RecordingState = 'idle' | 'recording' | 'paused';

export type RecordingConfig = {
  sampleRate?: number;      // Default: 44100
  bitRate?: number;         // Default: 128000
  channels?: number;        // Default: 1 (mono)
  enableChunking?: boolean; // Default: false
  chunkDuration?: number;   // Duration in ms, default: 1000
  microphoneId?: number;    // ID микрофона (null = автовыбор)
};

export type RecordingResult = {
  filePath: string;
  duration: number;  // seconds
  fileSize: number;  // bytes
};

export type RecordingStatus = {
  state: RecordingState;
  filePath: string | null;
  duration: number;
  isRecording: boolean;
  isPaused: boolean;
  noiseLevel: number;  // dB, -160 to 0
};

// ==================== Recovery ====================

export type RecoveryResult = {
  filePath: string;
  originalPath: string;
  duration: number;
  fileSize: number;
  timestamp: number;
  recovered: boolean;
};

// ==================== Audio Chunk ====================

export type AudioChunk = {
  data: number[];      // PCM data (float, -1 to 1)
  sampleRate: number;  // 16000
  timestamp: number;
};

// ==================== Microphones ====================

export type MicrophoneInfo = {
  id: number;
  type: number;
  typeName: string;
  name: string;
  isDefault: boolean;
  address: string | null;
  channelCounts: number[];
  sampleRates: number[];
};

export type MicrophoneType =
  | 'BUILTIN_MIC'
  | 'BLUETOOTH_SCO'
  | 'WIRED_HEADSET'
  | 'USB_HEADSET'
  | 'USB_DEVICE'
  | 'TELEPHONY'
  | 'UNKNOWN';

// ==================== Permissions ====================

export type PermissionResponse = {
  granted: boolean;
  canRequest: boolean;
};

// ==================== Events ====================

/**
 * Типы событий записи
 */
export type RecordingEventType =
  // Запись
  | 'completed'           // Запись завершена
  | 'canceled'            // Запись отменена
  | 'error'               // Ошибка 
  | 'audioFileError'      // Ошибка файла
  
  // Микрофон
  | 'cantHearMicrophone'  // Тишина > 3 сек
  
  // Чанки
  | 'chunk'               // Новый чанк
  | 'chunkWasLost'        // Чанк потерян
  
  // Recovery
  | 'recoveryCompleted'   // Восстановление завершено
  
  // Microphones
  | 'microphonesDetected'       // Обнаружены микрофоны
  | 'microphoneSelected'        // Микрофон выбран для записи
  | 'microphoneSelectionFailed'; // Не удалось выбрать микрофон

/**
 * Событие записи
 */
export type RecordingEvent = {
  type: RecordingEventType;
  timestamp: number;
  
  // Для 'completed', 'recoveryCompleted'
  filePath?: string;
  duration?: number;
  fileSize?: number;
  
  // Для 'cantHearMicrophone'
  silenceDuration?: number;
  
  // Для 'chunk'
  chunkIndex?: number;
  chunkData?: number[];
  
  // Для 'chunkWasLost', 'audioFileError'
  error?: {
    code: string;
    message: string;
  };
  
  // Для 'microphonesDetected'
  microphones?: MicrophoneInfo[];
  activeMicrophone?: MicrophoneInfo;
  
  // Для 'microphoneSelected', 'microphoneSelectionFailed'
  id?: number;
  name?: string;
  reason?: string;
};

// ==================== Module Events ====================

export type ExpoAudioRecorderModuleEvents = {
  onRecordingStateChanged: (status: RecordingStatus) => void;
  onAudioChunk: (chunk: AudioChunk) => void;
  onRecordingError: (error: { code: string; message: string }) => void;
  onRecordingEvent: (event: RecordingEvent) => void;
};

// ==================== Subscription ====================

export interface Subscription {
  remove(): void;
}