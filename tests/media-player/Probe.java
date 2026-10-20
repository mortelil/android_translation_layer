package org.atl.mediaplayer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.media.MediaPlayer;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import java.util.Collections;

public class Probe extends Activity {
    Handler handler = new Handler();
    MediaPlayer player;
    int checks, prepared, completed, seeks, size;
    int pausedAt;
    boolean ended;
    void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PLAYER_CHECK " + name);
    }
    void later(final Runnable run, long delay) {
        handler.postDelayed(new Runnable() { public void run() {
            try { run.run(); } catch (Throwable t) { fail(t); }
        } }, delay);
    }
    void fail(Throwable t) {
        t.printStackTrace();
        if (player != null) player.release();
        System.exit(1);
    }
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        final SurfaceView view = new SurfaceView(this);
        setContentView(view);
        later(new Runnable() { public void run() { throw new AssertionError("watchdog"); } }, 25000);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) { begin(holder); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { }
            public void surfaceDestroyed(SurfaceHolder h) { }
        });
    }
    void begin(SurfaceHolder holder) {
        if (player != null) return;
        try {
            player = new MediaPlayer();
            player.setVolume(0, 0);
            try { player.start(); throw new AssertionError("start in idle accepted"); }
            catch (IllegalStateException expected) { checks++; }
            player.setDisplay(holder);
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int w, int e) { fail(new AssertionError("unexpected error " + w + ":" + e)); return true; }
            });
            player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer p, int w, int h) { check(w == 160 && h == 90, "video dimensions"); size++; }
            });
            player.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                public void onSeekComplete(MediaPlayer p) { seeks++; }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer p) {
                    if (ended) return;
                    ended = true; completed++;
                    check(!p.isPlaying(), "completion stops state");
                    check(seeks > 0, "seek callback");
                    check(size > 0, "size callback");
                    p.reset();
                    try { p.getDuration(); throw new AssertionError("duration after reset accepted"); }
                    catch (IllegalStateException expected) { checks++; }
                    testSync();
                }
            });
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    prepared++;
                    check(prepared == 1, "single prepared callback");
                    check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper(), "callback Looper");
                    check(!p.isPlaying(), "prepare does not autoplay");
                    check(p.getDuration() >= 3900 && p.getDuration() <= 4300, "real duration");
                    p.start();
                    later(new Runnable() { public void run() {
                        check(player.getCurrentPosition() > 250, "position advances");
                        player.pause(); pausedAt = player.getCurrentPosition();
                        check(!player.isPlaying(), "paused state");
                        later(new Runnable() { public void run() {
                            check(Math.abs(player.getCurrentPosition() - pausedAt) < 100, "pause freezes clock");
                            player.seekTo(2800, MediaPlayer.SEEK_CLOSEST);
                            player.start();
                        } }, 400);
                    } }, 900);
                }
            });
            String source = System.getenv("ATL_PLAYER_TEST_URI");
            if (source == null) throw new AssertionError("ATL_PLAYER_TEST_URI required");
            player.setDataSource(source, Collections.singletonMap("X-ATL-Probe", "required"));
            player.prepareAsync();
        } catch (Throwable t) { fail(t); }
    }
    void testSync() {
        try {
            player.setOnPreparedListener(null);
            player.setDataSource(System.getenv("ATL_PLAYER_TEST_URI"), Collections.singletonMap("X-ATL-Probe", "required"));
            player.prepare();
            check(!player.isPlaying(), "sync prepare does not autoplay");
            player.start(); player.stop();
            check(!player.isPlaying(), "stop state");
            player.prepare();
            player.setLooping(true);
            player.seekTo(2800, MediaPlayer.SEEK_CLOSEST);
            player.start();
            later(new Runnable() { public void run() {
                check(player.isPlaying(), "looping continues");
                check(player.getCurrentPosition() < 2600, "looping returns to beginning");
                check(completed == 1, "looping suppresses completion");
                player.pause();
                player.setDisplay(null);
                player.reset(); check(!player.isLooping(), "reset clears looping");
                testError();
            } }, 2100);
        } catch (Throwable ex) { fail(ex); }
    }
    void testError() {
        player.setOnPreparedListener(null);
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer p, int w, int e) {
                check(w == MediaPlayer.MEDIA_ERROR_UNKNOWN, "error delivered");
                check(completed == 1, "handled error does not complete");
                p.release(); p.release();
                try { p.start(); throw new AssertionError("start after release accepted"); }
                catch (IllegalStateException expected) { checks++; }
                // Reset/release while preparation is in flight must suppress stale events.
                final MediaPlayer abandoned = new MediaPlayer();
                abandoned.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer q) { fail(new AssertionError("stale prepared callback")); }
                });
                try { abandoned.setDataSource(System.getenv("ATL_PLAYER_TEST_URI")); abandoned.prepareAsync(); abandoned.reset(); abandoned.release(); }
                catch (Exception ex) { fail(ex); }
                later(new Runnable() { public void run() {
                    System.out.println("MEDIA_PLAYER_CONTRACT PASS checks=" + checks); System.exit(0);
                } }, 500);
                return true;
            }
        });
        try { player.setDataSource("/tmp/atl-mediaplayer-file-that-does-not-exist"); player.prepareAsync(); }
        catch (Exception ex) { fail(ex); }
    }
}
