package android.graphics;

/**
 * Linear gradient shader.
 *
 * The parameters are stored the way AOSP's android.graphics.LinearGradient stores them (AOSP keeps
 * the native shader out of Java; ATL keeps the parameters because the GSK renderer needs them at
 * draw time). Rendering happens in android.atl.GskCanvas.native_drawRect().
 */
public class LinearGradient extends Shader {

	public final float x0, y0, x1, y1;
	public final int[] colors;
	public final float[] positions;
	public final TileMode tile_mode;

	public LinearGradient(float x0, float y0, float x1, float y1, int[] colors, float[] positions, TileMode mode) {
		if (colors == null || colors.length < 2)
			throw new IllegalArgumentException("needs >= 2 number of colors");
		if (positions != null && colors.length != positions.length)
			throw new IllegalArgumentException("color and position arrays must be of equal length");
		this.x0 = x0;
		this.y0 = y0;
		this.x1 = x1;
		this.y1 = y1;
		this.colors = colors.clone();
		this.positions = positions != null ? positions.clone() : null;
		this.tile_mode = mode;
	}

	public LinearGradient(float x0, float y0, float x1, float y1, long[] colors, float[] positions, TileMode mode) {
		this(x0, y0, x1, y1, colorLongsToColorInts(colors), positions, mode);
	}

	public LinearGradient(float x0, float y0, float x1, float y1, int color0, int color1, TileMode mode) {
		this(x0, y0, x1, y1, new int[] { color0, color1 }, null, mode);
	}

	private static int[] colorLongsToColorInts(long[] colors) {
		if (colors == null)
			throw new IllegalArgumentException("needs >= 2 number of colors");
		int[] color_ints = new int[colors.length];
		for (int i = 0; i < colors.length; i++)
			color_ints[i] = Color.toArgb(colors[i]);
		return color_ints;
	}
}
