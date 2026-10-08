// SPDX-License-Identifier: GPL-3.0-only
package android.security;

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.XmlResourceParser;
import java.util.ArrayList;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

/** Cleartext policy from the application's manifest and network security XML.
 * Certificate validation remains the responsibility of the TLS provider. */
public final class NetworkSecurityPolicy {
	private static NetworkSecurityPolicy instance;
	private boolean basePermitted;
	private final ArrayList<Domain> domains = new ArrayList<>();
	private static class Config {
		Config parent;
		Boolean permitted;
		boolean permits(boolean base) {
			return permitted != null ? permitted : parent != null ? parent.permits(base)
			                                                      : base;
		}
	}
	private static class Domain {
		String name;
		boolean includeSubdomains;
		Config config;
	}
	private NetworkSecurityPolicy() {}

	public static synchronized NetworkSecurityPolicy getInstance() {
		if (instance == null) {
			Context context = ATLLoadedApp.getPrimaryApplication().getApplication();
			ApplicationInfo info = context.getApplicationInfo();
			NetworkSecurityPolicy policy = new NetworkSecurityPolicy();
			policy.basePermitted = (info.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0;
			if (info.networkSecurityConfigRes != 0) {
				// XML policy supersedes the manifest cleartext flag.
				policy.basePermitted = info.targetSdkVersion < 28;
				try (XmlResourceParser parser = context.getResources().getXml(info.networkSecurityConfigRes)) {
					policy.parse(parser);
				} catch (Exception e) {
					throw new IllegalStateException("Cannot read network security configuration", e);
				}
			}
			instance = policy;
		}
		return instance;
	}

	private void parse(XmlPullParser parser) throws Exception {
		Config current = null;
		for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
			String name = parser.getName();
			if (event == XmlPullParser.START_TAG) {
				if ("base-config".equals(name)) {
					String value = parser.getAttributeValue(null, "cleartextTrafficPermitted");
					if (value != null)
						basePermitted = parseBoolean(value);
				} else if ("domain-config".equals(name)) {
					Config child = new Config();
					child.parent = current;
					String value = parser.getAttributeValue(null, "cleartextTrafficPermitted");
					if (value != null)
						child.permitted = parseBoolean(value);
					current = child;
				} else if ("domain".equals(name) && current != null) {
					Domain domain = new Domain();
					domain.includeSubdomains = "true".equals(parser.getAttributeValue(null, "includeSubdomains"));
					domain.name = parser.nextText().trim().toLowerCase(Locale.US);
					if (domain.name.isEmpty())
						throw new IllegalArgumentException("Empty policy domain");
					domain.config = current;
					domains.add(domain);
				}
			} else if (event == XmlPullParser.END_TAG && "domain-config".equals(name)) {
				current = current.parent;
			}
		}
	}

	private static boolean parseBoolean(String value) {
		if ("true".equals(value))
			return true;
		if ("false".equals(value))
			return false;
		throw new IllegalArgumentException("Invalid cleartext policy value: " + value);
	}

	public boolean isCleartextTrafficPermitted() {
		if (!basePermitted)
			return false;
		for (Domain domain : domains)
			if (!domain.config.permits(basePermitted))
				return false;
		return true;
	}

	public boolean isCleartextTrafficPermitted(String hostname) {
		if (hostname == null)
			throw new NullPointerException("hostname");
		hostname = hostname.toLowerCase(Locale.US);
		if (hostname.endsWith("."))
			hostname = hostname.substring(0, hostname.length() - 1);
		Domain match = null;
		for (Domain domain : domains) {
			if ((hostname.equals(domain.name) || domain.includeSubdomains && hostname.endsWith("." + domain.name))
			    && (match == null || domain.name.length() > match.name.length()))
				match = domain;
		}
		return match == null ? basePermitted : match.config.permits(basePermitted);
	}
}
