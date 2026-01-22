
/**
 * Источники прерываний
 */
export type InterruptionSource =
  | 'PHONE_CALL'       // Телефонный звонок
  | 'VOIP_CALL'        // VoIP (WhatsApp, Telegram, Zoom)
  | 'VOICE_ASSISTANT'  // Голосовой ассистент
  | 'VOICE_RECORDER'   // Другой диктофон
  | 'MUSIC_PLAYER'     // Музыкальный плеер
  | 'VIDEO_PLAYER'     // Видео приложение
  | 'GAME'             // Игра
  | 'NAVIGATION'       // Навигация
  | 'NOTIFICATION'     // Уведомление
  | 'UNKNOWN';         // Неизвестно

/**
 * Политики обработки прерываний
 */
export type InterruptionPolicy =
  | 'PAUSE'     // Рекомендуется пауза
  | 'CONTINUE'  // Можно продолжить
  | 'IGNORE';   // Игнорировать

/**
 * Информация о прерывании
 */
export interface InterruptionInfo {
  /** Источник прерывания */
  source: InterruptionSource;
  
  /** Рекомендуемая политика */
  policy: InterruptionPolicy;
  
  /** Тип потери фокуса (Android AudioManager константа) */
  focusChange: number;
  
  /** Человекочитаемое сообщение */
  message: string;
}

/**
 * Событие окончания прерывания
 */
export interface InterruptionEndEvent {
  /** Источник прерывания который закончился */
  source: InterruptionSource;
}

/**
 * Событие телефонного звонка
 */
export interface PhoneCallEvent {
  /** Состояние: "started" или "ended" */
  state: 'started' | 'ended';
}

/**
 * Состояние Bluetooth
 */
export interface BluetoothState {
  /** Подключено ли Bluetooth устройство */
  isConnected: boolean;
  
  /** Это наушники с микрофоном */
  isHeadset: boolean;
  
  /** Это колонка без микрофона */
  isSpeaker: boolean;
  
  /** Название устройства */
  deviceName?: string;
}

/**
 * Информация о микрофоне
 */
export interface MicrophoneInfo {
  /** ID устройства */
  id: number;
  
  /** Тип устройства (AudioDeviceInfo константа) */
  type: number;
  
  /** Название типа */
  typeName: string;
  
  /** Название устройства */
  name: string;
  
  /** Это микрофон по умолчанию */
  isDefault: boolean;
}

/**
 * Подписка на событие
 */
export interface Subscription {
  remove(): void;
}

/**
 * Константы типов потери фокуса
 */
export const AudioFocusChange = {
  /** Постоянная потеря фокуса */
  AUDIOFOCUS_LOSS: -1,
  
  /** Временная потеря фокуса */
  AUDIOFOCUS_LOSS_TRANSIENT: -2,
  
  /** Можно приглушить */
  AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: -3,
  
  /** Фокус получен */
  AUDIOFOCUS_GAIN: 1,
} as const;

/**
 * Константы источников прерываний
 */
export const InterruptionSources = {
  PHONE_CALL: 'PHONE_CALL',
  VOIP_CALL: 'VOIP_CALL',
  VOICE_ASSISTANT: 'VOICE_ASSISTANT',
  VOICE_RECORDER: 'VOICE_RECORDER',
  MUSIC_PLAYER: 'MUSIC_PLAYER',
  VIDEO_PLAYER: 'VIDEO_PLAYER',
  GAME: 'GAME',
  NAVIGATION: 'NAVIGATION',
  NOTIFICATION: 'NOTIFICATION',
  UNKNOWN: 'UNKNOWN',
} as const;

/**
 * Константы политик
 */
export const InterruptionPolicies = {
  PAUSE: 'PAUSE',
  CONTINUE: 'CONTINUE',
  IGNORE: 'IGNORE',
} as const;

export type AudioRecorderHelperModuleEvents = {
  // Существующие события
  // onRecordingStateChanged: (status: RecordingStatus) => void;
  // onAudioChunk: (chunk: AudioChunk) => void;
  // onRecordingError: (error: { code: string; message: string }) => void;
  
  // // НОВОЕ: Унифицированное событие записи
  // onRecordingEvent: (event: RecordingEvent) => void;

  onInterruption: (interruptionInfo : InterruptionInfo) => void;
  onInterruptionEnd: (event: InterruptionEndEvent) => void;
  onPhoneCall: (event: PhoneCallEvent) => void;
};