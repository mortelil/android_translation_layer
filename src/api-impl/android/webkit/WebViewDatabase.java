/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/** Public API from AOSP; ATL provides an app-private HTTP authentication store.
 * This does not automatically supply credentials to WebKit or enable autofill.
 * Concurrent processes writing the same store are not supported.
 */
public abstract class WebViewDatabase {
	protected static final String LOGTAG = "webviewdatabase";
	@Deprecated public WebViewDatabase() {}
	private static final Map<SharedPreferences, WebViewDatabase> instances = new HashMap<>();
	public static synchronized WebViewDatabase getInstance(Context context) {
		SharedPreferences prefs = context.getSharedPreferences("webview_database", Context.MODE_PRIVATE);
		WebViewDatabase result = instances.get(prefs);
		if (result == null) { result = new Store(prefs); instances.put(prefs, result); }
		return result;
	}
	@Deprecated public abstract boolean hasUsernamePassword();
	@Deprecated public abstract void clearUsernamePassword();
	public abstract boolean hasHttpAuthUsernamePassword();
	public abstract void clearHttpAuthUsernamePassword();
	public abstract void setHttpAuthUsernamePassword(String host, String realm, String username, String password);
	public abstract String[] getHttpAuthUsernamePassword(String host, String realm);
	@Deprecated public abstract boolean hasFormData();
	@Deprecated public abstract void clearFormData();

	private static final class Store extends WebViewDatabase {
		private final SharedPreferences prefs;
		Store(SharedPreferences prefs) { this.prefs = prefs; }
		// Encode UTF-16 units, including NUL and unpaired surrogates, into XML-safe
		// keys without conflating host/realm boundaries or replacement characters.
		private static String encode(String value) {
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < value.length(); i++) {
				String hex = Integer.toHexString(value.charAt(i));
				for (int pad = hex.length(); pad < 4; pad++) result.append('0');
				result.append(hex);
			}
			return result.toString();
		}
		private static String key(String host, String realm) { return "http:" + encode(host) + ":" + encode(realm); }
		private static void save(SharedPreferences.Editor editor) {
			if (!editor.commit()) throw new IllegalStateException("Cannot persist WebView HTTP authentication data");
		}
		public synchronized boolean hasHttpAuthUsernamePassword() {
			for (String key : prefs.getAll().keySet()) if (key.startsWith("http:") && key.endsWith(":present")) return true;
			return false;
		}
		public synchronized void clearHttpAuthUsernamePassword() {
			SharedPreferences.Editor editor = prefs.edit();
			for (String key : prefs.getAll().keySet()) if (key.startsWith("http:")) editor.remove(key);
			save(editor);
		}
		public synchronized void setHttpAuthUsernamePassword(String host, String realm, String username, String password) {
			if (host == null || realm == null) return;
			String key = key(host, realm);
			save(prefs.edit().putBoolean(key + ":present", true).putString(key + ":user", username).putString(key + ":pass", password));
		}
		public synchronized String[] getHttpAuthUsernamePassword(String host, String realm) {
			if (host == null || realm == null) return null;
			String key = key(host, realm);
			if (!prefs.getBoolean(key + ":present", false)) return null;
			return new String[] {prefs.getString(key + ":user", null), prefs.getString(key + ":pass", null)};
		}
		// Form passwords and autofill data are separate from HTTP authentication.
		public boolean hasUsernamePassword() { return false; }
		public void clearUsernamePassword() {}
		public boolean hasFormData() { return false; }
		public void clearFormData() {}
	}
}
