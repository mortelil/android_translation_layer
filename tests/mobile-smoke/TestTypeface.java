// SPDX-License-Identifier: GPL-3.0-only
import android.graphics.*;
import android.content.Context;
import android.text.*;
import java.io.*;
import android.graphics.fonts.FontVariationAxis;

public class TestTypeface {
	static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
	static File fixture(Context context, String name) throws IOException {
		File file = new File(context.getCacheDir(), name);
		try (InputStream in = context.getAssets().open("fonts/" + name); OutputStream out = new FileOutputStream(file)) {
			byte[] buffer = new byte[8192]; int count;
			while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
		}
		return file;
	}
	public static void run(Context context) throws Exception {
		File original = new File("/usr/share/fonts/dejavu/DejaVuSansMono.ttf");
		check(original.isFile(), "Test requires the DejaVu Mono font in the development image");
		File copy = File.createTempFile("atl-font-", ".ttf", context.getCacheDir());
		try (InputStream in = new FileInputStream(original); OutputStream out = new FileOutputStream(copy)) {
			byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
		}
		Typeface mono = new Typeface.Builder(copy).build();
		check(mono != null && mono.getWeight() == 400 && !mono.isItalic(), "Font file metadata");
		check(copy.delete(), "Remove original path before the first glyph load");
		Paint p = new Paint(); p.setTextSize(30); p.setTypeface(mono);
		float small = p.measureText("iiii"), wide = p.measureText("WWWW");
		check(small > 0 && Math.abs(small-wide) < 0.1, "Deleted file remains usable and monospace glyph advances agree");
		p.setTypeface(Typeface.SANS_SERIF);
		check(p.measureText("WWWW") > p.measureText("iiii") * 2, "System proportional font actually selected");
		p.setTypeface(mono); Paint clone = new Paint(p); p.reset();
		check(clone.getTypeface() == mono && clone.getTextSize() == 30 && Math.abs(clone.measureText("iiii") - small) < 0.1, "Paint clone retains font map, typeface and size");
		TextPaint tp = new TextPaint(clone);
		StaticLayout line = StaticLayout.Builder.obtain("iiii", 0, 4, tp, 1000).build();
		check(Math.abs(line.getLineWidth(0) - small) <= 1, "Pango layout and Paint use the same file face");
		Typeface styled = Typeface.create(mono, 700, true);
		android.text.style.TypefaceSpan explicit = new android.text.style.TypefaceSpan(mono);
		tp.setTypeface(styled); explicit.updateMeasureState(tp);
		check(tp.getTypeface() == mono && Math.abs(tp.measureText("iiii") - small) < 0.1, "Typeface span replaces style and changes measured glyphs");
		tp.setTypeface(styled); new android.text.style.TypefaceSpan("monospace").updateDrawState(tp);
		check(tp.getTypeface().getStyle() == Typeface.BOLD_ITALIC, "Family span preserves previous style");
		Typeface beforeNull = tp.getTypeface(); new android.text.style.TypefaceSpan((String)null).updateDrawState(tp);
		check(tp.getTypeface() == beforeNull, "Null family span leaves paint unchanged");
		try { new android.text.style.TypefaceSpan((Typeface)null); throw new AssertionError("null typeface"); } catch (NullPointerException expected) {}
		check(styled.isBold() && styled.isItalic() && styled.getStyle() == Typeface.BOLD_ITALIC, "Style copy metadata");
		clone.setTypeface(styled); check(clone.getTextSize() == 30 && clone.measureText("test") > 0, "Style preserves text size and renders");
		check(new Typeface.Builder(copy).build() == null, "Missing file cannot report success");
		File invalid = File.createTempFile("atl-not-font-", ".txt", context.getCacheDir());
		check(new Typeface.Builder(invalid).build() == null, "Invalid font rejected"); invalid.delete();
		check(new Typeface.Builder(original).setTtcIndex(99).build() == null, "Invalid collection face rejected");
		Typeface fallback = new Typeface.Builder(copy).setFallback("serif").setWeight(700).setItalic(true).build();
		check(fallback != null && fallback.getWeight() == 700 && fallback.isItalic(), "Explicit fallback honors style");
		try { new Typeface.Builder((File)null); throw new AssertionError("null file"); } catch (NullPointerException expected) {}
		try { new Typeface.Builder(original).setWeight(0); throw new AssertionError("invalid weight"); } catch (IllegalArgumentException expected) {}
		try { new Typeface.Builder(original).setFontVariationSettings("bad"); throw new AssertionError("invalid variations"); } catch (IllegalArgumentException expected) {}
		FontVariationAxis[] axes = FontVariationAxis.fromFontVariationSettings("'wdth' 200, \"wght\" 450");
		check(axes.length == 2 && axes[0].getStyleValue() == 200 && axes[1].getTag().equals("wght"), "Variation parser preserves tags and values");
		check(FontVariationAxis.fromFontVariationSettings(FontVariationAxis.toFontVariationSettings(axes))[1].getStyleValue() == 450, "Variation settings round trip");
		for (String bad : new String[]{"'wdth\" 200", "'wdth' 200,", "'bad' 4", "'wdth' 1e999"}) {
			try { FontVariationAxis.fromFontVariationSettings(bad); throw new AssertionError("Malformed axis: " + bad); } catch (IllegalArgumentException expected) {}
		}
		File variable = fixture(context, "width.ttf");
		Paint axisPaint = new Paint(); axisPaint.setTextSize(100);
		axisPaint.setTypeface(new Typeface.Builder(variable).setFontVariationSettings("'wdth' 100").build());
		float narrow = axisPaint.measureText("AAAA");
		axisPaint.setTypeface(new Typeface.Builder(variable).setFontVariationSettings(new FontVariationAxis[]{new FontVariationAxis("wdth", 200)}).build());
		check(narrow > 0 && Math.abs(axisPaint.measureText("AAAA") / narrow - 2) < 0.05, "String and array variation settings actually change glyph advances");
		File collection = fixture(context, "faces.ttc");
		axisPaint.setTypeface(new Typeface.Builder(collection).setTtcIndex(0).build());
		narrow = axisPaint.measureText("AAAA");
		axisPaint.setTypeface(new Typeface.Builder(collection).setTtcIndex(1).build());
		check(narrow > 0 && Math.abs(axisPaint.measureText("AAAA") / narrow - 2) < 0.05, "Collection index selects the requested face");
		android.widget.TextView view = new android.widget.TextView(context);
		view.setText("Private file font"); view.setTypeface(mono);
		check(view.getTypeface() == mono, "TextView retains selected typeface");
		android.widget.EditText edit = new android.widget.EditText(context);
		edit.setTypeface(mono); edit.setText("still editable");
		check(edit.getText().toString().equals("still editable"), "File font in editable GTK text widget");
		TextPaint spanPaint = new TextPaint(); spanPaint.setTextSize(14); spanPaint.density = 2;
		android.text.SpannableString sized = new android.text.SpannableString("Log in");
		sized.setSpan(new android.text.style.AbsoluteSizeSpan(16, true), 0, sized.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		float desired = android.text.Layout.getDesiredWidth(sized, spanPaint);
		StaticLayout fitted = new StaticLayout(sized, spanPaint, (int)Math.ceil(desired), android.text.Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		check(desired > android.text.Layout.getDesiredWidth("Log in", spanPaint) * 2,
			"Desired width must include density-aware size spans");
		check(fitted.getLineCount() == 1 && Math.abs(fitted.getLineWidth(0) - desired) <= 1,
			"Measured styled text must fit without unexpected wrapping");
		System.out.println("PASS: file fonts, real glyph advances, unlink lifetime, Paint copies, layout, styles, fallback and invalid input");
	}
}
