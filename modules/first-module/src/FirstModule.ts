import { NativeModule, requireNativeModule } from 'expo';

import { FirstModuleEvents } from './FirstModule.types';

declare class FirstModule extends NativeModule<FirstModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
  showButton: (text: string) => string;
  // getModuleVersion: () => string;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<FirstModule>('FirstModule');