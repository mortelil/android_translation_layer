// SPDX-License-Identifier: GPL-3.0-only
package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * A font face backed by a file, as loaded through {@link Builder}.
 *
 * ATL keeps the resolved file path, collection index, style and variation axes so that a
 * {@link FontFamily} can hand the declared chain to the native layout. The API surface mirrors
 * frameworks-base 94b4c163 graphics/java/android/graphics/fonts/Font.java.
 */
public final class Font {
	private final File mFile;
	private final int mTtcIndex;
	private final int mWeight;
	private final boolean mItalic;
	private final FontVariationAxis[] mAxes;

	private Font(File file, int ttcIndex, int weight, boolean italic, FontVariationAxis[] axes) {
		mFile = file;
		mTtcIndex = ttcIndex;
		mWeight = weight;
		mItalic = italic;
		mAxes = axes;
	}

	/** @return the file this font was loaded from */
	public @NonNull File getFile() {
		if (mFile == null) throw new UnsupportedOperationException("Font is not backed by a file");
		return mFile;
	}

	/** @return the resolved style of this font */
	public @NonNull FontStyle getStyle() {
		return new FontStyle(mWeight,
				mItalic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT);
	}

	/** @return the index of the face inside a font collection, or 0 */
	public int getTtcIndex() {
		return mTtcIndex;
	}

	/** @return the variation axes requested for this font, or null */
	public @Nullable FontVariationAxis[] getAxes() {
		return mAxes == null ? null : mAxes.clone();
	}

	/** Builder for {@link Font}. */
	public static final class Builder {
		private final File mFile;
		private int mWeight = -1;
		private int mItalic = -1;
		private int mTtcIndex = 0;
		private FontVariationAxis[] mAxes = null;

		/**
		 * Constructs a builder from a font file path.
		 *
		 * @param path a file path to the font file
		 */
		public Builder(@NonNull File path) {
			mFile = Objects.requireNonNull(path, "path can not be null");
		}

		/** @hide */
		public Builder(@NonNull File path, @NonNull String localeList) {
			this(path);
		}

		/**
		 * Sets the weight of the font.
		 *
		 * @param weight a weight value 1..1000
		 */
		public @NonNull Builder setWeight(int weight) {
			if (weight < FontStyle.FONT_WEIGHT_MIN || weight > FontStyle.FONT_WEIGHT_MAX)
				throw new IllegalArgumentException("weight must be 1..1000");
			mWeight = weight;
			return this;
		}

		/**
		 * Sets slant information of the font.
		 *
		 * @param slant an {@link FontStyle} slant value
		 */
		public @NonNull Builder setSlant(int slant) {
			mItalic = slant == FontStyle.FONT_SLANT_UPRIGHT ? 0 : 1;
			return this;
		}

		/**
		 * Sets an index of the font collection.
		 *
		 * @param ttcIndex an index of the font collection
		 */
		public @NonNull Builder setTtcIndex(int ttcIndex) {
			if (ttcIndex < 0) throw new IllegalArgumentException("ttcIndex can not be negative");
			mTtcIndex = ttcIndex;
			return this;
		}

		/**
		 * Sets the font variation settings from a settings string.
		 *
		 * @param variationSettings see {@link FontVariationAxis#fromFontVariationSettings(String)}
		 */
		public @NonNull Builder setFontVariationSettings(@Nullable String variationSettings) {
			mAxes = FontVariationAxis.fromFontVariationSettings(variationSettings);
			return this;
		}

		/**
		 * Sets the font variation settings from an array of axes.
		 *
		 * @param axes an array of font variation axis tag-value pairs
		 */
		public @NonNull Builder setFontVariationSettings(@Nullable FontVariationAxis[] axes) {
			mAxes = axes == null ? null : axes.clone();
			return this;
		}

		/**
		 * Creates the font based on the configured values.
		 *
		 * @return the Font object
		 */
		public @NonNull Font build() throws IOException {
			if (!mFile.isFile())
				throw new IOException("Font file does not exist: " + mFile);
			/* AOSP derives the unspecified weight/italic from the font tables (FontFileUtil
			 * .analyzeStyle); ATL does not re-read the tables here, so the unresolved values fall
			 * back to the regular upright style. */
			int weight = mWeight < 0 ? FontStyle.FONT_WEIGHT_NORMAL : mWeight;
			boolean italic = mItalic == 1;
			return new Font(mFile, mTtcIndex, weight, italic, mAxes);
		}
	}
}