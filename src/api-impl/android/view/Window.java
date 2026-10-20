package android.view;

import android.R;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.IBinder;
import android.transition.Transition;
import android.widget.FrameLayout;
import android.widget.Toolbar;

public class Window {
	public static final int FEATURE_OPTIONS_PANEL = 0;
	public static final int FEATURE_NO_TITLE = 1;

	public ViewTreeObserver view_tree_observer = null;
	private WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, 0);

	public static interface Callback {
		public default boolean dispatchTouchEvent(MotionEvent event) { return false; }
		public void onContentChanged();

		public abstract boolean onCreatePanelMenu(int featureId, Menu menu);

		public View onCreatePanelView(int featureId);

		public boolean onPreparePanel(int featureId, View view, Menu menu);

		public boolean onMenuItemSelected(int featureId, MenuItem item);

		public void onPanelClosed(int featureId, Menu menu);

		public boolean onMenuOpened(int featureId, Menu menu);
	}

	/**
	 * Callback for clients that want frame timing information for each frame
	 * rendered by the Window.
	 *
	 * AOSP frameworks/base/core/java/android/view/Window.java:728-748.
	 */
	public interface OnFrameMetricsAvailableListener {
		/**
		 * Called when information is available for the previously rendered
		 * frame. {@code frameMetrics} is reused between calls.
		 */
		void onFrameMetricsAvailable(Window window, FrameMetrics frameMetrics,
				int dropCountSinceLastInvocation);
	}

	/* Listeners registered through addOnFrameMetricsAvailableListener(), keyed by
	 * listener so a repeated add() is idempotent and remove() is exact - the same
	 * role View.mFrameMetricsObservers plays in AOSP (View.addFrameMetricsListener). */
	private ArrayMap<OnFrameMetricsAvailableListener, Handler> frameMetricsListeners;

	/**
	 * Set an observer to collect frame stats for each frame rendered in this window.
	 *
	 * AOSP Window.java:1043-1056 forwards this to the DecorView, whose
	 * View.addFrameMetricsListener() only starts a FrameMetricsObserver when the
	 * view is hardware accelerated (mAttachInfo.mThreadedRenderer != null); on the
	 * non-accelerated path it logs and produces no callbacks. ATL renders through
	 * GTK/GSK and has no ThreadedRenderer-backed frame-stats producer, so this is
	 * the non-accelerated path: the registration is recorded (and remove() is
	 * exact), but no FrameMetrics callback is produced.
	 */
	public final void addOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener, Handler handler) {
		if (decorView == null)
			throw new IllegalStateException("can't observe a Window without an attached view");
		if (listener == null)
			throw new NullPointerException("listener cannot be null");
		if (frameMetricsListeners == null)
			frameMetricsListeners = new ArrayMap<>();
		frameMetricsListeners.put(listener, handler);
	}

	/**
	 * Remove observer and stop listening to frame stats for this window.
	 */
	public final void removeOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener) {
		if (decorView != null && frameMetricsListeners != null)
			frameMetricsListeners.remove(listener);
	}

	private android.media.session.MediaController mediaController;
	public void setMediaController(android.media.session.MediaController controller) { mediaController = controller; }
	public android.media.session.MediaController getMediaController() { return mediaController; }

	public long native_window;
	private ViewGroup decorView;

	/* The window's identity, owned by the window itself (not re-created per call or
	 * per View). AOSP's token is the IWindow binder supplied by WindowManager; ATL
	 * has no IWindow binder, so a Binder allocated once per Window carries the same
	 * token-identity semantics. Every View of this window returns this same token
	 * from View.getWindowToken() / View.getWindowId(). */
	private IBinder windowToken;
	private WindowId windowId;

	/** @hide The stable identity token of this window. */
	public IBinder getWindowToken() {
		if (windowToken == null)
			windowToken = new Binder();
		return windowToken;
	}

	/** @hide The {@link WindowId} of this window; the same instance for every View
	 *  attached to it, so sibling Views compare equal. */
	public WindowId getWindowId() {
		if (windowId == null)
			windowId = new WindowId(getWindowToken());
		return windowId;
	}

	private Window.Callback callback;
	private Context context;

	public Window(Context context, Window.Callback callback) {
		this.callback = callback;
		this.context = context;
		decorView = new FrameLayout(context);
		decorView.setId(android.R.id.content);
	}

	public void set_native_window(long native_window) {
		this.native_window = native_window;
		set_jobject(native_window, this);
	}

	public void addFlags(int flags) {}
	public void setFlags(int flags, int mask) {}
	public void clearFlags(int flags) {}

	public final Callback getCallback() {
		return this.callback;
	}
	public void setCallback(Window.Callback callback) {
		this.callback = callback;
	}

		// Window hierarchy state (public API contract; internal bundle key is an
	// ATL implementation detail — AOSP PhoneWindow sources are not in the
	// pinned checkout, see tests/activity/DESIGN-CHECKPOINT.md).
	public Bundle saveHierarchyState() {
		if (decorView == null)
			return null;
		SparseArray<Parcelable> states = new SparseArray<Parcelable>();
		decorView.saveHierarchyState(states);
		Bundle out = new Bundle();
		out.putSparseParcelableArray("android:viewHierarchyState", states);
		return out;
	}

	public void restoreHierarchyState(Bundle savedState) {
		if (savedState == null || decorView == null)
			return;
		SparseArray<Parcelable> states = savedState.getSparseParcelableArray("android:viewHierarchyState");
		if (states != null)
			decorView.restoreHierarchyState(states);
	}

public void setContentView(View view) {
		if (decorView.getBackground() == null) {
			TypedArray ta = context.obtainStyledAttributes(new int[] {R.attr.windowBackground});
			if (ta.hasValue(0))
				setBackgroundDrawable(ta.getDrawable(0));
			ta.recycle();
		}
		decorView.removeAllViews();
		decorView.addView(view);
	}

	/** @hide Entry from the native window, before app dispatch overrides. */
	public boolean dispatchTouchEvent(MotionEvent event) {
		return callback != null ? callback.dispatchTouchEvent(event) : superDispatchTouchEvent(event);
	}

	public boolean superDispatchTouchEvent(MotionEvent event) {
		return decorView.dispatchTouchEvent(event);
	}

	public View getDecorView() {
		return decorView;
	}

	public void takeInputQueue(InputQueue.Callback callback) {
		take_input_queue(native_window, callback, new InputQueue());
	}

	public boolean requestFeature(int featureId) {
		return false;
	}

	public View findViewById(int id) {
		if (id == com.android.internal.R.id.action_bar)
			return new Toolbar(context);
		return decorView.findViewById(id);
	}

	public View peekDecorView() {
		return null;
	}

	public WindowManager.LayoutParams getAttributes() {
		return params;
	}

	public void setBackgroundDrawable(Drawable drawable) {
		// HACK: disable transparent background for WhatsApp dialogs
		// For some unknown reason, the language picker in WhatsApp doesn't render the BottomSheet background currently.
		if (!"com.whatsapp".equals(context.getPackageName()))
			remove_gtk_background(native_window);
		decorView.setBackgroundDrawable(drawable);
	}

	public void setAttributes(WindowManager.LayoutParams params) {
		if (params.screenBrightness != -1)
			set_screen_brightness(params.screenBrightness);
		this.params = params;
		setLayout(params.width, params.height);
	}

	public void takeSurface(SurfaceHolder.Callback2 callback) {}

	public void setStatusBarColor(int color) {}

	public void setNavigationBarColor(int color) {}

	public void setFormat(int format) {}

	public void setLayout(int width, int height) {
		params.width = width;
		params.height = height;
		set_layout(native_window, width, height);
	}

	/** @hide Supply resolved host dimensions without changing Android layout parameters. */
	public void internalSetDefaultSize(int width, int height) {
		set_layout(native_window, width, height);
	}

	public WindowManager getWindowManager() {
		return new WindowManagerImpl();
	}

	public void setSoftInputMode(int dummy) {}

	public int getNavigationBarColor() {
		return 0xFF888888; // gray
	}

	public void setBackgroundDrawableResource(int resId) {
		setBackgroundDrawable(context.getDrawable(resId));
	}

	public int getStatusBarColor() { return 0xFFFF0000; }

	public Context getContext() {
		return context;
	}

	public boolean hasFeature(int featureId) {
		return false;
	}

	public void setTitle(CharSequence title) {
		set_title(native_window, title != null ? title.toString() : context.getPackageName());
	}

	public Transition getSharedElementEnterTransition() {
		return new Transition();
	}

	public void setSharedElementExitTransition(Transition transition) {}

	public void setSharedElementReenterTransition(Transition transition) {}

	public void setSharedElementReturnTransition(Transition transition) {}

	public Transition getSharedElementExitTransition() {
		return new Transition();
	}

	public Transition getSharedElementReenterTransition() {
		return new Transition();
	}

	public void setReturnTransition(Transition transition) {}

	public void setEnterTransition(Transition transition) {}

	public void setGravity(int gravity) {}

	public void setDecorFitsSystemWindows(boolean fits) {}

	public WindowInsetsController getInsetsController() {
		return new InsetsController();
	}

	public void setStatusBarContrastEnforced(boolean enforced) {}

	/**
	 * Requested colour mode of this window. ATL renders through GTK/GSK into an
	 * 8-bit sRGB surface and has no wide-gamut/HDR output path, so the request is
	 * recorded for the getColorMode()/ActivityInfo contract but the backend
	 * limitation is explicit: it is not reported as satisfied anywhere.
	 */
	public static final int COLOR_MODE_DEFAULT = 0;
	public static final int COLOR_MODE_WIDE_COLOR_GAMUT = 1;
	public static final int COLOR_MODE_HDR = 2;

	private int colorMode = COLOR_MODE_DEFAULT;

	/** @hide */
	public void setColorMode(int colorMode) {
		if (colorMode < COLOR_MODE_DEFAULT || colorMode > COLOR_MODE_HDR)
			throw new IllegalArgumentException("Invalid color mode: " + colorMode);
		this.colorMode = colorMode;
	}

	/** @hide */
	public int getColorMode() {
		return colorMode;
	}

	public void setNavigationBarContrastEnforced(boolean enforced) {}

	public void setSharedElementEnterTransition(Transition transition) {}

	public Transition getEnterTransition() {
		return null;
	}

	public void setWindowAnimations(int resId) {}

	public void setDimAmount(float dimAmount) {}

	private native void set_title(long native_window, String title);
	public native void take_input_queue(long native_window, InputQueue.Callback callback, InputQueue queue);
	public native void set_layout(long native_window, int width, int height);
	private static native void set_jobject(long ptr, Window obj);
	private native void remove_gtk_background(long native_window);
	private native void set_screen_brightness(float brightness);
}
