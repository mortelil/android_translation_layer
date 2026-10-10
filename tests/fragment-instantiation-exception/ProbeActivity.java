// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.fragmentexception;

import android.app.Fragment;
import android.util.AndroidRuntimeException;

public class ProbeActivity extends android.app.Activity {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            check(Fragment.InstantiationException.class.getSuperclass() == AndroidRuntimeException.class,
                "direct superclass must be AndroidRuntimeException");
            Exception cause = new Exception("original cause");
            Fragment.InstantiationException error = new Fragment.InstantiationException("fragment failure", cause);
            check("fragment failure".equals(error.getMessage()), "message");
            check(error.getCause() == cause, "cause identity");
            try {
                throw error;
            } catch (AndroidRuntimeException caught) {
                check(caught == error, "framework exception catch");
            }
            Fragment.InstantiationException empty = new Fragment.InstantiationException(null, null);
            check(empty.getMessage() == null && empty.getCause() == null, "null message/cause");
            check(Fragment.InstantiationException.class.getConstructor(String.class, Exception.class) != null,
                "public constructor signature");
            System.out.println("FRAGMENT_EXCEPTION_PASS superclass,message,cause,catch,nulls,constructor");
        } catch (Throwable error) {
            error.printStackTrace();
            System.out.println("FRAGMENT_EXCEPTION_FAIL " + error);
        }
        finish();
    }
}
