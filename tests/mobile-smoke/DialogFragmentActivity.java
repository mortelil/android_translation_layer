// SPDX-License-Identifier: GPL-3.0-only
import android.app.*;
import android.content.DialogInterface;
import android.os.*;

public class DialogFragmentActivity extends Activity {
    int creates, cancels, dismisses;
    DialogFragment fragment;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.media.session.MediaController controller = new android.media.session.MediaController(this, null);
        setMediaController(controller);
        check(getMediaController() == controller && getWindow().getMediaController() == controller, "controller association lost");
        android.view.Window other = new android.view.Window(this, this);
        check(other.getMediaController() == null, "controller leaked to other window");
        setMediaController(null);
        check(getMediaController() == null, "controller not cleared");
        fragment = new DialogFragment() {
            @Override public Dialog onCreateDialog(Bundle b) {
                creates++;
                return new AlertDialog.Builder(DialogFragmentActivity.this).setTitle("Lifecycle test")
                    .setMessage("Synthetic test, no account data").setPositiveButton("OK", null).create();
            }
            @Override public void onCancel(DialogInterface d) { cancels++; super.onCancel(d); }
            @Override public void onDismiss(DialogInterface d) { dismisses++; super.onDismiss(d); }
        };
        fragment.show(getFragmentManager(), "dialog");
        check(fragment.getDialog() == null, "show must enqueue, not create synchronously");
    }
    @Override public void onResume() {
        super.onResume();
        new Handler().postDelayed(new Runnable() { public void run() {
            try {
                check(creates == 1, "dialog must be created once");
                final Dialog dialog = fragment.getDialog();
                check(dialog != null && dialog.isShowing(), "started fragment must show real dialog");
                fragment.onStop(); check(!dialog.isShowing(), "stop hides dialog");
                fragment.onStart(); check(dialog.isShowing(), "start reuses hidden window");
                check(creates == 1, "restart recreated dialog");
                fragment.setCancelable(false);
                java.lang.reflect.Method userCancel = Dialog.class.getDeclaredMethod("cancelFromUser");
                userCancel.setAccessible(true); userCancel.invoke(dialog);
                check(dialog.isShowing() && cancels == 0, "noncancelable user close accepted");
                fragment.setCancelable(true);
                dialog.cancel(); dialog.cancel();
                new Handler().postDelayed(new Runnable() { public void run() {
                    try {
                        getFragmentManager().executePendingTransactions();
                        check(cancels == 1 && dismisses == 1, "duplicate/missing cancel-dismiss callbacks");
                        check(getFragmentManager().findFragmentByTag("dialog") == null, "dismissed fragment retained");
                        check(fragment.getDialog() == null, "destroy retained dialog");
                        DialogFragment pending = new DialogFragment() {
                            @Override public Dialog onCreateDialog(Bundle b) { throw new AssertionError("dismissed pending dialog created"); }
                        };
                        pending.show(getFragmentManager().beginTransaction(), "pending");
                        pending.dismiss();
                        getFragmentManager().executePendingTransactions();
                        check(getFragmentManager().findFragmentByTag("pending") == null, "dismiss before execution lost");
                        System.out.println("DIALOG_FRAGMENT_PASS"); System.exit(0);
                    } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
                }}, 100);
            } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
        }}, 100);
    }
}
