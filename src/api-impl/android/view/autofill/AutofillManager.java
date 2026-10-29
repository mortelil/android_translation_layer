package android.view.autofill;

import android.graphics.Rect;
import android.view.View;

public class AutofillManager {
	// ATL has no autofill provider or save session. Ending an absent session
	// must be harmless, and clients must be told the service is unavailable.
	public boolean isEnabled() { return false; }
	public boolean isAutofillSupported() { return false; }
	/*
	 * Returns true if the calling application provides an AutofillService that is enabled for
	 * the current user. AOSP answers `false` as soon as the autofill system service is absent
	 * (frameworks/base/core/java/android/view/autofill/AutofillManager.java:2426-2427:
	 * `if (mService == null) return false;`), and that service is exactly
	 * `ServiceManager.getService(Context.AUTOFILL_SERVICE)` unwrapped into the manager
	 * (core/java/android/app/SystemServiceRegistry.java:1445-1452 -> the constructor at
	 * AutofillManager.java:972-977). ATL registers no such service, so the null-service early
	 * return is the entire documented behaviour here; no remote call and no fabricated result.
	 */
	public boolean hasEnabledAutofillServices() { return false; }
	// No activity autofill client/provider exists; the API specifies null here.
	public AutofillId getNextAutofillId() { return null; }
	public void commit() {}
	public void cancel() {}

	public static abstract class AutofillCallback {}

	public interface AutofillClient {}

	public void registerCallback(AutofillCallback callback) {}

	public void unregisterCallback(AutofillCallback callback) {}

	public void notifyViewEntered(View view, int id, Rect bounds) {}

	// AOSP android-15.0.0_r1 notifyValueChanged(View) returns before touching
	// the view when hasAutofillFeature() is false. ATL has no autofill service.
	public void notifyValueChanged(View view) {}

	public void notifyValueChanged(View view, int id, AutofillValue value) {}

	public void notifyViewExited(View view, int id) {}
}
