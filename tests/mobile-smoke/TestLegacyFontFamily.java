// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.FontFamily;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class TestLegacyFontFamily {
    private static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    public static void run(Context context) throws Exception {
        FontFamily invalid = new FontFamily();
        check(!invalid.addFontFromBuffer(ByteBuffer.wrap(new byte[]{1,2,3}), 0, null, 400, 0), "corrupt font accepted");
        check(!invalid.freeze(), "empty invalid family frozen");
        byte[] bytes;
        try (InputStream in = context.getAssets().open("fonts/faces.ttc")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            bytes = out.toByteArray();
        }
        java.io.File legacyFile = new java.io.File(context.getCacheDir(), "legacy-faces.ttc");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(legacyFile)) { out.write(bytes); }
        FontFamily legacy = new FontFamily();
        check(legacy.addFontWeightStyle(legacyFile.getAbsolutePath(), 700, true), "legacy path weight/slant");
        check(legacy.freeze(), "legacy path freeze");
        Typeface legacyFace = Typeface.createFromFamiliesWithDefault(new FontFamily[]{legacy}, -1, -1);
        check(legacyFace.isBold() && legacyFace.isItalic(), "legacy path style inferred");
        FontFamily family = new FontFamily();
        check(!family.addFontFromBuffer(ByteBuffer.wrap(bytes), 999, null, 400, 0), "invalid TTC index");
        check(!family.addFontFromBuffer(ByteBuffer.wrap(bytes), 0, null, 1001, 0), "invalid weight");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        check(family.addFontFromBuffer(buffer, 0, null, 700, 1), "narrow bold italic face");
        check(buffer.position() == 0, "caller buffer consumed");
        check(family.addFontFromBuffer(buffer, 1, null, 400, 0), "wide regular face");
        check(family.freeze(), "valid family freeze");
        try { family.addFontFromBuffer(buffer, 0, null, 400, 0); throw new AssertionError("add after freeze"); }
        catch (IllegalStateException expected) { }
        Typeface normal = Typeface.createFromFamiliesWithDefault(new FontFamily[]{family}, 400, 0);
        Typeface bold = Typeface.createFromFamiliesWithDefault(new FontFamily[]{family}, 700, 1);
        Typeface inferred = Typeface.createFromFamiliesWithDefault(new FontFamily[]{family}, -1, -1);
        check(inferred.isBold() && inferred.isItalic(), "first face inferred style");
        Paint p = new Paint(); p.setTextSize(40); p.setTypeface(normal);
        float wide = p.measureText("AAAA"); p.setTypeface(bold); float narrow = p.measureText("AAAA");
        check(wide > narrow * 1.5f, "per-face style did not select the correct family member: " + wide + "/" + narrow);
        FontFamily assets = new FontFamily();
        check(assets.addFontFromAssetManager(context.getAssets(), "fonts/faces.ttc", 0, true, 1, 400, 0, null), "asset registration");
        check(assets.freeze(), "asset freeze");
        p.setTypeface(Typeface.createFromFamiliesWithDefault(new FontFamily[]{assets}, 400, 0));
        check(Math.abs(p.measureText("AAAA") - wide) < .1f, "asset and buffer font differ");
        FontFamily aborted = new FontFamily();
        check(aborted.addFontFromBuffer(buffer, 0, null, -1, -1), "abort fixture");
        aborted.abortCreation();
        try { aborted.freeze(); throw new AssertionError("freeze after abort"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS: legacy fonts reject corrupt input, preserve face styles and enforce builder lifetime");
    }
}
