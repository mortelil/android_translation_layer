// SPDX-License-Identifier: GPL-3.0-only
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

/** Verify that AlertDialog button callbacks are queued and dismissal follows them. */
public final class TestAlertDialogButton {
	private TestAlertDialogButton() {}

	public static void run(Activity activity, Runnable continuation) {
		final int[] callbacks = {0};
		final int[] dismissals = {0};
		final AlertDialog dialog = new AlertDialog.Builder(activity)
			.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface callbackDialog, int which) {
					if (Looper.myLooper() != Looper.getMainLooper())
						throw new AssertionError("AlertDialog button callback must run on the main looper");
					if (which != DialogInterface.BUTTON_NEGATIVE)
						throw new AssertionError("Negative button identifier was not preserved");
					if (!((AlertDialog)callbackDialog).isShowing())
						throw new AssertionError("Dialog must remain visible during its button callback");
					callbacks[0]++;
				}
			}).create();
		dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
			@Override public void onDismiss(DialogInterface ignored) { dismissals[0]++; }
		});
		dialog.show();
		if (!dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick())
			throw new AssertionError("Configured AlertDialog button should handle performClick");
		if (callbacks[0] != 0 || dismissals[0] != 0)
			throw new AssertionError("AlertDialog callback and dismissal must be queued, not invoked inline");

		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override public void run() {
				if (callbacks[0] != 1 || dismissals[0] != 1 || dialog.isShowing())
					throw new AssertionError("AlertDialog button callback/dismissal order or count is wrong");
				System.out.println("PASS: AlertDialog button callback is deferred and dismisses once");
				continuation.run();
			}
		}, 100);
	}
}
