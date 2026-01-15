export type RecordingState = 'idle' | 'recording' | 'paused';

export type RecordingConfig = {
  sampleRate?: number; // Default: 44100
  bitRate?: number; // Default: 128000
  channels?: number; // Default: 1 (mono)
  enableChunking?: boolean; // Default: false
  chunkDuration?: number; // Duration in milliseconds, default: 1000
};

export type RecordingResult = {
  filePath: string;
  duration: number; // in seconds
  fileSize: number; // in bytes
};

export type AudioChunk = {
  data: number[]; // PCM data downsampled to 16kHz
  sampleRate: number;
  timestamp: number;
};

export type PermissionResponse = {
  granted: boolean;
  canRequest: boolean;
};

export type RecordingStatus = {
  state: RecordingState;
  filePath: string | null;
  duration: number; // in seconds
  isRecording: boolean;
  isPaused: boolean;
  noiseLevel: number; // dB level, -160 to 0
};

export type ExpoAudioRecorderModuleEvents = {
  onRecordingStateChanged: (status: RecordingStatus) => void;
  onAudioChunk: (chunk: AudioChunk) => void;
  onRecordingError: (error: { code: string; message: string }) => void;
};

// public enum RecordingEvent: Sendable {
//   case cantHearMicrophone
//   case chankWasLost(Error)
//   case chunk(Data)
//   case canceled
//   case completed(URL?)+
//   case audioFileError(Error)
// }