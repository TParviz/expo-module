import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Modal,
  PermissionsAndroid,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View
} from 'react-native';

// Module imports
import * as Interruption from 'audio-recorder-helper';
import * as Recorder from 'expo-audio-recorder';

// Types
interface LogEntry {
  id: number;
  time: string;
  type: 'info' | 'warning' | 'error' | 'success';
  message: string;
}

export default function App() {
  // === Recorder state ===
  const [recordingStatus, setRecordingStatus] = useState<Recorder.RecordingStatus>({
    state: 'idle',
    filePath: "",
    duration: 0,
    isRecording: false,
    isPaused: false,
    noiseLevel: -160,
  });

  // === Max Duration state ===
  const [maxDuration, setMaxDuration] = useState(60);
  const [lastReason, setLastReason] = useState<string | null>(null);

  // === Interruption state ===
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [bluetoothState, setBluetoothState] = useState<Interruption.BluetoothState | null>(null);
  const [bluetoothPermissionGranted, setBluetoothPermissionGranted] = useState(false);
  const [microphones, setMicrophones] = useState<Interruption.MicrophoneInfo[]>([]);
  const [activeMicrophone, setActiveMicrophone] = useState<Interruption.MicrophoneInfo | null>(null);
  const [lastInterruption, setLastInterruption] = useState<Interruption.InterruptionInfo | null>(null);

  // === Microphone picker ===
  const [showMicrophonePicker, setShowMicrophonePicker] = useState(false);

  // === Stats ===
  const [interruptionStats, setInterruptionStats] = useState<Record<string, number>>({});

  // === Logs ===
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const logIdRef = useRef(0);

  // === Permissions ===
  const [hasPermissions, setHasPermissions] = useState(false);
  const [isInitializing, setIsInitializing] = useState(true);

  // === Lifecycle guards ===
  const isMountedRef = useRef(true);
  const pausingRef = useRef(false);
  const resumingRef = useRef(false);

  // === Log helper ===
  const addLog = useCallback((type: LogEntry['type'], message: string) => {
    if (!isMountedRef.current) return;
    const time = new Date().toLocaleTimeString();
    setLogs(prev => [{ id: logIdRef.current++, time, type, message }, ...prev].slice(0, 100));
  }, []);

  // === Bluetooth Permission Request ===
  const requestBluetoothPermissions = async () => {
    try {
      const status = await Interruption.getBluetoothPermissionStatus();
      
      if (status.hasPermission) {
        setBluetoothPermissionGranted(true);
        addLog('success', 'Bluetooth permission already granted');
        return true;
      }

      const requiredPermissions = await Interruption.getBluetoothRequiredPermissions();
      addLog('info', `Requesting Bluetooth permissions: ${requiredPermissions.join(', ')}`);

      type Permission = 
        | 'android.permission.BLUETOOTH_CONNECT'
        | 'android.permission.BLUETOOTH_SCAN'
        | 'android.permission.BLUETOOTH'
        | 'android.permission.BLUETOOTH_ADMIN';

      const results = await PermissionsAndroid.requestMultiple(
        requiredPermissions as Permission[]
      );

      const allGranted = Object.values(results).every(
        (result) => result === PermissionsAndroid.RESULTS.GRANTED
      );

      if (allGranted) {
        setBluetoothPermissionGranted(true);
        addLog('success', 'Bluetooth permissions granted');
        await Interruption.initializeBluetoothAfterPermission();
        return true;
      } else {
        setBluetoothPermissionGranted(false);
        const denied = Object.entries(results)
          .filter(([_, v]) => v !== PermissionsAndroid.RESULTS.GRANTED)
          .map(([k]) => k.split('.').pop());
        addLog('warning', `Bluetooth permissions denied: ${denied.join(', ')}`);
        return false;
      }
    } catch (error) {
      addLog('error', `Bluetooth permission error: ${error}`);
      setBluetoothPermissionGranted(false);
      return false;
    }
  };

  // === Refresh Device Info ===
  const refreshDeviceInfo = async () => {
    if (!isMountedRef.current) return;
    try {
      const bt = await Interruption.getBluetoothState();
      if (isMountedRef.current) setBluetoothState(bt);

      const mics = await Interruption.getAvailableMicrophones();
      if (isMountedRef.current) setMicrophones(mics);

      const active = await Interruption.getActiveMicrophone();
      if (isMountedRef.current) {
        setActiveMicrophone(active);
        addLog('info', `Found ${mics.length} microphones, Active: ${active?.name || 'none'}`);
      }
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Device info error: ${error}`);
    }
  };

  // === Initialization ===
  useEffect(() => {
    isMountedRef.current = true;

    const init = async () => {
      try {
        setIsInitializing(true);
        
        // Request microphone permission
        const permission = await Recorder.requestPermissions();
        if (!permission.granted) {
          addLog('error', 'Microphone permission denied');
          setHasPermissions(false);
          Alert.alert('Permission Required', 'Microphone access is required', [{ text: 'OK' }]);
          return;
        }
        setHasPermissions(true);
        addLog('success', 'Microphone permission granted');

        // Request Bluetooth permissions on Android
        if (Platform.OS === 'android') {
          await requestBluetoothPermissions();
        } else {
          setBluetoothPermissionGranted(true);
        }

        // Check for unfinished recording
        const hasUnfinished = await Recorder.hasUnfinishedRecording();
        if (hasUnfinished && isMountedRef.current) {
          addLog('warning', 'Found unfinished recording, recovering...');
          const recovered = await Recorder.recoverUnfinishedRecording();
          if (recovered && isMountedRef.current) {
            addLog('success', `Recovered: ${recovered.filePath}`);
          }
        }

        if (isMountedRef.current) await refreshDeviceInfo();
      } catch (error) {
        if (isMountedRef.current) addLog('error', `Init error: ${error}`);
      } finally {
        if (isMountedRef.current) setIsInitializing(false);
      }
    };

    init();

    return () => {
      isMountedRef.current = false;
      if (isMonitoring) Interruption.stopMonitoring().catch(console.error);
    };
  }, []);

  // === Microphone selection ===
  const handleSelectMicrophone = async (mic: Interruption.MicrophoneInfo) => {
    try {
      const result = await Interruption.selectMicrophone(mic.id);
      if (result.success && result.microphone) {
        setActiveMicrophone(result.microphone);
        addLog('success', `Selected microphone: ${result.microphone.name}`);
      } else {
        addLog('error', `Failed to select microphone: ${result.error}`);
      }
    } catch (error) {
      addLog('error', `Microphone selection error: ${error}`);
    }
    setShowMicrophonePicker(false);
  };

  const handleResetMicrophoneSelection = async () => {
    try {
      const result = await Interruption.resetMicrophoneSelection();
      if (result.success && result.microphone) {
        setActiveMicrophone(result.microphone);
        addLog('info', `Reset to auto: ${result.microphone.name}`);
      }
    } catch (error) {
      addLog('error', `Reset microphone error: ${error}`);
    }
    setShowMicrophonePicker(false);
  };

  // === Event subscriptions ===
  useEffect(() => {
    if (!hasPermissions) return;

    // Recording state listener
    const stateSub = Recorder.addRecordingStateListener((status) => {
      if (!isMountedRef.current) return;
      setRecordingStatus(status);
    });

    // Recording event listener
    const eventSub = Recorder.addRecordingEventListener((event) => {
      if (!isMountedRef.current) return;
      
      switch (event.type) {
        case 'completed':
          const reason = event.reason || 'unknown';
          setLastReason(reason);
          
          if (reason === 'duration') {
            addLog('warning', `⏱️ AUTO-STOPPED by maxDuration!`);
            addLog('success', `Duration: ${event.duration?.toFixed(1)}s`);
          } else if (reason === 'user') {
            addLog('success', `Stopped by user: ${event.duration?.toFixed(1)}s`);
          } else {
            addLog('success', `Recording completed: ${event.duration?.toFixed(1)}s (reason: ${reason})`);
          }
          break;
          
        case 'canceled':
          addLog('warning', 'Recording canceled');
          setLastReason(null);
          break;
          
        case 'error':
          addLog('error', `Recording error: ${event.error?.message}`);
          setLastReason('error');
          break;
          
        case 'cantHearMicrophone':
          addLog('warning', `Silence detected: ${event.silenceDuration?.toFixed(1)}s`);
          break;
      }
    });

    // Interruption listener
    const interruptionSub = Interruption.addInterruptionListener((info) => {
      if (!isMountedRef.current) return;
      setLastInterruption(info);
      setInterruptionStats(prev => ({ ...prev, [info.source]: (prev[info.source] || 0) + 1 }));
      const policyIcon = info.policy === 'PAUSE_AUTO' ? '⏸' : info.policy === 'CONTINUE_NOTIFY' ? '▶' : '·';
      addLog('warning', `${policyIcon} Interruption: ${info.source} → ${info.policy}`);
    });

    // Interruption end listener
    const endSub = Interruption.addInterruptionEndListener((event) => {
      if (!isMountedRef.current) return;
      addLog('info', `Interruption ended: ${event.source}`);
    });

    // Phone call listener
    const phoneSub = Interruption.addPhoneCallListener((event) => {
      if (!isMountedRef.current) return;
      if (event.state === 'started') addLog('warning', '📞 Phone call started');
      else addLog('info', '📞 Phone call ended');
    });

    // Microphone changed listener
    const micSub = Interruption.addMicrophoneChangedListener((mic) => {
      if (!isMountedRef.current) return;
      setActiveMicrophone(mic);
      addLog('info', `🎤 Microphone changed: ${mic.name}`);
    });

    // === NEW: Auto pause/resume ===
    const pauseSub = Interruption.addPauseRequestedListener(async (event) => {
      if (!isMountedRef.current) return;
      if (pausingRef.current) return;
      pausingRef.current = true;
      
      try {
        addLog('warning', `⏸ Auto-pausing: ${event.source}`);
        await Recorder.pauseRecording();
      } catch (error) {
        addLog('error', `Auto-pause error: ${error}`);
      } finally {
        pausingRef.current = false;
      }
    });

    const resumeSub = Interruption.addResumeRequestedListener(async (event) => {
      if (!isMountedRef.current) return;
      if (resumingRef.current) return;
      resumingRef.current = true;
      
      try {
        addLog('info', `▶ Auto-resuming: ${event.source}`);
        await Recorder.resumeRecording();
      } catch (error) {
        addLog('error', `Auto-resume error: ${error}`);
      } finally {
        resumingRef.current = false;
      }
    });

    return () => {
      stateSub.remove();
      eventSub.remove();
      interruptionSub.remove();
      endSub.remove();
      phoneSub.remove();
      micSub.remove();
      pauseSub.remove();
      resumeSub.remove();
    };
  }, [hasPermissions, addLog]);

  // === Recorder actions ===
  const startRecording = async () => {
    try {
      setLastReason(null);
      addLog('info', `Starting recording (maxDuration: ${maxDuration}s)...`);
      
      // Start monitoring
      if (!isMonitoring) {
        await Interruption.startMonitoring();
        setIsMonitoring(true);
        addLog('info', '👁 Interruption monitoring started');
      }

      const filePath = await Recorder.startRecording({
        sampleRate: 44100,
        bitRate: 128000,
        channels: 1,
        maxDuration: maxDuration,
      });

      addLog('success', `Recording started: ${filePath.split('/').pop()}`);
    } catch (error) {
      addLog('error', `Start error: ${error}`);
    }
  };

  const stopRecording = async () => {
    try {
      addLog('info', 'Stopping recording...');
      const result = await Recorder.stopRecording();
      addLog('success', `Saved: ${result.filePath.split('/').pop()}`);
      addLog('info', `Duration: ${result.duration.toFixed(1)}s, Size: ${(result.fileSize / 1024).toFixed(1)}KB`);
      
      // Stop monitoring
      await Interruption.stopMonitoring();
      setIsMonitoring(false);
    } catch (error) {
      addLog('error', `Stop error: ${error}`);
    }
  };

  const pauseRecording = async () => {
    try {
      await Recorder.pauseRecording();
      addLog('info', 'Recording paused');
    } catch (error) {
      addLog('error', `Pause error: ${error}`);
    }
  };

  const resumeRecording = async () => {
    try {
      await Recorder.resumeRecording();
      addLog('info', 'Recording resumed');
    } catch (error) {
      addLog('error', `Resume error: ${error}`);
    }
  };

  const cancelRecording = async () => {
    try {
      await Recorder.cancelRecording();
      addLog('warning', 'Recording canceled');
      
      // Stop monitoring
      await Interruption.stopMonitoring();
      setIsMonitoring(false);
    } catch (error) {
      addLog('error', `Cancel error: ${error}`);
    }
  };

  // === Formatting ===
  const formatDuration = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
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

  // === Loading State ===
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
            onPress={() => Recorder.requestPermissions().then(p => setHasPermissions(p.granted))}
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
          <Text style={styles.subtitle}>expo-audio-recorder + interruption</Text>
        </View>

        {/* Status Panel */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Recording Status</Text>
          
          <View style={styles.statusRow}>
            <View style={[styles.statusIndicator, { backgroundColor: getStateColor() }]} />
            <Text style={styles.statusText}>{recordingStatus.state.toUpperCase()}</Text>
            {isMonitoring && (
              <View style={styles.monitoringBadge}>
                <Text style={styles.monitoringText}>👁 Monitoring</Text>
              </View>
            )}
          </View>

          <Text style={styles.duration}>{formatDuration(recordingStatus.duration)}</Text>

          <Text style={styles.noiseLevel}>
            Noise: {recordingStatus.noiseLevel.toFixed(1)} dB
          </Text>

          {lastReason && (
            <View style={[styles.reasonBadge, lastReason === 'duration' ? styles.reasonDuration : styles.reasonUser]}>
              <Text style={styles.reasonText}>
                Last stop: {lastReason.toUpperCase()}
              </Text>
            </View>
          )}
        </View>

        {/* Max Duration */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>⏱️ Max Duration</Text>
          
          <View style={styles.durationRow}>
            {[30, 60, 120, 300].map((sec) => (
              <TouchableOpacity 
                key={sec}
                style={[styles.presetButton, maxDuration === sec && styles.presetButtonActive]}
                onPress={() => setMaxDuration(sec)}
                disabled={recordingStatus.isRecording}
              >
                <Text style={[styles.presetText, maxDuration === sec && styles.presetTextActive]}>
                  {sec < 60 ? `${sec}s` : `${sec / 60}m`}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          <View style={styles.inputRow}>
            <Text style={styles.inputLabel}>Custom:</Text>
            <TextInput
              style={styles.input}
              keyboardType="numeric"
              value={maxDuration.toString()}
              onChangeText={(text) => setMaxDuration(Math.max(1, parseInt(text) || 1))}
              editable={!recordingStatus.isRecording}
            />
            <Text style={styles.inputHint}>sec</Text>
          </View>

          {recordingStatus.isRecording && (
            <View style={styles.countdownContainer}>
              <Text style={styles.countdownLabel}>Auto-stop in:</Text>
              <Text style={styles.countdownValue}>
                {formatDuration(Math.max(0, maxDuration - recordingStatus.duration))}
              </Text>
            </View>
          )}
        </View>

        {/* Controls */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Controls</Text>
          
          <View style={styles.buttonRow}>
            {!recordingStatus.isRecording ? (
              <TouchableOpacity style={[styles.button, styles.startButton]} onPress={startRecording}>
                <Text style={styles.buttonText}>▶ Start</Text>
              </TouchableOpacity>
            ) : (
              <>
                <TouchableOpacity style={[styles.button, styles.stopButton]} onPress={stopRecording}>
                  <Text style={styles.buttonText}>⏹ Stop</Text>
                </TouchableOpacity>

                {!recordingStatus.isPaused ? (
                  <TouchableOpacity style={[styles.button, styles.pauseButton]} onPress={pauseRecording}>
                    <Text style={styles.buttonText}>⏸ Pause</Text>
                  </TouchableOpacity>
                ) : (
                  <TouchableOpacity style={[styles.button, styles.resumeButton]} onPress={resumeRecording}>
                    <Text style={styles.buttonText}>▶ Resume</Text>
                  </TouchableOpacity>
                )}

                <TouchableOpacity style={[styles.button, styles.cancelButton]} onPress={cancelRecording}>
                  <Text style={styles.buttonText}>✕ Cancel</Text>
                </TouchableOpacity>
              </>
            )}
          </View>
        </View>

        {/* Devices */}
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
                {bluetoothState.isConnected ? `${bluetoothState.deviceName || 'Connected'}` : 'Not connected'}
                {bluetoothState.isHeadset && ' 🎧'}
              </Text>
            </View>
          )}

          <TouchableOpacity 
            style={styles.microphoneSelector}
            onPress={() => setShowMicrophonePicker(true)}
          >
            <View style={styles.microphoneValue}>
              <Text style={styles.deviceLabel}>Microphone:</Text>
              <Text style={styles.deviceValue}>{activeMicrophone?.name || 'Auto'}</Text>
              {activeMicrophone?.isSelected && <Text style={styles.manualBadge}>MANUAL</Text>}
            </View>
            <Text style={styles.tapToChange}>Tap to change</Text>
          </TouchableOpacity>
        </View>

        {/* Interruptions */}
        <View style={styles.panel}>
          <View style={styles.panelHeader}>
            <Text style={styles.panelTitle}>Interruptions</Text>
            <TouchableOpacity onPress={() => setInterruptionStats({})}>
              <Text style={styles.clearButton}>Clear</Text>
            </TouchableOpacity>
          </View>

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
                <Text style={[styles.logMessage, { color: getLogColor(log.type) }]}>{log.message}</Text>
              </View>
            ))
          ) : (
            <Text style={styles.noLogs}>No logs yet</Text>
          )}
        </View>

        {/* Info */}
        <View style={styles.infoPanel}>
          <Text style={styles.infoText}>
            📋 Features:{'\n'}
            • maxDuration with auto-stop{'\n'}
            • Auto pause/resume on interruptions{'\n'}
            • Bluetooth & microphone management{'\n'}
            • Interruption notifications
          </Text>
        </View>
      </ScrollView>

      {/* Microphone Picker Modal */}
      <Modal visible={showMicrophonePicker} transparent animationType="slide" onRequestClose={() => setShowMicrophonePicker(false)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>Select Microphone</Text>
            
            <TouchableOpacity style={styles.microphoneOption} onPress={handleResetMicrophoneSelection}>
              <View style={styles.micOptionContent}>
                <Text style={styles.micOptionName}>Auto</Text>
                <Text style={styles.micOptionDesc}>System will choose the best microphone</Text>
              </View>
              {!activeMicrophone?.isSelected && <Text style={styles.micOptionCheck}>✓</Text>}
            </TouchableOpacity>

            {microphones.map((mic) => (
              <TouchableOpacity key={mic.id} style={styles.microphoneOption} onPress={() => handleSelectMicrophone(mic)}>
                <View style={styles.micOptionContent}>
                  <Text style={styles.micOptionName}>{mic.name}</Text>
                  <Text style={styles.micOptionDesc}>{Interruption.getMicrophoneTypeName(mic.type)}</Text>
                </View>
                {activeMicrophone?.id === mic.id && activeMicrophone?.isSelected && <Text style={styles.micOptionCheck}>✓</Text>}
              </TouchableOpacity>
            ))}

            <TouchableOpacity style={styles.modalCloseButton} onPress={() => setShowMicrophonePicker(false)}>
              <Text style={styles.modalCloseText}>Cancel</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#121212' },
  centerContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 },
  loadingText: { color: '#FFFFFF', fontSize: 18 },
  errorText: { color: '#F44336', fontSize: 18, marginBottom: 20, textAlign: 'center' },
  scrollView: { flex: 1 },
  header: { padding: 20, alignItems: 'center' },
  title: { fontSize: 24, fontWeight: 'bold', color: '#FFFFFF' },
  subtitle: { fontSize: 14, color: '#888888', marginTop: 4 },
  panel: { backgroundColor: '#1E1E1E', marginHorizontal: 16, marginBottom: 16, borderRadius: 12, padding: 16 },
  panelHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  panelTitle: { fontSize: 16, fontWeight: '600', color: '#FFFFFF', marginBottom: 12 },
  statusRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  statusIndicator: { width: 12, height: 12, borderRadius: 6, marginRight: 8 },
  statusText: { fontSize: 18, fontWeight: 'bold', color: '#FFFFFF' },
  duration: { fontSize: 48, fontWeight: '300', color: '#FFFFFF', textAlign: 'center', marginVertical: 16, fontVariant: ['tabular-nums'] },
  noiseLevel: { fontSize: 14, color: '#888888', textAlign: 'center' },
  monitoringBadge: { backgroundColor: '#2196F3', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 8, marginLeft: 8 },
  monitoringText: { color: '#FFFFFF', fontSize: 10, fontWeight: '600' },
  reasonBadge: { marginTop: 12, padding: 8, borderRadius: 8, alignSelf: 'center' },
  reasonDuration: { backgroundColor: '#FF9800' },
  reasonUser: { backgroundColor: '#4CAF50' },
  reasonText: { color: '#FFFFFF', fontSize: 12, fontWeight: '600' },
  durationRow: { flexDirection: 'row', justifyContent: 'center', gap: 8, marginBottom: 16 },
  presetButton: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20, backgroundColor: '#2A2A2A' },
  presetButtonActive: { backgroundColor: '#4CAF50' },
  presetText: { color: '#888888', fontSize: 14 },
  presetTextActive: { color: '#FFFFFF', fontWeight: '600' },
  inputRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center' },
  inputLabel: { color: '#888888', fontSize: 14, marginRight: 8 },
  input: { backgroundColor: '#2A2A2A', color: '#FFFFFF', paddingHorizontal: 16, paddingVertical: 8, borderRadius: 8, width: 80, textAlign: 'center', fontSize: 16 },
  inputHint: { color: '#666666', fontSize: 12, marginLeft: 8 },
  countdownContainer: { marginTop: 16, alignItems: 'center', backgroundColor: '#2A2A2A', padding: 12, borderRadius: 8 },
  countdownLabel: { color: '#888888', fontSize: 12 },
  countdownValue: { color: '#FF9800', fontSize: 24, fontWeight: 'bold', fontVariant: ['tabular-nums'] },
  buttonRow: { flexDirection: 'row', justifyContent: 'center', flexWrap: 'wrap', gap: 8 },
  button: { paddingHorizontal: 20, paddingVertical: 14, borderRadius: 8, minWidth: 100, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontSize: 16, fontWeight: '600' },
  startButton: { backgroundColor: '#4CAF50' },
  stopButton: { backgroundColor: '#F44336' },
  pauseButton: { backgroundColor: '#FF9800' },
  resumeButton: { backgroundColor: '#2196F3' },
  cancelButton: { backgroundColor: '#757575' },
  refreshButton: { color: '#2196F3', fontSize: 14 },
  clearButton: { color: '#2196F3', fontSize: 14 },
  deviceRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 8 },
  deviceLabel: { color: '#888888', fontSize: 14 },
  deviceValue: { color: '#FFFFFF', fontSize: 14 },
  microphoneSelector: { backgroundColor: '#2A2A2A', padding: 12, borderRadius: 8 },
  microphoneValue: { flexDirection: 'row', alignItems: 'center' },
  manualBadge: { backgroundColor: '#4CAF50', color: '#FFFFFF', fontSize: 10, paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4, marginLeft: 8, overflow: 'hidden' },
  tapToChange: { color: '#2196F3', fontSize: 12, marginTop: 4 },
  lastInterruption: { flexDirection: 'row', backgroundColor: '#2A2A2A', padding: 8, borderRadius: 8, marginBottom: 12 },
  lastInterruptionLabel: { color: '#888888', fontSize: 12, marginRight: 8 },
  lastInterruptionValue: { color: '#FF9800', fontSize: 12, fontWeight: '600' },
  statRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 4 },
  statSource: { color: '#AAAAAA', fontSize: 12 },
  statCount: { color: '#FFFFFF', fontSize: 12, fontWeight: '600' },
  noStats: { color: '#666666', fontSize: 12, fontStyle: 'italic' },
  logEntry: { flexDirection: 'row', marginBottom: 4 },
  logTime: { color: '#666666', fontSize: 10, marginRight: 8, width: 60 },
  logMessage: { fontSize: 11, flex: 1 },
  noLogs: { color: '#666666', fontSize: 12, fontStyle: 'italic' },
  infoPanel: { marginHorizontal: 16, marginBottom: 32, padding: 16, backgroundColor: '#1A237E', borderRadius: 12 },
  infoText: { color: '#BBDEFB', fontSize: 12, lineHeight: 18 },
  // Modal
  modalOverlay: { flex: 1, backgroundColor: 'rgba(0, 0, 0, 0.7)', justifyContent: 'flex-end' },
  modalContent: { backgroundColor: '#1E1E1E', borderTopLeftRadius: 20, borderTopRightRadius: 20, padding: 20, maxHeight: '70%' },
  modalTitle: { fontSize: 20, fontWeight: 'bold', color: '#FFFFFF', textAlign: 'center', marginBottom: 20 },
  microphoneOption: { flexDirection: 'row', alignItems: 'center', padding: 16, backgroundColor: '#2A2A2A', borderRadius: 12, marginBottom: 8 },
  micOptionContent: { flex: 1 },
  micOptionName: { color: '#FFFFFF', fontSize: 16, fontWeight: '500' },
  micOptionDesc: { color: '#888888', fontSize: 12, marginTop: 2 },
  micOptionCheck: { color: '#4CAF50', fontSize: 20, fontWeight: 'bold' },
  modalCloseButton: { padding: 16, alignItems: 'center', marginTop: 8 },
  modalCloseText: { color: '#2196F3', fontSize: 16, fontWeight: '600' },
});