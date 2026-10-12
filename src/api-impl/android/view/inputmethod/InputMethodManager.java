package android.view.inputmethod;

import android.os.IBinder;
import android.util.Log;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class InputMethodManager {

	private static long im_context = nativeInit();
	private static View activeView = null;

	private ArrayList<InputMethodInfo> input_method_list = new ArrayList<InputMethodInfo>();

	/**
	 * Set ATL_DEBUG_INPUT to trace how host key/IME input is routed to app views,
	 * together with the "ATL input:"/"ATL key:" lines printed by the native side.
	 */
	private static void debugLog(String message) {
		if (System.getenv("ATL_DEBUG_INPUT") != null)
			Log.w("ATL-input", message);
	}

	private static String name(View view) {
		return view == null ? "null" : view.getClass().getName();
	}

	public boolean hideSoftInputFromWindow(IBinder windowToken, int flags) {
		debugLog("hideSoftInputFromWindow: view=" + name(activeView));
		nativeHideSoftInput(im_context);
		activeView = null;
		return false;
	}

	public boolean showSoftInput(View view, int flags) {
		if (view == activeView) {
			boolean shown = nativeShowSoftInput(im_context, view.widget, null, 0);
			debugLog("showSoftInput: view=" + name(view) + " (already active) flags=0x"
			         + Integer.toHexString(flags) + " -> " + shown);
			return shown;
		}
		EditorInfo outAttrs = new EditorInfo();
		InputConnection ic = view.onCreateInputConnection(outAttrs);
		if (ic == null) {
			debugLog("showSoftInput: view=" + name(view) + " flags=0x" + Integer.toHexString(flags)
			         + " -> false, onCreateInputConnection returned null");
			return false;
		}
		activeView = view;
		boolean shown = nativeShowSoftInput(im_context, view.widget, ic, outAttrs.inputType);
		debugLog("showSoftInput: view=" + name(view) + " flags=0x" + Integer.toHexString(flags)
		         + " inputType=0x" + Integer.toHexString(outAttrs.inputType) + " -> " + shown);
		return shown;
	}

	public boolean isFullscreenMode() { return false; }

	public boolean isActive(View view) {
		return activeView == view;
	}

	public boolean isAcceptingText() {
		return activeView != null;
	}

	public List<InputMethodInfo> getEnabledInputMethodList() {
		return input_method_list;
	}

	public List<InputMethodInfo> getInputMethodList() {
		return input_method_list;
	}

	public void restartInput(View view) {
		if (view == null)
			return;
		// AOSP restarts input for the view that is passed in: Flutter uses one View for
		// several Dart text fields, and Compose reports each newly focused text field by
		// calling restartInput with a fresh InputConnection. Restarting input must
		// therefore not be dropped just because this view was not seen by showSoftInput.
		EditorInfo outAttrs = new EditorInfo();
		InputConnection ic = view.onCreateInputConnection(outAttrs);
		if (ic != null) {
			activeView = view;
			nativeShowSoftInput(im_context, view.widget, ic, outAttrs.inputType);
			debugLog("restartInput: view=" + name(view) + " inputType=0x"
			         + Integer.toHexString(outAttrs.inputType));
		} else if (view == activeView) {
			nativeHideSoftInput(im_context);
			activeView = null;
			debugLog("restartInput: view=" + name(view) + " -> no InputConnection, soft input hidden");
		} else {
			debugLog("restartInput: view=" + name(view) + " -> no InputConnection, nothing to restart");
		}
	}

	public void updateSelection(View view, int selStart, int selEnd, int candidatesStart, int candidatesEnd) {}

	public InputMethodSubtype getCurrentInputMethodSubtype() {
		return new InputMethodSubtype();
	}

	private static native long nativeInit();
	private native boolean nativeShowSoftInput(long im_context, long widget, InputConnection ic, int inputType);
	private native void nativeHideSoftInput(long im_context);
}
