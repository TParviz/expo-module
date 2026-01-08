import type { StyleProp, ViewStyle } from 'react-native';

export type OnLoadEventPayload = {
  url: string;
};

export type FirstModuleEvents = {
  onRecordingStatusChanged: (params: RecordingStatusChangeEvent) => void;

  onChange: (params: ChangeEventPayload) => void;
};

export type ChangeEventPayload = {
  value: string;
};

export type FirstModuleViewProps = {
  url: string;
  onLoad: (event: { nativeEvent: OnLoadEventPayload }) => void;
  style?: StyleProp<ViewStyle>;
};

export type RecordingStatus = {
  uri: string;
  status: 'recording' | 'stopped';
};

export type PermissionResponse = {
  granted: boolean;
};

export type RecordingStatusChangeEvent = {
  isRecording: boolean;
  uri?: string;
};