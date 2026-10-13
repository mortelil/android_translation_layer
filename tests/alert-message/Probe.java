/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.alertmessage;

public class Probe extends android.app.Activity {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle("Visible message probe")
            .setMessage("BEFORE: this text must be replaced")
            .setPositiveButton("Verify", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                try {
                    android.widget.TextView message = (android.widget.TextView)((android.app.AlertDialog)d).findViewById(android.R.id.message);
                    check("AFTER: visible message updated".contentEquals(message.getText()), "updated text");
                    check(message.getWidth() > 0 && message.getHeight() > 0, "message allocated on screen");
                    getFilesDir().mkdirs();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "results.json"))) {
                        out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));
                    }
                    System.out.println("ALERT_MESSAGE PASS");
                } catch (Throwable t) { t.printStackTrace(); System.out.println("ALERT_MESSAGE FAIL " + t); }
                }
            }).create();
        dialog.show();
        final android.widget.TextView message = (android.widget.TextView)dialog.findViewById(android.R.id.message);
        check(message != null, "message view lookup");
        check(dialog.getWindow().findViewById(android.R.id.message) == message, "real Window hierarchy");
        check("BEFORE: this text must be replaced".contentEquals(message.getText()), "initial text");
        message.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        new android.os.Handler().postDelayed(new Runnable() { public void run() { message.setText("AFTER: visible message updated"); } }, 1000);
    }
}
