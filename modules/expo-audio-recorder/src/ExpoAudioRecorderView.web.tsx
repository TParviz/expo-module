import * as React from 'react';

import { ExpoAudioRecorderViewProps } from './ExpoAudioRecorder.types';

export default function ExpoAudioRecorderView(props: ExpoAudioRecorderViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
