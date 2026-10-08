// SPDX-License-Identifier: GPL-3.0-only
package android.text;

/** Pango-backed layout which follows edits to a Spannable text buffer. */
public class DynamicLayout extends Layout {
	private final TextWatcher watcher = new TextWatcher() {
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			native_set_text(layout, getText().toString());
		}
		public void afterTextChanged(Editable text) {}
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
