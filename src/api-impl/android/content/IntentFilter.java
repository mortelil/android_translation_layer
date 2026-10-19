/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.net.Uri;
import android.os.PatternMatcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Intent matching adapted from AOSP frameworks/base
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, IntentFilter.java.
 * ATL stores eager lists rather than nullable AOSP collections.
 */
public class IntentFilter {

	public static final int MATCH_CATEGORY_EMPTY = 0x0100000;
	public static final int MATCH_CATEGORY_SCHEME = 0x0200000;
	public static final int MATCH_CATEGORY_HOST = 0x0300000;
	public static final int MATCH_CATEGORY_PORT = 0x0400000;
	public static final int MATCH_CATEGORY_PATH = 0x0500000;
	public static final int MATCH_CATEGORY_SCHEME_SPECIFIC_PART = 0x0580000;
	public static final int MATCH_CATEGORY_TYPE = 0x0600000;

	public static final int NO_MATCH_TYPE = -1;
	public static final int NO_MATCH_DATA = -2;
	public static final int NO_MATCH_ACTION = -3;
	public static final int NO_MATCH_CATEGORY = -4;

	public static final int MATCH_ADJUSTMENT_NORMAL = 0x8000;

	private List<String> actions = new ArrayList<>();
	/* Ordered like AOSP's ArrayList: getCategory(index) must be stable. */
	private List<String> categories = new ArrayList<>();
	private List<String> dataSchemes = new ArrayList<>();
	private List<String> dataTypes = new ArrayList<>();
	private List<AuthorityEntry> dataAuthorities = new ArrayList<>();
	private List<PatternMatcher> dataPaths = new ArrayList<>();
	private List<PatternMatcher> dataSchemeSpecificParts = new ArrayList<>();
	private int priority;

	public static class MalformedMimeTypeException extends android.util.AndroidException {
		public MalformedMimeTypeException() {}
		public MalformedMimeTypeException(String type) { super(type); }
	}

	public IntentFilter() {}
	public IntentFilter(String action) {
		addAction(action);
	}

	public IntentFilter(String action, String type) throws MalformedMimeTypeException {
		addAction(action); addDataType(type);
	}
	public IntentFilter(IntentFilter other) {
		actions.addAll(other.actions); categories.addAll(other.categories);
		dataSchemes.addAll(other.dataSchemes); dataTypes.addAll(other.dataTypes);
		dataAuthorities.addAll(other.dataAuthorities); dataPaths.addAll(other.dataPaths);
		dataSchemeSpecificParts.addAll(other.dataSchemeSpecificParts);
		priority = other.priority;
	}

	public void addAction(String action) {
		if (!actions.contains(action)) actions.add(action.intern());
	}
	public int countActions() {
		return actions.size();
	}

	public final boolean matchAction(String action) {
		return actions.contains(action);
	}

	public void addCategory(String category) {
		if (!categories.contains(category)) categories.add(category);
	}
	public int countCategories() {
		return categories.size();
	}

	public final boolean hasCategory(String category) {
		return categories.contains(category);
	}

	public String getAction(int index) {
		return actions.get(index);
	}

	public String getCategory(int index) {
		return categories.get(index);
	}

	public void setPriority(int priority) { this.priority = priority; }
	public int getPriority() { return priority; }

	public void addDataScheme(String dataScheme) {
		if (!dataSchemes.contains(dataScheme)) dataSchemes.add(dataScheme);
	}

	public boolean hasDataScheme(String dataScheme) {
		return dataSchemes.contains(dataScheme);
	}

	public int countDataSchemes() {
		return dataSchemes.size();
	}

	public String getDataScheme(int index) {
		return dataSchemes.get(index);
	}

	public Iterator<String> schemesIterator() {
		return dataSchemes.isEmpty() ? null : dataSchemes.iterator();
	}

	public final void addDataType(String type) throws MalformedMimeTypeException {
		final int slashpos = type.indexOf('/');
		final int typelen = type.length();
		if (slashpos > 0 && typelen >= slashpos + 2) {
			if (typelen == slashpos + 2 && type.charAt(slashpos + 1) == '*') {
				String str = type.substring(0, slashpos);
				if (!dataTypes.contains(str)) dataTypes.add(str.intern());
			} else {
				if (!dataTypes.contains(type)) dataTypes.add(type.intern());
			}
			return;
		}
		throw new MalformedMimeTypeException(type);
	}

	public final boolean hasDataType(String type) {
		return dataTypes != null && findMimeType(type);
	}

	public final int countDataTypes() {
		return dataTypes.size();
	}

	public final String getDataType(int index) {
		return dataTypes.get(index);
	}

	public final Iterator<String> typesIterator() {
		return dataTypes.isEmpty() ? null : dataTypes.iterator();
	}

	public final void addDataAuthority(String host, String port) {
		dataAuthorities.add(new AuthorityEntry(host, port));
	}

	public final int countDataAuthorities() {
		return dataAuthorities.size();
	}

	public final AuthorityEntry getDataAuthority(int index) {
		return dataAuthorities.get(index);
	}

	public final Iterator<AuthorityEntry> authoritiesIterator() {
		return dataAuthorities.isEmpty() ? null : dataAuthorities.iterator();
	}

	public final void addDataPath(String path, int type) {
		dataPaths.add(new PatternMatcher(path, type));
	}

	public final int countDataPaths() {
		return dataPaths.size();
	}

	public final PatternMatcher getDataPath(int index) {
		return dataPaths.get(index);
	}

	public final Iterator<PatternMatcher> pathsIterator() {
		return dataPaths.isEmpty() ? null : dataPaths.iterator();
	}

	public final void addDataSchemeSpecificPart(String ssp, int type) {
		dataSchemeSpecificParts.add(new PatternMatcher(ssp, type));
	}
	public int countDataSchemeSpecificParts() { return dataSchemeSpecificParts.size(); }
	public PatternMatcher getDataSchemeSpecificPart(int index) {
		return dataSchemeSpecificParts.get(index);
	}
	public boolean hasDataSchemeSpecificPart(String ssp) {
		for (PatternMatcher part : dataSchemeSpecificParts) if (part.match(ssp)) return true;
		return false;
	}
	public Iterator<PatternMatcher> schemeSpecificPartsIterator() {
		return dataSchemeSpecificParts.isEmpty() ? null : dataSchemeSpecificParts.iterator();
	}

	public final Iterator<String> actionsIterator() {
		return actions.iterator();
	}

	public boolean hasAction(String action) {
		return actions.contains(action);
	}

	public int match(String action, String type, String scheme, Uri data, Set<String> categories, String logTag) {
		if (action != null && !matchAction(action)) {
			return NO_MATCH_ACTION;
		}
		int dataMatch = matchData(type, scheme, data);
		if (dataMatch < 0) {
			return dataMatch;
		}
		String categoryMismatch = matchCategories(categories);
		if (categoryMismatch != null) {
			return NO_MATCH_CATEGORY;
		}
		return dataMatch;
	}

	public final int match(Intent intent, String logTag) {
		return match(intent.getAction(), intent.getType(), intent.getScheme(),
		             intent.getData(), intent.getCategories(), logTag);
	}

	public final String matchCategories(Set<String> categories) {
		if (categories == null) {
			return null;
		}
		Iterator<String> it = categories.iterator();
		if (this.categories.isEmpty()) {
			return it.hasNext() ? it.next() : null;
		}
		while (it.hasNext()) {
			final String category = it.next();
			if (!hasCategory(category)) {
				return category;
			}
		}
		return null;
	}

	public final int matchData(String type, String scheme, Uri data) {
		final List<String> types = dataTypes;
		final List<String> schemes = dataSchemes;

		int match = MATCH_CATEGORY_EMPTY;

		if (types.isEmpty() && schemes.isEmpty()) {
			return ((type == null && data == null)
			    ? (MATCH_CATEGORY_EMPTY + MATCH_ADJUSTMENT_NORMAL) : NO_MATCH_DATA);
		}

		if (!schemes.isEmpty()) {
			if (!schemes.contains(scheme != null ? scheme : "")) {
				return NO_MATCH_DATA;
			}
			match = MATCH_CATEGORY_SCHEME;

			if (!dataSchemeSpecificParts.isEmpty() && data != null) {
				match = hasDataSchemeSpecificPart(data.getSchemeSpecificPart())
					? MATCH_CATEGORY_SCHEME_SPECIFIC_PART : NO_MATCH_DATA;
			}
			if (match != MATCH_CATEGORY_SCHEME_SPECIFIC_PART && !dataAuthorities.isEmpty()) {
				int authMatch = matchDataAuthority(data);
				if (authMatch < 0) return NO_MATCH_DATA;
				if (dataPaths.isEmpty()) match = authMatch;
				else if (hasDataPath(data.getPath())) match = MATCH_CATEGORY_PATH;
				else return NO_MATCH_DATA;
			}
			if (match == NO_MATCH_DATA) return NO_MATCH_DATA;
		} else {
			/* AOSP's content:/file: convenience (IntentFilter.java:1789-1807): a
			 * filter with types but no schemes matches a content:/file: URI, so a
			 * provider can be found by MIME type without declaring the scheme. */
			if (scheme != null && !"".equals(scheme)
			    && !"content".equals(scheme) && !"file".equals(scheme)) {
				return NO_MATCH_DATA;
			}
		}

		if (!types.isEmpty()) {
			if (findMimeType(type)) {
				match = MATCH_CATEGORY_TYPE;
			} else {
				return NO_MATCH_TYPE;
			}
		} else {
			// No MIME types declared: only an intent without one can match.
			if (type != null) {
				return NO_MATCH_TYPE;
			}
		}

		return match + MATCH_ADJUSTMENT_NORMAL;
	}

	public final int matchDataAuthority(Uri data) {
		if (dataAuthorities.isEmpty() || data == null) {
			return NO_MATCH_DATA;
		}
		for (int i = 0; i < dataAuthorities.size(); i++) {
			final int match = dataAuthorities.get(i).match(data);
			if (match >= 0) {
				return match;
			}
		}
		return NO_MATCH_DATA;
	}

	public final boolean hasDataPath(String path) {
		if (path == null) {
			return false;
		}
		for (int i = 0; i < dataPaths.size(); i++) {
			if (dataPaths.get(i).match(path)) {
				return true;
			}
		}
		return false;
	}

	private boolean findMimeType(String type) {
		if (type == null) {
			return false;
		}
		if (dataTypes.contains(type)) {
			return true;
		}
		// An intent asking for every type matches every filter with types.
		if (type.equals("*/*")) {
			return !dataTypes.isEmpty();
		}
		if (dataTypes.contains("*")) return true;
		// A filter declaring "base" (from "base/*") accepts any "base/subtype".
		final int slashpos = type.indexOf('/');
		if (slashpos > 0) {
			final String base = type.substring(0, slashpos);
			if (dataTypes.contains(base)) {
				return true;
			}
			if (type.length() == slashpos + 2 && type.charAt(slashpos + 1) == '*') {
				for (int i = 0; i < dataTypes.size(); i++) {
					if (type.regionMatches(0, dataTypes.get(i), 0, slashpos + 1)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static final class AuthorityEntry {
		private final String mOrigHost;
		private final String mHost;
		private final boolean mWild;
		private final int mPort;

		public AuthorityEntry(String host, String port) {
			mOrigHost = host;
			if (host.length() > 0 && host.charAt(0) == '*') {
				mWild = true;
				mHost = host.substring(1).intern();
			} else {
				mWild = false;
				mHost = host;
			}
			mPort = port != null ? Integer.parseInt(port) : -1;
		}

		public String getHost() {
			return mOrigHost;
		}

		public int getPort() {
			return mPort;
		}

		public int match(Uri data) {
			return match(data, false);
		}

        public int match(Uri data, boolean wildcardSupported) {
            String host = data.getHost();
            if (host == null) {
                if (wildcardSupported && mWild && mHost.isEmpty()) {
                    // special case, if no host is provided, but the Authority is wildcard, match
                    return MATCH_CATEGORY_HOST;
                } else {
                    return NO_MATCH_DATA;
                }
            }
            if (!wildcardSupported || !"*".equals(host)) {
                if (mWild) {
                    if (host.length() < mHost.length()) {
                        return NO_MATCH_DATA;
                    }
                    host = host.substring(host.length() - mHost.length());
                }
                if (host.compareToIgnoreCase(mHost) != 0) {
                    return NO_MATCH_DATA;
                }
            }
            // if we're dealing with wildcard support, we ignore ports
            if (!wildcardSupported && mPort >= 0) {
                if (mPort != data.getPort()) {
                    return NO_MATCH_DATA;
                }
                return MATCH_CATEGORY_PORT;
            }
            return MATCH_CATEGORY_HOST;
        }

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("AuthorityEntry{");
			sb.append(mHost);
			if (mPort >= 0) sb.append(':').append(mPort);
			sb.append('}');
			return sb.toString();
		}
	}
}
