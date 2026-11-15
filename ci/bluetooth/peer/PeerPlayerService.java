package dev.audiopipe.ci.peer;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Handler;
import android.media.*;

/** An ordinary, SDK-compliant media player. No capture, routing privilege or preferred output. */
public final class PeerPlayerService extends Service {
    private AudioTrack track;
    private AudioFocusRequest focus;
    private AudioManager manager;
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "peer", "CI original media", NotificationManager.IMPORTANCE_LOW));
        startForeground(1,new Notification.Builder(this,"peer").setContentTitle("CI original 660 Hz player")
                .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        manager = getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {},new Handler(getMainLooper())).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("Peer audio focus was not granted");
        int frames=4800;
        short[] samples=new short[frames*2];
        for(int f=0;f<frames;f++) samples[f*2]=samples[f*2+1]=(short)(1000*Math.sin(2*Math.PI*660*f/48000));
        track=new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames*4).build();
        if(track.write(samples,0,samples.length)!=samples.length) throw new IllegalStateException("Peer PCM write failed");
        track.setLoopPoints(0,frames,-1);
        track.play();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if(track!=null) { track.stop();track.release(); }
        if(focus!=null) manager.abandonAudioFocusRequest(focus);
        super.onDestroy();
    }
}
