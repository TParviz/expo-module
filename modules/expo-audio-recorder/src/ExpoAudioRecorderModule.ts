import { NativeModule, requireNativeModule } from 'expo';
import {
  ExpoAudioRecorderModuleEvents,
  PermissionResponse,
  RecordingConfig,
  RecordingResult,
  RecordingStatus,
} from './ExpoAudioRecorder.types';

declare class ExpoAudioRecorderModule extends NativeModule<ExpoAudioRecorderModuleEvents> {
  startRecording(config: RecordingConfig): Promise<string>;
  stopRecording(): Promise<RecordingResult>;
  pauseRecording(): Promise<void>;
  resumeRecording(): Promise<void>;
  getStatusAsync(): Promise<RecordingStatus>;
  requestPermissions(): Promise<PermissionResponse>;
}

export default requireNativeModule<ExpoAudioRecorderModule>('ExpoAudioRecorder');