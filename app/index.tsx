import * as AudioRecorder from 'expo-audio-recorder';
import React, { useEffect, useState } from 'react';
import {
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View
} from 'react-native';


export default function InterruptionTestExample() {
  const [status, setStatus] = useState({
    duration: 0,
    isRecording: false,
    isPaused: false,
    noiseLevel: -160,
  });

  const [events, setEvents] = useState<string[]>([]);
  const [interruptions, setInterruptions] = useState({
    audioFocusLost: 0,
    audioFocusGained: 0,
    phoneCallStarted: 0,
    phoneCallEnded: 0,
  });

  // Добавить событие в лог
  const addEvent = (message: string) => {
    const timestamp = new Date().toLocaleTimeString();
    setEvents(prev => [`[${timestamp}] ${message}`, ...prev.slice(0, 19)]);
    console.log(`[InterruptionTest] ${message}`);
  };

  useEffect(() => {
    // Подписка на изменения статуса
    const statusSub = AudioRecorder.addRecordingStateListener((newStatus) => {
      setStatus(newStatus);
      
      if (newStatus.isPaused && status.isRecording && !status.isPaused) {
        addEvent('📍 Статус: Запись приостановлена');
      }
      
      if (newStatus.isRecording && !newStatus.isPaused && status.isPaused) {
        addEvent('📍 Статус: Запись возобновлена');
      }
    });

    // Подписка на ошибки (включая прерывания)
    const errorSub = AudioRecorder.addRecordingErrorListener((error) => {
      addEvent(`⚠️ Событие: ${error.code}`);
      addEvent(`   Сообщение: ${error.message}`);
      
      // Счетчики для статистики
      setInterruptions(prev => {
        switch (error.code) {
          case 'AUDIO_FOCUS_LOST':
            return { ...prev, audioFocusLost: prev.audioFocusLost + 1 };
          case 'AUDIO_FOCUS_GAINED':
            return { ...prev, audioFocusGained: prev.audioFocusGained + 1 };
          case 'PHONE_CALL_STARTED':
            return { ...prev, phoneCallStarted: prev.phoneCallStarted + 1 };
          case 'PHONE_CALL_ENDED':
            return { ...prev, phoneCallEnded: prev.phoneCallEnded + 1 };
          default:
            return prev;
        }
      });
      
      // Показать Alert для критичных событий
      if (error.code === 'PHONE_CALL_STARTED') {
        Alert.alert(
          '☎️ Звонок!',
          'Запись автоматически приостановлена',
          [{ text: 'OK' }]
        );
      }
    });

    // Polling для обновления времени
    const interval = setInterval(async () => {
      try {
        const currentStatus = await AudioRecorder.getStatusAsync();
        setStatus(currentStatus);
      } catch (error) {
        // ignore
      }
    }, 100);

    return () => {
      statusSub.remove();
      errorSub.remove();
      clearInterval(interval);
    };
  }, [status.isRecording, status.isPaused]);

  const handleStart = async () => {
    try {
      const permission = await AudioRecorder.requestPermissions();
      
      if (!permission.granted) {
        Alert.alert('Ошибка', 'Требуется разрешение на микрофон');
        return;
      }

      await AudioRecorder.startRecording({
        sampleRate: 44100,
        bitRate: 128000,
        channels: 1,
      });

      addEvent('✅ Запись началась');
      
      Alert.alert(
        '🧪 Тест прерываний',
        'Запись началась!\n\n' +
        'Теперь попробуйте:\n' +
        '1. Позвонить на этот телефон\n' +
        '2. Открыть YouTube\n' +
        '3. Включить музыку\n' +
        '4. Активировать Google Assistant\n\n' +
        'Все события будут залогированы ниже.',
        [{ text: 'Начать тесты' }]
      );
    } catch (error: any) {
      addEvent(`❌ Ошибка старта: ${error.message}`);
      Alert.alert('Ошибка', error.message);
    }
  };

  const handleStop = async () => {
    try {
      const result = await AudioRecorder.stopRecording();
      addEvent(`✅ Запись остановлена: ${result.duration.toFixed(1)}с`);
      
      // Показать статистику
      Alert.alert(
        '📊 Статистика прерываний',
        `Запись завершена: ${result.duration.toFixed(1)}с\n\n` +
        `🔊 AudioFocus Lost: ${interruptions.audioFocusLost}\n` +
        `✅ AudioFocus Gained: ${interruptions.audioFocusGained}\n` +
        `☎️ Phone Call Started: ${interruptions.phoneCallStarted}\n` +
        `✅ Phone Call Ended: ${interruptions.phoneCallEnded}\n\n` +
        `Файл: ${result.filePath.split('/').pop()}`,
        [{ text: 'OK' }]
      );
    } catch (error: any) {
      addEvent(`❌ Ошибка остановки: ${error.message}`);
      Alert.alert('Ошибка', error.message);
    }
  };

  const handleResume = async () => {
    try {
      await AudioRecorder.resumeRecording();
      addEvent('▶️ Запись возобновлена вручную');
    } catch (error: any) {
      addEvent(`❌ Ошибка возобновления: ${error.message}`);
      Alert.alert('Ошибка', error.message);
    }
  };

  const clearLog = () => {
    setEvents([]);
    addEvent('🗑️ Лог очищен');
  };

  const clearStats = () => {
    setInterruptions({
      audioFocusLost: 0,
      audioFocusGained: 0,
      phoneCallStarted: 0,
      phoneCallEnded: 0,
    });
    addEvent('📊 Статистика сброшена');
  };

  const openTestInstructions = () => {
    Alert.alert(
      '🧪 Инструкции по тестированию',
      'После начала записи:\n\n' +
      '✅ ТЕСТ 1: Входящий звонок\n' +
      '   Позвоните на этот телефон с другого устройства\n' +
      '   Ожидается: PHONE_CALL_STARTED\n\n' +
      '✅ ТЕСТ 2: YouTube\n' +
      '   Откройте YouTube и включите видео\n' +
      '   Ожидается: AUDIO_FOCUS_LOST\n\n' +
      '✅ ТЕСТ 3: Музыка\n' +
      '   Откройте Spotify/YouTube Music\n' +
      '   Ожидается: AUDIO_FOCUS_LOST\n\n' +
      '✅ ТЕСТ 4: Google Assistant\n' +
      '   Скажите "OK Google"\n' +
      '   Ожидается: AUDIO_FOCUS_LOST\n\n' +
      '✅ ТЕСТ 5: Уведомление\n' +
      '   Отправьте себе SMS или WhatsApp\n' +
      '   Ожидается: AUDIO_FOCUS_LOST (может быть)\n\n' +
      'Все события отобразятся в логе.',
      [{ text: 'Понятно' }]
    );
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>Тест прерываний</Text>
        <TouchableOpacity onPress={openTestInstructions}>
          <Text style={styles.helpButton}>❓ Инструкции</Text>
        </TouchableOpacity>
      </View>

      {/* Status Card */}
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.label}>Статус:</Text>
          <Text style={[
            styles.value,
            status.isRecording && styles.valueRecording,
            status.isPaused && styles.valuePaused
          ]}>
            {status.isRecording 
              ? (status.isPaused ? '⏸️ ПАУЗА' : '⏺️ ЗАПИСЬ')
              : '⏹️ ОСТАНОВЛЕНО'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={styles.label}>Время:</Text>
          <Text style={styles.timeValue}>
            {Math.floor(status.duration)}с
          </Text>
        </View>

        {status.isPaused && (
          <View style={styles.warningBox}>
            <Text style={styles.warningText}>
              ⚠️ Запись на паузе
            </Text>
            <Text style={styles.warningSubtext}>
              Возможно из-за прерывания
            </Text>
          </View>
        )}
      </View>

      {/* Statistics */}
      <View style={styles.card}>
        <View style={styles.statsHeader}>
          <Text style={styles.statsTitle}>📊 Статистика прерываний</Text>
          <TouchableOpacity onPress={clearStats}>
            <Text style={styles.clearButton}>Сбросить</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.statsRow}>
          <Text style={styles.statsLabel}>🔊 AudioFocus Lost:</Text>
          <Text style={styles.statsValue}>{interruptions.audioFocusLost}</Text>
        </View>

        <View style={styles.statsRow}>
          <Text style={styles.statsLabel}>✅ AudioFocus Gained:</Text>
          <Text style={styles.statsValue}>{interruptions.audioFocusGained}</Text>
        </View>

        <View style={styles.statsRow}>
          <Text style={styles.statsLabel}>☎️ Phone Call Started:</Text>
          <Text style={styles.statsValue}>{interruptions.phoneCallStarted}</Text>
        </View>

        <View style={styles.statsRow}>
          <Text style={styles.statsLabel}>✅ Phone Call Ended:</Text>
          <Text style={styles.statsValue}>{interruptions.phoneCallEnded}</Text>
        </View>
      </View>

      {/* Controls */}
      <View style={styles.controls}>
        {!status.isRecording ? (
          <TouchableOpacity
            style={[styles.button, styles.startButton]}
            onPress={handleStart}
          >
            <Text style={styles.buttonText}>🎤 Начать тест</Text>
          </TouchableOpacity>
        ) : (
          <>
            <TouchableOpacity
              style={[styles.button, styles.stopButton]}
              onPress={handleStop}
            >
              <Text style={styles.buttonText}>⏹️ Остановить</Text>
            </TouchableOpacity>

            {status.isPaused && (
              <TouchableOpacity
                style={[styles.button, styles.resumeButton]}
                onPress={handleResume}
              >
                <Text style={styles.buttonText}>▶️ Возобновить</Text>
              </TouchableOpacity>
            )}
          </>
        )}
      </View>

      {/* Event Log */}
      <View style={styles.logCard}>
        <View style={styles.logHeader}>
          <Text style={styles.logTitle}>📝 Лог событий</Text>
          <TouchableOpacity onPress={clearLog}>
            <Text style={styles.clearButton}>Очистить</Text>
          </TouchableOpacity>
        </View>

        <ScrollView style={styles.logScroll}>
          {events.length === 0 ? (
            <Text style={styles.logEmpty}>
              События появятся здесь...
            </Text>
          ) : (
            events.map((event, index) => (
              <Text key={index} style={styles.logItem}>
                {event}
              </Text>
            ))
          )}
        </ScrollView>
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
    backgroundColor: '#9C27B0',
    padding: 20,
    paddingTop: 60,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#fff',
  },
  helpButton: {
    color: '#fff',
    fontSize: 24,
  },
  card: {
    backgroundColor: '#fff',
    margin: 16,
    marginBottom: 8,
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
  valueRecording: {
    color: '#F44336',
  },
  valuePaused: {
    color: '#FF9800',
  },
  timeValue: {
    fontSize: 32,
    fontWeight: 'bold',
    color: '#9C27B0',
  },
  warningBox: {
    backgroundColor: '#FFF3E0',
    padding: 12,
    borderRadius: 8,
    marginTop: 8,
    borderLeftWidth: 4,
    borderLeftColor: '#FF9800',
  },
  warningText: {
    fontSize: 14,
    fontWeight: 'bold',
    color: '#E65100',
    marginBottom: 4,
  },
  warningSubtext: {
    fontSize: 12,
    color: '#F57C00',
  },
  statsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  statsTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#333',
  },
  clearButton: {
    color: '#9C27B0',
    fontSize: 14,
    fontWeight: '600',
  },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  statsLabel: {
    fontSize: 14,
    color: '#666',
  },
  statsValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#9C27B0',
  },
  controls: {
    padding: 16,
    paddingTop: 8,
  },
  button: {
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 8,
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
  resumeButton: {
    backgroundColor: '#2196F3',
  },
  logCard: {
    backgroundColor: '#fff',
    margin: 16,
    marginTop: 0,
    padding: 16,
    borderRadius: 12,
    flex: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  logTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#333',
  },
  logScroll: {
    flex: 1,
  },
  logEmpty: {
    fontSize: 14,
    color: '#999',
    fontStyle: 'italic',
    textAlign: 'center',
    marginTop: 20,
  },
  // logItem: {
  //   fontSize: 12,
  //   fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  //   color: '#333',
  //   paddingVertical: 4,
  //   borderBottomWidth: 1,
  //   borderBottomColor: '#f5f5f5',
  // },
});