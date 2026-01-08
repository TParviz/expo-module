import { NativeModule, requireNativeModule } from 'expo';

import { FirstModuleEvents, PermissionResponse, RecordingInfo, RecordingStatus } from './FirstModule.types';

declare class FirstModule extends NativeModule<FirstModuleEvents> {
  // PI: number;
  // hello(): string;
  // setValueAsync(value: string): Promise<void>;
  // showButton: (text: string) => string;
  // getModuleVersion: () => string;

  requestPermissions(): Promise<PermissionResponse>;
  startRecording(outputPath?: string): Promise<RecordingStatus>;
  pauseRecording(): Promise<RecordingStatus>;
  resumeRecording(): Promise<RecordingStatus>;
  stopRecording(): Promise<RecordingStatus>;
  isRecording(): boolean;
  isPaused(): boolean;
  getStatus(): RecordingInfo;

}

// This call loads the native module object from the JSI.
export default requireNativeModule<FirstModule>('FirstModule');