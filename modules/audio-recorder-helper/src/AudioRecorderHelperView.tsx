import { requireNativeView } from 'expo';
import * as React from 'react';

import { AudioRecorderHelperViewProps } from './AudioRecorderHelper.types';

const NativeView: React.ComponentType<AudioRecorderHelperViewProps> =
  requireNativeView('ExpoAudioRecorder');

export default function ExpoAudioRecorderView(props: AudioRecorderHelperViewProps) {
  return <NativeView {...props} />;
}
