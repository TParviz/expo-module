import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View
} from 'react-native';

// Module imports
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
  const [maxDuration, setMaxDuration] = useState(15); // default 15 секунд для теста
  const [lastReason, setLastReason] = useState<string | null>(null);

  // === Logs ===
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const logIdRef = useRef(0);

  // === Permissions ===
  const [hasPermissions, setHasPermissions] = useState(false);
  const [isInitializing, setIsInitializing] = useState(true);

  // === Lifecycle guards ===
  const isMountedRef = useRef(true);

  // === Log helper ===
  const addLog = useCallback((type: LogEntry['type'], message: string) => {
    if (!isMountedRef.current) return;
    const time = new Date().toLocaleTimeString();
    setLogs(prev => [{ id: logIdRef.current++, time, type, message }, ...prev].slice(0, 50));
  }, []);

  // === Initialization ===
  useEffect(() => {
    isMountedRef.current = true;

    const init = async () => {
      try {
        setIsInitializing(true);
        const permission = await Recorder.requestPermissions();
        if (!permission.granted) {
          addLog('error', 'Microphone permission denied');
          setHasPermissions(false);
          Alert.alert('Permission Required', 'Microphone access is required', [{ text: 'OK' }]);
          return;
        }
        setHasPermissions(true);
        addLog('success', 'Microphone permission granted');

        // Check for unfinished recording
        const hasUnfinished = await Recorder.hasUnfinishedRecording();
        if (hasUnfinished) {
          addLog('warning', 'Found unfinished recording, recovering...');
          const recovered = await Recorder.recoverUnfinishedRecording();
          if (recovered) {
            addLog('success', `Recovered: ${recovered.duration.toFixed(1)}s`);
          }
        }
      } catch (error) {
        addLog('error', `Init error: ${error}`);
      } finally {
        setIsInitializing(false);
      }
    };

    init();

    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // === Event subscriptions ===
  useEffect(() => {
    if (!hasPermissions) return;

    // Recording state listener
    const stateSub = Recorder.addRecordingStateListener((status) => {
      if (!isMountedRef.current) return;
      setRecordingStatus(status);
    });

    // Recording event listener - HERE WE CHECK REASON
    const eventSub = Recorder.addRecordingEventListener((event) => {
      if (!isMountedRef.current) return;
      
      switch (event.type) {
        case 'completed':
          // NEW: Check reason
          const reason = event.reason || 'unknown';
          setLastReason(reason);
          
          if (reason === 'duration') {
            addLog('warning', `⏱️ AUTO-STOPPED by maxDuration!`);
            addLog('success', `Duration: ${event.duration?.toFixed(1)}s, File: ${event.filePath?.split('/').pop()}`);
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

    return () => {
      stateSub.remove();
      eventSub.remove();
    };
  }, [hasPermissions, addLog]);

  // === Recorder actions ===
  const startRecording = async () => {
    try {
      setLastReason(null);
      addLog('info', `Starting recording (maxDuration: ${maxDuration}s)...`);

      const filePath = await Recorder.startRecording({
        sampleRate: 44100,
        bitRate: 128000,
        channels: 1,
        maxDuration: maxDuration, // <-- NEW: Передаём maxDuration
      });

      addLog('success', `Recording started: ${filePath.split('/').pop()}`);
      addLog('info', `Will auto-stop in ${maxDuration} seconds`);
    } catch (error) {
      addLog('error', `Start error: ${error}`);
    }
  };

  const stopRecording = async () => {
    try {
      addLog('info', 'Stopping recording (user)...');
      const result = await Recorder.stopRecording();
      addLog('success', `Saved: ${result.filePath.split('/').pop()}`);
      addLog('info', `Duration: ${result.duration.toFixed(1)}s, Size: ${(result.fileSize / 1024).toFixed(1)}KB`);
    } catch (error) {
      addLog('error', `Stop error: ${error}`);
    }
  };

  const pauseRecording = async () => {
    try {
      await Recorder.pauseRecording();
      addLog('info', 'Recording paused (timer also paused)');
    } catch (error) {
      addLog('error', `Pause error: ${error}`);
    }
  };

  const resumeRecording = async () => {
    try {
      await Recorder.resumeRecording();
      addLog('info', 'Recording resumed (timer also resumed)');
    } catch (error) {
      addLog('error', `Resume error: ${error}`);
    }
  };

  const cancelRecording = async () => {
    try {
      await Recorder.cancelRecording();
      addLog('warning', 'Recording canceled');
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
          <Text style={styles.title}>MaxDuration Test</Text>
          <Text style={styles.subtitle}>expo-audio-recorder</Text>
        </View>

        {/* Status Panel */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Recording Status</Text>
          
          <View style={styles.statusRow}>
            <View style={[styles.statusIndicator, { backgroundColor: getStateColor() }]} />
            <Text style={styles.statusText}>{recordingStatus.state.toUpperCase()}</Text>
          </View>

          <Text style={styles.duration}>{formatDuration(recordingStatus.duration)}</Text>

          <Text style={styles.noiseLevel}>
            Noise: {recordingStatus.noiseLevel.toFixed(1)} dB
          </Text>

          {lastReason && (
            <View style={[styles.reasonBadge, lastReason === 'duration' ? styles.reasonDuration : styles.reasonUser]}>
              <Text style={styles.reasonText}>
                Last stop reason: {lastReason.toUpperCase()}
              </Text>
            </View>
          )}
        </View>

        {/* Max Duration Settings */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>⏱️ Max Duration (seconds)</Text>
          
          <View style={styles.durationRow}>
            {[10, 15, 30, 60].map((sec) => (
              <TouchableOpacity 
                key={sec}
                style={[styles.presetButton, maxDuration === sec && styles.presetButtonActive]}
                onPress={() => setMaxDuration(sec)}
                disabled={recordingStatus.isRecording}
              >
                <Text style={[styles.presetText, maxDuration === sec && styles.presetTextActive]}>
                  {sec}s
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
              placeholder="sec"
              placeholderTextColor="#666"
            />
            <Text style={styles.inputHint}>seconds</Text>
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
                <Text style={styles.buttonText}>▶ Start ({maxDuration}s)</Text>
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

        {/* Logs */}
        <View style={styles.panel}>
          <View style={styles.panelHeader}>
            <Text style={styles.panelTitle}>Logs</Text>
            <TouchableOpacity onPress={() => setLogs([])}>
              <Text style={styles.clearButton}>Clear</Text>
            </TouchableOpacity>
          </View>
          
          {logs.length > 0 ? (
            logs.map((log) => (
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
            📋 Test maxDuration:{'\n'}
            1. Set duration (e.g. 15s){'\n'}
            2. Press Start{'\n'}
            3. Wait for auto-stop{'\n'}
            4. Check "reason" in logs = "duration"{'\n\n'}
            ⏸ Pause/Resume: timer also pauses!
          </Text>
        </View>
      </ScrollView>
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
  duration: { 
    fontSize: 56, 
    fontWeight: '200', 
    color: '#FFFFFF', 
    textAlign: 'center', 
    marginVertical: 20,
    fontVariant: ['tabular-nums'],
  },
  noiseLevel: { fontSize: 14, color: '#888888', textAlign: 'center' },
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
  clearButton: { color: '#2196F3', fontSize: 14 },
  logEntry: { flexDirection: 'row', marginBottom: 6 },
  logTime: { color: '#666666', fontSize: 11, marginRight: 8, width: 70 },
  logMessage: { fontSize: 12, flex: 1 },
  noLogs: { color: '#666666', fontSize: 12, fontStyle: 'italic', textAlign: 'center' },
  infoPanel: { marginHorizontal: 16, marginBottom: 32, padding: 16, backgroundColor: '#1A237E', borderRadius: 12 },
  infoText: { color: '#BBDEFB', fontSize: 13, lineHeight: 20 },
});