import { requireNativeView } from 'expo';
import * as React from 'react';

import { FirstModuleViewProps } from './FirstModule.types';

const NativeView: React.ComponentType<FirstModuleViewProps> =
  requireNativeView('FirstModule');

export default function FirstModuleView(props: FirstModuleViewProps) {
  return <NativeView {...props} />;
}
