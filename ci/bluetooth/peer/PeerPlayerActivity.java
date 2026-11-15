package dev.audiopipe.ci.peer;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.TextView;

/** CI-only ordinary music app; ongoing playback belongs to its media foreground service. */
public final class PeerPlayerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("CI ordinary peer: original 660 Hz media track (no preferred device)");
        setContentView(view);
        startForegroundService(new Intent(this,PeerPlayerService.class));
    }
}
