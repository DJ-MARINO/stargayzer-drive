package com.djmarino.stargayzer.drive;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Pauses the music while someone talks to an app (Maps voice search, the Assistant) and resumes it after.
 * The X10 keeps a separate audio focus stack per screen, so Maps in one frame asking for focus never reaches
 * Spotify in the other and the music kept playing over the voice prompt. Android tells any app when recording
 * starts and stops (without saying who), so Drive does the pausing itself. The Google app's always-on hotword
 * listening (source HOTWORD) is ignored; only real listening counts.
 */
final class VoicePause extends AudioManager.AudioRecordingCallback {
    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pausedByUs;

    private VoicePause(Context c) { this.c = c.getApplicationContext(); }

    static void start(Context c) {
        try {
            VoicePause v = new VoicePause(c);
            c.getSystemService(AudioManager.class).registerAudioRecordingCallback(v, v.main);
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "voice pause", e);
        }
    }

    private static boolean listening(List<AudioRecordingConfiguration> configs) {
        for (AudioRecordingConfiguration r : configs) {
            int src = r.getClientAudioSource();
            if (r.isClientSilenced()) continue;
            if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION || src == MediaRecorder.AudioSource.MIC
                    || src == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return true;
        }
        return false;
    }

    private final Runnable resume = () -> {
        if (!pausedByUs) return;
        pausedByUs = false;
        Live.mediaCmd(c, "play");
    };

    @Override
    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        if (listening(configs)) {
            main.removeCallbacks(resume);
            if (pausedByUs) return;
            MediaController mc = Live.controller(c);
            PlaybackState st = mc == null ? null : mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                pausedByUs = true;
                mc.getTransportControls().pause();
            }
        } else if (pausedByUs) {
            // a short gap: Maps often listens twice (prompt, then the answer)
            main.removeCallbacks(resume);
            main.postDelayed(resume, 1500);
        }
    }
}
