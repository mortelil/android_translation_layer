package android.graphics;

import android.atl.ATLFontFiles;
import android.atl.ATLFontTrace;
import android.content.res.AssetManager;
import java.io.File;
import android.graphics.fonts.FontVariationAxis;
import java.util.Objects;

public class Typeface {
	public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
	/** The family a typeface created from bundled fonts falls back to (frameworks-base 94b4c163
	 *  graphics/java/android/graphics/Typeface.java:292). */
	public static final String DEFAULT_FAMILY = "sans-serif";
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
	public static Typeface createFromFile(String path) {
		ATLFontTrace.log("Typeface.createFromFile(String) path=" + path);
		return createFromFile(new File(path));
	}
	public static Typeface createFromFile(File file) {
		Typeface result = new Builder(file).build();
		ATLFontTrace.log("Typeface.createFromFile(File) path="
		                 + (file == null ? "null" : file.getAbsolutePath())
		                 + " -> " + describe(result));
		if (result == null) throw new RuntimeException("Unable to load font: " + file);
		return result;
	}
	/**
	 * Creates a typeface from a font resource the AssetManager serves. AOSP reads the entry
	 * through the AssetManager (frameworks-base 94b4c163 graphics/java/android/graphics/Typeface.java
	 * createFromAsset/nativeCreateFromAsset); ATL materializes those bytes as a file the native
	 * loader can open, and - like AOSP, whose font collection then holds only the system fallback -
	 * leaves the result on DEFAULT_FAMILY when the entry cannot be read.
	 */
	public static Typeface createFromAsset(AssetManager mgr, String path) {
		Objects.requireNonNull(mgr, "AssetManager cannot be null");
		Objects.requireNonNull(path, "The asset path cannot be null");
		long handle = 0;
		try {
			handle = nativeLoad(ATLFontFiles.materialize(mgr.open(path, AssetManager.ACCESS_STREAMING)), 0);
		} catch (java.io.IOException e) {
			ATLFontTrace.log("Typeface.createFromAsset asset=" + path + " failed: " + e);
		}
		Typeface result = handle == 0 ? create(DEFAULT_FAMILY, NORMAL) : new Typeface(handle);
		ATLFontTrace.log("Typeface.createFromAsset asset=" + path + " -> " + describe(result));
		return result;
	}

	/**
	 * Creates a typeface from an array of font families followed by the system fallback family.
	 * AOSP delegates both public overloads to frameworks-base 94b4c163
	 * graphics/java/android/graphics/Typeface.java:1310
	 * createFromFamiliesWithDefault(families, fallbackName, weight, italic), which resolves
	 * FALLBACKNAME as the fallback typeface and passes it with the families' native pointers and the
	 * chain-level weight/slant to nativeCreateFromArray; ATL passes the equivalent file chain to
	 * nativeCreateFromFamilies. androidx.core's TypefaceCompatApi26Impl builds a bundled FontFamily
	 * and calls the (families, weight, italic) overload, so this is the only way a font resource
	 * reaches the layout on SDK 26.
	 */
	public static Typeface createFromFamiliesWithDefault(FontFamily[] families) {
		return createFromFamiliesWithDefault(families, DEFAULT_FAMILY, -1, -1);
	}

	/** See {@link #createFromFamiliesWithDefault(FontFamily[])}. */
	public static Typeface createFromFamiliesWithDefault(FontFamily[] families, int weight, int italic) {
		return createFromFamiliesWithDefault(families, DEFAULT_FAMILY, weight, italic);
	}

	private static Typeface createFromFamiliesWithDefault(FontFamily[] families, String fallbackName, int weight, int italic) {
		java.util.ArrayList<String> paths = new java.util.ArrayList<>();
		java.util.ArrayList<Integer> indices = new java.util.ArrayList<>();
		java.util.ArrayList<String> variations = new java.util.ArrayList<>();
		java.util.ArrayList<Integer> weights = new java.util.ArrayList<>(), slants = new java.util.ArrayList<>(), groups = new java.util.ArrayList<>();
		int group = 0;
		if (families != null) for (FontFamily family : families) {
			if (family == null) continue;
			for (int i = 0; i < family.getFontCount(); i++) {
				FontFamily.Entry entry = family.getFont(i);
				paths.add(entry.path);
				weights.add(entry.weight); slants.add(entry.italic); groups.add(group);
				indices.add(entry.ttcIndex);
				variations.add(FontVariationAxis.toFontVariationSettings(entry.axes));
			}
			group++;
		}
		if (paths.isEmpty()) {
			ATLFontTrace.log("Typeface.createFromFamiliesWithDefault " + describeFamilies(families)
			                 + " -> no font, using " + fallbackName);
			return create(fallbackName, NORMAL);
		}
		int[] indexArray = new int[indices.size()];
		for (int i = 0; i < indexArray.length; i++) indexArray[i] = indices.get(i);
		int[] weightArray = new int[indices.size()], slantArray = new int[indices.size()], groupArray = new int[indices.size()];
		for (int i = 0; i < indices.size(); i++) { weightArray[i] = weights.get(i); slantArray[i] = slants.get(i); groupArray[i] = groups.get(i); }
		Typeface result = new Typeface(nativeCreateFromLegacyFamilies(paths.toArray(new String[0]), indexArray,
				variations.toArray(new String[0]), fallbackName, weight, italic, weightArray, slantArray, groupArray));
		ATLFontTrace.log("Typeface.createFromFamiliesWithDefault " + describeFamilies(families)
		                 + " fallback=" + fallbackName + " weight=" + weight + " italic=" + italic
		                 + " -> " + describe(result));
		return result;
	}

	/** @return a one-line description of a typeface for the ATL_DEBUG_FONT trace */
	private static String describe(Typeface typeface) {
		if (typeface == null) return "null";
		return "handle=0x" + Long.toHexString(typeface.native_instance)
		       + (typeface == DEFAULT ? " (Typeface.DEFAULT)" : "");
	}

	/** @return a one-line description of a family chain for the ATL_DEBUG_FONT trace */
	private static String describeFamilies(FontFamily[] families) {
		if (families == null) return "families=null";
		StringBuilder sb = new StringBuilder("families=").append(families.length);
		for (int i = 0; i < families.length; i++) {
			FontFamily family = families[i];
			if (family == null) {
				sb.append(" [").append(i).append("]=null");
				continue;
			}
			sb.append(" [").append(i).append("] fonts=").append(family.getFontCount());
			for (int j = 0; j < family.getFontCount(); j++) {
				FontFamily.Entry entry = family.getFont(j);
				sb.append(' ').append(entry.path).append('#').append(entry.ttcIndex);
			}
		}
		return sb.toString();
	}

	public static class Builder {
		private String path, fallback, variations;
		private int weight = -1, italic = -1, index;
		private AssetManager assets;
		public Builder(File file) { path = Objects.requireNonNull(file).getAbsolutePath(); }
		public Builder(String path) { this(new File(Objects.requireNonNull(path))); }
		public Builder(AssetManager mgr, String path) {
			assets = Objects.requireNonNull(mgr);
			this.path = Objects.requireNonNull(path);
			ATLFontTrace.log("Typeface.Builder(AssetManager, " + path + ")");
		}
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
			long handle;
			if (assets != null) {
				// An asset is a font resource inside the APK, the same input createFromAsset takes.
				try {
					handle = nativeLoad(ATLFontFiles.materialize(assets.open(path, AssetManager.ACCESS_STREAMING)), index);
				} catch (java.io.IOException e) {
					ATLFontTrace.log("Typeface.Builder.build asset=" + path + " failed: " + e);
					handle = 0;
				}
			} else {
				handle = nativeLoad(path, index);
			}
			if (handle == 0) {
				ATLFontTrace.log("Typeface.Builder.build path=" + path + " index=" + index
				                 + " -> nativeLoad failed (handle=0), fallback=" + fallback);
				if (fallback == null && assets != null) return create(DEFAULT_FAMILY, NORMAL);
				return fallback == null ? null : create(create(fallback, NORMAL), weight < 0 ? 400 : weight, italic == 1);
			}
			long configured = nativeCopy(handle, weight, italic, variations);
			nativeRelease(handle);
			Typeface result = new Typeface(configured);
			ATLFontTrace.log("Typeface.Builder.build path=" + path + " index=" + index
			                 + " -> " + describe(result));
			return result;
		}
	}

	/**
	 * Builds a Typeface from an ordered chain of custom {@link android.graphics.fonts.FontFamily}s,
	 * optionally followed by a system fallback family. Each text run is later measured with the file
	 * that actually serves it (AOSP minikin FontCollection semantics; frameworks-base 94b4c163
	 * graphics/java/android/graphics/Typeface.java:881).
	 */
	public static final class CustomFallbackBuilder {
		private static final int MAX_CUSTOM_FALLBACK = 64;
		private final java.util.ArrayList<android.graphics.fonts.FontFamily> mFamilies = new java.util.ArrayList<>();
		private String mFallbackName = null;
		private android.graphics.fonts.FontStyle mStyle = null;

		/** @return the maximum number of custom fallback families */
		public static int getMaxCustomFallbackCount() { return MAX_CUSTOM_FALLBACK; }

		/**
		 * Constructs a builder with a font family.
		 *
		 * @param family a family object
		 */
		public CustomFallbackBuilder(android.graphics.fonts.FontFamily family) {
			mFamilies.add(Objects.requireNonNull(family));
		}

		/**
		 * Append a font family to the end of the custom font fallback.
		 *
		 * @param family a fallback family
		 */
		public CustomFallbackBuilder addCustomFallback(android.graphics.fonts.FontFamily family) {
			Objects.requireNonNull(family);
			if (mFamilies.size() >= MAX_CUSTOM_FALLBACK)
				throw new IllegalArgumentException("Custom fallback limit exceeded");
			mFamilies.add(family);
			return this;
		}

		/**
		 * Sets the system fallback family name.
		 *
		 * @param familyName a family name to be used for fallback
		 */
		public CustomFallbackBuilder setSystemFallback(String familyName) {
			mFallbackName = Objects.requireNonNull(familyName);
			return this;
		}

		/**
		 * Sets the style of the Typeface.
		 *
		 * @param style a font style
		 */
		public CustomFallbackBuilder setStyle(android.graphics.fonts.FontStyle style) {
			mStyle = Objects.requireNonNull(style);
			return this;
		}

		/** Creates the Typeface based on the configured values. */
		public Typeface build() {
			java.util.ArrayList<String> paths = new java.util.ArrayList<>();
			java.util.ArrayList<Integer> indices = new java.util.ArrayList<>();
			java.util.ArrayList<String> variations = new java.util.ArrayList<>();
			for (android.graphics.fonts.FontFamily family : mFamilies) {
				for (int i = 0; i < family.getSize(); i++) {
					android.graphics.fonts.Font font = family.getFont(i);
					paths.add(font.getFile().getAbsolutePath());
					indices.add(font.getTtcIndex());
					variations.add(FontVariationAxis.toFontVariationSettings(font.getAxes()));
				}
			}
			int[] indexArray = new int[indices.size()];
			for (int i = 0; i < indexArray.length; i++) indexArray[i] = indices.get(i);
			int weight = mStyle == null ? 400 : mStyle.getWeight();
			boolean italic = mStyle != null
					&& mStyle.getSlant() == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC;
			Typeface result = new Typeface(nativeCreateFromFamilies(
					paths.toArray(new String[0]), indexArray, variations.toArray(new String[0]),
					mFallbackName, weight, italic));
			ATLFontTrace.log("Typeface.CustomFallbackBuilder.build paths=" + paths
			                 + " fallback=" + mFallbackName + " weight=" + weight + " italic=" + italic
			                 + " -> " + describe(result));
			return result;
		}
	}

	private static native long nativeCreate(String family, int weight, boolean italic);
	static boolean isValidFont(String path, int index) {
		long handle = nativeLoad(path, index);
		if (handle == 0) return false;
		nativeRelease(handle);
		return true;
	}
	private static native long nativeCreateFromLegacyFamilies(String[] paths, int[] indices, String[] variations,
		String fallback, int weight, int italic, int[] weights, int[] slants, int[] groups);
	private static native long nativeLoad(String path, int index);
	private static native long nativeCopy(long handle, int weight, int italic, String variations);
	private static native long nativeCreateFromFamilies(String[] paths, int[] indices, String[] variations, String fallback, int weight, boolean italic);
	private static native int nativeStyle(long handle);
	private static native void nativeRelease(long handle);
}
