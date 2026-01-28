import { NativeModule, requireNativeModule } from 'expo-modules-core';

import {
  AudioRecorderHelperEvents,
  AudioState,
  BluetoothPermissionStatus,
  BluetoothState,
  MicrophoneInfo,
  MicrophoneSelectionResult,
} from './AudioRecorderHelper.types';
  
declare class AudioRecorderHelperModule extends NativeModule<AudioRecorderHelperEvents> {
  // === Мониторинг ===
  startMonitoring(): Promise<void>;
  stopMonitoring(): Promise<void>;
  isMonitoring(): Promise<boolean>;

  // === Аудио фокус ===
  requestAudioFocus(): Promise<boolean>;
  abandonAudioFocus(): Promise<void>;
  hasAudioFocus(): Promise<boolean>;
  getAudioState(): Promise<AudioState>;

  // === Bluetooth ===
  getBluetoothState(): Promise<BluetoothState>;
  
  /** Проверить наличие разрешений Bluetooth */
  hasBluetoothPermission(): Promise<boolean>;
  
  /** Получить статус разрешений Bluetooth */
  getBluetoothPermissionStatus(): Promise<BluetoothPermissionStatus>;
  
  /** Получить список необходимых разрешений для Bluetooth */
  getBluetoothRequiredPermissions(): Promise<string[]>;
  
  /** Инициализировать Bluetooth после получения разрешений */
  initializeBluetoothAfterPermission(): Promise<void>;

  // === Микрофоны ===
  getAvailableMicrophones(): Promise<MicrophoneInfo[]>;
  getActiveMicrophone(): Promise<MicrophoneInfo | null>;
  getSelectedMicrophone(): Promise<MicrophoneInfo | null>;
  
  /** Выбрать микрофон по ID (null для автовыбора) */
  selectMicrophone(id: number | null): Promise<MicrophoneSelectionResult>;
  
  /** Выбрать микрофон по типу */
  selectMicrophoneByType(type: number): Promise<MicrophoneSelectionResult>;
  
  /** Сбросить на автовыбор */
  resetMicrophoneSelection(): Promise<MicrophoneSelectionResult>;
  
  /** Проверить выбран ли микрофон вручную */
  isManualMicrophoneSelection(): Promise<boolean>;

  // === Утилиты ===
  isInPhoneCall(): Promise<boolean>;
  hasActiveMediaPlayback(): Promise<boolean>;
}

export default requireNativeModule<AudioRecorderHelperModule>('AudioRecorderHelper');