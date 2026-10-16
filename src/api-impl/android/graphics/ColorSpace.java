package android.graphics;

public class ColorSpace {

	public static enum Named {
		SRGB,
	}

	public static ColorSpace get(Named named) {
		return new ColorSpace();
	}

	/**
	 * ATL renders through GTK/GSK into 8-bit sRGB; no colour space it can actually
	 * produce is wide gamut, so this is always false.
	 */
	public boolean isWideGamut() {
		return false;
	}
}
