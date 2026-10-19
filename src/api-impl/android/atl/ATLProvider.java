package android.atl;

import android.content.ContentProvider;
import android.content.pm.ProviderInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import java.util.Objects;

/**
 * One provider record and its publication attempt. Based on AOSP frameworks/base
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d: ActivityThread installs on main;
 * ContentProviderHelper publishes only after initialization and bounds waiters.
 * A timed-out/interrupted caller does not cancel the shared installation.
 */
public final class ATLProvider {
    private static final String TAG = "ATLProvider";
    private static final int NEW = 0, LOADING = 1, READY = 2, FAILED = 3;
    private final ATLLoadedApp app;
    private final ProviderInfo info;
    private int state;
    private long attempt;
    private Thread installer;
    private ContentProvider instance;
    private Throwable failure;

    public ATLProvider(ATLLoadedApp app, ProviderInfo info) {
        this.app = Objects.requireNonNull(app);
        this.info = Objects.requireNonNull(info);
    }
    public ATLProvider(ContentProvider instance) {
        this.app = null;
        this.info = null;
        this.instance = Objects.requireNonNull(instance);
        state = READY;
    }
    public String getAuthority() { return info == null ? null : info.authority; }
    public synchronized Throwable getLoadFailure() { return failure; }

    private static long timeoutMillis() {
        String value = System.getenv("ATL_PROVIDER_READY_TIMEOUT_MS");
        if (value != null) try {
            long ms = Long.parseLong(value);
            if (ms > 0 && ms <= 600000) return ms;
        } catch (NumberFormatException ignored) {}
        return 20000; // AOSP ContentResolver.CONTENT_PROVIDER_READY_TIMEOUT_MILLIS
    }

    public ContentProvider getContentProvider() {
        Looper main = Looper.getMainLooper();
        boolean onMain = main != null && Looper.myLooper() == main;
        final long ticket;
        boolean schedule = false;
        synchronized (this) {
            if (state == READY) return instance;
            if (state == LOADING) {
                if (installer == Thread.currentThread()) return null; // self lookup before publication
                ticket = attempt;
                if (!onMain || installer != null) return await(ticket);
                // Main is booting and can execute the pending install before its posted task.
            } else {
                if (main == null) {
                    failure = new IllegalStateException("Provider install requires a main looper");
                    return null;
                }
                ticket = ++attempt;
                state = LOADING;
                failure = null;
                schedule = !onMain;
            }
        }
        if (onMain) return install(ticket);
        if (schedule && !new Handler(main).post(new Runnable() {
            public void run() { install(ticket); }
        })) {
            synchronized (this) {
                if (state == LOADING && attempt == ticket && installer == null) {
                    state = FAILED;
                    failure = new IllegalStateException("Provider main looper is exiting");
                    notifyAll();
                }
            }
        }
        synchronized (this) { return await(ticket); }
    }

    private ContentProvider install(long ticket) {
        synchronized (this) {
            if (attempt != ticket || state != LOADING || installer != null)
                return state == READY ? instance : null;
            installer = Thread.currentThread();
        }
        ContentProvider loaded = null;
        Throwable problem = null;
        try {
            loaded = app.loadClass(info.name).asSubclass(ContentProvider.class).newInstance();
            loaded.attachInfo(app.getApplication(), info);
            // ATL attachInfo does not call onCreate. AOSP ignores its boolean result too.
            loaded.onCreate();
        } catch (Throwable t) {
            loaded = null;
            problem = t;
            Slog.e(TAG, "Failed to initialize provider " + getAuthority(), t);
        }
        synchronized (this) {
            instance = loaded;
            failure = problem;
            installer = null;
            state = loaded == null ? FAILED : READY;
            notifyAll();
            return loaded;
        }
    }

    // Called with the record lock. Wait for THIS attempt, never for a later retry.
    private ContentProvider await(long ticket) {
        long deadline = System.nanoTime() + timeoutMillis() * 1000000L;
        while (attempt == ticket && state == LOADING) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            try { wait(remaining / 1000000L, (int)(remaining % 1000000L)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return attempt == ticket && state == READY ? instance : null;
    }
}
