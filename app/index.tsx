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
  View,
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

  // === Interruption state ===
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [bluetoothState, setBluetoothState] = useState<Interruption.BluetoothState | null>(null);
  const [bluetoothPermissionGranted, setBluetoothPermissionGranted] = useState(false);
  const [microphones, setMicrophones] = useState<Interruption.MicrophoneInfo[]>([]);
  const [activeMicrophone, setActiveMicrophone] = useState<Interruption.MicrophoneInfo | null>(null);
  const [lastInterruption, setLastInterruption] = useState<Interruption.InterruptionInfo | null>(null);

  // === Recording Time Limit state ===
  const [timeLimitEnabled, setTimeLimitEnabled] = useState(false);
  const [maxDurationSeconds, setMaxDurationSeconds] = useState(60);
  const [warningBeforeEndSeconds, setWarningBeforeEndSeconds] = useState(10);
  const [timerElapsed, setTimerElapsed] = useState(0);
  const [timerRemaining, setTimerRemaining] = useState(0);

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

  // === Timer fallback ===
  const [jsTimerDuration, setJsTimerDuration] = useState(0);
  const timerStartRef = useRef<number | null>(null);
  const timerIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // === Log helper ===
  const addLog = useCallback((type: LogEntry['type'], message: string) => {
    if (!isMountedRef.current) return;
    const time = new Date().toLocaleTimeString();
    setLogs(prev => [{ id: logIdRef.current++, time, type, message }, ...prev].slice(0, 100));
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
          Alert.alert('Permission Required', 'Microphone access is required for recording', [{ text: 'OK' }]);
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
      stopJsTimer();
      if (isMonitoring) Interruption.stopMonitoring().catch(console.error);
      Interruption.stopRecordingTimer().catch(console.error);
    };
  }, []);

  // === Bluetooth Permission Request ===
  const requestBluetoothPermissions = async () => {
    try {
      // Check current status
      const status = await Interruption.getBluetoothPermissionStatus();
      
      if (status.hasPermission) {
        setBluetoothPermissionGranted(true);
        addLog('success', 'Bluetooth permission already granted');
        return true;
      }

      // Get required permissions
      const requiredPermissions = await Interruption.getBluetoothRequiredPermissions();
      addLog('info', `Requesting Bluetooth permissions: ${requiredPermissions.join(', ')}`);

      // Request permissions
      const results = await PermissionsAndroid.requestMultiple(
        requiredPermissions as Permission[]
      );

      // Check if all granted
      const allGranted = Object.values(results).every(
        (result) => result === PermissionsAndroid.RESULTS.GRANTED
      );

      if (allGranted) {
        setBluetoothPermissionGranted(true);
        addLog('success', 'Bluetooth permissions granted');
        
        // Initialize Bluetooth after permission
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

  // Type for PermissionsAndroid.requestMultiple
  type Permission = 
    | 'android.permission.BLUETOOTH_CONNECT'
    | 'android.permission.BLUETOOTH_SCAN'
    | 'android.permission.BLUETOOTH'
    | 'android.permission.BLUETOOTH_ADMIN';

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

  // === JS Timer fallback ===
  const startJsTimer = useCallback(() => {
    if (timerIntervalRef.current) return;
    timerStartRef.current = Date.now();
    setJsTimerDuration(0);
    timerIntervalRef.current = setInterval(() => {
      if (!isMountedRef.current) return;
      const elapsed = (Date.now() - (timerStartRef.current || Date.now())) / 1000;
      setJsTimerDuration(elapsed);
    }, 100);
    addLog('info', '[Timer] Started JS fallback timer');
  }, []);

  const stopJsTimer = useCallback(() => {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
      timerStartRef.current = null;
      setJsTimerDuration(0);
    }
  }, []);

  const pauseJsTimer = useCallback(() => {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
  }, []);

  const resumeJsTimer = useCallback(() => {
    if (!timerIntervalRef.current && timerStartRef.current) {
      const pausedDuration = jsTimerDuration;
      timerStartRef.current = Date.now() - (pausedDuration * 1000);
      timerIntervalRef.current = setInterval(() => {
        if (!isMountedRef.current) return;
        const elapsed = (Date.now() - (timerStartRef.current || Date.now())) / 1000;
        setJsTimerDuration(elapsed);
      }, 100);
    }
  }, [jsTimerDuration]);

  // === Event subscriptions ===
  useEffect(() => {
    if (!hasPermissions) return;
    const statusRef = { current: recordingStatus };

    const recorderSub = Recorder.addRecordingStateListener((status) => {
      if (!isMountedRef.current) return;
      if (status.isRecording && status.duration > 0) stopJsTimer();
      else if (status.isRecording && status.duration === 0 && !timerIntervalRef.current) startJsTimer();
      setRecordingStatus(status);
      statusRef.current = status;
      if (!status.isRecording && timerIntervalRef.current) stopJsTimer();
      else if (status.isPaused && timerIntervalRef.current) pauseJsTimer();
    });

    const eventSub = Recorder.addRecordingEventListener((event) => {
      if (!isMountedRef.current) return;
      switch (event.type) {
        case 'completed':
          addLog('success', `Recording completed: ${event.duration?.toFixed(1)}s`);
          stopJsTimer();
          break;
        case 'canceled':
          addLog('warning', 'Recording canceled');
          stopJsTimer();
          break;
        case 'error':
          addLog('error', `Recording error: ${event.error?.message}`);
          stopJsTimer();
          break;
      }
    });

    const interruptionSub = Interruption.addInterruptionListener((info) => {
      if (!isMountedRef.current) return;
      setLastInterruption(info);
      setInterruptionStats(prev => ({ ...prev, [info.source]: (prev[info.source] || 0) + 1 }));
      const policyIcon = info.policy === 'PAUSE_AUTO' ? '||' : info.policy === 'CONTINUE_NOTIFY' ? '>' : '-';
      addLog('warning', `${policyIcon} Interruption: ${info.source} -> ${info.policy}`);
      if (info.policy === 'PAUSE_AUTO' && statusRef.current.isRecording && !statusRef.current.isPaused) {
        handlePauseFromInterruption(info.source).catch(err => addLog('error', `Auto-pause failed: ${err}`));
      }
    });

    const endSub = Interruption.addInterruptionEndListener((event) => {
      if (!isMountedRef.current) return;
      addLog('info', `Interruption ended: ${event.source}`);
      if (Interruption.shouldPauseRecording(event.source) && statusRef.current.isPaused) {
        handleResumeFromInterruption(event.source).catch(err => addLog('error', `Auto-resume failed: ${err}`));
      }
    });

    const phoneSub = Interruption.addPhoneCallListener((event) => {
      if (!isMountedRef.current) return;
      if (event.state === 'started') addLog('warning', 'Phone call started');
      else addLog('info', 'Phone call ended');
    });

    const micSub = Interruption.addMicrophoneChangedListener((mic) => {
      if (!isMountedRef.current) return;
      setActiveMicrophone(mic);
      addLog('info', `Microphone changed: ${mic.name}`);
    });

    // === Recording Timer subscriptions ===
    const timerTickSub = Interruption.addRecordingTimerTickListener((event) => {
      if (!isMountedRef.current) return;
      setTimerElapsed(event.elapsedSeconds);
      setTimerRemaining(event.remainingSeconds);
    });

    const timerWarningSub = Interruption.addRecordingTimeWarningListener((event) => {
      if (!isMountedRef.current) return;
      addLog('warning', `⏰ Recording ending in ${event.remainingSeconds} seconds!`);
    });

    const timerLimitSub = Interruption.addRecordingTimeLimitListener(async (event) => {
      if (!isMountedRef.current) return;
      addLog('warning', `⏱️ Time limit reached! Recorded ${event.elapsedSeconds}s`);
      try {
        const result = await Recorder.stopRecording();
        stopJsTimer();
        if (isMountedRef.current) {
          addLog('success', `Auto-saved: ${result.filePath.split('/').pop()}`);
          addLog('info', `Duration: ${result.duration.toFixed(1)}s (limit: ${event.maxDurationSeconds}s)`);
        }
        await Interruption.stopMonitoring();
        setIsMonitoring(false);
        setTimerElapsed(0);
        setTimerRemaining(0);
      } catch (error) {
        if (isMountedRef.current) addLog('error', `Auto-stop error: ${error}`);
      }
    });

    return () => {
      recorderSub.remove();
      eventSub.remove();
      interruptionSub.remove();
      endSub.remove();
      phoneSub.remove();
      micSub.remove();
      timerTickSub.remove();
      timerWarningSub.remove();
      timerLimitSub.remove();
    };
  }, [hasPermissions, startJsTimer, stopJsTimer, pauseJsTimer]);

  // === Interruption handlers ===
  const handlePauseFromInterruption = async (source: string) => {
    if (pausingRef.current) return;
    pausingRef.current = true;
    try {
      await Recorder.pauseRecording();
      pauseJsTimer();
      if (timeLimitEnabled) await Interruption.pauseRecordingTimer();
      if (isMountedRef.current) addLog('info', `Auto-paused due to ${source}`);
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Auto-pause error: ${error}`);
    } finally {
      pausingRef.current = false;
    }
  };

  const handleResumeFromInterruption = async (source: string) => {
    if (resumingRef.current) return;
    resumingRef.current = true;
    try {
      await Recorder.resumeRecording();
      resumeJsTimer();
      if (timeLimitEnabled) await Interruption.resumeRecordingTimer();
      if (isMountedRef.current) addLog('info', `Auto-resumed after ${source}`);
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Auto-resume error: ${error}`);
    } finally {
      resumingRef.current = false;
    }
  };

  // === Recorder actions ===
  const startRecording = async () => {
    try {
      addLog('info', 'Starting recording...');
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
      startJsTimer();

      if (timeLimitEnabled && maxDurationSeconds > 0) {
        await Interruption.startRecordingTimer({ maxDurationSeconds, warningBeforeEndSeconds });
        setTimerRemaining(maxDurationSeconds);
        addLog('info', `⏱️ Time limit: ${maxDurationSeconds}s (warning at ${warningBeforeEndSeconds}s before)`);
      }

      if (isMountedRef.current) addLog('success', `Recording started: ${filePath.split('/').pop()}`);
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Start error: ${error}`);
    }
  };

  const stopRecording = async () => {
    try {
      addLog('info', 'Stopping recording...');
      const result = await Recorder.stopRecording();
      stopJsTimer();
      await Interruption.stopRecordingTimer();
      setTimerElapsed(0);
      setTimerRemaining(0);
      if (isMountedRef.current) {
        addLog('success', `Saved: ${result.filePath.split('/').pop()}`);
        addLog('info', `Duration: ${result.duration.toFixed(1)}s, Size: ${(result.fileSize / 1024).toFixed(1)}KB`);
      }
      await Interruption.stopMonitoring();
      setIsMonitoring(false);
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Stop error: ${error}`);
    }
  };

  const pauseRecording = async () => {
    try {
      await Recorder.pauseRecording();
      pauseJsTimer();
      if (timeLimitEnabled) await Interruption.pauseRecordingTimer();
      if (isMountedRef.current) addLog('info', 'Recording paused');
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Pause error: ${error}`);
    }
  };

  const resumeRecording = async () => {
    try {
      await Recorder.resumeRecording();
      resumeJsTimer();
      if (timeLimitEnabled) await Interruption.resumeRecordingTimer();
      if (isMountedRef.current) addLog('info', 'Recording resumed');
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Resume error: ${error}`);
    }
  };

  const cancelRecording = async () => {
    try {
      await Recorder.cancelRecording();
      stopJsTimer();
      await Interruption.stopRecordingTimer();
      setTimerElapsed(0);
      setTimerRemaining(0);
      if (isMountedRef.current) addLog('warning', 'Recording canceled');
      await Interruption.stopMonitoring();
      setIsMonitoring(false);
    } catch (error) {
      if (isMountedRef.current) addLog('error', `Cancel error: ${error}`);
    }
  };

  // === Formatting ===
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

  const displayDuration = recordingStatus.duration > 0 ? recordingStatus.duration : jsTimerDuration;

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
            onPress={() => Recorder.requestPermissions().then(p => setHasPermissions(p.granted)).catch(console.error)}
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
        <View style={styles.header}>
          <Text style={styles.title}>Audio Recorder Test</Text>
          <Text style={styles.subtitle}>Core + Interruption + Time Limit</Text>
        </View>

        {/* Status Panel */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Recording Status</Text>
          <View style={styles.statusRow}>
            <View style={[styles.statusIndicator, { backgroundColor: getStateColor() }]} />
            <Text style={styles.statusText}>{recordingStatus.state.toUpperCase()}</Text>
          </View>

          <Text style={styles.duration}>{formatDuration(displayDuration)}</Text>

          {timeLimitEnabled && recordingStatus.isRecording && (
            <View style={styles.timeLimitDisplay}>
              <Text style={styles.timeLimitText}>⏱️ {formatDuration(timerRemaining)} remaining</Text>
              <View style={styles.progressBar}>
                <View
                  style={[
                    styles.progressFill,
                    {
                      width: `${Math.max(0, (timerRemaining / maxDurationSeconds) * 100)}%`,
                      backgroundColor: timerRemaining <= warningBeforeEndSeconds ? '#FF9800' : '#4CAF50',
                    },
                  ]}
                />
              </View>
            </View>
          )}

          {recordingStatus.isRecording && (
            <Text style={styles.timerSource}>{recordingStatus.duration > 0 ? '(Native)' : '(JS Fallback)'}</Text>
          )}

          <Text style={styles.noiseLevel}>
            {formatNoiseLevel(recordingStatus.noiseLevel)} {recordingStatus.noiseLevel.toFixed(1)} dB
          </Text>

          {isMonitoring && (
            <View style={styles.monitoringBadge}>
              <Text style={styles.monitoringText}>Monitoring Active</Text>
            </View>
          )}
        </View>

        {/* Time Limit Settings */}
        <View style={styles.panel}>
          <View style={styles.panelHeader}>
            <Text style={styles.panelTitle}>⏱️ Time Limit</Text>
            <TouchableOpacity
              onPress={() => setTimeLimitEnabled(!timeLimitEnabled)}
              style={[styles.toggleButton, timeLimitEnabled && styles.toggleButtonActive]}
            >
              <Text style={styles.toggleButtonText}>{timeLimitEnabled ? 'ON' : 'OFF'}</Text>
            </TouchableOpacity>
          </View>

          {timeLimitEnabled && (
            <View style={styles.timeLimitSettings}>
              <View style={styles.settingRow}>
                <Text style={styles.settingLabel}>Max Duration:</Text>
                <View style={styles.settingInputRow}>
                  {[30, 60, 300, 600].map((sec) => (
                    <TouchableOpacity key={sec} style={styles.presetButton} onPress={() => setMaxDurationSeconds(sec)}>
                      <Text style={[styles.presetText, maxDurationSeconds === sec && styles.presetTextActive]}>
                        {sec < 60 ? `${sec}s` : `${sec / 60}m`}
                      </Text>
                    </TouchableOpacity>
                  ))}
                </View>
              </View>

              <View style={styles.settingRow}>
                <Text style={styles.settingLabel}>Current: {formatDuration(maxDurationSeconds)}</Text>
                <TextInput
                  style={styles.settingInput}
                  keyboardType="numeric"
                  value={maxDurationSeconds.toString()}
                  onChangeText={(text) => setMaxDurationSeconds(Math.max(1, parseInt(text) || 0))}
                  placeholder="seconds"
                  placeholderTextColor="#666"
                />
              </View>

              <View style={styles.settingRow}>
                <Text style={styles.settingLabel}>Warning before end:</Text>
                <TextInput
                  style={styles.settingInput}
                  keyboardType="numeric"
                  value={warningBeforeEndSeconds.toString()}
                  onChangeText={(text) => setWarningBeforeEndSeconds(Math.max(0, parseInt(text) || 0))}
                  placeholder="seconds"
                  placeholderTextColor="#666"
                />
                <Text style={styles.settingHint}>sec</Text>
              </View>
            </View>
          )}
        </View>

        {/* Controls */}
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Controls</Text>
          <View style={styles.buttonRow}>
            {!recordingStatus.isRecording ? (
              <TouchableOpacity style={[styles.button, styles.startButton]} onPress={startRecording}>
                <Text style={styles.buttonText}>
                  {timeLimitEnabled ? `Start (${formatDuration(maxDurationSeconds)})` : 'Start'}
                </Text>
              </TouchableOpacity>
            ) : (
              <>
                <TouchableOpacity style={[styles.button, styles.stopButton]} onPress={stopRecording}>
                  <Text style={styles.buttonText}>Stop</Text>
                </TouchableOpacity>
                {!recordingStatus.isPaused ? (
                  <TouchableOpacity style={[styles.button, styles.pauseButton]} onPress={pauseRecording}>
                    <Text style={styles.buttonText}>Pause</Text>
                  </TouchableOpacity>
                ) : (
                  <TouchableOpacity style={[styles.button, styles.resumeButton]} onPress={resumeRecording}>
                    <Text style={styles.buttonText}>Resume</Text>
                  </TouchableOpacity>
                )}
                <TouchableOpacity style={[styles.button, styles.cancelButton]} onPress={cancelRecording}>
                  <Text style={styles.buttonText}>Cancel</Text>
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
                {!bluetoothPermissionGranted
                  ? 'Permission required'
                  : bluetoothState.isConnected
                    ? `${bluetoothState.isHeadset ? 'Headset' : 'Speaker'} ${bluetoothState.deviceName || 'Connected'}`
                    : 'Not connected'}
              </Text>
            </View>
          )}

          {!bluetoothPermissionGranted && Platform.OS === 'android' && (
            <TouchableOpacity
              style={[styles.button, { backgroundColor: '#2196F3', marginBottom: 12 }]}
              onPress={requestBluetoothPermissions}
            >
              <Text style={styles.buttonText}>Grant Bluetooth Permission</Text>
            </TouchableOpacity>
          )}

          <TouchableOpacity style={styles.microphoneSelector} onPress={() => setShowMicrophonePicker(true)}>
            <View style={styles.deviceRow}>
              <Text style={styles.deviceLabel}>Microphone:</Text>
              <View style={styles.microphoneValue}>
                <Text style={styles.deviceValue}>{activeMicrophone?.name || 'Not selected'}</Text>
                {activeMicrophone?.isSelected && <Text style={styles.manualBadge}>Manual</Text>}
              </View>
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
            Time Limit: Recording will automatically stop when the time limit is reached.{'\n'}Pause time is not counted towards the limit.
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
  duration: { fontSize: 48, fontWeight: '300', color: '#FFFFFF', textAlign: 'center', marginVertical: 16, ...(Platform.OS === 'ios' && { fontVariant: ['tabular-nums'] }) },
  timerSource: { fontSize: 10, color: '#666666', textAlign: 'center', marginTop: -12, marginBottom: 8 },
  noiseLevel: { fontSize: 14, color: '#888888', textAlign: 'center' },
  monitoringBadge: { backgroundColor: '#2196F3', paddingHorizontal: 12, paddingVertical: 4, borderRadius: 12, alignSelf: 'center', marginTop: 12 },
  monitoringText: { color: '#FFFFFF', fontSize: 12, fontWeight: '600' },
  buttonRow: { flexDirection: 'row', justifyContent: 'center', flexWrap: 'wrap' },
  button: { paddingHorizontal: 20, paddingVertical: 12, borderRadius: 8, minWidth: 80, alignItems: 'center', margin: 4 },
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
  microphoneSelector: { backgroundColor: '#2A2A2A', padding: 12, borderRadius: 8, marginBottom: 12 },
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
  infoText: { color: '#BBDEFB', fontSize: 12, textAlign: 'center' },
  // Time Limit Styles
  timeLimitDisplay: { backgroundColor: '#2A2A2A', padding: 12, borderRadius: 8, marginBottom: 12 },
  timeLimitText: { color: '#FFFFFF', fontSize: 16, fontWeight: '600', textAlign: 'center', marginBottom: 8 },
  progressBar: { height: 6, backgroundColor: '#444444', borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: '100%', borderRadius: 3 },
  toggleButton: { paddingHorizontal: 16, paddingVertical: 6, borderRadius: 16, backgroundColor: '#444444' },
  toggleButtonActive: { backgroundColor: '#4CAF50' },
  toggleButtonText: { color: '#FFFFFF', fontSize: 12, fontWeight: '600' },
  timeLimitSettings: { marginTop: 8 },
  settingRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
  settingLabel: { color: '#888888', fontSize: 14, flex: 1 },
  settingInputRow: { flexDirection: 'row', gap: 8 },
  settingInput: { backgroundColor: '#2A2A2A', color: '#FFFFFF', paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8, width: 80, textAlign: 'center' },
  settingHint: { color: '#666666', fontSize: 12, marginLeft: 4 },
  presetButton: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 16, backgroundColor: '#2A2A2A' },
  presetText: { color: '#888888', fontSize: 12 },
  presetTextActive: { color: '#4CAF50', fontWeight: '600' },
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