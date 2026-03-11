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
    public static final String ACTION_STOP="com.ridex.app.STOP";
    private static final String CH="ridex_ch";
    private static final int NID=1;
    private static final int VOICE_RATE=16000;
    private static final int FORMAT=AudioFormat.ENCODING_PCM_16BIT;
    private static final int PKT_SIZE=320;
    private final IBinder binder=new LocalBinder();
    public class LocalBinder extends Binder{public CallService get(){return CallService.this;}}
    @Override public IBinder onBind(Intent i){return binder;}
    private AudioRecord recorder;
    private AudioTrack voicePlayer;
    private AudioManager am;
    private DatagramSocket voiceSendSock,voiceRecvSock,controlSock;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private final AtomicBoolean musicRunning=new AtomicBoolean(false);
    private final AtomicInteger musicGeneration=new AtomicInteger(0);
    private final LinkedBlockingQueue<byte[]> voiceQ=new LinkedBlockingQueue<>(80);
    private final LinkedBlockingQueue<byte[]> musicQ=new LinkedBlockingQueue<>(300);
    private InetAddress peerAddr;
    private float voiceGain=0.9f,musicGain=0.7f;
    private boolean muted=false,ducking=false,isHost=false;
    private PlaylistManager playlists;
    private Callback callback;
    public interface Callback{
        void onPeerConnected(String username,InetAddress addr);
        void onDisconnected();
        void onControlMessage(String msg);
        void onNowPlaying(String playlist,String song);
    }
    public void setCallback(Callback cb){this.callback=cb;}
    public void setHost(boolean h){this.isHost=h;}
    public void setPlaylists(PlaylistManager p){this.playlists=p;}
    public void setMuted(boolean m){this.muted=m;}
    public void setSpeaker(boolean on){am.setSpeakerphoneOn(on);}
    public void setVoiceGain(float g){this.voiceGain=g;}
    public void setMusicGain(float g){this.musicGain=g;sendControl(Protocol.CMD_MUSIC_VOL+g);}
    public void setPeer(InetAddress addr){this.peerAddr=addr;}
    public int size(){return playlists==null?0:playlists.size();}
    @Override public void onCreate(){
        super.onCreate();
        am=(AudioManager)getSystemService(AUDIO_SERVICE);
        createChannel();
        startForeground(NID,buildNotif("RideX ready"));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSession();stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    public void startSession(InetAddress peer,boolean host){
        if(running.get())return;
        peerAddr=peer;isHost=host;
        running.set(true);voiceQ.clear();musicQ.clear();
        setupAudio();
        new Thread(this::controlLoop,"Control").start();
        new Thread(this::voiceSendLoop,"VoiceSend").start();
        new Thread(this::voiceRecvLoop,"VoiceRecv").start();
        new Thread(this::voicePlayLoop,"VoicePlay").start();
        if(!isHost)new Thread(this::musicRecvLoop,"MusicRecv").start();
        updateNotif(host?"RideX hosting":"RideX in call");
    }
    private void setupAudio(){
        am.setMode(AudioManager.MODE_NORMAL);
        am.setSpeakerphoneOn(true);
        int recBuf=Math.max(AudioRecord.getMinBufferSize(VOICE_RATE,AudioFormat.CHANNEL_IN_MONO,FORMAT),PKT_SIZE*4);
        recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,VOICE_RATE,AudioFormat.CHANNEL_IN_MONO,FORMAT,recBuf);
        int playBuf=Math.max(AudioTrack.getMinBufferSize(VOICE_RATE,AudioFormat.CHANNEL_OUT_MONO,FORMAT),PKT_SIZE*4);
        voicePlayer=new AudioTrack(AudioManager.STREAM_MUSIC,VOICE_RATE,AudioFormat.CHANNEL_OUT_MONO,FORMAT,playBuf,AudioTrack.MODE_STREAM);
        if(recorder.getState()==AudioRecord.STATE_INITIALIZED)recorder.startRecording();
        if(voicePlayer.getState()==AudioTrack.STATE_INITIALIZED)voicePlayer.play();
    }
    private void voiceSendLoop(){
        try{
            voiceSendSock=new DatagramSocket();
            byte[] buf=new byte[PKT_SIZE];
            while(running.get()){
                if(recorder==null||muted||peerAddr==null){Thread.sleep(10);continue;}
                int n=recorder.read(buf,0,buf.length);
                if(n>0){detectVoice(buf,n);voiceSendSock.send(new DatagramPacket(buf,n,peerAddr,Protocol.PORT_VOICE));}
            }
        }catch(Exception e){e.printStackTrace();}
        finally{closeSock(voiceSendSock);voiceSendSock=null;}
    }
    private void voiceRecvLoop(){
        try{
            voiceRecvSock=new DatagramSocket(Protocol.PORT_VOICE);
            voiceRecvSock.setSoTimeout(3000);
            byte[] buf=new byte[PKT_SIZE*4];
            while(running.get()){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    voiceRecvSock.receive(p);
                    byte[] d=new byte[p.getLength()];
                    System.arraycopy(p.getData(),0,d,0,p.getLength());
                    if(!voiceQ.offer(d)){voiceQ.poll();voiceQ.offer(d);}
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception e){e.printStackTrace();}
        finally{closeSock(voiceRecvSock);voiceRecvSock=null;}
    }
    private void voicePlayLoop(){
        while(running.get()){
            try{
                byte[] d=voiceQ.poll(300,TimeUnit.MILLISECONDS);
                if(d!=null&&voicePlayer!=null){
                    // Boost voice so it cuts through music
                    amplify(d, voiceGain * 2.5f);
                    voicePlayer.write(d,0,d.length);
                }
            }catch(InterruptedException e){break;}
        }
    }
    private void musicRecvLoop(){
        DatagramSocket s=null;
        AudioTrack track=null;
        try{
            s=new DatagramSocket(Protocol.PORT_MUSIC);
            s.setSoTimeout(3000);
            byte[] buf=new byte[8192];
            int curRate=44100,curCh=2;
            track=buildRecvTrack(curRate,curCh);
            while(running.get()){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    s.receive(p);
                    if(p.getLength()<64){
                        String peek=new String(p.getData(),0,p.getLength(),"UTF-8");
                        if(peek.startsWith("HDR:")){
                            String[] parts=peek.split(":");
                            int rate=parts.length>1?Integer.parseInt(parts[1].trim()):44100;
                            int ch=parts.length>2?Integer.parseInt(parts[2].trim()):2;
                            if(rate!=curRate||ch!=curCh){
                                curRate=rate;curCh=ch;
                                if(track!=null){track.stop();track.release();}
                                track=buildRecvTrack(curRate,curCh);
                            }
                            continue;
                        }
                    }
                    if(track==null||track.getState()!=AudioTrack.STATE_INITIALIZED)continue;
                    float g=ducking?musicGain*0.12f:musicGain;
                    byte[] d=new byte[p.getLength()];
                    System.arraycopy(p.getData(),0,d,0,p.getLength());
                    applyGain(d,g);track.write(d,0,d.length);
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception e){e.printStackTrace();}
        finally{
            closeSock(s);
            if(track!=null){try{track.stop();track.release();}catch(Exception ignored){}}
        }
    }
    private AudioTrack buildRecvTrack(int rate,int ch){
        int chOut=ch>=2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO;
        int minBuf=Math.max(AudioTrack.getMinBufferSize(rate,chOut,FORMAT),8192);
        AudioTrack t=new AudioTrack(AudioManager.STREAM_MUSIC,rate,chOut,FORMAT,minBuf*4,AudioTrack.MODE_STREAM);
        if(t.getState()==AudioTrack.STATE_INITIALIZED)t.play();
        return t;
    }
    public void streamSong(String uriStr,int plIdx,int songIdx){
        final int myGen=musicGeneration.incrementAndGet();
        musicRunning.set(false);
        try{Thread.sleep(80);}catch(Exception ignored){}
        musicRunning.set(true);
        new Thread(()->{
            MediaExtractor extractor=new MediaExtractor();
            MediaCodec codec=null;AudioTrack localTrack=null;DatagramSocket sock=null;
            try{
                extractor.setDataSource(this,Uri.parse(uriStr),null);
                int trackIdx=-1;MediaFormat fmt=null;
                for(int i=0;i<extractor.getTrackCount();i++){
                    MediaFormat f=extractor.getTrackFormat(i);
                    String mime=f.getString(MediaFormat.KEY_MIME);
                    if(mime!=null&&mime.startsWith("audio/")){fmt=f;trackIdx=i;break;}
                }
                if(trackIdx<0||fmt==null)return;
                extractor.selectTrack(trackIdx);
                String mime=fmt.getString(MediaFormat.KEY_MIME);
                int songRate=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
                int songCh=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT):2;
                int chOut=songCh>=2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO;
                int bytesPerSec=songRate*songCh*2;
                // Use small local buffer so write() blocks quickly — this IS our clock
                int localBuf=AudioTrack.getMinBufferSize(songRate,chOut,FORMAT);
                localTrack=new AudioTrack(AudioManager.STREAM_MUSIC,songRate,chOut,FORMAT,
                    localBuf*2,AudioTrack.MODE_STREAM);
                if(localTrack.getState()==AudioTrack.STATE_INITIALIZED)localTrack.play();
                sock=new DatagramSocket();
                if(peerAddr!=null){
                    byte[] hdr=("HDR:"+songRate+":"+songCh).getBytes("UTF-8");
                    sock.send(new DatagramPacket(hdr,hdr.length,peerAddr,Protocol.PORT_MUSIC));
                    Thread.sleep(150);
                }
                codec=MediaCodec.createDecoderByType(mime);
                codec.configure(fmt,null,null,0);codec.start();
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                boolean inputDone=false;
                // UDP-safe chunk: 20ms of audio
                int chunkSize=Math.min((bytesPerSec/50)&~1,1400);
                while(musicRunning.get()&&musicGeneration.get()==myGen){
                    if(!inputDone){
                        int inIdx=codec.dequeueInputBuffer(10000);
                        if(inIdx>=0){
                            ByteBuffer inBuf=codec.getInputBuffer(inIdx);inBuf.clear();
                            int n=extractor.readSampleData(inBuf,0);
                            if(n<0){codec.queueInputBuffer(inIdx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                            else{codec.queueInputBuffer(inIdx,0,n,extractor.getSampleTime(),0);extractor.advance();}
                        }
                    }
                    int outIdx=codec.dequeueOutputBuffer(info,10000);
                    if(outIdx>=0){
                        ByteBuffer outBuf=codec.getOutputBuffer(outIdx);
                        byte[] pcm=new byte[info.size];outBuf.get(pcm);
                        codec.releaseOutputBuffer(outIdx,false);
                        // Send to receiver in chunks, then write same chunk locally.
                        // localTrack.write() BLOCKS until HW consumes audio — natural clock!
                        int offset=0;
                        while(offset<pcm.length&&musicRunning.get()&&musicGeneration.get()==myGen){
                            int len=Math.min(chunkSize,pcm.length-offset);
                            // 1. Send to receiver first (non-blocking)
                            if(peerAddr!=null)
                                sock.send(new DatagramPacket(pcm,offset,len,peerAddr,Protocol.PORT_MUSIC));
                            // 2. Play locally — this write() blocks, pacing the loop naturally
                            if(localTrack!=null&&localTrack.getState()==AudioTrack.STATE_INITIALIZED){
                                byte[] lc=new byte[len];
                                System.arraycopy(pcm,offset,lc,0,len);
                                applyGain(lc,musicGain);
                                localTrack.write(lc,0,len); // blocks here = our clock
                            }
                            offset+=len;
                        }
                        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;
                    }
                }
            }catch(Exception e){e.printStackTrace();}
            finally{
                try{if(codec!=null){codec.stop();codec.release();}}catch(Exception ignored){}
                try{if(localTrack!=null){localTrack.stop();localTrack.release();}}catch(Exception ignored){}
                extractor.release();closeSock(sock);
            }
        },"MusicStream-"+myGen).start();
    }
    public void stopMusicStream(){musicGeneration.incrementAndGet();musicRunning.set(false);}
    public void sendControl(String msg){if(peerAddr!=null)sendControlTo(msg,peerAddr);}
    public void sendControlTo(String msg,InetAddress to){
        new Thread(()->{
            try(DatagramSocket s=new DatagramSocket()){
                byte[] d=msg.getBytes("UTF-8");
                s.send(new DatagramPacket(d,d.length,to,Protocol.PORT_CONTROL));
            }catch(Exception e){}
        }).start();
    }
    private void controlLoop(){
        try{
            controlSock=new DatagramSocket(Protocol.PORT_CONTROL);
            controlSock.setSoTimeout(3000);
            byte[] buf=new byte[8192];
            while(running.get()){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    controlSock.receive(p);
                    handleControl(new String(p.getData(),0,p.getLength(),"UTF-8"),p.getAddress());
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception e){e.printStackTrace();}
        finally{closeSock(controlSock);controlSock=null;}
    }
    private void handleControl(String msg,InetAddress from){
        if(callback==null)return;
        if(msg.startsWith(Protocol.CMD_HELLO)){
            peerAddr=from;
            callback.onPeerConnected(msg.substring(Protocol.CMD_HELLO.length()),from);
            if(isHost&&playlists!=null){
                sendControlTo(Protocol.CMD_ACK+Prefs.getUsername(this),from);
                sendControlTo(Protocol.CMD_PLAYLISTS+playlists.serializeForRemote(),from);
            }
        }else if(msg.startsWith(Protocol.CMD_ACK)){
            callback.onPeerConnected(msg.substring(Protocol.CMD_ACK.length()),from);
        }else if(msg.startsWith(Protocol.CMD_NOW_PLAYING)){
            String rest=msg.substring(Protocol.CMD_NOW_PLAYING.length());
            int sep=rest.indexOf('|');
            callback.onNowPlaying(sep>=0?rest.substring(0,sep):rest,sep>=0?rest.substring(sep+1):"");
        }else if(msg.startsWith(Protocol.CMD_MUSIC_VOL)){
            try{musicGain=Float.parseFloat(msg.substring(Protocol.CMD_MUSIC_VOL.length()));}catch(Exception ignored){}
        }else if(msg.equals(Protocol.CMD_BYE)){
            if(isHost){peerAddr=null;voiceQ.clear();musicQ.clear();stopMusicStream();}
            callback.onDisconnected();
        }else{callback.onControlMessage(msg);}
    }
    private void detectVoice(byte[] buf,int len){
        long sum=0;
        for(int i=0;i<len-1;i+=2){short s=(short)((buf[i+1]<<8)|(buf[i]&0xFF));sum+=Math.abs(s);}
        ducking=len>0&&(sum/(len/2f))>400;
    }
    private void applyGain(byte[] buf,float gain){
        if(gain>=0.99f)return;
        for(int i=0;i<buf.length-1;i+=2){
            short s=(short)((buf[i+1]<<8)|(buf[i]&0xFF));
            s=(short)Math.max(-32768,Math.min(32767,(int)(s*gain)));
            buf[i]=(byte)(s&0xFF);buf[i+1]=(byte)((s>>8)&0xFF);
        }
    }
    // Like applyGain but allows gain > 1.0 for voice boost
    private void amplify(byte[] buf,float gain){
        for(int i=0;i<buf.length-1;i+=2){
            short s=(short)((buf[i+1]<<8)|(buf[i]&0xFF));
            s=(short)Math.max(-32768,Math.min(32767,(int)(s*gain)));
            buf[i]=(byte)(s&0xFF);buf[i+1]=(byte)((s>>8)&0xFF);
        }
    }
    private void closeSock(DatagramSocket s){if(s!=null&&!s.isClosed())try{s.close();}catch(Exception ignored){}}
    public void stopSession(){
        if(!running.getAndSet(false))return;
        stopMusicStream();
        // Send BYE before closing sockets
        try{
            if(peerAddr!=null){
                DatagramSocket tmp=new DatagramSocket();
                byte[] d=Protocol.CMD_BYE.getBytes("UTF-8");
                tmp.send(new DatagramPacket(d,d.length,peerAddr,Protocol.PORT_CONTROL));
                tmp.close();
            }
            Thread.sleep(200);
        }catch(Exception ignored){}
        closeSock(voiceRecvSock);voiceRecvSock=null;
        closeSock(voiceSendSock);voiceSendSock=null;
        closeSock(controlSock);controlSock=null;
        try{Thread.sleep(100);}catch(Exception ignored){}
        try{if(recorder!=null){recorder.stop();recorder.release();recorder=null;}}catch(Exception e){}
        try{if(voicePlayer!=null){voicePlayer.stop();voicePlayer.release();voicePlayer=null;}}catch(Exception e){}
        peerAddr=null;voiceQ.clear();musicQ.clear();
        if(am!=null)am.setMode(AudioManager.MODE_NORMAL);
        updateNotif("RideX ready");
    }
    public boolean isSessionActive(){return running.get();}
    public void resetPeerKeepAlive(){
        stopMusicStream();peerAddr=null;voiceQ.clear();musicQ.clear();
        updateNotif("RideX hosting");
    }
    private void createChannel(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationChannel ch=new NotificationChannel(CH,"RideX",NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null,null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    private Notification buildNotif(String text){
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CH)
            .setContentTitle("RideX").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build();
    }
    private void updateNotif(String text){getSystemService(NotificationManager.class).notify(NID,buildNotif(text));}
    @Override public void onDestroy(){stopSession();super.onDestroy();}
}
