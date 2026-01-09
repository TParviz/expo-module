import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoAudioRecorderViewProps } from './ExpoAudioRecorder.types';

const NativeView: React.ComponentType<ExpoAudioRecorderViewProps> =
  requireNativeView('ExpoAudioRecorder');

export default function ExpoAudioRecorderView(props: ExpoAudioRecorderViewProps) {
  return <NativeView {...props} />;
}
