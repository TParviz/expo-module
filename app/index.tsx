import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import * as Interruption from 'audio-recorder-helper';
import * as Recorder from 'expo-audio-recorder';

// Типы
interface LogEntry {
  id: number;
  time: string;
  type: 'info' | 'warning' | 'error' | 'success';
  message: string;
}

export default function App() {
  // === Состояние рекордера ===
  const [recordingStatus, setRecordingStatus] = useState<Recorder.RecordingStatus>({
    state: 'idle',
    duration: 0,
    isRecording: false,
    isPaused: false,
    noiseLevel: -160,
  });
  
  // === Состояние прерываний ===
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [bluetoothState, setBluetoothState] = useState<Interruption.BluetoothState | null>(null);
  const [microphones, setMicrophones] = useState<Interruption.MicrophoneInfo[]>([]);
  const [lastInterruption, setLastInterruption] = useState<Interruption.InterruptionInfo | null>(null);
  
  // === Статистика ===
  const [interruptionStats, setInterruptionStats] = useState<Record<string, number>>({});
  
  // === Логи ===
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const logIdRef = useRef(0);

  // === Permissions ===
  const [hasPermissions, setHasPermissions] = useState(false);
  const [isInitializing, setIsInitializing] = useState(true);

  // === Refs для lifecycle ===
  const isMountedRef = useRef(true);
  const pausingRef = useRef(false);
  const resumingRef = useRef(false);

  // ✅ FIX: addLog с guard для unmount
  const addLog = useCallback((type: LogEntry['type'], message: string) => {
    if (!isMountedRef.current) return;
    
    const time = new Date().toLocaleTimeString();
    setLogs(prev => [{
      id: logIdRef.current++,
      time,
      type,
      message,
    }, ...prev].slice(0, 100));
  }, []);

  // ✅ FIX: Инициализация с proper async handling
  useEffect(() => {
    isMountedRef.current = true;

    const init = async () => {
      try {
        setIsInitializing(true);
        
        // Запрашиваем разрешения
        const permission = await Recorder.requestPermissions();
        if (!permission.granted) {
          addLog('error', 'Microphone permission denied');
          setHasPermissions(false);
          Alert.alert(
            'Permission Required',
            'Microphone access is required for recording',
            [{ text: 'OK' }]
          );
          return;
        }
        
        setHasPermissions(true);
        addLog('success', 'Microphone permission granted');

        // Проверяем незавершённую запись
        const hasUnfinished = await Recorder.hasUnfinishedRecording();
        if (hasUnfinished && isMountedRef.current) {
          addLog('warning', 'Found unfinished recording, recovering...');
          const recovered = await Recorder.recoverUnfinishedRecording();
          if (recovered && isMountedRef.current) {
            addLog('success', `Recovered: ${recovered.filePath}`);
          }
        }

        // Получаем информацию об устройствах
        if (isMountedRef.current) {
          await refreshDeviceInfo();
        }

      } catch (error) {
        if (isMountedRef.current) {
          addLog('error', `Init error: ${error}`);
        }
      } finally {
        if (isMountedRef.current) {
          setIsInitializing(false);
        }
      }
    };

    init();

    return () => {
      isMountedRef.current = false;
      // Cleanup (fire-and-forget)
      if (isMonitoring) {
        Interruption.stopMonitoring().catch(console.error);
      }
    };
  }, []);

  const refreshDeviceInfo = async () => {
    if (!isMountedRef.current) return;
    
    try {
      const bt = await Interruption.getBluetoothState();
      if (isMountedRef.current) {
        setBluetoothState(bt);
      }
      
      const mics = await Interruption.getAvailableMicrophones();
      if (isMountedRef.current) {
        setMicrophones(mics);
        addLog('info', `Found ${mics.length} microphones, BT: ${bt.isConnected ? bt.deviceName : 'not connected'}`);
      }
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Device info error: ${error}`);
      }
    }
  };

  // ✅ FIX: Подписки БЕЗ dependencies на recordingStatus
  useEffect(() => {
    if (!hasPermissions) return;

    // Ref для доступа к актуальному состоянию
    const recordingStatusRef = { current: recordingStatus };

    // Подписка на состояние рекордера
    const recorderSub = Recorder.addRecordingStateListener((status) => {
      if (!isMountedRef.current) return;
      setRecordingStatus(status);
      recordingStatusRef.current = status;
    });

    // Подписка на события рекордера
    const eventSub = Recorder.addRecordingEventListener((event) => {
      if (!isMountedRef.current) return;
      
      switch (event.type) {
        case 'completed':
          addLog('success', `Recording completed: ${event.duration?.toFixed(1)}s`);
          break;
        case 'canceled':
          addLog('warning', 'Recording canceled');
          break;
        case 'error':
          addLog('error', `Recording error: ${event.message}`);
          break;
      }
    });

    // Подписка на прерывания
    const interruptionSub = Interruption.addInterruptionListener((info) => {
      if (!isMountedRef.current) return;
      
      setLastInterruption(info);
      
      // Обновляем статистику
      setInterruptionStats(prev => ({
        ...prev,
        [info.source]: (prev[info.source] || 0) + 1,
      }));
      
      const policyIcon = info.policy === 'PAUSE' ? '||' : info.policy === 'CONTINUE' ? '>' : 'X';
      addLog('warning', `${policyIcon} Interruption: ${info.source} -> ${info.policy}`);
      
      // Автоматическая обработка
      if (info.policy === 'PAUSE' && 
          recordingStatusRef.current.isRecording && 
          !recordingStatusRef.current.isPaused) {
        handlePauseFromInterruption(info.source).catch(err => {
          addLog('error', `Auto-pause failed: ${err}`);
        });
      }
    });

    // Подписка на окончание прерываний
    const endSub = Interruption.addInterruptionEndListener((event) => {
      if (!isMountedRef.current) return;
      
      addLog('info', `Interruption ended: ${event.source}`);
      
      // Автоматическое возобновление
      if (Interruption.shouldPauseRecording(event.source) && 
          recordingStatusRef.current.isPaused) {
        handleResumeFromInterruption(event.source).catch(err => {
          addLog('error', `Auto-resume failed: ${err}`);
        });
      }
    });

    // Подписка на звонки
    const phoneSub = Interruption.addPhoneCallListener((event) => {
      if (!isMountedRef.current) return;
      
      if (event.state === 'started') {
        addLog('warning', 'Phone call started');
      } else {
        addLog('info', 'Phone call ended');
      }
    });

    return () => {
      recorderSub.remove();
      eventSub.remove();
      interruptionSub.remove();
      endSub.remove();
      phoneSub.remove();
    };
  }, [hasPermissions]); // ✅ Только hasPermissions

  // === Обработка прерываний ===
  const handlePauseFromInterruption = async (source: string) => {
    if (pausingRef.current) return;
    pausingRef.current = true;
    
    try {
      await Recorder.pauseRecording();
      if (isMountedRef.current) {
        addLog('info', `Auto-paused due to ${source}`);
      }
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Auto-pause error: ${error}`);
      }
    } finally {
      pausingRef.current = false;
    }
  };

  const handleResumeFromInterruption = async (source: string) => {
    if (resumingRef.current) return;
    resumingRef.current = true;
    
    try {
      await Recorder.resumeRecording();
      if (isMountedRef.current) {
        addLog('info', `Auto-resumed after ${source}`);
      }
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Auto-resume error: ${error}`);
      }
    } finally {
      resumingRef.current = false;
    }
  };

  // === Действия с рекордером ===
  const startRecording = async () => {
    try {
      addLog('info', 'Starting recording...');
      
      // Включаем мониторинг прерываний
      if (!isMonitoring) {
        await Interruption.startMonitoring();
        setIsMonitoring(true);
        addLog('info', 'Interruption monitoring started');
      }
      
      const filePath = await Recorder.startRecording({
        sampleRate: 44100,
        bitRate: 128000,
        channels: 1,
        enableChunking: false,
      });
      
      if (isMountedRef.current) {
        addLog('success', `Recording started: ${filePath.split('/').pop()}`);
      }
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Start error: ${error}`);
      }
    }
  };

  const stopRecording = async () => {
    try {
      addLog('info', 'Stopping recording...');
      
      const result = await Recorder.stopRecording();
      
      if (isMountedRef.current) {
        addLog('success', `Saved: ${result.filePath.split('/').pop()}`);
        addLog('info', `Duration: ${result.duration.toFixed(1)}s, Size: ${(result.fileSize / 1024).toFixed(1)}KB`);
      }
      
      // Останавливаем мониторинг
      await Interruption.stopMonitoring();
      setIsMonitoring(false);
      addLog('info', 'Interruption monitoring stopped');
      
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Stop error: ${error}`);
      }
    }
  };

  const pauseRecording = async () => {
    try {
      await Recorder.pauseRecording();
      if (isMountedRef.current) {
        addLog('info', 'Recording paused');
      }
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Pause error: ${error}`);
      }
    }
  };

  const resumeRecording = async () => {
    try {
      await Recorder.resumeRecording();
      if (isMountedRef.current) {
        addLog('info', 'Recording resumed');
      }
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Resume error: ${error}`);
      }
    }
  };

  const cancelRecording = async () => {
    try {
      await Recorder.cancelRecording();
      if (isMountedRef.current) {
        addLog('warning', 'Recording canceled');
      }
      
      await Interruption.stopMonitoring();
      setIsMonitoring(false);
    } catch (error) {
      if (isMountedRef.current) {
        addLog('error', `Cancel error: ${error}`);
      }
    }
  };

  // === Форматирование ===
  const formatDuration = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const formatNoiseLevel = (db: number): string => {
    if (db <= -160) return 'Silent';
    if (db <= -40) return 'Low';
    if (db <= -20) return 'Medium';
    return 'Loud';
  };

  const getStateColor = () => {
    switch (recordingStatus.state) {
      case 'recording': return '#4CAF50';
      case 'paused': return '#FF9800';
      default: return '#9E9E9E';
    }
  };

  const getLogColor = (type: LogEntry['type']) => {
    switch (type) {
      case 'error': return '#F44336';
      case 'warning': return '#FF9800';
      case 'success': return '#4CAF50';
      default: return '#2196F3';
    }
  };

  // === Loading/Error States ===
  if (isInitializing) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.centerContainer}>
          <Text style={styles.loadingText}>Initializing...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!hasPermissions) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.centerContainer}>
          <Text style={styles.errorText}>Microphone Permission Required</Text>
          <TouchableOpacity 
            style={[styles.button, styles.startButton]} 
            onPress={() => {
              Recorder.requestPermissions()
                .then(p => setHasPermissions(p.granted))
                .catch(console.error);
            }}
          >
            <Text style={styles.buttonText}>Request Permission</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  // === Main Render ===
  return (
    <SafeAreaView style={styles.container}>
      <ScrollView style={styles.scrollView}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>Audio Recorder Test</Text>
          <Text style={styles.subtitle}>Core + Interruption Modules</Text>
        </View>

        {/* Status Panel */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Recording Status</Text>
          
          <View style={styles.statusRow}>
            <View style={[styles.statusIndicator, { backgroundColor: getStateColor() }]} />
            <Text style={styles.statusText}>
              {recordingStatus.state.toUpperCase()}
            </Text>
          </View>
          
          <Text style={styles.duration}>
            {formatDuration(recordingStatus.duration)}
          </Text>
          
          <Text style={styles.noiseLevel}>
            {formatNoiseLevel(recordingStatus.noiseLevel)} {recordingStatus.noiseLevel.toFixed(1)} dB
          </Text>
          
          {isMonitoring && (
            <View style={styles.monitoringBadge}>
              <Text style={styles.monitoringText}>Monitoring Active</Text>
            </View>
          )}
        </View>

        {/* Control Buttons */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Controls</Text>
          
          <View style={styles.buttonRow}>
            {!recordingStatus.isRecording ? (
              <TouchableOpacity 
                style={[styles.button, styles.startButton]} 
                onPress={startRecording}
              >
                <Text style={styles.buttonText}>Start</Text>
              </TouchableOpacity>
            ) : (
              <>
                <TouchableOpacity 
                  style={[styles.button, styles.stopButton]} 
                  onPress={stopRecording}
                >
                  <Text style={styles.buttonText}>Stop</Text>
                </TouchableOpacity>
                
                {!recordingStatus.isPaused ? (
                  <TouchableOpacity 
                    style={[styles.button, styles.pauseButton]} 
                    onPress={pauseRecording}
                  >
                    <Text style={styles.buttonText}>Pause</Text>
                  </TouchableOpacity>
                ) : (
                  <TouchableOpacity 
                    style={[styles.button, styles.resumeButton]} 
                    onPress={resumeRecording}
                  >
                    <Text style={styles.buttonText}>Resume</Text>
                  </TouchableOpacity>
                )}
                
                <TouchableOpacity 
                  style={[styles.button, styles.cancelButton]} 
                  onPress={cancelRecording}
                >
                  <Text style={styles.buttonText}>Cancel</Text>
                </TouchableOpacity>
              </>
            )}
          </View>
        </View>

        {/* Device Info */}
        <View style={styles.panel}>
          <View style={styles.panelHeader}>
            <Text style={styles.panelTitle}>Devices</Text>
            <TouchableOpacity onPress={refreshDeviceInfo}>
              <Text style={styles.refreshButton}>Refresh</Text>
            </TouchableOpacity>
          </View>
          
          {bluetoothState && (
            <View style={styles.deviceRow}>
              <Text style={styles.deviceLabel}>Bluetooth:</Text>
              <Text style={styles.deviceValue}>
                {bluetoothState.isConnected 
                  ? `${bluetoothState.isHeadset ? 'Headset' : 'Speaker'} ${bluetoothState.deviceName || 'Connected'}`
                  : 'Not connected'}
              </Text>
            </View>
          )}
          
          <View style={styles.deviceRow}>
            <Text style={styles.deviceLabel}>Microphones:</Text>
            <Text style={styles.deviceValue}>{microphones.length} found</Text>
          </View>
          
          {microphones.map((mic) => (
            <Text key={mic.id} style={styles.micItem}>
              {mic.isDefault ? '✓ ' : '  '}{mic.typeName}
            </Text>
          ))}
        </View>

        {/* Interruption Stats */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Interruption Stats</Text>
          
          {lastInterruption && (
            <View style={styles.lastInterruption}>
              <Text style={styles.lastInterruptionLabel}>Last:</Text>
              <Text style={styles.lastInterruptionValue}>
                {lastInterruption.source} → {lastInterruption.policy}
              </Text>
            </View>
          )}
          
          {Object.keys(interruptionStats).length > 0 ? (
            Object.entries(interruptionStats).map(([source, count]) => (
              <View key={source} style={styles.statRow}>
                <Text style={styles.statSource}>{source}</Text>
                <Text style={styles.statCount}>{count}</Text>
              </View>
            ))
          ) : (
            <Text style={styles.noStats}>No interruptions yet</Text>
          )}
        </View>

        {/* Logs */}
        <View style={styles.panel}>
          <View style={styles.panelHeader}>
            <Text style={styles.panelTitle}>Logs</Text>
            <TouchableOpacity onPress={() => setLogs([])}>
              <Text style={styles.clearButton}>Clear</Text>
            </TouchableOpacity>
          </View>
          
          {logs.length > 0 ? (
            logs.slice(0, 20).map((log) => (
              <View key={log.id} style={styles.logEntry}>
                <Text style={styles.logTime}>{log.time}</Text>
                <Text style={[styles.logMessage, { color: getLogColor(log.type) }]}>
                  {log.message}
                </Text>
              </View>
            ))
          ) : (
            <Text style={styles.noLogs}>No logs yet</Text>
          )}
        </View>

        {/* Info */}
        <View style={styles.infoPanel}>
          <Text style={styles.infoText}>
            Try: playing music, receiving calls, using voice assistant
          </Text>
        </View>

      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
  },
  centerContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  loadingText: {
    color: '#FFFFFF',
    fontSize: 18,
  },
  errorText: {
    color: '#F44336',
    fontSize: 18,
    marginBottom: 20,
    textAlign: 'center',
  },
  scrollView: {
    flex: 1,
  },
  header: {
    padding: 20,
    alignItems: 'center',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#FFFFFF',
  },
  subtitle: {
    fontSize: 14,
    color: '#888888',
    marginTop: 4,
  },
  panel: {
    backgroundColor: '#1E1E1E',
    marginHorizontal: 16,
    marginBottom: 16,
    borderRadius: 12,
    padding: 16,
  },
  panelHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  panelTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#FFFFFF',
    marginBottom: 12,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  statusIndicator: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 8,
  },
  statusText: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#FFFFFF',
  },
  duration: {
    fontSize: 48,
    fontWeight: '300',
    color: '#FFFFFF',
    textAlign: 'center',
    marginVertical: 16,
  },
  noiseLevel: {
    fontSize: 14,
    color: '#888888',
    textAlign: 'center',
  },
  monitoringBadge: {
    backgroundColor: '#2196F3',
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 12,
    alignSelf: 'center',
    marginTop: 12,
  },
  monitoringText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '600',
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'center',
    flexWrap: 'wrap',
  },
  button: {
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 8,
    minWidth: 80,
    alignItems: 'center',
    margin: 4,
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
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
  cancelButton: {
    backgroundColor: '#757575',
  },
  refreshButton: {
    color: '#2196F3',
    fontSize: 14,
  },
  clearButton: {
    color: '#2196F3',
    fontSize: 14,
  },
  deviceRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  deviceLabel: {
    color: '#888888',
    fontSize: 14,
  },
  deviceValue: {
    color: '#FFFFFF',
    fontSize: 14,
  },
  micItem: {
    color: '#AAAAAA',
    fontSize: 12,
    marginLeft: 16,
    marginTop: 4,
  },
  lastInterruption: {
    flexDirection: 'row',
    backgroundColor: '#2A2A2A',
    padding: 8,
    borderRadius: 8,
    marginBottom: 12,
  },
  lastInterruptionLabel: {
    color: '#888888',
    fontSize: 12,
    marginRight: 8,
  },
  lastInterruptionValue: {
    color: '#FF9800',
    fontSize: 12,
    fontWeight: '600',
  },
  statRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 4,
  },
  statSource: {
    color: '#AAAAAA',
    fontSize: 12,
  },
  statCount: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '600',
  },
  noStats: {
    color: '#666666',
    fontSize: 12,
    fontStyle: 'italic',
  },
  logEntry: {
    flexDirection: 'row',
    marginBottom: 4,
  },
  logTime: {
    color: '#666666',
    fontSize: 10,
    marginRight: 8,
    width: 60,
  },
  logMessage: {
    fontSize: 11,
    flex: 1,
  },
  noLogs: {
    color: '#666666',
    fontSize: 12,
    fontStyle: 'italic',
  },
  infoPanel: {
    marginHorizontal: 16,
    marginBottom: 32,
    padding: 16,
    backgroundColor: '#1A237E',
    borderRadius: 12,
  },
  infoText: {
    color: '#BBDEFB',
    fontSize: 12,
    textAlign: 'center',
  },
});