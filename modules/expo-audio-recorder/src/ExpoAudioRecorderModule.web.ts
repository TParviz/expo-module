import { registerWebModule, NativeModule } from 'expo';

import { ExpoAudioRecorderModuleEvents } from './ExpoAudioRecorder.types';

class ExpoAudioRecorderModule extends NativeModule<ExpoAudioRecorderModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoAudioRecorderModule, 'ExpoAudioRecorderModule');
