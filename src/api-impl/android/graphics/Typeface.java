package android.graphics;

import android.content.res.AssetManager;
import java.io.File;
import android.graphics.fonts.FontVariationAxis;
import java.util.Objects;

public class Typeface {
	public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
	public static final Typeface DEFAULT = create("sans-serif", NORMAL);
	public static final Typeface DEFAULT_BOLD = create("sans-serif", BOLD);
	public static final Typeface SANS_SERIF = DEFAULT;
	public static final Typeface SERIF = create("serif", NORMAL);
	public static final Typeface MONOSPACE = create("monospace", NORMAL);

	public long native_instance; // ATL handle, also read by androidx
	private final int weight;
	private final boolean italic;
	public Typeface() { this(nativeCreate("sans-serif", 400, false)); }
	private Typeface(long handle) {
		if (handle == 0) throw new OutOfMemoryError("Unable to allocate typeface");
		native_instance = handle;
		int style = nativeStyle(handle);
		weight = style & 0xffff;
		italic = (style & 0x10000) != 0;
	}
	@Override protected void finalize() throws Throwable {
		try { nativeRelease(native_instance); } finally { super.finalize(); }
	}
	public static Typeface create(String family, int style) {
		if (style < 0 || style > 3) style = NORMAL;
		return new Typeface(nativeCreate(family == null ? "sans-serif" : family,
			(style & BOLD) != 0 ? 700 : 400, (style & ITALIC) != 0));
	}
	public static Typeface create(Typeface source, int style) {
		if (style < 0 || style > 3) style = NORMAL;
		return create(source, (style & BOLD) != 0 ? 700 : 400, (style & ITALIC) != 0);
	}
	public static Typeface create(Typeface source, int weight, boolean italic) {
		checkWeight(weight);
		if (source == null) source = DEFAULT;
		return new Typeface(nativeCopy(source.native_instance, weight, italic ? 1 : 0, null));
	}
	private static void checkWeight(int weight) {
		if (weight < 1 || weight > 1000) throw new IllegalArgumentException("weight must be 1..1000");
	}
	public int getWeight() { return weight; }
	public boolean isItalic() { return italic; }
	public boolean isBold() { return weight >= 600; }
	public int getStyle() { return (isBold() ? BOLD : 0) | (italic ? ITALIC : 0); }
	public static Typeface defaultFromStyle(int style) { return create((String)null, style); }
	public static Typeface createFromFile(String path) { return createFromFile(new File(path)); }
	public static Typeface createFromFile(File file) {
		Typeface result = new Builder(file).build();
		if (result == null) throw new RuntimeException("Unable to load font: " + file);
		return result;
	}
	// These older asset/family entry points still need a font-data backend.
	public static Typeface createFromAsset(AssetManager mgr, String path) { return DEFAULT; }
	public static Typeface createFromFamiliesWithDefault(FontFamily[] families) { return DEFAULT; }
	public static Typeface createFromFamiliesWithDefault(FontFamily[] families, int a, int b) { return DEFAULT; }

	public static class Builder {
		private String path, fallback, variations;
		private int weight = -1, italic = -1, index;
		private boolean asset;
		public Builder(File file) { path = Objects.requireNonNull(file).getAbsolutePath(); }
		public Builder(String path) { this(new File(Objects.requireNonNull(path))); }
		public Builder(AssetManager mgr, String path) { asset = true; }
		public Builder setWeight(int weight) { checkWeight(weight); this.weight = weight; return this; }
		public Builder setItalic(boolean italic) { this.italic = italic ? 1 : 0; return this; }
		public Builder setTtcIndex(int index) { if (index < 0) throw new IllegalArgumentException("negative font index"); this.index = index; return this; }
		public Builder setFallback(String family) { fallback = family; return this; }
		public Builder setFontVariationSettings(String settings) {
			return setFontVariationSettings(FontVariationAxis.fromFontVariationSettings(settings));
		}
		public Builder setFontVariationSettings(FontVariationAxis[] axes) {
			StringBuilder result = new StringBuilder();
			if (axes != null) for (FontVariationAxis axis : axes) {
				Objects.requireNonNull(axis);
				if (axis.getTag().indexOf(',') >= 0 || axis.getTag().indexOf('=') >= 0)
					throw new UnsupportedOperationException("Pango cannot encode this variation tag");
				if (result.length() > 0) result.append(',');
				result.append(axis.getTag()).append('=').append(axis.getStyleValue());
			}
			variations = result.length() == 0 ? null : result.toString();
			return this;
		}
		public Typeface build() {
			if (asset) return DEFAULT; // Existing asset behavior, not file-font support.
			long handle = nativeLoad(path, index);
			if (handle == 0) return fallback == null ? null : create(create(fallback, NORMAL), weight < 0 ? 400 : weight, italic == 1);
			long configured = nativeCopy(handle, weight, italic, variations);
			nativeRelease(handle);
			return new Typeface(configured);
		}
	}
	private static native long nativeCreate(String family, int weight, boolean italic);
	private static native long nativeLoad(String path, int index);
	private static native long nativeCopy(long handle, int weight, int italic, String variations);
	private static native int nativeStyle(long handle);
	private static native void nativeRelease(long handle);
}
