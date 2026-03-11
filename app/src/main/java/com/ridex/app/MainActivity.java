package com.ridex.app;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import org.json.*;
import java.net.InetAddress;
import java.util.*;
public class MainActivity extends AppCompatActivity implements CallService.Callback {
    private EditText etUsername;
    private TextView tvSaveUser,tvStatus,tvPeer,tvTimer,tvRoomCode;
    private TextView tvNowPlaying,tvPlaylistName,tvVoiceVol,tvMusicVol;
    private Button btnHost,btnJoin,btnEndCall,btnMute,btnSpeaker;
    private Button btnPrev,btnPlayPause,btnNext,btnExpandMusic,btnAddPlaylist;
    private SeekBar seekVoice,seekMusic;
    private Spinner spinnerPlaylist;
    private ListView listSongs;
    private View musicPanel;
    private boolean musicPanelOpen=false,inCall=false,muted=false,speakerOn=false;
    private boolean isHost=false,isPlaying=false;
    private int currentPl=0,currentSong=0,callSeconds=0;
    private Runnable timerRunnable;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private CallService service;
    private boolean bound=false;
    private PlaylistManager playlists;
    private DiscoveryHelper discovery;
    private final List<String> remotePlNames=new ArrayList<>();
    private final List<List<String>> remoteSongs=new ArrayList<>();
    private ActivityResultLauncher<Uri> folderPicker;
    private final ServiceConnection conn=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){
            service=((CallService.LocalBinder)b).get();
            service.setCallback(MainActivity.this);
            service.setPlaylists(playlists);
            bound=true;
            handler.postDelayed(()->autoJoin(),1500);
        }
        public void onServiceDisconnected(ComponentName n){bound=false;}
    };
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        playlists=new PlaylistManager(this);
        bindViews();requestPerms();loadPrefs();setupListeners();setupFolderPicker();
        Intent svc=new Intent(this,CallService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(svc);else startService(svc);
        bindService(svc,conn,Context.BIND_AUTO_CREATE);
    }
    private void bindViews(){
        etUsername=findViewById(R.id.etUsername);
        tvSaveUser=findViewById(R.id.tvSaveUser);
        tvStatus=findViewById(R.id.tvStatus);
        tvPeer=findViewById(R.id.tvPeer);
        tvTimer=findViewById(R.id.tvTimer);
        tvRoomCode=findViewById(R.id.tvRoomCode);
        tvNowPlaying=findViewById(R.id.tvNowPlaying);
        tvPlaylistName=findViewById(R.id.tvPlaylistName);
        tvVoiceVol=findViewById(R.id.tvVoiceVol);
        tvMusicVol=findViewById(R.id.tvMusicVol);
        btnHost=findViewById(R.id.btnConnect);
        btnJoin=findViewById(R.id.btnJoin);
        btnEndCall=findViewById(R.id.btnEndCall);
        btnMute=findViewById(R.id.btnMute);
        btnSpeaker=findViewById(R.id.btnSpeaker);
        btnPrev=findViewById(R.id.btnPrev);
        btnPlayPause=findViewById(R.id.btnPlayPause);
        btnNext=findViewById(R.id.btnNext);
        btnExpandMusic=findViewById(R.id.btnExpandMusic);
        btnAddPlaylist=findViewById(R.id.btnAddPlaylist);
        seekVoice=findViewById(R.id.seekVoice);
        seekMusic=findViewById(R.id.seekMusic);
        spinnerPlaylist=findViewById(R.id.spinnerPlaylist);
        listSongs=findViewById(R.id.listSongs);
        musicPanel=findViewById(R.id.musicPanel);
    }
    private void loadPrefs(){etUsername.setText(Prefs.getUsername(this));}
    private void setupListeners(){
        tvSaveUser.setOnClickListener(v->{
            String u=etUsername.getText().toString().trim();
            if(!u.isEmpty()){Prefs.saveUsername(this,u);toast("Saved");}
        });
        btnHost.setOnClickListener(v->startHost());
        btnJoin.setOnClickListener(v->startJoin());
        btnEndCall.setOnClickListener(v->endCall());
        btnMute.setOnClickListener(v->toggleMute());
        btnSpeaker.setOnClickListener(v->toggleSpeaker());
        btnExpandMusic.setOnClickListener(v->toggleMusicPanel());
        btnPrev.setOnClickListener(v->onPrev());
        btnNext.setOnClickListener(v->onNext());
        btnPlayPause.setOnClickListener(v->onPlayPause());
        btnAddPlaylist.setOnClickListener(v->folderPicker.launch(null));
        seekVoice.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar sb,int p,boolean u){
                tvVoiceVol.setText(p+"%");if(bound)service.setVoiceGain(p/100f);}
            public void onStartTrackingTouch(SeekBar sb){}
            public void onStopTrackingTouch(SeekBar sb){}
        });
        seekMusic.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar sb,int p,boolean u){
                tvMusicVol.setText(p+"%");if(bound)service.setMusicGain(p/100f);}
            public void onStartTrackingTouch(SeekBar sb){}
            public void onStopTrackingTouch(SeekBar sb){}
        });
        spinnerPlaylist.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){
                selectPlaylist(pos);
                if(!isHost&&bound&&inCall)service.sendControl(Protocol.CMD_SELECT_PL+pos);}
            public void onNothingSelected(AdapterView<?> parent){}
        });
        listSongs.setOnItemClickListener((parent,view,pos,id)->{
            if(isHost)playSong(currentPl,pos);
            else if(bound&&inCall)service.sendControl(Protocol.CMD_SELECT_SONG+pos);
        });
    }
    private void setupFolderPicker(){
        folderPicker=registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),uri->{
            if(uri==null)return;
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            playlists.addFromFolder(uri);
            if(bound)service.setPlaylists(playlists);
            refreshPlaylistSpinner();toast("Playlist added");
        });
    }
    private void startHost(){
        isHost=true;
        String myName=Prefs.getUsername(this);
        tvRoomCode.setText("Hosting as: "+myName);
        tvRoomCode.setVisibility(View.VISIBLE);
        setStatus("Waiting for guest...");
        if(discovery!=null)discovery.stop();
        discovery=new DiscoveryHelper();
        discovery.startBeacon(myName);
        if(bound){service.setHost(true);service.setPlaylists(playlists);service.startSession(null,true);}
        showCallUI();refreshPlaylistSpinner();
    }
    private void startJoin(){
        if(inCall)return;
        isHost=false;
        setStatus("Scanning for host...");
        if(discovery!=null)discovery.stop();
        discovery=new DiscoveryHelper();
        discovery.searchForHost(new DiscoveryHelper.Listener(){
            public void onFound(InetAddress addr,String hostName){
                runOnUiThread(()->{
                    Prefs.saveLastIp(MainActivity.this,addr.getHostAddress());
                    Prefs.saveLastCode(MainActivity.this,hostName);
                    if(bound){
                        service.setHost(false);
                        service.startSession(addr,false);
                        service.sendControl(Protocol.CMD_HELLO+Prefs.getUsername(MainActivity.this));
                    }
                    showCallUI();setStatus("Connected to "+hostName);startTimer();
                });
            }
            public void onTimeout(){runOnUiThread(()->{if(!inCall)setStatus("No host found");});}
        });
    }
    private void autoJoin(){
        String ip=Prefs.getLastIp(this);
        if(ip==null||inCall)return;
        setStatus("Looking for last host...");
        new Thread(()->{
            try{
                InetAddress addr=InetAddress.getByName(ip);
                if(addr.isReachable(2000))runOnUiThread(this::startJoin);
                else runOnUiThread(()->setStatus("Idle"));
            }catch(Exception e){runOnUiThread(()->setStatus("Idle"));}
        }).start();
    }
    private void showCallUI(){
        inCall=true;
        btnHost.setVisibility(View.GONE);
        btnJoin.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.VISIBLE);
        callSeconds=0;
    }
    private void endCall(){
        inCall=false;stopTimer();
        if(discovery!=null){discovery.stop();discovery=null;}
        if(bound)service.stopSession();
        btnHost.setVisibility(View.VISIBLE);
        btnJoin.setVisibility(View.VISIBLE);
        btnEndCall.setVisibility(View.GONE);
        tvTimer.setVisibility(View.GONE);
        tvRoomCode.setVisibility(View.GONE);
        tvPeer.setVisibility(View.GONE);
        setStatus("Idle");isPlaying=false;
        btnPlayPause.setText("\u25B6");
        remotePlNames.clear();remoteSongs.clear();
    }
    private void guestLeft(){
        stopTimer();callSeconds=0;
        tvTimer.setText("00:00");
        tvPeer.setVisibility(View.GONE);
        isPlaying=false;btnPlayPause.setText("\u25B6");
        if(bound)service.resetPeerKeepAlive();
        if(discovery!=null)discovery.stop();
        discovery=new DiscoveryHelper();
        discovery.startBeacon(Prefs.getUsername(this));
        setStatus("Waiting for guest...");
    }
    private void refreshPlaylistSpinner(){
        List<String> names=new ArrayList<>();
        if(isHost){for(PlaylistManager.Playlist pl:playlists.getAll())names.add(pl.name);}
        else names.addAll(remotePlNames);
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPlaylist.setAdapter(a);
        if(!names.isEmpty())selectPlaylist(0);
    }
    private void selectPlaylist(int idx){
        currentPl=idx;
        List<String> songs=new ArrayList<>();
        if(isHost){
            PlaylistManager.Playlist pl=playlists.get(idx);
            if(pl!=null){songs.addAll(pl.titles);tvPlaylistName.setText(pl.name);}
        }else{
            if(idx<remoteSongs.size())songs.addAll(remoteSongs.get(idx));
            if(idx<remotePlNames.size())tvPlaylistName.setText(remotePlNames.get(idx));
        }
        listSongs.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,songs));
    }
    private void playSong(int pl,int song){
        if(!isHost||!bound)return;
        PlaylistManager.Playlist p=playlists.get(pl);
        if(p==null||song>=p.uris.size())return;
        currentPl=pl;currentSong=song;isPlaying=true;
        service.streamSong(p.uris.get(song),pl,song);
        String title=p.titles.get(song);
        tvNowPlaying.setText(title);tvPlaylistName.setText(p.name);
        btnPlayPause.setText("\u23F8");
        service.sendControl(Protocol.CMD_NOW_PLAYING+p.name+"|"+title);
    }
    private void onPlayPause(){
        if(isHost){
            if(isPlaying){
                service.stopMusicStream();isPlaying=false;
                btnPlayPause.setText("\u25B6");
                service.sendControl(Protocol.CMD_PAUSE);
            }else playSong(currentPl,currentSong);
        }else if(bound&&inCall){
            service.sendControl(isPlaying?Protocol.CMD_PAUSE:Protocol.CMD_PLAY);
            isPlaying=!isPlaying;
            btnPlayPause.setText(isPlaying?"\u23F8":"\u25B6");
        }
    }
    private void onNext(){
        if(isHost){
            PlaylistManager.Playlist pl=playlists.get(currentPl);
            if(pl!=null)playSong(currentPl,(currentSong+1)%pl.uris.size());
        }else if(bound&&inCall)service.sendControl(Protocol.CMD_NEXT);
    }
    private void onPrev(){
        if(isHost){
            PlaylistManager.Playlist pl=playlists.get(currentPl);
            if(pl==null)return;
            int idx=currentSong-1;if(idx<0)idx=pl.uris.size()-1;
            playSong(currentPl,idx);
        }else if(bound&&inCall)service.sendControl(Protocol.CMD_PREV);
    }
    private void toggleMusicPanel(){
        musicPanelOpen=!musicPanelOpen;
        musicPanel.setVisibility(musicPanelOpen?View.VISIBLE:View.GONE);
        btnExpandMusic.setText(musicPanelOpen?"\u2304":"\u2303");
    }
    private void toggleMute(){
        muted=!muted;btnMute.setText(muted?"Unmute":"Mute");
        if(bound)service.setMuted(muted);
    }
    private void toggleSpeaker(){
        speakerOn=!speakerOn;btnSpeaker.setText(speakerOn?"Earpiece":"Speaker");
        if(bound)service.setSpeaker(speakerOn);
    }
    @Override public void onPeerConnected(String username,InetAddress addr){
        runOnUiThread(()->{
            tvPeer.setText("\u2022 "+username);tvPeer.setVisibility(View.VISIBLE);
            if(isHost){
                Prefs.saveLastIp(this,addr.getHostAddress());
                service.setPeer(addr);
                if(playlists.size()>0)
                    service.sendControl(Protocol.CMD_PLAYLISTS+playlists.serializeForRemote());
            }
            setStatus("In call with "+username);startTimer();
        });
    }
    @Override public void onDisconnected(){
        runOnUiThread(()->{
            if(!inCall)return;
            if(isHost)guestLeft();
            else{setStatus("Disconnected");endCall();}
        });
    }
    @Override public void onNowPlaying(String playlist,String song){
        runOnUiThread(()->{
            tvNowPlaying.setText(song);tvPlaylistName.setText(playlist);
            isPlaying=true;btnPlayPause.setText("\u23F8");
        });
    }
    @Override public void onControlMessage(String msg){
        if(msg.startsWith(Protocol.CMD_PLAYLISTS))
            runOnUiThread(()->parseRemotePlaylists(msg.substring(Protocol.CMD_PLAYLISTS.length())));
        else if(isHost)runOnUiThread(()->handleHostCommand(msg));
    }
    private void parseRemotePlaylists(String json){
        remotePlNames.clear();remoteSongs.clear();
        try{
            JSONArray root=new JSONArray(json);
            for(int i=0;i<root.length();i++){
                JSONObject o=root.getJSONObject(i);
                remotePlNames.add(o.getString("name"));
                JSONArray s=o.getJSONArray("songs");
                List<String> sl=new ArrayList<>();
                for(int j=0;j<s.length();j++)sl.add(s.getString(j));
                remoteSongs.add(sl);
            }
        }catch(Exception e){e.printStackTrace();}
        refreshPlaylistSpinner();
    }
    private void handleHostCommand(String msg){
        if(msg.equals(Protocol.CMD_PLAY))onPlayPause();
        else if(msg.equals(Protocol.CMD_PAUSE))onPlayPause();
        else if(msg.equals(Protocol.CMD_NEXT))onNext();
        else if(msg.equals(Protocol.CMD_PREV))onPrev();
        else if(msg.startsWith(Protocol.CMD_SELECT_PL)){
            try{selectPlaylist(Integer.parseInt(msg.substring(Protocol.CMD_SELECT_PL.length())));}catch(Exception ignored){}
        }else if(msg.startsWith(Protocol.CMD_SELECT_SONG)){
            try{playSong(currentPl,Integer.parseInt(msg.substring(Protocol.CMD_SELECT_SONG.length())));}catch(Exception ignored){}
        }
    }
    private void startTimer(){
        stopTimer();
        timerRunnable=new Runnable(){public void run(){
            callSeconds++;
            tvTimer.setText(String.format("%02d:%02d",callSeconds/60,callSeconds%60));
            handler.postDelayed(this,1000);
        }};
        handler.postDelayed(timerRunnable,1000);
    }
    private void stopTimer(){if(timerRunnable!=null)handler.removeCallbacks(timerRunnable);}
    private void setStatus(String s){tvStatus.setText(s);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void requestPerms(){
        List<String> need=new ArrayList<>();
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        if(Build.VERSION.SDK_INT>=33){
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            need.add(Manifest.permission.READ_MEDIA_AUDIO);
        }else need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        List<String> missing=new ArrayList<>();
        for(String p:need)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(!missing.isEmpty())ActivityCompat.requestPermissions(this,missing.toArray(new String[0]),100);
    }
    @Override public void onRequestPermissionsResult(int req,@NonNull String[] p,@NonNull int[] r){
        super.onRequestPermissionsResult(req,p,r);
    }
    @Override protected void onDestroy(){if(bound)unbindService(conn);super.onDestroy();}
}
