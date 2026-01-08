import { NativeModule, registerWebModule } from 'expo';

import {
  FirstModuleEvents,
  PermissionResponse,
  RecordingStatus
} from './FirstModule.types';

class FirstModule extends NativeModule<FirstModuleEvents> {
  private isRecordingState = false;
  private recordingUri: string | null = null;

  async requestPermissions(): Promise<PermissionResponse> {
    // Web fallback: запрос разрешений через Web Audio API
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach(track => track.stop());
      return { granted: true };
    } catch (error) {
      return { granted: false };
    }
  }

  async startRecording(outputPath?: string): Promise<RecordingStatus> {
    // Web fallback: заглушка для веб-версии
    this.isRecordingState = true;
    this.recordingUri = outputPath || `web-recording-${Date.now()}.m4a`;
    
    this.emit('onRecordingStatusChanged', {
      isRecording: true,
      uri: this.recordingUri
    });

    return {
      uri: this.recordingUri,
      status: 'recording'
    };
  }

  async stopRecording(): Promise<RecordingStatus> {
    // Web fallback: заглушка для веб-версии
    this.isRecordingState = false;
    const uri = this.recordingUri;
    this.recordingUri = null;

    this.emit('onRecordingStatusChanged', {
      isRecording: false,
      uri: uri
    });

    return {
      uri: uri || '',
      status: 'stopped'
    };
  }

  isRecording(): boolean {
    return this.isRecordingState;
  }
};

export default registerWebModule(FirstModule, 'FirstModule');
