// SPDX-License-Identifier: GPL-3.0-only
package android.graphics.fonts;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A group of {@link Font}s that share a style family.
 *
 * ATL stores the declared fonts so {@link android.graphics.Typeface.CustomFallbackBuilder} can
 * pass the ordered chain to the native layout. Mirrors frameworks-base 94b4c163
 * graphics/java/android/graphics/fonts/FontFamily.java.
 */
public final class FontFamily {
	private final List<Font> mFonts;

	private FontFamily(List<Font> fonts) {
		mFonts = fonts;
	}

	/** @return the font at the given index */
	public @NonNull Font getFont(int index) {
		return mFonts.get(index);
	}

	/** @return the number of fonts in the family */
	public int getSize() {
		return mFonts.size();
	}

	/** @hide */
	public long getNativePtr() {
		return 0;
	}

	/** Builder for {@link FontFamily}. */
	public static final class Builder {
		private final List<Font> mFonts = new ArrayList<>();

		/**
		 * Constructs a builder with a font.
		 *
		 * @param font a font to include in the family
		 */
		public Builder(@NonNull Font font) {
			mFonts.add(Objects.requireNonNull(font, "font can not be null"));
		}

		/**
		 * Adds a font to the family.
		 *
		 * @param font a font to append
		 */
		public @NonNull Builder addFont(@NonNull Font font) {
			mFonts.add(Objects.requireNonNull(font, "font can not be null"));
			return this;
		}

		/**
		 * Creates the family based on the configured values.
		 *
		 * @return the FontFamily object
		 */
		public @NonNull FontFamily build() {
			if (mFonts.isEmpty())
				throw new IllegalArgumentException("FontFamily must contain at least one Font");
			return new FontFamily(new ArrayList<>(mFonts));
		}
	}
}