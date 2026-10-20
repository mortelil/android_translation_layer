/* SPDX-License-Identifier: GPL-3.0-only */
package org.chromium.support_lib_glue;

import android.webkit.WebView;
import java.lang.reflect.InvocationHandler;

/** AndroidX's public reflection entry point. Only implemented features are advertised.
 * Protocol: Chromium android_webview/support_library/boundary_interfaces (BSD-3-Clause).
 * This is an independent adapter; it does not load Chromium implementation classes. */
public final class SupportLibReflectionUtil {
    private SupportLibReflectionUtil() {}
    public static InvocationHandler createWebViewProviderFactory() {
        return new InvocationHandler() {
          public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "getSupportedFeatures": return new String[] {"WEB_MESSAGE_LISTENER"};
                case "createWebView": return ((WebView) args[0]).internalMessageProvider();
                default: throw new UnsupportedOperationException("WebView factory: " + method.getName());
            }
          }
        };
    }
}
