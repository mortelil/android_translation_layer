// SPDX-License-Identifier: GPL-3.0-only
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.atl.GskCanvas;

public class TestTextSpans {
	public static void run() throws Exception {
		SpannableString source = new SpannableString("\ud83d\ude00Hi");
		source.setSpan(new ForegroundColorSpan(0xffff0000), 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		int[] encoded = android.atl.TextSpanAttributes.encode(source, 1);
		if (encoded.length != 4 || encoded[0] != 4 || encoded[1] != 6)
			throw new AssertionError("Span ranges must convert UTF-16 to UTF-8");
		TextPaint paint = new TextPaint(); paint.setTextSize(12); paint.setColor(0xff000000);
		SpannableString text = new SpannableString("MMMM");
		text.setSpan(new ForegroundColorSpan(0xffff0000), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		text.setSpan(new AbsoluteSizeSpan(30), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		StaticLayout styled = new StaticLayout(text, paint, 200, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		StaticLayout plain = new StaticLayout("MMMM", paint, 200, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		if (styled.getLineWidth(0) <= plain.getLineWidth(0) * 1.8f || styled.getHeight() <= plain.getHeight())
			throw new AssertionError("Size spans must affect measurement");
		Bitmap bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888);
		java.lang.reflect.Method snapshot = Bitmap.class.getDeclaredMethod("getSnapshot");
		snapshot.setAccessible(true);
		styled.draw(new GskCanvas((Long)snapshot.invoke(bitmap)));
		int[] pixels = new int[16000]; bitmap.getPixels(pixels, 0, 200, 0, 0, 200, 80);
		int red = 0;
		for (int pixel : pixels) if ((pixel >>> 24) > 128 && ((pixel >> 16) & 255) > 128 && (pixel & 0xffff) == 0) red++;
		if (red < 20) throw new AssertionError("Foreground span must render red glyph pixels");
		SpannableStringBuilder mutable = new SpannableStringBuilder("MMMM");
		DynamicLayout live = new DynamicLayout(mutable, paint, 200, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		float originalWidth = live.getLineWidth(0);
		AbsoluteSizeSpan size = new AbsoluteSizeSpan(30);
		mutable.setSpan(size, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		if (live.getLineWidth(0) < originalWidth * 1.8f) throw new AssertionError("live size span addition");
		mutable.removeSpan(size);
		if (Math.abs(live.getLineWidth(0) - originalWidth) > 1) throw new AssertionError("live size span removal");
		System.out.println("PASS: UTF-8 span offsets, styled text measurement and rendered foreground pixels");
	}
}
