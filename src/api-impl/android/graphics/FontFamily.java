package android.graphics;

import android.atl.ATLFontFiles;
import android.atl.ATLFontTrace;
import android.content.res.AssetManager;
import android.graphics.fonts.FontVariationAxis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A font family built from per-font entries, as added by this class's deprecated API-26 builder
 * surface (frameworks-base 94b4c163 graphics/java/android/graphics/FontFamily.java).
 *
 * androidx.core's TypefaceCompatApi26Impl creates the family through
 * {@code addFontFromAssetManager}/{@code addFontFromBuffer}, freezes it and then hands it to
 * {@link Typeface#createFromFamiliesWithDefault(FontFamily[], int, int)}. A family that stores
 * nothing (and a freeze that always succeeds) therefore drops every bundled font resource and
 * silently degrades the app to the default typeface, so the added fonts are kept here as the file
 * the native layout loads.
 *
 * The per-font weight and slant describe the fonts; like AOSP's chains (nativeCreateFromArray),
 * the style the layout is asked for comes from the weight/slant passed to
 * createFromFamiliesWithDefault, and the first font's variation axes carry the per-face instance.
 */
public class FontFamily {

	/** One font of this family, in the form the native layout loads it. */
	static final class Entry {
		final String path;
		final int ttcIndex;
		final FontVariationAxis[] axes;

		Entry(String path, int ttcIndex, FontVariationAxis[] axes) {
			this.path = path;
			this.ttcIndex = ttcIndex;
			this.axes = axes;
		}
	}

	private final List<Entry> fonts = new ArrayList<>();

	/** @hide */
	public void addFontWeightStyle(String path, int ttcIndex, boolean isAsset) {
		add(null, path, 0, isAsset, ttcIndex, 400, 0, null);
	}

	/** @hide */
	public boolean addFontFromAssetManager(AssetManager mgr, String path, int cookie, boolean isAsset, int ttcIndex, int weight, int isItalic, FontVariationAxis[] axes) {
		return add(mgr, path, cookie, isAsset, ttcIndex, weight, isItalic, axes);
	}

	/** @hide */
	public boolean addFontFromBuffer(ByteBuffer buffer, int ttcIndex, FontVariationAxis[] axes, int weight, int isItalic) {
		try {
			if (buffer == null) return false;
			Entry entry = new Entry(ATLFontFiles.materialize(buffer), ttcIndex, axes);
			fonts.add(entry);
			ATLFontTrace.log("FontFamily.addFontFromBuffer bytes=" + buffer.remaining()
			                 + " ttcIndex=" + ttcIndex + " weight=" + weight + " italic=" + isItalic
			                 + " -> " + entry.path + "#" + ttcIndex);
			return true;
		} catch (IOException e) {
			ATLFontTrace.log("FontFamily.addFontFromBuffer ttcIndex=" + ttcIndex + " failed: " + e);
			return false;
		}
	}

	/** @hide */
	public boolean freeze() {
		boolean frozen = !fonts.isEmpty();
		ATLFontTrace.log("FontFamily.freeze() fonts=" + fonts.size() + " -> " + frozen);
		return frozen;
	}

	/** @hide */
	public void abortCreation() {
		fonts.clear();
	}

	/** @return the number of fonts added to this family (used by the typeface chain) */
	int getFontCount() {
		return fonts.size();
	}

	/** @return the font at INDEX (used by the typeface chain) */
	Entry getFont(int index) {
		return fonts.get(index);
	}

	/**
	 * Adds one font: PATH is an asset ("assets/..." semantics when ISASSET) or a non-asset entry
	 * of MGR, resolved through MGR; without an AssetManager PATH is a filesystem path, which is the
	 * only form {@link #addFontWeightStyle(String, int, boolean)}'s callers can pass.
	 */
	private boolean add(AssetManager mgr, String path, int cookie, boolean isAsset, int ttcIndex, int weight, int isItalic, FontVariationAxis[] axes) {
		if (path == null) return false;
		int resolvedWeight = weight >= 1 && weight <= 1000 ? weight : 400;
		try {
			String file = path;
			if (isAsset || mgr != null) {
				InputStream stream = isAsset
						? mgr.open(path, AssetManager.ACCESS_STREAMING)
						: mgr.openNonAsset(cookie, path, AssetManager.ACCESS_STREAMING);
				file = ATLFontFiles.materialize(stream);
			}
			fonts.add(new Entry(file, ttcIndex, axes));
			ATLFontTrace.log("FontFamily.addFont path=" + path + " cookie=" + cookie
			                 + " isAsset=" + isAsset + " ttcIndex=" + ttcIndex
			                 + " weight=" + resolvedWeight + " italic=" + isItalic
			                 + " -> " + file + "#" + ttcIndex);
			return true;
		} catch (IOException | RuntimeException e) {
			ATLFontTrace.log("FontFamily.addFont path=" + path + " cookie=" + cookie
			                 + " isAsset=" + isAsset + " failed: " + e);
			return false;
		}
	}
}
