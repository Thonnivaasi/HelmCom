package com.ridex.app;
import android.app.*;
import android.content.Intent;
import android.media.*;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class CallService extends Service {
    public static final String ACTION_STOP = "com.ridex.app.STOP";
    private static final String CH  = "ridex_ch";
    private static final int    NID = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final int BUF        = 1280;
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder { public CallService get() { return CallService.this; } }
    @Override public IBinder onBind(Intent i) { return binder; }

    private AudioRecord recorder;
    private AudioTrack  voicePlayer;
    private AudioTrack  musicPlayer;
    private AudioManager am;
    private DatagramSocket voiceSendSock;
    private DatagramSocket musicSendSock;
    private final AtomicBoolean running      = new AtomicBoolean(false);
    private final AtomicBoolean musicRunning = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> voiceQ = new LinkedBlockingQueue<>(60);
    private final LinkedBlockingQueue<byte[]> musicQ = new LinkedBlockingQueue<>(120);
    private InetAddress peerAddr;
    private float voiceGain = 0.8f;
    private float musicGain = 0.7f;
    private boolean muted   = false;
    private boolean ducking = false;
    private boolean isHost  = false;
    private PlaylistManager playlists;
    private InputStream musicStream;
    private Callback callback;

    public interface Callback {
        void onPeerConnected(String username, InetAddress addr);
        void onDisconnected();
        void onControlMessage(String msg);
        void onNowPlaying(String playlist, String song);
    }

    public void setCallback(Callback cb)       { this.callback  = cb; }
    public void setHost(boolean h)             { this.isHost    = h; }
    public void setPlaylists(PlaylistManager p){ this.playlists = p; }
    public void setMuted(boolean m)            { this.muted     = m; }
    public void setSpeaker(boolean on)         { am.setSpeakerphoneOn(on); }
    public void setVoiceGain(float g)          { this.voiceGain = g; }
    public void setMusicGain(float g)          { this.musicGain = g; sendControl(Protocol.CMD_MUSIC_VOL + g); }

    @Override public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        startForeground(NID, buildNotif("RideX ready"));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSession(); stopSelf(); return START_NOT_STICKY;
        }
        startForeground(NID, buildNotif("RideX"));
        return START_STICKY;
    }

    public void startSession(InetAddress peer, boolean host) {
        if (running.get()) return;
        peerAddr = peer; isHost = host;
        running.set(true);
        setupAudio();
        new Thread(this::voiceSendLoop, "VoiceSend").start();
        new Thread(this::voiceRecvLoop, "VoiceRecv").start();
        new Thread(this::voicePlayLoop, "VoicePlay").start();
        new Thread(this::controlLoop,   "Control").start();
        if (!isHost) new Thread(this::musicRecvLoop, "MusicRecv").start();
        new Thread(this::musicPlayLoop, "MusicPlay").start();
    }

    private void setupAudio() {
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        int minR = Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BUF * 2);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minR);
        int minVP = Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), BUF * 2);
        voicePlayer = new AudioTrack(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minVP, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        int minMP = Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), BUF * 2);
        musicPlayer = new AudioTrack(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minMP, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (recorder.getState() == AudioRecord.STATE_INITIALIZED) recorder.startRecording();
        if (voicePlayer.getState() == AudioTrack.STATE_INITIALIZED) voicePlayer.play();
        if (musicPlayer.getState() == AudioTrack.STATE_INITIALIZED) musicPlayer.play();
    }

    private void voiceSendLoop() {
        try {
            voiceSendSock = new DatagramSocket();
            byte[] buf = new byte[BUF];
            while (running.get()) {
                if (recorder == null || muted || peerAddr == null) { Thread.sleep(10); continue; }
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0) {
                    detectVoice(buf, n);
                    voiceSendSock.send(new DatagramPacket(buf, n, peerAddr, Protocol.PORT_VOICE));
                }
            }
        } catch (Exception e) {}
    }
    private void voiceRecvLoop() {
        try (DatagramSocket s = new DatagramSocket(Protocol.PORT_VOICE)) {
            s.setSoTimeout(3000);
            byte[] buf = new byte[BUF * 2];
            while (running.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    byte[] d = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, d, 0, p.getLength());
                    if (!voiceQ.offer(d)) { voiceQ.poll(); voiceQ.offer(d); }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {}
    }
    private void voicePlayLoop() {
        while (running.get()) {
            try {
                byte[] d = voiceQ.poll(200, TimeUnit.MILLISECONDS);
                if (d != null && voicePlayer != null) { applyGain(d, voiceGain); voicePlayer.write(d, 0, d.length); }
            } catch (InterruptedException e) { break; }
        }
    }
    private void musicRecvLoop() {
        try (DatagramSocket s = new DatagramSocket(Protocol.PORT_MUSIC)) {
            s.setSoTimeout(5000);
            byte[] buf = new byte[2048];
            while (running.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    byte[] d = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, d, 0, p.getLength());
                    if (!musicQ.offer(d)) { musicQ.poll(); musicQ.offer(d); }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {}
    }
    private void musicPlayLoop() {
        while (running.get()) {
            try {
                byte[] d = musicQ.poll(300, TimeUnit.MILLISECONDS);
                if (d != null && musicPlayer != null) {
                    float g = ducking ? musicGain * 0.3f : musicGain;
                    applyGain(d, g);
                    musicPlayer.write(d, 0, d.length);
                }
            } catch (InterruptedException e) { break; }
        }
    }
    private void detectVoice(byte[] buf, int len) {
        long sum = 0;
        for (int i = 0; i < len - 1; i += 2) {
            short s = (short)((buf[i+1] << 8) | (buf[i] & 0xFF));
            sum += Math.abs(s);
        }
        ducking = (sum / (len / 2f)) > 1200;
    }
    private void applyGain(byte[] buf, float gain) {
        for (int i = 0; i < buf.length - 1; i += 2) {
            short s = (short)((buf[i+1] << 8) | (buf[i] & 0xFF));
            s = (short) Math.max(-32768, Math.min(32767, (int)(s * gain)));
            buf[i]   = (byte)(s & 0xFF);
            buf[i+1] = (byte)((s >> 8) & 0xFF);
        }
    }
    public void streamSong(String uriStr, int plIdx, int songIdx) {
        stopMusicStream();
        musicRunning.set(true);
        new Thread(() -> {
            try {
                musicSendSock = new DatagramSocket();
                musicStream = getContentResolver().openInputStream(Uri.parse(uriStr));
                if (musicStream == null) return;
                byte[] buf = new byte[1024]; int n;
                while (musicRunning.get() && (n = musicStream.read(buf)) > 0) {
                    if (peerAddr != null)
                        musicSendSock.send(new DatagramPacket(buf, n, peerAddr, Protocol.PORT_MUSIC));
                    Thread.sleep(11);
                }
            } catch (Exception e) {}
        }, "MusicStream").start();
    }
    public void stopMusicStream() {
        musicRunning.set(false);
        try { if (musicStream    != null) { musicStream.close();    musicStream    = null; } } catch (Exception e) {}
        try { if (musicSendSock  != null) { musicSendSock.close();  musicSendSock  = null; } } catch (Exception e) {}
    }
    public void sendControl(String msg) {
        if (peerAddr == null) return;
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] d = msg.getBytes();
                s.send(new DatagramPacket(d, d.length, peerAddr, Protocol.PORT_CONTROL));
            } catch (Exception e) {}
        }).start();
    }
    private void controlLoop() {
        try (DatagramSocket s = new DatagramSocket(Protocol.PORT_CONTROL)) {
            s.setSoTimeout(3000);
            byte[] buf = new byte[4096];
            while (running.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    handleControl(new String(p.getData(), 0, p.getLength()), p.getAddress());
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) {}
    }
    private void handleControl(String msg, InetAddress from) {
        if (callback == null) return;
        if (msg.startsWith(Protocol.CMD_HELLO)) {
            peerAddr = from;
            callback.onPeerConnected(msg.substring(Protocol.CMD_HELLO.length()), from);
            if (isHost && playlists != null) {
                sendControl(Protocol.CMD_ACK + Prefs.getUsername(this));
                sendControl(Protocol.CMD_PLAYLISTS + playlists.serializeForRemote());
            }
        } else if (msg.startsWith(Protocol.CMD_ACK)) {
            callback.onPeerConnected(msg.substring(Protocol.CMD_ACK.length()), from);
        } else if (msg.startsWith(Protocol.CMD_NOW_PLAYING)) {
            String rest = msg.substring(Protocol.CMD_NOW_PLAYING.length());
            String[] parts = rest.split("\\|", 2);
            callback.onNowPlaying(parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : "");
        } else if (msg.startsWith(Protocol.CMD_MUSIC_VOL)) {
            try { musicGain = Float.parseFloat(msg.substring(Protocol.CMD_MUSIC_VOL.length())); } catch (Exception ignored) {}
        } else if (msg.equals(Protocol.CMD_BYE)) {
            callback.onDisconnected();
        } else {
            callback.onControlMessage(msg);
        }
    }
    public void stopSession() {
        if (!running.get()) return;
        running.set(false);
        if (peerAddr != null) sendControl(Protocol.CMD_BYE);
        stopMusicStream();
        try { if (recorder    != null) { recorder.stop();    recorder.release();    recorder    = null; } } catch (Exception e) {}
        try { if (voicePlayer != null) { voicePlayer.stop(); voicePlayer.release(); voicePlayer = null; } } catch (Exception e) {}
        try { if (musicPlayer != null) { musicPlayer.stop(); musicPlayer.release(); musicPlayer = null; } } catch (Exception e) {}
        try { if (voiceSendSock != null) { voiceSendSock.close(); voiceSendSock = null; } } catch (Exception e) {}
        peerAddr = null;
        am.setMode(AudioManager.MODE_NORMAL);
        stopForeground(true);
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH, "RideX", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    private Notification buildNotif(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH)
            .setContentTitle("RideX").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build();
    }
    @Override public void onDestroy() { stopSession(); super.onDestroy(); }
}
