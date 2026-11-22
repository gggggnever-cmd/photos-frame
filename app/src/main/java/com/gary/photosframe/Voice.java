package com.gary.photosframe;

import android.content.Context;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

/**
 * Voice and media keys (asked 2026-11-22): the TV's Google Assistant ("next", "previous", "pause", "play" on the
 * remote's mic) and any play/pause/next buttons reach the app through this media session. The Assistant only has
 * these generic words, so the app decides what they mean, as agreed with the owner:
 *   YouTube showing (any size, or music only)  → the song;
 *   YouTube off or closed                       → the photos ("pause" freezes the slideshow on this photo).
 * Active only while the frame is in front, so other apps get their voice commands back.
 */
final class Voice {
    /** What the commands act on. MainActivity implements it. */
    interface Target {
        boolean songMode();                 // YouTube is showing (any size, or music only)
        void song(String command);          // next, previous, pause, play
        void photos(String command);
        boolean slideshowPaused();
    }

    private final MediaSession session;
    private final Target target;

    Voice(Context ctx, Target target) {
        this.target = target;
        session = new MediaSession(ctx, "PhotosFrame");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onSkipToNext() { route("next"); }
            @Override public void onSkipToPrevious() { route("previous"); }
            @Override public void onPause() { route("pause"); }
            @Override public void onStop() { route("pause"); }
            @Override public void onPlay() { route("play"); }
        });
        update();
    }

    private void route(String command) {
        if (target.songMode()) target.song(command); else target.photos(command);
        update();
    }

    /** Tells Android what can be asked, and that something is "playing" so the Assistant picks this app. */
    void update() {
        boolean paused = !target.songMode() && target.slideshowPaused();
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    void setActive(boolean on) { session.setActive(on); if (on) update(); }

    void release() { session.release(); }
}
