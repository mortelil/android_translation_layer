/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.editableappend;
import android.text.*;
public class Probe extends android.app.Activity {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        Editable text = Editable.Factory.getInstance().newEditable("A");
        check(text.append("BC") == text, "append returns same Editable");
        check(text.append("0123", 1, 3) == text, "range append returns same Editable");
        check(text.append('!') == text, "char append returns same Editable");
        check("ABC12!".contentEquals(text), "append contents");
        SpannableString source = new SpannableString("xy"); Object span = new Object();
        source.setSpan(span, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(source);
        check(text.getSpanStart(span) == 6 && text.getSpanEnd(span) == 8, "span copied with offset");
        try { ((Appendable)text).append('?'); } catch (java.io.IOException e) { throw new AssertionError(e); }
        check("ABC12!xy?".contentEquals(text), "Appendable bridge");
        try {
            getFilesDir().mkdirs();
            java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "results.json"));
            out.write("{\"status\":\"PASS\"}".getBytes("UTF-8")); out.close();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        System.out.println("EDITABLE_APPEND PASS");
        finish();
    }
}
