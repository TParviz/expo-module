import { requireNativeModule } from 'expo';

// import {
//     ExpoAudioRecorderModuleEvents,
// } from './ExpoAudioRecorder.types';

// /**
//  * Native module bridge для expo-audio-recorder-core
//  */

// declare class ExpoAudioRecorderModule extends NativeModule<ExpoAudioRecorderModuleEvents> {
//     // Core recording methods
//     pauseRecording(): Promise<void>;
//     resumeRecording(): Promise<void>;
//     cancelRecording(): Promise<void>; // НОВОЕ: Отмена записи
  
//     // Recovery methods
//     hasUnfinishedRecordingAsync(): Promise<boolean>; // НОВОЕ
//     deleteRecoveryFileAsync(path: string): Promise<boolean>;
//     cleanOldRecoveryFilesAsync(daysToKeep: number): Promise<boolean>;
    
//     // File repair methods
//     repairFileAsync(inputPath: string, outputPath: string): Promise<boolean>;
//     canReadFileAsync(filePath: string): Promise<boolean>;
// }

export default requireNativeModule('ExpoAudioRecorder');