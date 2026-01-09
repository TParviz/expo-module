import type { RecordingStatus } from 'expo-audio-recorder';
import * as AudioRecorder from 'expo-audio-recorder';
import React, { useEffect, useState } from 'react';
import {
  Alert,
  Linking,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

export default function App() {
  const [status, setStatus] = useState<RecordingStatus>({
    state: 'idle',
    filePath: null,
    duration: 0,
    isRecording: false,
    isPaused: false,
    noiseLevel: -160,
  });

  useEffect(() => {
    // Check permissions on mount
    checkPermissions();

    // Subscribe to state changes
    const subscription = AudioRecorder.addRecordingStateListener((newStatus) => {
      setStatus(newStatus);
    });

    // Poll status periodically for real-time updates (duration and noise level)
    const interval = setInterval(async () => {
      try {
        const currentStatus = await AudioRecorder.getStatusAsync();
        setStatus(currentStatus);
      } catch (error) {
        // Ignore errors during polling
      }
    }, 100); // Update every 100ms for smooth animation

    return () => {
      subscription.remove();
      clearInterval(interval);
    };
  }, []);

  const checkPermissions = async () => {
    const permission = await AudioRecorder.requestPermissions();
    
    if (!permission.granted) {
      if (permission.canRequest) {
        Alert.alert(
          'Нужен доступ',
          'Требуется разрешение на использование микрофона'
        );
      } else {
        Alert.alert(
          'Разрешение заблокировано',
          'Пожалуйста, включите доступ к микрофону в настройках',
          [
            { text: 'Отмена', style: 'cancel' },
            { text: 'Настройки', onPress: () => Linking.openSettings() }
          ]
        );
      }
    }
  };

  const handleStart = async () => {
    try {
      const filePath = await AudioRecorder.startRecording({
        sampleRate: 44100,
        bitRate: 128000,
        channels: 1,
        enableChunking: false,
      });
      console.log('Recording started:', filePath);
    } catch (error: any) {
      Alert.alert('Ошибка', error.message || 'Не удалось начать запись');
    }
  };

  const handleStop = async () => {
    try {
      const result = await AudioRecorder.stopRecording();
      Alert.alert(
        'Запись сохранена',
        `Длительность: ${result.duration.toFixed(1)}с\n` +
        `Размер: ${(result.fileSize / 1024).toFixed(0)} КБ\n` +
        `Файл: ${result.filePath.split('/').pop()}`
      );
    } catch (error: any) {
      Alert.alert('Ошибка', error.message || 'Не удалось остановить запись');
    }
  };

  const handlePause = async () => {
    try {
      await AudioRecorder.pauseRecording();
    } catch (error: any) {
      Alert.alert('Ошибка', error.message || 'Не удалось поставить на паузу');
    }
  };

  const handleResume = async () => {
    try {
      await AudioRecorder.resumeRecording();
    } catch (error: any) {
      Alert.alert('Ошибка', error.message || 'Не удалось возобновить запись');
    }
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const getNoiseColor = (level: number) => {
    if (level > -20) return '#4CAF50'; // Громко
    if (level > -40) return '#FFC107'; // Нормально
    return '#9E9E9E'; // Тихо
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>Аудио Рекордер</Text>
      </View>

      {/* Status Card */}
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.label}>Статус:</Text>
          <View style={[
            styles.badge,
            status.state === 'recording' && styles.badgeRecording,
            status.state === 'paused' && styles.badgePaused,
          ]}>
            <Text style={styles.badgeText}>
              {status.state === 'recording' ? '⏺️ ЗАПИСЬ' : 
               status.state === 'paused' ? '⏸️ ПАУЗА' : 
               '⏹️ ОСТАНОВЛЕНО'}
            </Text>
          </View>
        </View>

        <View style={styles.row}>
          <Text style={styles.label}>Время:</Text>
          <Text style={styles.value}>{formatTime(status.duration)}</Text>
        </View>

        {status.isRecording && (
          <View style={styles.row}>
            <Text style={styles.label}>Шум:</Text>
            <View style={styles.noiseContainer}>
              <View style={[
                styles.noiseDot,
                { backgroundColor: getNoiseColor(status.noiseLevel) }
              ]} />
              <Text style={[styles.value, { color: getNoiseColor(status.noiseLevel) }]}>
                {status.noiseLevel.toFixed(0)} дБ
              </Text>
            </View>
          </View>
        )}

        {status.filePath && (
          <View style={styles.row}>
            <Text style={styles.label}>Файл:</Text>
            <Text style={styles.fileName} numberOfLines={1}>
              {status.filePath.split('/').pop()}
            </Text>
          </View>
        )}
      </View>

      {/* Controls */}
      <View style={styles.controls}>
        {!status.isRecording ? (
          <TouchableOpacity
            style={[styles.button, styles.startButton]}
            onPress={handleStart}
          >
            <Text style={styles.buttonText}>🎤 Начать запись</Text>
          </TouchableOpacity>
        ) : (
          <>
            <TouchableOpacity
              style={[styles.button, styles.stopButton]}
              onPress={handleStop}
            >
              <Text style={styles.buttonText}>⏹️ Остановить</Text>
            </TouchableOpacity>

            {status.isPaused ? (
              <TouchableOpacity
                style={[styles.button, styles.resumeButton]}
                onPress={handleResume}
              >
                <Text style={styles.buttonText}>▶️ Продолжить</Text>
              </TouchableOpacity>
            ) : (
              <TouchableOpacity
                style={[styles.button, styles.pauseButton]}
                onPress={handlePause}
              >
                <Text style={styles.buttonText}>⏸️ Пауза</Text>
              </TouchableOpacity>
            )}
          </>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    backgroundColor: '#2196F3',
    padding: 20,
    paddingTop: 60,
    alignItems: 'center',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#fff',
  },
  card: {
    backgroundColor: '#fff',
    margin: 16,
    padding: 16,
    borderRadius: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  label: {
    fontSize: 16,
    fontWeight: '600',
    color: '#666',
    width: 80,
  },
  value: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#333',
  },
  fileName: {
    fontSize: 14,
    color: '#666',
    flex: 1,
  },
  badge: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
    backgroundColor: '#9E9E9E',
  },
  badgeRecording: {
    backgroundColor: '#F44336',
  },
  badgePaused: {
    backgroundColor: '#FF9800',
  },
  badgeText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  noiseContainer: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  noiseDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 8,
  },
  controls: {
    padding: 16,
  },
  button: {
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 3,
  },
  buttonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  startButton: {
    backgroundColor: '#4CAF50',
  },
  stopButton: {
    backgroundColor: '#F44336',
  },
  pauseButton: {
    backgroundColor: '#FF9800',
  },
  resumeButton: {
    backgroundColor: '#2196F3',
  },
});