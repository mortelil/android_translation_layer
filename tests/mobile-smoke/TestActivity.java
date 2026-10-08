// SPDX-License-Identifier: GPL-3.0-only
public class TestActivity extends android.app.Activity {
	@Override
	public void onCreate(android.os.Bundle state) {
		super.onCreate(state);
		try {
			TestBitmap.main(new String[0]);
			TestTLS.main(new String[0]);
			TestRange.main(new String[0]);
			TestViews.run(this);
			TestMedia.run(this);
			android.text.SpannableStringBuilder editable = new android.text.SpannableStringBuilder("first");
			android.text.TextPaint paint = new android.text.TextPaint();
			paint.setTextSize(16);
			android.text.DynamicLayout dynamic = new android.text.DynamicLayout(editable, paint, 500,
			                                                                    android.text.Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
			if (dynamic.getLineCount() != 1)
				throw new AssertionError("Initial line count");
			editable.append("\nsecond");
			if (dynamic.getLineCount() != 2 || dynamic.getLineStart(1) != 6)
				throw new AssertionError("Edited line layout");
			editable.clear();
			if (dynamic.getLineCount() != 1 || dynamic.getLineEnd(0) != 0)
				throw new AssertionError("Cleared layout");
			System.out.println("PASS: DynamicLayout follows append and clear with real Pango line positions");
			final android.text.SpannableStringBuilder input = new android.text.SpannableStringBuilder();
			android.text.Selection.setSelection(input, 0);
			android.view.inputmethod.BaseInputConnection ic = new android.view.inputmethod.BaseInputConnection((android.view.View)null, true) {
				@Override
				public android.text.Editable getEditable() { return input; }
			};
			ic.commitText("hello", 1);
			if (!input.toString().equals("hello") || android.text.Selection.getSelectionStart(input) != 5)
				throw new AssertionError("Commit text/cursor");
			ic.setSelection(1, 4);
			if (!ic.getSelectedText(0).toString().equals("ell"))
				throw new AssertionError("Selected text");
			ic.setComposingText("XY", 1);
			if (!input.toString().equals("hXYo") || ic.getComposingSpanStart(input) != 1 || ic.getComposingSpanEnd(input) != 3)
				throw new AssertionError("Composing text");
			ic.commitText("Z", 1);
			if (!input.toString().equals("hZo") || ic.getComposingSpanStart(input) != -1)
				throw new AssertionError("Composing replacement");
			if (!ic.getTextBeforeCursor(1, 0).toString().equals("Z") || !ic.getTextAfterCursor(1, 0).toString().equals("o"))
				throw new AssertionError("Surrounding text");
			ic.deleteSurroundingText(1, 1);
			if (!input.toString().equals("h"))
				throw new AssertionError("Surrounding deletion");
			System.out.println("PASS: InputConnection override, insertion, cursor, selection, composition and deletion");
			java.lang.reflect.Constructor<android.security.NetworkSecurityPolicy> constructor = android.security.NetworkSecurityPolicy.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			android.security.NetworkSecurityPolicy policy = constructor.newInstance();
			org.xmlpull.v1.XmlPullParser policyXml = android.util.Xml.newPullParser();
			policyXml.setInput(new java.io.StringReader("<network-security-config><base-config cleartextTrafficPermitted='true'/><domain-config cleartextTrafficPermitted='false'><domain includeSubdomains='true'>example.test</domain><domain-config cleartextTrafficPermitted='true'><domain>allowed.example.test</domain></domain-config></domain-config></network-security-config>"));
			java.lang.reflect.Method parsePolicy = android.security.NetworkSecurityPolicy.class.getDeclaredMethod("parse", org.xmlpull.v1.XmlPullParser.class);
			parsePolicy.setAccessible(true);
			parsePolicy.invoke(policy, policyXml);
			if (policy.isCleartextTrafficPermitted() || policy.isCleartextTrafficPermitted("sub.example.test") || !policy.isCleartextTrafficPermitted("allowed.example.test") || !policy.isCleartextTrafficPermitted("192.0.2.1") || !policy.isCleartextTrafficPermitted("otherexample.test"))
				throw new AssertionError("Cleartext domain policy");
			System.out.println("PASS: cleartext base policy, domain deny, subdomain inheritance and specific override");
			android.net.NetworkRequest.Builder builder = new android.net.NetworkRequest.Builder();
			android.net.NetworkRequest before = builder.build();
			android.net.NetworkRequest after = builder.removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
			if (!before.networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
			    || after.networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
				throw new AssertionError("Request copy");
			android.net.ConnectivityManager cm = (android.net.ConnectivityManager)getSystemService("connectivity");
			android.net.ConnectivityManager.NetworkCallback callback = new android.net.ConnectivityManager.NetworkCallback();
			cm.registerNetworkCallback(after, callback, new android.os.Handler(getMainLooper()));
			cm.unregisterNetworkCallback(callback);
			if (!getCodeCacheDir().isDirectory())
				throw new AssertionError("Code cache");
			System.out.println("PASS: network request immutability, callback lifecycle and code cache");
			System.out.println("ATL TEST APK PASSED");
			System.exit(0);
		} catch (Throwable e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
}
