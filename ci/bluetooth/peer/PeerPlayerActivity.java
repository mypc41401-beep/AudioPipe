package dev.audiopipe.ci.peer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.media.*;
import android.widget.TextView;

/** CI-only ordinary second app UID. No capture, replay, root or preferred-device API. */
public final class PeerPlayerActivity extends Activity {
    private AudioTrack track;
    private AudioFocusRequest focus;
    private AudioManager manager;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("CI ordinary peer: original 660 Hz media track (no preferred device)");
        setContentView(view);
        manager = getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {}, new Handler(getMainLooper())).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("Peer audio focus was not granted");
        int frames = 4800;
        short[] samples = new short[frames*2];
        for (int frame=0; frame<frames; frame++) samples[frame*2] = samples[frame*2+1] =
                (short)(1000 * Math.sin(2*Math.PI*660*frame/48000));
        track = new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames*4).build();
        if (track.write(samples,0,samples.length) != samples.length) throw new IllegalStateException("Peer PCM write failed");
        track.setLoopPoints(0,frames,-1);
        track.play();
    }
    // Keep playing when the test opens another Activity or presses Home.
    @Override public void onDestroy() {
        if (track != null) { track.stop(); track.release(); }
        if (focus != null) manager.abandonAudioFocusRequest(focus);
        super.onDestroy();
    }
}
