package android.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Map;

/**
 * Linux playback implementation. State/callback contracts follow AOSP MediaPlayer
 * (android-10.0.0_r1); decoding, scheduling and network I/O use GStreamer playbin.
 * This is not a port of Android's mediaserver. DRM and audio-session effects are
 * not implemented. Unsupported sources fail explicitly rather than preparing a
 * synthetic stream.
 */
public class MediaPlayer {
    public static final int MEDIA_ERROR_UNKNOWN = 1, MEDIA_ERROR_SERVER_DIED = 100;
    public static final int MEDIA_ERROR_IO = -1004, MEDIA_ERROR_UNSUPPORTED = -1010;
    public static final int MEDIA_INFO_BUFFERING_START = 701, MEDIA_INFO_BUFFERING_END = 702;
    public static final int SEEK_PREVIOUS_SYNC = 0, SEEK_NEXT_SYNC = 1, SEEK_CLOSEST_SYNC = 2, SEEK_CLOSEST = 3;
    private enum State { IDLE, INITIALIZED, PREPARING, PREPARED, STARTED, PAUSED, STOPPED, COMPLETE, ERROR, RELEASED }
    private State state = State.IDLE;
    private long peer;
    private int generation;
    private Surface surface;
    private boolean looping, errorDelivered;
    private SurfaceHolder displayHolder;
    private boolean screenOn;
    private float volume = 1;
    private int width, height;
    private final Handler events;
    private OnPreparedListener preparedListener;
    private OnCompletionListener completionListener;
    private OnErrorListener errorListener;
    private OnBufferingUpdateListener bufferingListener;
    private OnInfoListener infoListener;
    private OnSeekCompleteListener seekListener;
    private OnVideoSizeChangedListener sizeListener;

    public interface OnCompletionListener { void onCompletion(MediaPlayer player); }
    public interface OnErrorListener { boolean onError(MediaPlayer player, int what, int extra); }
    public interface OnPreparedListener { void onPrepared(MediaPlayer player); }
    public interface OnBufferingUpdateListener { void onBufferingUpdate(MediaPlayer player, int percent); }
    public interface OnInfoListener { boolean onInfo(MediaPlayer player, int what, int extra); }
    public interface OnSeekCompleteListener { void onSeekComplete(MediaPlayer player); }
    public interface OnVideoSizeChangedListener { void onVideoSizeChanged(MediaPlayer player, int width, int height); }
    public interface MediaPlayerControl { }

    public MediaPlayer() {
        Looper looper = Looper.myLooper();
        if (looper == null) looper = Looper.getMainLooper();
        events = looper == null ? null : new Handler(looper);
    }
    private void require(State... allowed) {
        for (State s : allowed) if (state == s) return;
        throw new IllegalStateException("MediaPlayer is " + state);
    }
    private void alive() {
        if (state == State.RELEASED) throw new IllegalStateException("MediaPlayer is released");
    }
    public synchronized void setDataSource(String path) throws IOException { setDataSource(path, null); }
    public synchronized void setDataSource(String path, Map<String, String> headers) throws IOException {
        require(State.IDLE);
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Empty source");
        String[] keys = headers == null ? new String[0] : headers.keySet().toArray(new String[0]);
        String[] values = new String[keys.length];
        for (int i = 0; i < keys.length; ++i) {
            values[i] = headers.get(keys[i]);
            if (keys[i] == null || values[i] == null || keys[i].contains("\r") || keys[i].contains("\n")
                    || values[i].contains("\r") || values[i].contains("\n"))
                throw new IllegalArgumentException("Invalid HTTP header");
        }
        peer = nativeCreate(path, keys, values, generation);
        if (peer == 0) throw new IOException("Cannot create media playback pipeline");
        try { nativeSurface(peer, surface); } catch (RuntimeException e) {
            nativeRelease(peer); peer = 0; throw e;
        }
        nativeVolume(peer, volume);
        state = State.INITIALIZED;
    }
    public void setDataSource(Context context, Uri uri) throws IOException { setDataSource(context, uri, null); }
    public void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException {
        if (uri == null) throw new IllegalArgumentException("Null uri");
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) setDataSource(uri.getPath(), headers);
        else if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) setDataSource(uri.toString(), headers);
        else throw new IOException("MediaPlayer source scheme not supported: " + scheme);
    }
    public void setDataSource(FileDescriptor fd) throws IOException { setDataSource(fd, 0, Long.MAX_VALUE); }
    public synchronized void setDataSource(FileDescriptor fd, long offset, long length) throws IOException {
        require(State.IDLE);
        if (fd == null || offset < 0 || length < 0) throw new IllegalArgumentException("Invalid descriptor range");
        throw new IOException("MediaPlayer descriptor sources are not yet supported");
    }
    public static MediaPlayer create(Context context, int resource) { return null; }
    public synchronized void setDisplay(SurfaceHolder holder) {
        setSurface(holder == null ? null : holder.getSurface());
        displayHolder = holder;
        updateScreenOn();
    }
    private void updateScreenOn() {
        if (displayHolder != null) displayHolder.setKeepScreenOn(screenOn && state == State.STARTED);
    }
    public synchronized void setSurface(Surface target) {
        alive();
        if (target != null && !target.isValid()) throw new IllegalArgumentException("Invalid Surface");
        if (peer != 0) nativeSurface(peer, target);
        if (displayHolder != null) displayHolder.setKeepScreenOn(false);
        displayHolder = null;
        surface = target;
    }
    public synchronized void prepareAsync() {
        require(State.INITIALIZED, State.STOPPED);
        state = State.PREPARING;
        if (!nativePrepare(peer, false)) { state = State.ERROR; postEvent(generation, 100, MEDIA_ERROR_IO, 0); }
    }
    public synchronized void prepare() throws IOException {
        require(State.INITIALIZED, State.STOPPED);
        state = State.PREPARING;
        if (!nativePrepare(peer, true)) { state = State.ERROR; throw new IOException("MediaPlayer preparation failed"); }
        state = State.PREPARED;
    }
    public synchronized void start() {
        require(State.PREPARED, State.STARTED, State.PAUSED, State.COMPLETE);
        if (state == State.COMPLETE) nativeSeek(peer, 0, SEEK_CLOSEST);
        nativeState(peer, 1); state = State.STARTED; updateScreenOn();
    }
    public synchronized void pause() {
        require(State.STARTED, State.PAUSED, State.COMPLETE);
        nativeState(peer, 2); state = State.PAUSED; updateScreenOn();
    }
    public synchronized void stop() {
        require(State.PREPARED, State.STARTED, State.PAUSED, State.STOPPED, State.COMPLETE);
        nativeState(peer, 3); state = State.STOPPED; updateScreenOn();
    }
    public synchronized boolean isPlaying() { alive(); return state == State.STARTED; }
    public synchronized void seekTo(int millis) { seekTo((long) millis, SEEK_PREVIOUS_SYNC); }
    public synchronized void seekTo(long millis, int mode) {
        require(State.PREPARED, State.STARTED, State.PAUSED, State.COMPLETE);
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("Invalid seek mode");
        if (!nativeSeek(peer, Math.max(0, millis), mode)) throw new IllegalStateException("Source is not seekable");
    }
    public synchronized int getDuration() {
        require(State.PREPARED, State.STARTED, State.PAUSED, State.STOPPED, State.COMPLETE);
        return nativeTime(peer, true);
    }
    public synchronized int getCurrentPosition() {
        require(State.INITIALIZED, State.PREPARED, State.STARTED, State.PAUSED, State.STOPPED, State.COMPLETE);
        return nativeTime(peer, false);
    }
    public synchronized int getVideoWidth() { alive(); return width; }
    public synchronized int getVideoHeight() { alive(); return height; }
    public synchronized void setLooping(boolean loop) { alive(); looping = loop; }
    public synchronized boolean isLooping() { alive(); return looping; }
    public synchronized void setVolume(float left, float right) {
        alive(); volume = Math.max(0, Math.min(1, (left + right) / 2));
        if (peer != 0) nativeVolume(peer, volume);
    }
    public void setAudioAttributes(AudioAttributes attributes) { }
    public void setAudioStreamType(int streamType) { }
    public void setWakeMode(Context context, int mode) { }
    public synchronized void setScreenOnWhilePlaying(boolean enabled) { alive(); screenOn = enabled; updateScreenOn(); }
    public int getAudioSessionId() { return 0; }
    public synchronized void reset() { alive(); dispose(); looping = false; state = State.IDLE; }
    public synchronized void release() {
        if (state == State.RELEASED) return;
        dispose(); surface = null; displayHolder = null; state = State.RELEASED;
        preparedListener = null; completionListener = null; errorListener = null;
        bufferingListener = null; infoListener = null; seekListener = null; sizeListener = null;
    }
    private void dispose() {
        ++generation;
        errorDelivered = false;
        if (displayHolder != null) displayHolder.setKeepScreenOn(false);
        if (peer != 0) { nativeRelease(peer); peer = 0; }
        width = height = 0;
        if (events != null) events.removeCallbacksAndMessages(null);
    }
    @Override protected void finalize() throws Throwable { try { release(); } finally { super.finalize(); } }
    public synchronized void setOnPreparedListener(OnPreparedListener l) { preparedListener = l; }
    public synchronized void setOnCompletionListener(OnCompletionListener l) { completionListener = l; }
    public synchronized void setOnErrorListener(OnErrorListener l) { errorListener = l; }
    public synchronized void setOnBufferingUpdateListener(OnBufferingUpdateListener l) { bufferingListener = l; }
    public synchronized void setOnInfoListener(OnInfoListener l) { infoListener = l; }
    public synchronized void setOnSeekCompleteListener(OnSeekCompleteListener l) { seekListener = l; }
    public synchronized void setOnVideoSizeChangedListener(OnVideoSizeChangedListener l) { sizeListener = l; }
    // Called by the native bus/frame threads. Listeners always run on the creator's Looper.
    private void postEvent(int token, int what, int arg1, int arg2) {
        if (events != null) events.post(new Runnable() {
            public void run() { dispatchEvent(token, what, arg1, arg2); }
        });
    }
    private synchronized void dispatchEvent(int token, int what, int a, int b) {
        if (token != generation || state == State.RELEASED) return;
        switch (what) {
        case 1:
            if (state != State.PREPARING && state != State.PREPARED) return;
            state = State.PREPARED;
            if (preparedListener != null) preparedListener.onPrepared(this);
            break;
        case 2:
            if (state != State.STARTED && state != State.PAUSED) return;
            if (looping && nativeSeek(peer, 0, SEEK_CLOSEST)) { nativeState(peer, 1); state = State.STARTED; updateScreenOn(); break; }
            state = State.COMPLETE; updateScreenOn();
            if (completionListener != null) completionListener.onCompletion(this);
            break;
        case 3: if (bufferingListener != null) bufferingListener.onBufferingUpdate(this, a); break;
        case 4: if (seekListener != null) seekListener.onSeekComplete(this); break;
        case 5:
            width = a; height = b;
            if (sizeListener != null) sizeListener.onVideoSizeChanged(this, a, b);
            break;
        case 100:
            if (errorDelivered) return;
            errorDelivered = true;
            state = State.ERROR; updateScreenOn();
            boolean handled = errorListener != null && errorListener.onError(this, MEDIA_ERROR_UNKNOWN, a);
            if (!handled && completionListener != null) completionListener.onCompletion(this);
            break;
        }
    }
    private native long nativeCreate(String path, String[] keys, String[] values, int token);
    private static native void nativeSurface(long peer, Surface surface);
    private static native boolean nativePrepare(long peer, boolean wait);
    private static native void nativeState(long peer, int state);
    private static native boolean nativeSeek(long peer, long millis, int mode);
    private static native int nativeTime(long peer, boolean duration);
    private static native void nativeVolume(long peer, float volume);
    private static native void nativeRelease(long peer);
}
