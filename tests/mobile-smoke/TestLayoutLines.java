// SPDX-License-Identifier: GPL-3.0-only
import android.text.*;

public class TestLayoutLines {
	private static void equal(String what, int expected, int actual) {
		if (expected != actual)
			throw new AssertionError(what + ": expected " + expected + ", got " + actual);
	}
	private static StaticLayout layout(String text, int width) {
		TextPaint paint = new TextPaint();
		paint.setTextSize(20);
		return new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
	}
	private static void checkBoundaries(Layout layout) {
		int count = layout.getLineCount();
		if (count < 1) throw new AssertionError("Even empty text needs a line");
		equal("first start", 0, layout.getLineStart(0));
		equal("first top", 0, layout.getLineTop(0));
		equal("Compose empty-line predecessor", 0, layout.getLineBottom(-1));
		equal("end sentinel", layout.getText().length(), layout.getLineStart(count));
		equal("height sentinel", layout.getHeight(), layout.getLineTop(count));
		equal("negative offset", 0, layout.getLineForOffset(Integer.MIN_VALUE));
		equal("past-end offset", count - 1, layout.getLineForOffset(Integer.MAX_VALUE));
		equal("exact end offset", count - 1, layout.getLineForOffset(layout.getText().length()));
		for (int line = 0; line < count; line++) {
			int start = layout.getLineStart(line), end = layout.getLineEnd(line);
			equal("contiguous lines", layout.getLineStart(line + 1), end);
			equal("contiguous vertical bounds", layout.getLineTop(line + 1), layout.getLineBottom(line));
			if (layout.getLineBottom(line) <= layout.getLineTop(line))
				throw new AssertionError("Line must have positive height");
			for (int offset = start; offset < end; offset++)
				equal("UTF-16 offset " + offset, line, layout.getLineForOffset(offset));
		}
		try {
			layout.getLineStart(count + 1);
			throw new AssertionError("start past sentinel accepted");
		} catch (ArrayIndexOutOfBoundsException expected) {}
		try {
			layout.getLineBottom(-2);
			throw new AssertionError("bottom below predecessor accepted");
		} catch (ArrayIndexOutOfBoundsException expected) {}
	}
	public static void run() {
		checkBoundaries(layout("", 500));
		String text = "a\ud83d\ude00\n\u00e9e\u0301\n\u05d0\u05d1\n";
		StaticLayout hard = layout(text, 500);
		equal("hard line count including final empty line", 4, hard.getLineCount());
		int[] starts = {0, 4, 8, 11, 11};
		for (int i = 0; i < starts.length; i++) equal("hard start " + i, starts[i], hard.getLineStart(i));
		checkBoundaries(hard);
		equal("visible end still excludes newline", 3, hard.getLineVisibleEnd(0));
		StaticLayout wrapped = layout("a\ud83d\ude00 b\u00e9 c\ud83d\ude00 def ghij", 45);
		if (wrapped.getLineCount() < 2) throw new AssertionError("Fixture did not wrap");
		checkBoundaries(wrapped);
		SpannableStringBuilder editable = new SpannableStringBuilder("x");
		DynamicLayout dynamic = new DynamicLayout(editable, new TextPaint(), 500,
		    Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		editable.replace(0, editable.length(), text);
		checkBoundaries(dynamic);
		equal("edited astral line start", 4, dynamic.getLineStart(1));
		editable.clear();
		checkBoundaries(dynamic);
		equal("empty line max", 0, Math.round(layout("",500).getLineMax(0)));
		StaticLayout last = layout("abc   ",500);
		equal("final line keeps spaces",6,last.getLineVisibleEnd(0));
		if (last.getLineMax(0) <= layout("abc",500).getLineMax(0))
			throw new AssertionError("Final line spacing lost");
		StaticLayout newline = layout("abc  \nx",500);
		equal("newline preserves preceding spaces",5,newline.getLineVisibleEnd(0));
		StaticLayout soft = layout("abc     def ghi",60);
		int visible = soft.getLineVisibleEnd(0);
		if (visible <= 0 || soft.getText().charAt(visible-1)==' ')
			throw new AssertionError("Soft wrap did not trim spaces");
		float expected = layout(soft.getText().subSequence(0,visible).toString(),500).getLineMax(0);
		if (Math.abs(expected-soft.getLineMax(0))>1)
			throw new AssertionError("Shaped visible width differs: "+expected+" / "+soft.getLineMax(0));
		for (String value : new String[]{"a\ud83d\ude00", "\u05d0\u05d1 abc", "a\tb", "a\u00a0"}) {
			StaticLayout row = layout(value,500);
			if (Math.abs(row.getLineMax(0)-row.getLineWidth(0))>1)
				throw new AssertionError("Full shaped extent mismatch: "+value);
		}
		// AOSP Minikin maps isolated UTF-16 surrogates to U+FFFD for shaping.
		// They must not make ATL's UTF-8/Pango conversion reject the whole layout.
		for (String malformed : new String[]{"\ud800", "\udc00", "a\ud800b", "a\udc00b", "\ud800\ud800"}) {
			StaticLayout replacement = layout(malformed, 500);
			equal("isolated surrogate stays one UTF-16 code unit", malformed.length(), replacement.getText().length());
			if (replacement.getLineCount() != 1 || replacement.getLineMax(0) <= 0)
				throw new AssertionError("Isolated surrogate must shape as replacement character");
		}
		try { last.getLineMax(-1); throw new AssertionError("Negative line accepted"); }
		catch (ArrayIndexOutOfBoundsException expectedError) {}
		try { last.getLineMax(last.getLineCount()); throw new AssertionError("End sentinel accepted"); }
		catch (ArrayIndexOutOfBoundsException expectedError) {}
		System.out.println("PASS: Layout UTF-16 boundaries, malformed-surrogate replacement, wrapping and live edits");
	}
}
