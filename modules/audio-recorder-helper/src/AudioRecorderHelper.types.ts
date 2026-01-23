export type InterruptionSource =
  | 'PHONE_CALL'       // Телефонный звонок
  | 'VOIP_CALL'        // VoIP (WhatsApp, Telegram)
  | 'VOICE_ASSISTANT'  // Голосовой ассистент
  | 'VOICE_RECORDER'   // Другой диктофон
  | 'MUSIC_PLAYER'     // Музыка
  | 'VIDEO_PLAYER'     // Видео
  | 'GAME'             // Игра
  | 'NAVIGATION'       // Навигация
  | 'NOTIFICATION'     // Уведомление
  | 'UNKNOWN';

// ==================== Policies ====================

export type InterruptionPolicy =
  | 'PAUSE_AUTO'       // Пауза с автовозобновлением
  | 'CONTINUE_NOTIFY'  // Продолжить + уведомление
  | 'CONTINUE_SILENT'; // Продолжить молча

// ==================== Events ====================

export interface InterruptionInfo {
  source: InterruptionSource;
  policy: InterruptionPolicy;
  focusChange: number;
  message: string;
}

export interface InterruptionEndEvent {
  source: InterruptionSource;
}

export interface PhoneCallEvent {
  state: 'started' | 'ended';
}

export interface AudioFocusEvent {
  focusChange: number;
  focusName: string;
  hasFocus: boolean;
}

export interface AudioState {
  mode: number;
  modeName: string;
  isMusicActive: boolean;
  isSpeakerphoneOn: boolean;
  isBluetoothScoOn: boolean;
  isBluetoothA2dpOn: boolean;
  ringerMode: number;
}

// ==================== Bluetooth ====================

export interface BluetoothState {
  isConnected: boolean;
  isHeadset: boolean;
  isSpeaker: boolean;
  deviceName?: string;
}

// ==================== Microphones ====================

export interface MicrophoneInfo {
  id: number;
  type: number;
  typeName: string;
  name: string;
  isDefault: boolean;
  isSelected: boolean;
  address: string | null;
  channelCounts: number[];
  sampleRates: number[];
}

export type MicrophoneType =
  | 'BUILTIN_MIC'
  | 'BLUETOOTH_SCO'
  | 'WIRED_HEADSET'
  | 'USB_HEADSET'
  | 'USB_DEVICE'
  | 'TELEPHONY'
  | 'UNKNOWN';

/** Результат выбора микрофона */
export interface MicrophoneSelectionResult {
  success: boolean;
  microphone: MicrophoneInfo | null;
  error?: string;
}

/** Типы микрофонов для удобного выбора */
export const MicrophoneTypes = {
  BUILTIN_MIC: 15,       // AudioDeviceInfo.TYPE_BUILTIN_MIC
  BLUETOOTH_SCO: 7,      // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
  WIRED_HEADSET: 3,      // AudioDeviceInfo.TYPE_WIRED_HEADSET
  USB_HEADSET: 22,       // AudioDeviceInfo.TYPE_USB_HEADSET
  USB_DEVICE: 11,        // AudioDeviceInfo.TYPE_USB_DEVICE
  TELEPHONY: 18,         // AudioDeviceInfo.TYPE_TELEPHONY
} as const;

// ==================== Subscription ====================

export interface Subscription {
  remove(): void;
}

// ==================== Constants ====================

export const AudioFocusChange = {
  AUDIOFOCUS_LOSS: -1,
  AUDIOFOCUS_LOSS_TRANSIENT: -2,
  AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: -3,
  AUDIOFOCUS_GAIN: 1,
  AUDIOFOCUS_GAIN_TRANSIENT: 2,
  AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: 3,
  AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: 4,
} as const;

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

export const InterruptionPolicies = {
  PAUSE_AUTO: 'PAUSE_AUTO',
  CONTINUE_NOTIFY: 'CONTINUE_NOTIFY',
  CONTINUE_SILENT: 'CONTINUE_SILENT',
} as const;

// ==================== Module Events ====================

export type AudioRecorderHelperEvents = {
  onInterruption: (info: InterruptionInfo) => void;
  onInterruptionEnd: (event: InterruptionEndEvent) => void;
  onPhoneCall: (event: PhoneCallEvent) => void;
  onBluetoothChange: (state: BluetoothState) => void;
  onAudioFocusChanged: (event: AudioFocusEvent) => void;
  onAudioStateChanged: (state: AudioState) => void;
  onMicrophoneChanged: (info: MicrophoneInfo) => void;
};