package android.view.accessibility;

import android.os.Handler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AccessibilityManager {

	/**
	 * Listener for the accessibility state changing. Register it with the system by calling
	 * {@link #addAccessibilityStateChangeListener}.
	 */
	public interface AccessibilityStateChangeListener {

		/**
		 * Called when the accessibility enabled state changes.
		 *
		 * @param enabled Whether accessibility is enabled.
		 */
		void onAccessibilityStateChanged(boolean enabled);
	}

	/**
	 * Listener for the system touch exploration state. Register it with the system by calling
	 * {@link #addTouchExplorationStateChangeListener}.
	 */
	public interface TouchExplorationStateChangeListener {

		/**
		 * Called when the touch exploration enabled state changes.
		 *
		 * @param enabled Whether touch exploration is enabled.
		 */
		void onTouchExplorationStateChanged(boolean enabled);
	}

	private final Map<AccessibilityStateChangeListener, Handler> mAccessibilityStateChangeListeners
			= new LinkedHashMap<>();
	private final Map<TouchExplorationStateChangeListener, Handler> mTouchExplorationStateChangeListeners
			= new LinkedHashMap<>();

	public boolean isTouchExplorationEnabled() { return false; }

	public boolean isEnabled() { return false; }

	public List getEnabledAccessibilityServiceList(int feedbackTypeFlags) {
		return new ArrayList<>();
	}

	public boolean addAccessibilityStateChangeListener(AccessibilityStateChangeListener listener) {
		addAccessibilityStateChangeListener(listener, null);
		return true;
	}

	public void addAccessibilityStateChangeListener(AccessibilityStateChangeListener listener, Handler handler) {
		mAccessibilityStateChangeListeners.put(listener, handler);
	}

	public boolean addTouchExplorationStateChangeListener(TouchExplorationStateChangeListener listener) {
		addTouchExplorationStateChangeListener(listener, null);
		return true;
	}

	public void addTouchExplorationStateChangeListener(TouchExplorationStateChangeListener listener, Handler handler) {
		mTouchExplorationStateChangeListeners.put(listener, handler);
	}

	public boolean removeAccessibilityStateChangeListener(AccessibilityStateChangeListener listener) {
		boolean registered = mAccessibilityStateChangeListeners.containsKey(listener);
		mAccessibilityStateChangeListeners.remove(listener);
		return registered;
	}

	public boolean removeTouchExplorationStateChangeListener(TouchExplorationStateChangeListener listener) {
		boolean registered = mTouchExplorationStateChangeListeners.containsKey(listener);
		mTouchExplorationStateChangeListeners.remove(listener);
		return registered;
	}
}
