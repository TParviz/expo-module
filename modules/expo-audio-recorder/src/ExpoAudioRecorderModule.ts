import { NativeModule, requireNativeModule } from 'expo';

import {
    ExpoAudioRecorderModuleEvents,
    MicrophoneInfo,
    PermissionResponse,
    RecordingConfig,
    RecordingResult,
    RecordingStatus,
    RecoveryResult,
} from './ExpoAudioRecorder.types';

/**
 * Native module bridge для expo-audio-recorder-core
 */

declare class ExpoAudioRecorderModule extends NativeModule<ExpoAudioRecorderModuleEvents> {
    requestPermissions(): Promise<PermissionResponse>;
    startRecording(config: RecordingConfig): Promise<string>;
    pauseRecording(): Promise<void>;
    resumeRecording(): Promise<void>;
    cancelRecording(): Promise<void>;
    stopRecording(): Promise<RecordingResult>;

    getStatus(): Promise<RecordingStatus>;
    hasUnfinishedRecording(): Promise<boolean>;
    recoverUnfinishedRecording(): Promise<RecoveryResult | null>;
    getAvailableMicrophones(): Promise<MicrophoneInfo[]>;
    getActiveMicrophone(): Promise<MicrophoneInfo | null>;
    isInGracePeriod(): Promise<boolean>

    // Convenience Helpers
    initializeRecorder(): Promise<RecoveryResult | null>;
    isRecording(): Promise<boolean>;
    isPaused(): Promise<boolean>;
    getDuration(): Promise<number>;
    getNoiseLevel(): Promise<number>;
}

export default requireNativeModule<ExpoAudioRecorderModule>('ExpoAudioRecorder');