// Reexport the native module. On web, it will be resolved to FirstModule.web.ts
// and on native platforms to FirstModule.ts
export { default } from './src/FirstModule';
export * from './src/FirstModule.types';
export { default as FirstModuleView } from './src/FirstModuleView';
import { EventSubscription } from 'expo-modules-core';
import FirstModule from './src/FirstModule';
import {
  PermissionResponse,
  RecordingInfo,
  RecordingStatus,
  RecordingStatusChangeEvent
} from './src/FirstModule.types';

// export function showButton(text: string): string {
//     return FirstModule.showButton(text);
// }

// export function getModuleVersion(): string {
//     return FirstModule.getModuleVersion();
// }

export async function requestPermissions(): Promise<PermissionResponse> {
  return await FirstModule.requestPermissions();
}

export async function startRecording(outputPath?: string): Promise<RecordingStatus> {
  return await FirstModule.startRecording(outputPath);
}

export async function pauseRecording(): Promise<RecordingStatus> {
  return await FirstModule.pauseRecording();
}

export async function resumeRecording(): Promise<RecordingStatus> {
  return await FirstModule.resumeRecording();
}

export async function stopRecording(): Promise<RecordingStatus> {
  return await FirstModule.stopRecording();
}

export function isRecording(): boolean {
  return FirstModule.isRecording();
}

export function isPaused(): boolean {
  return FirstModule.isPaused();
}

export function getStatus(): RecordingInfo {
  return FirstModule.getStatus();
}

export function addRecordingStatusListener(
  listener: (event: RecordingStatusChangeEvent) => void
): EventSubscription {
  return FirstModule.addListener('onRecordingStatusChanged', listener);
}