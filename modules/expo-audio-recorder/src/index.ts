import { EventSubscription } from 'expo-modules-core';
import type {
  AudioChunk,
  PermissionResponse,
  RecordingConfig,
  RecordingResult,
  RecordingState,
  RecordingStatus,
} from './ExpoAudioRecorder.types';
import ExpoAudioRecorderModule from './ExpoAudioRecorderModule';

export {
  AudioChunk,
  PermissionResponse, RecordingConfig,
  RecordingResult, RecordingState, RecordingStatus
};

/**
 * Request audio recording permissions
 * @returns Promise with permission status including granted and canRequest flags
 */
export async function requestPermissions(): Promise<PermissionResponse> {
  return await ExpoAudioRecorderModule.requestPermissions();
}

/**
 * Start audio recording
 * @param config Recording configuration
 * @returns Promise with file path where recording is being saved
 */
export async function startRecording(config: RecordingConfig = {}): Promise<string> {
  return await ExpoAudioRecorderModule.startRecording(config);
}

/**
 * Stop audio recording
 * @returns Promise with recording result (path, duration, size)
 */
export async function stopRecording(): Promise<RecordingResult> {
  return await ExpoAudioRecorderModule.stopRecording();
}

/**
 * Pause audio recording
 */
export async function pauseRecording(): Promise<void> {
  return await ExpoAudioRecorderModule.pauseRecording();
}

/**
 * Resume audio recording
 */
export async function resumeRecording(): Promise<void> {
  return await ExpoAudioRecorderModule.resumeRecording();
}

/**
 * Get current recording status (async)
 * @returns Promise with current recording status including noise level
 */
export async function getStatusAsync(): Promise<RecordingStatus> {
  return await ExpoAudioRecorderModule.getStatusAsync();
}

/**
 * Subscribe to recording state changes
 */
export function addRecordingStateListener(
  listener: (status: RecordingStatus) => void
): EventSubscription {
  return ExpoAudioRecorderModule.addListener('onRecordingStateChanged', listener);
}

/**
 * Subscribe to audio chunks (when chunking is enabled)
 */
export function addAudioChunkListener(
  listener: (chunk: AudioChunk) => void
): EventSubscription {
  return ExpoAudioRecorderModule.addListener('onAudioChunk', listener);
}

/**
 * Subscribe to recording errors
 */
export function addRecordingErrorListener(
  listener: (error: { code: string; message: string }) => void
): EventSubscription {
  return ExpoAudioRecorderModule.addListener('onRecordingError', listener);
}
