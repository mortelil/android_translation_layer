/* SPDX-License-Identifier: GPL-3.0-only */
package org.chromium.support_lib_boundary;
import java.lang.reflect.InvocationHandler;
import android.webkit.WebView;
import android.net.Uri;
/** Test-side protocol surface, separate from the framework class loader. */
public interface WebMessageListenerBoundaryInterface {
    void onPostMessage(WebView view, InvocationHandler message, Uri origin, boolean main, InvocationHandler reply);
}
