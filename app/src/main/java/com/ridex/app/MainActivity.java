package com.voicecallpro.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 100;

    // UI
    private SwitchMaterial    switchWifiBt;
    private SwitchMaterial    switchBtMic;
    private TextView          tvStatus, tvSignal, tvTimer, tvRoomCodeDisplay;
    private Button            btnHost, btnCall, btnMute, btnSpeaker, btnEndCall;
    private TextInputEditText etRoomCode;
    private View              layoutWifiCode, layoutBtDevices;
    private Spinner           spinnerDevices;

    // State
    private boolean inCall    = false;
    private boolean muted     = false;
    private boolean speakerOn = false;

    // Timer
    private Handler       timerHandler = new Handler(Looper.getMainLooper());
    private int           callSeconds  = 0;
    private Runnable      timerRunnable;

    // Service
    private CallService callService;
    private boolean     serviceBound = false;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            callService  = ((CallService.LocalBinder) binder).getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // Bluetooth
    private BluetoothAdapter         btAdapter;
    private List<BluetoothDevice>     pairedDevices = new ArrayList<>();
    private List<String>              pairedNames    = new ArrayList<>();

    // Discovery
    private WifiDiscoveryHelper wifiDiscovery;
    private String              currentRoomCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupBluetooth();
        requestPermissions();
        setupListeners();
        bindCallService();
    }

    private void bindViews() {
        switchWifiBt      = findViewById(R.id.switchWifiBt);
        switchBtMic       = findViewById(R.id.switchBtMic);
        tvStatus          = findViewById(R.id.tvStatus);
        tvSignal          = findViewById(R.id.tvSignal);
        tvTimer           = findViewById(R.id.tvTimer);
        tvRoomCodeDisplay = findViewById(R.id.tvRoomCodeDisplay);
        btnHost           = findViewById(R.id.btnHost);
        btnCall           = findViewById(R.id.btnCall);
        btnMute           = findViewById(R.id.btnMute);
        btnSpeaker        = findViewById(R.id.btnSpeaker);
        btnEndCall        = findViewById(R.id.btnEndCall);
        etRoomCode        = findViewById(R.id.etRoomCode);
        layoutWifiCode    = findViewById(R.id.layoutWifiCode);
        layoutBtDevices   = findViewById(R.id.layoutBtDevices);
        spinnerDevices    = findViewById(R.id.spinnerDevices);
    }

    private void setupBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
    }

    private void setupListeners() {
        // Toggle 1: WiFi only <-> WiFi + BT Audio
        switchWifiBt.setOnCheckedChangeListener((btn, checked) -> {
            TextView tvLeft  = findViewById(R.id.tvWifiLabel);
            TextView tvRight = findViewById(R.id.tvWifiBtLabel);
            if (checked) {
                tvLeft.setTextColor(getColor(R.color.text_secondary));
                tvRight.setTextColor(getColor(R.color.accent));
                setStatus("Mode: WiFi + BT Audio");
            } else {
                tvLeft.setTextColor(getColor(R.color.accent));
                tvRight.setTextColor(getColor(R.color.text_secondary));
                setStatus("Mode: WiFi Only");
            }
            updateModeUi();
        });

        // Toggle 2: BT data only <-> BT data + BT Mic
        switchBtMic.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                setStatus("Mode: BT Transfer + BT Mic (SCO)");
                // Switch to BT device picker
                layoutWifiCode.setVisibility(View.GONE);
                layoutBtDevices.setVisibility(View.VISIBLE);
                loadPairedDevices();
            } else {
                setStatus("Mode: BT Transfer only");
                updateModeUi();
            }
        });

        btnHost.setOnClickListener(v -> onHostClicked());
        btnCall.setOnClickListener(v -> onCallClicked());
        btnMute.setOnClickListener(v -> toggleMute());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        btnEndCall.setOnClickListener(v -> endCall());
    }

    private void updateModeUi() {
        boolean btMode = switchBtMic.isChecked();
        if (btMode) {
            layoutWifiCode.setVisibility(View.GONE);
            layoutBtDevices.setVisibility(View.VISIBLE);
            loadPairedDevices();
        } else {
            layoutWifiCode.setVisibility(View.VISIBLE);
            layoutBtDevices.setVisibility(View.GONE);
        }
    }

    private void loadPairedDevices() {
        pairedDevices.clear();
        pairedNames.clear();
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BT Connect permission needed", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                pairedDevices.add(d);
                pairedNames.add(d.getName() != null ? d.getName() : d.getAddress());
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, pairedNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);
        if (pairedNames.isEmpty()) {
            Toast.makeText(this, "No paired BT devices found", Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------
    // Host / Call actions
    // -------------------------------------------------------

    private void onHostClicked() {
        if (switchBtMic.isChecked()) {
            // BT HOST — wait for RFCOMM connection
            startBtHost();
        } else {
            // WiFi HOST — generate room code and broadcast
            currentRoomCode = String.format("%06d", new Random().nextInt(1000000));
            tvRoomCodeDisplay.setText("Room Code: " + currentRoomCode);
            tvRoomCodeDisplay.setVisibility(View.VISIBLE);
            setStatus("● Hosting — sharing room code");
            if (wifiDiscovery != null) wifiDiscovery.stop();
            wifiDiscovery = new WifiDiscoveryHelper();
            wifiDiscovery.startHostBeacon(currentRoomCode);
            // Host also needs to listen for audio — we start service but wait for peer
            startCallServiceFg();
            // When guest connects they send audio; host starts audio immediately
            startWifiCallAsHost();
        }
    }

    private void onCallClicked() {
        if (switchBtMic.isChecked()) {
            // BT GUEST — connect to selected paired device
            if (pairedDevices.isEmpty()) {
                Toast.makeText(this, "No paired device selected", Toast.LENGTH_SHORT).show();
                return;
            }
            int idx = spinnerDevices.getSelectedItemPosition();
            BluetoothDevice device = pairedDevices.get(idx);
            startBtGuest(device);
        } else {
            // WiFi GUEST — search for host by room code
            String code = etRoomCode.getText() != null ? etRoomCode.getText().toString().trim() : "";
            if (code.length() != 6) {
                Toast.makeText(this, "Enter a 6-digit room code", Toast.LENGTH_SHORT).show();
                return;
            }
            searchForWifiHost(code);
        }
    }

    // -------------------------------------------------------
    // WiFi flow
    // -------------------------------------------------------

    private void startCallServiceFg() {
        Intent svc = new Intent(this, CallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void startWifiCallAsHost() {
        // Host listens; peer address will be set when first packet arrives.
        // For simplicity we set the call as started and audio threads handle the rest.
        if (!serviceBound) return;
        boolean useBtAudio = switchWifiBt.isChecked();
        callService.setCallMode(useBtAudio ? CallService.CallMode.WIFI_BT_AUDIO : CallService.CallMode.WIFI_ONLY);
        // Host doesn't know peer yet — peerAddress = null initially, sendThread handles null check
        callService.startWifiCall(null, useBtAudio);
        onCallStarted();
    }

    private void searchForWifiHost(String code) {
        setStatus("● Searching for host...");
        if (wifiDiscovery != null) wifiDiscovery.stop();
        wifiDiscovery = new WifiDiscoveryHelper();
        wifiDiscovery.searchForHost(code, new WifiDiscoveryHelper.DiscoveryListener() {
            @Override
            public void onHostFound(InetAddress address) {
                runOnUiThread(() -> {
                    setStatus("● Connected (WiFi)");
                    tvSignal.setText("Signal: Good");
                    startCallServiceFg();
                    if (serviceBound) {
                        boolean useBtAudio = switchWifiBt.isChecked();
                        callService.startWifiCall(address, useBtAudio);
                    }
                    onCallStarted();
                });
            }
            @Override
            public void onTimeout() {
                runOnUiThread(() -> setStatus("● Host not found. Check code."));
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() -> setStatus("● Error: " + msg));
            }
        });
    }

    // -------------------------------------------------------
    // Bluetooth flow
    // -------------------------------------------------------

    private void startBtHost() {
        if (btAdapter == null) { Toast.makeText(this, "No BT", Toast.LENGTH_SHORT).show(); return; }
        setStatus("● Waiting for BT connection...");
        startCallServiceFg();
        BluetoothCallHelper helper = new BluetoothCallHelper();
        if (serviceBound) callService.setBtHelper(helper);
        helper.startAsHost(btAdapter, new BluetoothCallHelper.ConnectionListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    if (helper.isConnected()) {
                        setStatus("● Connected (BT)");
                        if (serviceBound) callService.startBtCall(switchBtMic.isChecked());
                        onCallStarted();
                    } else {
                        setStatus("● Waiting for guest to connect...");
                    }
                });
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() -> setStatus("● BT Error: " + msg));
            }
        });
    }

    private void startBtGuest(BluetoothDevice device) {
        setStatus("● Connecting to " + (device.getName() != null ? device.getName() : device.getAddress()));
        startCallServiceFg();
        BluetoothCallHelper helper = new BluetoothCallHelper();
        if (serviceBound) callService.setBtHelper(helper);
        helper.connectToDevice(device, new BluetoothCallHelper.ConnectionListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    setStatus("● Connected (BT)");
                    if (serviceBound) callService.startBtCall(switchBtMic.isChecked());
                    onCallStarted();
                });
            }
            @Override
            public void onError(String msg) {
                runOnUiThread(() -> setStatus("● BT Connect Error: " + msg));
            }
        });
    }

    // -------------------------------------------------------
    // Call state helpers
    // -------------------------------------------------------

    private void onCallStarted() {
        inCall = true;
        btnEndCall.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.VISIBLE);
        callSeconds = 0;
        startTimer();
    }

    private void endCall() {
        inCall = false;
        stopTimer();
        tvTimer.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.GONE);
        tvRoomCodeDisplay.setVisibility(View.GONE);
        setStatus("● Idle");
        tvSignal.setText("Signal: --");
        if (wifiDiscovery != null) { wifiDiscovery.stop(); wifiDiscovery = null; }
        if (serviceBound) callService.stopCall();
        Intent stopIntent = new Intent(this, CallService.class);
        stopService(stopIntent);
    }

    private void toggleMute() {
        muted = !muted;
        btnMute.setText(muted ? "🔇 Unmute" : "🎤 Mute");
        if (serviceBound) callService.setMuted(muted);
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        btnSpeaker.setText(speakerOn ? "🔇 Earpiece" : "🔊 Speaker");
        if (serviceBound) callService.setSpeakerOn(speakerOn);
    }

    // -------------------------------------------------------
    // Timer
    // -------------------------------------------------------

    private void startTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                callSeconds++;
                int m = callSeconds / 60;
                int s = callSeconds % 60;
                tvTimer.setText(String.format("%02d:%02d", m, s));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void stopTimer() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        tvTimer.setText("00:00");
    }

    // -------------------------------------------------------
    // Status
    // -------------------------------------------------------

    private void setStatus(String msg) {
        tvStatus.setText(msg);
    }

    // -------------------------------------------------------
    // Service binding
    // -------------------------------------------------------

    private void bindCallService() {
        Intent intent = new Intent(this, CallService.class);
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE);
    }

    // -------------------------------------------------------
    // Permissions
    // -------------------------------------------------------

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) unbindService(serviceConn);
        super.onDestroy();
    }
}
