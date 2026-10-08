// SPDX-License-Identifier: GPL-3.0-only
package android.view.textservice;

public final class SuggestionsInfo {
	public static final int RESULT_ATTR_IN_THE_DICTIONARY = 1;
	public static final int RESULT_ATTR_LOOKS_LIKE_TYPO = 2;
	public static final int RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS = 4;
	private final int attributes;
	private final String[] suggestions;
	private int cookie, sequence;
	public SuggestionsInfo(int attributes, String[] suggestions) { this(attributes, suggestions, 0, 0); }
	public SuggestionsInfo(int attributes, String[] suggestions, int cookie, int sequence) {
		this.attributes = attributes;
		this.suggestions = suggestions == null ? null : suggestions.clone();
		setCookieAndSequence(cookie, sequence);
	}
	public void setCookieAndSequence(int cookie, int sequence) {
		this.cookie = cookie;
		this.sequence = sequence;
	}
	public int getCookie() { return cookie; }
	public int getSequence() { return sequence; }
	public int getSuggestionsAttributes() { return attributes; }
	public int getSuggestionsCount() { return suggestions == null ? -1 : suggestions.length; }
	public String getSuggestionAt(int index) { return suggestions[index]; }
}
