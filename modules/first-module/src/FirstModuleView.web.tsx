import * as React from 'react';

import { FirstModuleViewProps } from './FirstModule.types';

export default function FirstModuleView(props: FirstModuleViewProps) {
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
