// SPDX-License-Identifier: GPL-3.0-only
public class TestActivity extends android.app.Activity {
	@Override
	public void onCreate(android.os.Bundle state) {
		super.onCreate(state);
		try {
			if (android.os.SystemProperties.getInt("ro.build.version.sdk", -1) != android.os.Build.VERSION.SDK_INT)
				throw new AssertionError("Java SDK property differs from Build.VERSION");
			System.out.println("PASS: Java SDK property matches the selected API level");
			if (getResources().getClassLoader() != getClassLoader() ||
			    getResources().getClassLoader().loadClass("TestActivity") != TestActivity.class)
				throw new AssertionError("Resources must retain the app class loader");
			System.out.println("PASS: Resources resolves app classes through the owning class loader");
			TestBitmap.main(new String[0]);
			TestDynamicLayout.run();
			TestQwerty.run();
			TestEditText.run(this);
			android.app.job.JobInfo.TriggerContentUri trigger = new android.app.job.JobInfo.TriggerContentUri(android.net.Uri.parse("content://atl.test/items"), 1);
			android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(123456, new android.content.ComponentName("atl.test", "atl.test.Job"))
			    .setTriggerContentUpdateDelay(123).setTriggerContentMaxDelay(456).addTriggerContentUri(trigger).build();
			if (job.getTriggerContentUpdateDelay() != 123 || job.getTriggerContentMaxDelay() != 456 || !job.getTriggerContentUris()[0].equals(trigger))
				throw new AssertionError("JobInfo trigger metadata");
			android.app.job.JobScheduler scheduler = new android.app.job.JobScheduler(this);
			if (scheduler.schedule(job) != 0) throw new AssertionError("Unsupported content triggers must not be scheduled successfully");
			for (android.app.job.JobInfo pending : scheduler.getAllPendingJobs())
				if (pending.getId() == 123456) throw new AssertionError("Rejected job was queued");
			System.out.println("PASS: JobInfo content-trigger metadata and honest scheduler rejection");
			TestTextSpans.run();
			TestCookies.run();
			if (android.nfc.NfcAdapter.getDefaultAdapter(this) != null || android.nfc.tech.Ndef.get(null) != null)
				throw new AssertionError("NFC must remain unavailable without a backend");
			if (!android.os.Parcelable.class.isAssignableFrom(Class.forName("android.nfc.Tag")) ||
			    !android.nfc.tech.TagTechnology.class.isAssignableFrom(Class.forName("android.nfc.tech.Ndef")))
				throw new AssertionError("Optional NFC type hierarchy");
			System.out.println("PASS: optional NFC types load and unsupported hardware stays unavailable");
			for (String key : new String[] {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"}) {
				float scale = Float.parseFloat(android.provider.Settings.Global.getString(null, key));
				if (scale != android.provider.Settings.Global.getFloat(null, key, -1))
					throw new AssertionError("Inconsistent animation setting " + key);
			}
			if (android.provider.Settings.Global.getString(null, "atl.nonexistent.setting") != null)
				throw new AssertionError("Missing settings must not return fabricated strings");
			System.out.println("PASS: animation settings use consistent numeric values and unknown keys return null");
			android.hardware.HardwareBuffer hardware = android.hardware.HardwareBuffer.create(16, 1, 0x21, 1, 0x33);
			if (hardware.getWidth() != 16 || hardware.getUsage() != 0x33) throw new AssertionError("HardwareBuffer JNI properties");
			hardware.close(); hardware.close();
			if (!hardware.isClosed()) throw new AssertionError("HardwareBuffer close");
			try { hardware.getWidth(); throw new AssertionError("Closed buffer access"); }
			catch (IllegalStateException expected) {}
			System.out.println("PASS: HardwareBuffer Java/native creation, properties and close");
			TestTLS.main(new String[0]);
			TestRange.main(new String[0]);
			TestViews.run(this);
			TestCanvasScope.run(this);
			TestMedia.run(this);
			TestDocuments.run(this);
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
			android.net.NetworkInfo online = new android.net.NetworkInfo(true);
			android.net.NetworkInfo offline = new android.net.NetworkInfo(false);
			if (online.getDetailedState() != android.net.NetworkInfo.DetailedState.CONNECTED
			    || !online.isConnected() || !online.isAvailable()
			    || offline.getDetailedState() != android.net.NetworkInfo.DetailedState.DISCONNECTED
			    || offline.isConnected() || offline.isAvailable())
				throw new AssertionError("Network snapshot state consistency");
			System.out.println("PASS: connected and disconnected NetworkInfo detailed states");
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
