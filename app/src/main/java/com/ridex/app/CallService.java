package com.voicecallpro.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CallService extends Service {

    public static final String ACTION_END_CALL = "com.voicecallpro.END_CALL";
    public static final String ACTION_MUTE     = "com.voicecallpro.MUTE";

    private static final String CHANNEL_ID  = "voicecall_channel";
    private static final int    NOTIF_ID    = 1;

    // Audio config
    private static final int SAMPLE_RATE  = 8000;
    private static final int BUFFER_SIZE  = 1024;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO;

    // Ports (WiFi)
    public static final int PORT_AUDIO     = 50005;
    public static final int PORT_DISCOVERY = 50006;

    private final IBinder binder = new LocalBinder();

    // State
    private CallMode callMode = CallMode.WIFI_ONLY;
    private boolean  muted    = false;

    // WiFi
    private DatagramSocket audioSocket;
    private DatagramSocket discoverySocket;
    private InetAddress    peerAddress;

    // Bluetooth
    private BluetoothCallHelper btHelper;

    // Audio
    private AudioRecord audioRecord;
    private AudioTrack  audioTrack;
    private AudioManager audioManager;

    // Threads
    private Thread sendThread;
    private Thread receiveThread;
    private Thread keepAliveThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public enum CallMode {
        WIFI_ONLY,
        WIFI_BT_AUDIO,   // WiFi data + BT headphone audio (SCO)
        BT_ONLY,         // RFCOMM data, phone mic/speaker
        BT_BT_MIC        // RFCOMM data + BT headphone mic (SCO)
    }

    public class LocalBinder extends Binder {
        public CallService getService() { return CallService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END_CALL.equals(intent.getAction())) {
            stopCall();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_MUTE.equals(intent.getAction())) {
            muted = !muted;
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Connecting..."));
        return START_STICKY;
    }

    // -------------------------------------------------------
    // Public API called from Activity
    // -------------------------------------------------------

    public void setCallMode(CallMode mode) {
        this.callMode = mode;
    }

    public void startWifiCall(InetAddress peer, boolean useBtAudio) {
        this.peerAddress = peer;
        running.set(true);
        setupAudio(useBtAudio ? CallMode.WIFI_BT_AUDIO : CallMode.WIFI_ONLY);
        startWifiSend();
        startWifiReceive();
        startKeepAlive();
        updateNotification("In call (WiFi" + (useBtAudio ? " + BT Audio)" : ")"));
    }

    public void startBtCall(boolean useBtMic) {
        running.set(true);
        CallMode mode = useBtMic ? CallMode.BT_BT_MIC : CallMode.BT_ONLY;
        setupAudio(mode);
        // BT audio send/receive are driven by btHelper callbacks
        updateNotification("In call (BT" + (useBtMic ? " + BT Mic)" : ")"));
    }

    public void setBtHelper(BluetoothCallHelper helper) {
        this.btHelper = helper;
        helper.setAudioCallback(this::onBtAudioReceived, this::readMicBuffer);
    }

    public void stopCall() {
        running.set(false);
        stopAudio();
        closeWifiSockets();
        if (btHelper != null) {
            btHelper.close();
            btHelper = null;
        }
        stopForeground(true);
        stopSelf();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public void setSpeakerOn(boolean on) {
        audioManager.setSpeakerphoneOn(on);
    }

    // -------------------------------------------------------
    // Audio setup
    // -------------------------------------------------------

    private void setupAudio(CallMode mode) {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (mode == CallMode.WIFI_BT_AUDIO || mode == CallMode.BT_BT_MIC) {
            // Start SCO for BT headphone mic/earphone
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
        int recBuf  = Math.max(minBuf, BUFFER_SIZE * 2);

        int micSource = (mode == CallMode.BT_BT_MIC || mode == CallMode.WIFI_BT_AUDIO)
                ? MediaRecorder.AudioSource.DEFAULT  // Android picks BT mic when SCO active
                : MediaRecorder.AudioSource.MIC;

        audioRecord = new AudioRecord(micSource, SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, recBuf);

        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
        int trackBuf = Math.max(minTrack, BUFFER_SIZE * 2);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT,
                trackBuf, AudioTrack.MODE_STREAM);

        audioRecord.startRecording();
        audioTrack.play();
    }

    private void stopAudio() {
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) { /* ignore */ }
    }

    // -------------------------------------------------------
    // Mic buffer for BT helper
    // -------------------------------------------------------

    private byte[] readMicBuffer() {
        if (audioRecord == null || muted) return new byte[BUFFER_SIZE];
        byte[] buf = new byte[BUFFER_SIZE];
        audioRecord.read(buf, 0, buf.length);
        return buf;
    }

    private void onBtAudioReceived(byte[] data) {
        if (audioTrack != null) {
            audioTrack.write(data, 0, data.length);
        }
    }

    // -------------------------------------------------------
    // WiFi threads
    // -------------------------------------------------------

    private void startWifiSend() {
        sendThread = new Thread(() -> {
            try {
                audioSocket = new DatagramSocket();
                audioSocket.setSoTimeout(0);
                byte[] buf = new byte[BUFFER_SIZE];
                while (running.get()) {
                    if (audioRecord == null || muted) {
                        Thread.sleep(20);
                        continue;
                    }
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0 && peerAddress != null) {
                        DatagramPacket pkt = new DatagramPacket(buf, read, peerAddress, PORT_AUDIO);
                        audioSocket.send(pkt);
                    }
                }
            } catch (Exception e) { /* stopped */ }
        });
        sendThread.start();
    }

    private void startWifiReceive() {
        receiveThread = new Thread(() -> {
            try {
                DatagramSocket recvSocket = new DatagramSocket(PORT_AUDIO);
                recvSocket.setSoTimeout(0);
                byte[] buf = new byte[BUFFER_SIZE];
                while (running.get()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    recvSocket.receive(pkt);
                    if (audioTrack != null) {
                        audioTrack.write(pkt.getData(), 0, pkt.getLength());
                    }
                }
                recvSocket.close();
            } catch (Exception e) { /* stopped */ }
        });
        receiveThread.start();
    }

    private void startKeepAlive() {
        keepAliveThread = new Thread(() -> {
            try {
                while (running.get()) {
                    Thread.sleep(3000);
                    if (peerAddress != null && audioSocket != null) {
                        byte[] ping = "PING".getBytes();
                        DatagramPacket pkt = new DatagramPacket(ping, ping.length, peerAddress, PORT_DISCOVERY);
                        audioSocket.send(pkt);
                    }
                }
            } catch (Exception e) { /* stopped */ }
        });
        keepAliveThread.start();
    }

    private void closeWifiSockets() {
        try { if (audioSocket     != null) audioSocket.close();     } catch (Exception e) {}
        try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception e) {}
        audioSocket     = null;
        discoverySocket = null;
    }

    // -------------------------------------------------------
    // Notification
    // -------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "VoiceCall Pro", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent endIntent = new Intent(this, CallService.class);
        endIntent.setAction(ACTION_END_CALL);
        PendingIntent endPi = PendingIntent.getService(this, 0, endIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VoiceCall Pro")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setSilent(true)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "End Call", endPi)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        stopCall();
        super.onDestroy();
    }
}
