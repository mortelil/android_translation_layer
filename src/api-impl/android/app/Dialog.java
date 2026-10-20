package android.app;

import android.R;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;

public class Dialog implements Window.Callback, DialogInterface {
	protected long nativePtr;

	protected native long nativeInit(long decor_view);
	private native void nativeSetTitle(long ptr, String title);
	private native void nativeSetContentView(long ptr, long widget);
	private native void nativeShow(long ptr);
	private native void nativeClose(long ptr);
	private native boolean nativeIsShowing(long ptr);
	private native void nativeSetCanceledOnTouchOutside(long ptr, boolean cancel);

	private Context context;
	private Window window;
	private OnDismissListener onDismissListener;
	private OnShowListener onShowListener;
	private OnCancelListener onCancelListener;
	private boolean cancelable = true, canceled, created, showing;

	public Dialog(Context context, int themeResId) {
		// Same theme selection as AOSP Dialog(Context, int, boolean),
		// frameworks/base/core/java/android/app/Dialog.java, android-17.0.0_r1.
		if (themeResId == 0) {
			TypedValue value = new TypedValue();
			context.getTheme().resolveAttribute(R.attr.dialogTheme, value, true);
			themeResId = value.resourceId;
		}
		this.context = new ContextThemeWrapper(context, themeResId);
		window = new Window(this.context, this);
		nativePtr = nativeInit(window.getDecorView().widget);

		window.set_native_window(nativePtr);
		TypedArray attrs = this.context.obtainStyledAttributes(R.styleable.Window);
		boolean floating = attrs.getBoolean(R.styleable.Window_windowIsFloating, false);
		attrs.recycle();
		if (!floating) window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
	}

	public Dialog(Context context) {
		this(context, 0);
	}

	public final boolean requestWindowFeature(int featureId) {
		return false;
	}

	public Context getContext() {
		return context;
	}

	public void setContentView(View view) {
		getWindow().setContentView(view);
	}

	public void setContentView(int layoutResId) {
		setContentView(LayoutInflater.from(context).inflate(layoutResId, null));
	}

	public void setContentView(View view, ViewGroup.LayoutParams lp) {
		view.setLayoutParams(lp);
		setContentView(view);
	}

	public void setTitle(CharSequence title) {
		nativeSetTitle(nativePtr, String.valueOf(title));
	}

	public void setTitle(int titleId) {
		nativeSetTitle(nativePtr, context.getString(titleId));
	}

	public void setOwnerActivity(Activity activity) {}

	public void setCancelable(boolean cancelable) { this.cancelable = cancelable; }

	public void setOnCancelListener(OnCancelListener onCancelListener) { this.onCancelListener = onCancelListener; }

	public void setOnDismissListener(OnDismissListener onDismissListener) {
		this.onDismissListener = onDismissListener;
	}

	public View findViewById(int id) {
		return window.findViewById(id);
	}

	/** Create the dialog without displaying it; show() must not recreate it. */
	public void create() {
		if (!created) {
			onCreate(null);
			created = true;
		}
	}

	public void show() {
		System.out.println("showing the Dialog " + this);
		Runnable action = new Runnable() {
			@Override
			public void run() {
				if (showing) { nativeShow(nativePtr); return; }
				create();
				onStart();
				showing = true;
				canceled = false;
				// Read the size of the main window. Floating dialogs should be smaller as specified in the windowMinWidth* attributes.
				// Non-floating are typically constructed with MATCH_PARENT layout params and thus get the exact size of the main window.
				// Most non-floating dialogs are technically dialogs, but are expected to behave more like full size activities.
				Rect displayFrame = new Rect();
				getWindow().getDecorView().getWindowVisibleDisplayFrame(displayFrame);

				TypedArray a = context.obtainStyledAttributes(R.styleable.Window);
				if (a.hasValue(R.styleable.Window_windowCloseOnTouchOutside))
					setCanceledOnTouchOutside(a.getBoolean(R.styleable.Window_windowCloseOnTouchOutside, true));
				float windowWidthFraction = 1;
				if (a.getBoolean(R.styleable.Window_windowIsFloating, false)) {
					if (displayFrame.width() > displayFrame.height())
						windowWidthFraction = a.getFraction(R.styleable.Window_windowMinWidthMajor, 1, 1, 1);
					else
						windowWidthFraction = a.getFraction(R.styleable.Window_windowMinWidthMinor, 1, 1, 1);
				}
				a.recycle();

				LayoutParams lp = getWindow().getAttributes();
				int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(lp.width >= 0 ? lp.width : (int)(displayFrame.width() * windowWidthFraction),
				                                                        lp.width == LayoutParams.WRAP_CONTENT ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY);

				int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(lp.height >= 0 ? lp.height : (int)(displayFrame.height()),
				                                                         lp.height == LayoutParams.WRAP_CONTENT ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY);

				getWindow().getDecorView().internalSetDefaultMeasureSpec(widthMeasureSpec, heightMeasureSpec);
				window.internalSetDefaultSize(
					lp.width == LayoutParams.WRAP_CONTENT ? -1 : View.MeasureSpec.getSize(widthMeasureSpec),
					lp.height == LayoutParams.WRAP_CONTENT ? -1 : View.MeasureSpec.getSize(heightMeasureSpec));

				nativeShow(nativePtr);
				final OnShowListener listener = onShowListener;
				if (listener != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
					public void run() { listener.onShow(Dialog.this); }
				});
			}
		};
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action.run();
		} else {
			new Handler(Looper.getMainLooper()).post(action);
		}
	}

	public boolean isShowing() {
		return nativeIsShowing(nativePtr);
	}

	public void dismiss() {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override public void run() { dismiss(); }
			});
			return;
		}
		if (!showing) return;
		showing = false;
		// Remove now; only listener delivery is posted, like AOSP dismissDialog.
		// A delayed removal could otherwise close a subsequent show().
		nativeClose(nativePtr);
		onStop();
		final OnDismissListener listener = onDismissListener;
		if (listener != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override public void run() { listener.onDismiss(Dialog.this); }
		});
	}

	public Window getWindow() {
		return window;
	}

	public void setCanceledOnTouchOutside(boolean cancel) {
		nativeSetCanceledOnTouchOutside(nativePtr, cancel);
	}

	public class Builder {
		public Builder(Context context) {
			System.out.println("making a Dialog$Builder");
		}
	}

	@Override
	public void onContentChanged() {
	}
	@Override
	public boolean onCreatePanelMenu(int featureId, Menu menu) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onCreatePanelMenu'");
	}
	@Override
	public View onCreatePanelView(int featureId) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onCreatePanelView'");
	}
	@Override
	public boolean onPreparePanel(int featureId, View view, Menu menu) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onPreparePanel'");
	}
	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onMenuItemSelected'");
	}
	@Override
	public void onPanelClosed(int featureId, Menu menu) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onPanelClosed'");
	}

	@Override
	public boolean onMenuOpened(int featureId, Menu menu) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'onMenuOpened'");
	}

	protected void onStart() {}
	protected void onStop() {}

	protected void onCreate(Bundle savedInstanceState) {
		System.out.println("- onCreate - Dialog!");
	}

	private native void nativeHide(long ptr);

	public void hide() {
		System.out.println("hiding the Dialog " + this);
		nativeHide(nativePtr);
	}

	@Override
	public void cancel() {
		if (!canceled) { canceled = true; if (onCancelListener != null) onCancelListener.onCancel(this); }
		dismiss();
	}

	private void cancelFromUser() { if (cancelable) cancel(); }

	public void setOnShowListener(OnShowListener onShowListener) {
		this.onShowListener = onShowListener;
	}

	public void setCancelMessage(Message msg) {}

	public void setDismissMessage(Message msg) {}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		return getWindow().superDispatchTouchEvent(event) || onTouchEvent(event);
	}

	public boolean onTouchEvent(MotionEvent event) {
		return false;
	}

	public void setOnKeyListener(OnKeyListener onKeyListener) {}

	public View getCurrentFocus() {
		return null;
	}
}
