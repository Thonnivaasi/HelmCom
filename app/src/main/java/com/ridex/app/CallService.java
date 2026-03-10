package com.ridex.app;
import android.app.*;
import android.content.Intent;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CallService extends Service {
    public static final String ACTION_STOP = "com.ridex.app.STOP";
    private static final String CH  = "ridex_ch";
    private static final int    NID = 1;
    private static final int VOICE_RATE = 16000;
    private static final int FORMAT     = AudioFormat.ENCODING_PCM_16BIT;
    private static final int PKT_SIZE   = 320;

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public CallService get() { return CallService.this; }
    }
    @Override public IBinder onBind(Intent i) { return binder; }

    private AudioRecord recorder;
    private AudioTrack  voicePlayer;
    private AudioTrack  musicPlayer;
    private AudioManager am;
    private DatagramSocket voiceSendSock;
    private DatagramSocket musicSendSock;

    private final AtomicBoolean running      = new AtomicBoolean(false);
    private final AtomicBoolean musicRunning = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> voiceQ = new LinkedBlockingQueue<>(80);
    private final LinkedBlockingQueue<byte[]> musicQ = new LinkedBlockingQueue<>(200);

    private InetAddress peerAddr;
    private float voiceGain = 0.9f;
    private float musicGain = 0.7f;
    private boolean muted   = false;
    private boolean ducking = false;
    private boolean isHost  = false;
    private PlaylistManager playlists;
    private Callback callback;

    public interface Callback {
        void onPeerConnected(String username, InetAddress addr);
        void onDisconnected();
        void onControlMessage(String msg);
        void onNowPlaying(String playlist, String song);
    }

    public void setCallback(Callback cb)        { this.callback  = cb; }
    public void setHost(boolean h)              { this.isHost    = h; }
    public void setPlaylists(PlaylistManager p) { this.playlists = p; }
    public void setMuted(boolean m)             { this.muted     = m; }
    public void setSpeaker(boolean on)          { am.setSpeakerphoneOn(on); }
    public void setVoiceGain(float g)           { this.voiceGain = g; }
    public void setMusicGain(float g)           { this.musicGain = g; sendControl(Protocol.CMD_MUSIC_VOL + g); }
    public void setPeer(InetAddress addr)        { this.peerAddr = addr; }

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
        return START_STICKY;
    }

    public void startSession(InetAddress peer, boolean host) {
        if (running.get()) return;
        peerAddr = peer;
        isHost   = host;
        running.set(true);
        setupAudio();
        new Thread(this::controlLoop,   "Control").start();
        new Thread(this::voiceSendLoop, "VoiceSend").start();
        new Thread(this::voiceRecvLoop, "VoiceRecv").start();
        new Thread(this::voicePlayLoop, "VoicePlay").start();
        if (!isHost) new Thread(this::musicRecvLoop, "MusicRecv").start();
        new Thread(this::musicPlayLoop, "MusicPlay").start();
        updateNotif(host ? "RideX hosting" : "RideX in call");
    }

    private void setupAudio() {
        am.setMode(AudioManager.MODE_NORMAL);
        am.setSpeakerphoneOn(true);
        int recBuf = Math.max(AudioRecord.getMinBufferSize(VOICE_RATE,
            AudioFormat.CHANNEL_IN_MONO, FORMAT), PKT_SIZE * 4);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
            VOICE_RATE, AudioFormat.CHANNEL_IN_MONO, FORMAT, recBuf);
        int playBuf = Math.max(AudioTrack.getMinBufferSize(VOICE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, FORMAT), PKT_SIZE * 4);
        voicePlayer = new AudioTrack(AudioManager.STREAM_MUSIC,
            VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, FORMAT,
            playBuf, AudioTrack.MODE_STREAM);
        int musicBuf = Math.max(AudioTrack.getMinBufferSize(44100,
            AudioFormat.CHANNEL_OUT_STEREO, FORMAT), 8192);
        musicPlayer = new AudioTrack(AudioManager.STREAM_MUSIC,
            44100, AudioFormat.CHANNEL_OUT_STEREO, FORMAT,
            musicBuf, AudioTrack.MODE_STREAM);
        if (recorder.getState()    == AudioRecord.STATE_INITIALIZED) recorder.startRecording();
        if (voicePlayer.getState() == AudioTrack.STATE_INITIALIZED)  voicePlayer.play();
        if (musicPlayer.getState() == AudioTrack.STATE_INITIALIZED)  musicPlayer.play();
    }

    private void voiceSendLoop() {
        try {
            voiceSendSock = new DatagramSocket();
            byte[] buf = new byte[PKT_SIZE];
            while (running.get()) {
                if (recorder == null || muted || peerAddr == null) { Thread.sleep(10); continue; }
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0) {
                    detectVoice(buf, n);
                    voiceSendSock.send(new DatagramPacket(buf, n, peerAddr, Protocol.PORT_VOICE));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void voiceRecvLoop() {
        try (DatagramSocket s = new DatagramSocket(Protocol.PORT_VOICE)) {
            s.setSoTimeout(5000);
            byte[] buf = new byte[PKT_SIZE * 4];
            while (running.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    byte[] d = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, d, 0, p.getLength());
                    if (!voiceQ.offer(d)) { voiceQ.poll(); voiceQ.offer(d); }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void voicePlayLoop() {
        while (running.get()) {
            try {
                byte[] d = voiceQ.poll(300, TimeUnit.MILLISECONDS);
                if (d != null && voicePlayer != null) {
                    applyGain(d, voiceGain);
                    voicePlayer.write(d, 0, d.length);
                }
            } catch (InterruptedException e) { break; }
        }
    }

    private void musicRecvLoop() {
        try (DatagramSocket s = new DatagramSocket(Protocol.PORT_MUSIC)) {
            s.setSoTimeout(5000);
            byte[] buf = new byte[8192];
            while (running.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    // Check for header packet
                    if (p.getLength() < 64) {
                        String peek = new String(p.getData(), 0, p.getLength(), "UTF-8");
                        if (peek.startsWith("HDR:")) {
                            String[] parts = peek.split(":");
                            int rate = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 44100;
                            int ch   = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 2;
                            rebuildMusicPlayer(rate, ch);
                            continue;
                        }
                    }
                    byte[] d = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, d, 0, p.getLength());
                    if (!musicQ.offer(d)) { musicQ.poll(); musicQ.offer(d); }
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void rebuildMusicPlayer(int rate, int channels) {
        try {
            if (musicPlayer != null) {
                musicPlayer.stop(); musicPlayer.release(); musicPlayer = null;
            }
            int chOut = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int minBuf = Math.max(AudioTrack.getMinBufferSize(rate, chOut, FORMAT), 8192);
            musicPlayer = new AudioTrack(AudioManager.STREAM_MUSIC,
                rate, chOut, FORMAT, minBuf, AudioTrack.MODE_STREAM);
            if (musicPlayer.getState() == AudioTrack.STATE_INITIALIZED) musicPlayer.play();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void musicPlayLoop() {
        while (running.get()) {
            try {
                byte[] d = musicQ.poll(300, TimeUnit.MILLISECONDS);
                if (d == null) continue;
                if (musicPlayer == null || musicPlayer.getState() != AudioTrack.STATE_INITIALIZED) continue;
                float g = ducking ? musicGain * 0.3f : musicGain;
                applyGain(d, g);
                musicPlayer.write(d, 0, d.length);
            } catch (InterruptedException e) { break; }
            catch (Exception ignored) {}
        }
    }

    public void streamSong(String uriStr, int plIdx, int songIdx) {
        stopMusicStream();
        musicRunning.set(true);
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            try {
                extractor.setDataSource(this, Uri.parse(uriStr), null);
                int trackIdx = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat fmt = extractor.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
                }
                if (trackIdx < 0) return;
                extractor.selectTrack(trackIdx);
                MediaFormat fmt = extractor.getTrackFormat(trackIdx);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                int songRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                int songCh   = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

                // Send header so receiver sets up correct AudioTrack
                musicSendSock = new DatagramSocket();
                if (peerAddr != null) {
                    byte[] hdr = ("HDR:" + songRate + ":" + songCh).getBytes("UTF-8");
                    musicSendSock.send(new DatagramPacket(hdr, hdr.length, peerAddr, Protocol.PORT_MUSIC));
                    Thread.sleep(300);
                }

                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;

                while (musicRunning.get()) {
                    if (!inputDone) {
                        int inIdx = codec.dequeueInputBuffer(10000);
                        if (inIdx >= 0) {
                            ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                            inBuf.clear();
                            int n = extractor.readSampleData(inBuf, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(info, 10000);
                    if (outIdx >= 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        byte[] pcm = new byte[info.size];
                        outBuf.get(pcm);
                        codec.releaseOutputBuffer(outIdx, false);
                        // Send in UDP-safe chunks
                        int offset = 0;
                        while (offset < pcm.length && musicRunning.get()) {
                            int len = Math.min(1400, pcm.length - offset);
                            if (peerAddr != null)
                                musicSendSock.send(new DatagramPacket(pcm, offset, len, peerAddr, Protocol.PORT_MUSIC));
                            offset += len;
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
            finally {
                try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
                extractor.release();
            }
        }, "MusicStream").start();
    }

    public void stopMusicStream() {
        musicRunning.set(false);
        try { if (musicSendSock != null) { musicSendSock.close(); musicSendSock = null; } } catch (Exception e) {}
    }

    public void sendControl(String msg) {
        if (peerAddr == null) return;
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] d = msg.getBytes("UTF-8");
                s.send(new DatagramPacket(d, d.length, peerAddr, Protocol.PORT_CONTROL));
            } catch (Exception e) {}
        }).start();
    }

    private void controlLoop() {
        try (DatagramSocket s = new DatagramSocket(Protocol.PORT_CONTROL)) {
            s.setSoTimeout(3000);
            byte[] buf = new byte[8192];
            while (running.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    handleControl(new String(p.getData(), 0, p.getLength(), "UTF-8"), p.getAddress());
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception e) { e.printStackTrace(); }
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
            int sep = rest.indexOf('|');
            callback.onNowPlaying(sep >= 0 ? rest.substring(0, sep) : rest,
                                  sep >= 0 ? rest.substring(sep + 1) : "");
        } else if (msg.startsWith(Protocol.CMD_MUSIC_VOL)) {
            try { musicGain = Float.parseFloat(msg.substring(Protocol.CMD_MUSIC_VOL.length())); } catch (Exception ignored) {}
        } else if (msg.equals(Protocol.CMD_BYE)) {
            callback.onDisconnected();
        } else {
            callback.onControlMessage(msg);
        }
    }

    private void detectVoice(byte[] buf, int len) {
        long sum = 0;
        for (int i = 0; i < len - 1; i += 2) {
            short s = (short)((buf[i+1] << 8) | (buf[i] & 0xFF));
            sum += Math.abs(s);
        }
        ducking = len > 0 && (sum / (len / 2f)) > 800;
    }

    private void applyGain(byte[] buf, float gain) {
        if (gain >= 0.99f) return;
        for (int i = 0; i < buf.length - 1; i += 2) {
            short s = (short)((buf[i+1] << 8) | (buf[i] & 0xFF));
            s = (short) Math.max(-32768, Math.min(32767, (int)(s * gain)));
            buf[i]   = (byte)(s & 0xFF);
            buf[i+1] = (byte)((s >> 8) & 0xFF);
        }
    }

    public void stopSession() {
        if (!running.getAndSet(false)) return;
        try { sendControl(Protocol.CMD_BYE); Thread.sleep(100); } catch (Exception ignored) {}
        stopMusicStream();
        try { if (recorder    != null) { recorder.stop();    recorder.release();    recorder    = null; } } catch (Exception e) {}
        try { if (voicePlayer != null) { voicePlayer.stop(); voicePlayer.release(); voicePlayer = null; } } catch (Exception e) {}
        try { if (musicPlayer != null) { musicPlayer.stop(); musicPlayer.release(); musicPlayer = null; } } catch (Exception e) {}
        try { if (voiceSendSock != null) { voiceSendSock.close(); voiceSendSock = null; } } catch (Exception e) {}
        peerAddr = null;
        if (am != null) am.setMode(AudioManager.MODE_NORMAL);
        updateNotif("RideX ready");
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
    private void updateNotif(String text) {
        getSystemService(NotificationManager.class).notify(NID, buildNotif(text));
    }
    @Override public void onDestroy() { stopSession(); super.onDestroy(); }
}
