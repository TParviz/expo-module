// import { getModuleVersion, showButton } from '@/modules/first-module';
import * as FirstModule from '@/modules/first-module';
import { useEffect, useState } from 'react';
import { Alert, Button, Platform, StyleSheet, Text, View } from "react-native";


export default function App() {
  const [isRecording, setIsRecording] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [recordingUri, setRecordingUri] = useState<string>('');
  const [hasPermission, setHasPermission] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);

  useEffect(() => {
    requestPermission();

    const subscription = FirstModule.addRecordingStatusListener((event) => {
      setIsRecording(event.isRecording);
      setIsPaused(event.isPaused);
      if (event.uri) {
        setRecordingUri(event.uri);
      }
    });

    return () => subscription.remove();
  }, []);

  // Таймер для отображения длительности записи
  useEffect(() => {
    let interval: NodeJS.Timeout;
    
    if (isRecording && !isPaused) {
      interval = setInterval(() => {
        setRecordingDuration(prev => prev + 1);
      }, 1000);
    }

    return () => {
      if (interval) clearInterval(interval);
    };
  }, [isRecording, isPaused]);

  const requestPermission = async () => {
    try {
      const { granted } = await FirstModule.requestPermissions();
      setHasPermission(granted);
      
      if (!granted) {
        Alert.alert('Permission Required', 'Please grant microphone permission');
      }
    } catch (error) {
      console.error('Permission error:', error);
    }
  };

  const handleStartRecording = async () => {
    try {
      if (!hasPermission) {
        await requestPermission();
        return;
      }

      setRecordingDuration(0);
      const result = await FirstModule.startRecording();
      console.log('Recording started:', result);
    } catch (error: any) {
      Alert.alert('Error', error.message);
    }
  };

  const handlePauseRecording = async () => {
    try {
      const result = await FirstModule.pauseRecording();
      console.log('Recording paused:', result);
    } catch (error: any) {
      if (error.code === 'NOT_SUPPORTED') {
        Alert.alert(
          'Not Supported', 
          'Pause/Resume is not supported on Android versions below 7.0'
        );
      } else {
        Alert.alert('Error', error.message);
      }
    }
  };

  const handleResumeRecording = async () => {
    try {
      const result = await FirstModule.resumeRecording();
      console.log('Recording resumed:', result);
    } catch (error: any) {
      Alert.alert('Error', error.message);
    }
  };

  const handleStopRecording = async () => {
    try {
      const result = await FirstModule.stopRecording();
      console.log('Recording stopped:', result);
      setRecordingDuration(0);
      Alert.alert(
        'Recording Saved', 
        `File: ${result.uri}\n\nDuration: ${formatDuration(recordingDuration)}`
      );
    } catch (error: any) {
      Alert.alert('Error', error.message);
    }
  };

  const formatDuration = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const getStatusText = () => {
    if (!isRecording) return '⚪ Stopped';
    if (isPaused) return '⏸️ Paused';
    return '🔴 Recording...';
  };

  const supportsAndroidVersion = Platform.OS === 'ios' || 
    (Platform.OS === 'android' && Platform.Version >= 24);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Audio Recorder</Text>
      
      <View style={styles.statusContainer}>
        <Text style={styles.status}>{getStatusText()}</Text>
        {isRecording && (
          <Text style={styles.duration}>{formatDuration(recordingDuration)}</Text>
        )}
      </View>

      {recordingUri ? (
        <Text style={styles.uri} numberOfLines={2}>
          File: {recordingUri}
        </Text>
      ) : null}

      <View style={styles.buttons}>
        <Button
          title="Start Recording"
          onPress={handleStartRecording}
          disabled={isRecording || !hasPermission}
          color="#4CAF50"
        />
        
        <View style={styles.controlButtons}>
          <View style={styles.halfButton}>
            <Button
              title="Pause"
              onPress={handlePauseRecording}
              disabled={!isRecording || isPaused || !supportsAndroidVersion}
              color="#FF9800"
            />
          </View>
          
          <View style={styles.halfButton}>
            <Button
              title="Resume"
              onPress={handleResumeRecording}
              disabled={!isRecording || !isPaused || !supportsAndroidVersion}
              color="#2196F3"
            />
          </View>
        </View>
        
        <Button
          title="Stop Recording"
          onPress={handleStopRecording}
          disabled={!isRecording}
          color="#F44336"
        />

        <Button
          title="Request Permission"
          onPress={requestPermission}
          disabled={hasPermission} 
          color="#9E9E9E"
        />
      </View>

      <View style={styles.info}>
        <Text style={styles.permission}>
          Permission: {hasPermission ? '✅ Granted' : '❌ Not granted'}
        </Text>
        {Platform.OS === 'android' && Platform.Version < 24 && (
          <Text style={styles.warning}>
            ⚠️ Pause/Resume requires Android 7.0+
          </Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
    backgroundColor: '#f5f5f5',
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    marginBottom: 30,
    color: '#333',
  },
  statusContainer: {
    alignItems: 'center',
    marginBottom: 10,
  },
  status: {
    fontSize: 20,
    marginBottom: 5,
    fontWeight: '600',
  },
  duration: {
    fontSize: 32,
    fontWeight: 'bold',
    color: '#4CAF50',
    fontVariant: ['tabular-nums'],
  },
  uri: {
    fontSize: 12,
    color: 'gray',
    marginBottom: 20,
    textAlign: 'center',
    paddingHorizontal: 10,
  },
  buttons: {
    gap: 12,
    width: '100%',
    maxWidth: 300,
  },
  controlButtons: {
    flexDirection: 'row',
    gap: 10,
  },
  halfButton: {
    flex: 1,
  },
  info: {
    marginTop: 30,
    alignItems: 'center',
  },
  permission: {
    fontSize: 14,
    color: '#666',
  },
  warning: {
    marginTop: 10,
    fontSize: 12,
    color: '#FF5722',
    textAlign: 'center',
  },
});