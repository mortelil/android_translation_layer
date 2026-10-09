// SPDX-License-Identifier: GPL-3.0-only
package android.text;

/** Pango-backed layout which follows edits to a Spannable text buffer. */
public class DynamicLayout extends Layout {
	private void refreshText() {
		native_set_text(layout, getText().toString());
		native_set_text_attributes(layout, android.atl.TextSpanAttributes.encode(getText(), getPaint().density));
	}
	private final ChangeWatcher watcher = new ChangeWatcher();
	private class ChangeWatcher implements TextWatcher, SpanWatcher, NoCopySpan {
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			refreshText();
		}
		public void afterTextChanged(Editable text) {}
		public void onSpanAdded(Spannable text, Object what, int start, int end) { refreshText(); }
		public void onSpanRemoved(Spannable text, Object what, int start, int end) { refreshText(); }
		public void onSpanChanged(Spannable text, Object what, int oldStart, int oldEnd, int start, int end) { refreshText(); }
	};

	public DynamicLayout(CharSequence base, TextPaint paint, int width, Alignment align,
	                     float spacingMult, float spacingAdd, boolean includePad) {
		this(base, base, paint, width, align, spacingMult, spacingAdd, includePad);
	}

	public DynamicLayout(CharSequence base, CharSequence display, TextPaint paint, int width,
	                     Alignment align, float spacingMult, float spacingAdd, boolean includePad) {
		super(display, paint, width, align, spacingMult, spacingAdd);
		if (base instanceof Spannable)
			((Spannable)base).setSpan(watcher, 0, base.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
	}
}
