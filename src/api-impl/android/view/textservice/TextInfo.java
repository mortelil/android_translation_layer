// SPDX-License-Identifier: GPL-3.0-only
package android.view.textservice;

public final class TextInfo {
	private final String text;
	private final int cookie, sequence;
	public TextInfo(String text) { this(text, 0, 0); }
	public TextInfo(String text, int cookie, int sequence) {
		if (text == null || text.isEmpty())
			throw new IllegalArgumentException("Empty text");
		this.text = text;
		this.cookie = cookie;
		this.sequence = sequence;
	}
	public String getText() { return text; }
	public int getCookie() { return cookie; }
	public int getSequence() { return sequence; }
}
