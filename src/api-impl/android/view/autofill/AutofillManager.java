package android.view.autofill;

import android.graphics.Rect;
import android.view.View;

public class AutofillManager {
	// ATL has no autofill provider or save session. Ending an absent session
	// must be harmless, and clients must be told the service is unavailable.
	public boolean isEnabled() { return false; }
	public boolean isAutofillSupported() { return false; }
	public void commit() {}
	public void cancel() {}

	public static abstract class AutofillCallback {}

	public interface AutofillClient {}

	public void registerCallback(AutofillCallback callback) {}

	public void unregisterCallback(AutofillCallback callback) {}

	public void notifyViewEntered(View view, int id, Rect bounds) {}

	public void notifyValueChanged(View view, int id, AutofillValue value) {}

	public void notifyViewExited(View view, int id) {}
}
