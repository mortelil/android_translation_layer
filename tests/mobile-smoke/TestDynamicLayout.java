// SPDX-License-Identifier: GPL-3.0-only
import android.text.*;

public class TestDynamicLayout {
	public static void run() {
		final SpannableStringBuilder base = new SpannableStringBuilder("secret");
		// A live display sequence masks every character, including newlines.
		CharSequence masked = new CharSequence() {
			public int length() { return base.length(); }
			public char charAt(int index) {
				base.charAt(index); // Keep the source's bounds checks.
				return '*';
			}
			public CharSequence subSequence(int start, int end) {
				return toString().substring(start, end);
			}
			public String toString() {
				StringBuilder result = new StringBuilder();
				for (int i = 0; i < length(); i++) result.append('*');
				return result.toString();
			}
		};
		TextPaint paint = new TextPaint();
		paint.setTextSize(16);
		StaticLayout.Builder builder = StaticLayout.Builder.obtain("Latin\n\u0928\u092e\u0938\u094d\u0924\u0947", 0, 12, paint, 500);
		if (builder.setUseLineSpacingFromFallbacks(true) != builder)
			throw new AssertionError("Fallback spacing builder chaining");
		StaticLayout fallback = builder.build();
		if (fallback.getLineCount() != 2 || fallback.getHeight() <= 0
		    || fallback.getLineBaseline(1) <= fallback.getLineBaseline(0))
			throw new AssertionError("Fallback-font lines must have usable metrics");
		try {
			builder.setUseLineSpacingFromFallbacks(false);
			throw new AssertionError("Unsupported primary-font-only metrics must not be silently accepted");
		} catch (UnsupportedOperationException expected) {}
		DynamicLayout plain = new DynamicLayout(base, paint, 500, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		DynamicLayout hidden = new DynamicLayout(base, masked, paint, 500, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		if (plain.getText() != base || hidden.getText() != masked)
			throw new AssertionError("Layout must retain its display sequence");
		base.append("\nmore");
		if (!plain.getText().toString().equals("secret\nmore") || plain.getLineCount() != 2)
			throw new AssertionError("Plain text and native layout must both follow edits");
		if (!hidden.getText().toString().equals("***********") || hidden.getLineCount() != 1 || hidden.getLineEnd(0) != 11)
			throw new AssertionError("Native layout must use display text, not raw source text");
		base.replace(0, base.length(), "x");
		if (!plain.getText().toString().equals("x") || hidden.getLineEnd(0) != 1)
			throw new AssertionError("Replacement must update both layouts");
		base.clear();
		if (plain.getText().length() != 0 || hidden.getText().length() != 0 || hidden.getLineEnd(0) != 0)
			throw new AssertionError("Clearing must update both layouts");
		System.out.println("PASS: DynamicLayout retains live display text and preserves masking across edits");
	}
}
