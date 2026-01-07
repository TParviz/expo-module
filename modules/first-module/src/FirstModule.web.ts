import { NativeModule, registerWebModule } from 'expo';

import { ChangeEventPayload } from './FirstModule.types';

type FirstModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
}

class FirstModule extends NativeModule<FirstModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
  showButton(buttonText: string): string {
    return `Button clicked: ${buttonText}`;
  }
};

export default registerWebModule(FirstModule, 'FirstModule');
