import { NativeModule, registerWebModule } from 'expo';

import { AudioRecorderHelperModuleEvents } from './AudioRecorderHelper.types';

class AudioRecorderHelperModule extends NativeModule<AudioRecorderHelperModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(AudioRecorderHelperModule, 'AudioRecorderHelperModule');
