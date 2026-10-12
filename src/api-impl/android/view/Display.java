/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import java.util.Arrays;

public final class Display {

	public static int window_width = 960;
	public static int window_height = 540;

	// FIXME: what do we return here?
	// we don't want to hardcode this stuff, but at the same time the apps can cache it
	public void getMetrics(DisplayMetrics outMetrics) {
		outMetrics.widthPixels = this.window_width;
		outMetrics.heightPixels = this.window_height;
	}

	public void getRealMetrics(DisplayMetrics outMetrics) {
		getMetrics(outMetrics); // probably?
	}

	public int getWidth() {
		return window_width;
	}

	public int getHeight() {
		return window_height;
	}

	public int getRawWidth() {
		return window_width; // what's the difference?
	}

	public int getRawHeight() {
		return window_height; // what's the difference?
	}

	public int getRotation() {
		return 0 /*ROTATION_0*/;
	}

	public float getRefreshRate() {
		return 60; // FIXME
	}

	public long getAppVsyncOffsetNanos() {
		return 0; // what else would we return here?
	}

	public int getDisplayId() {
		return 0;
	}

	public long getPresentationDeadlineNanos() {
		return 0; // what else...
	}

	public void getSize(Point size) {
		size.set(getWidth(), getHeight());
	}

	public void getRealSize(Point size) {
		getSize(size);
	}

	public void getRectSize(Rect rect) {
		rect.set(0, 0, getWidth(), getHeight());
	}

	public DisplayCutout getCutout() {
		return DisplayCutout.NO_CUTOUT;
	}

	public Display.Mode getMode() {
		return new Display.Mode();
	}

	/**
	 * Returns the current display mode's HDR capabilities.
	 *
	 * <p>ATL renders to host window surfaces that expose no HDR metadata, so the display has no
	 * HDR capabilities; AOSP reports that case as {@code null}
	 * ({@code DisplayInfo.hdrCapabilities} unset).
	 *
	 * @return the HDR capabilities of the display, or {@code null} if the display has none
	 * @see #isHdr()
	 */
	public HdrCapabilities getHdrCapabilities() {
		return null;
	}

	/**
	 * Returns true if the display is HDR capable.
	 *
	 * @see #getHdrCapabilities()
	 */
	public boolean isHdr() {
		HdrCapabilities hdrCapabilities = getHdrCapabilities();
		if (hdrCapabilities == null) {
			return false;
		}
		return !(hdrCapabilities.getSupportedHdrTypes().length == 0);
	}

	public static final class Mode {
		// ATL exposes one logical window mode through getSupportedModes().
		public int getModeId() {
			return 1;
		}

		public float getRefreshRate() {
			return new Display().getRefreshRate();
		}

		public int getPhysicalWidth() {
			return window_width;
		}

		public int getPhysicalHeight() {
			return window_height;
		}
	}

	public Mode[] getSupportedModes() {
		return new Mode[] {getMode()};
	}

	/**
	 * Encapsulates the HDR capabilities of a given display.
	 * For example, what HDR types it supports and details about the desired luminance data.
	 * <p>You can get an instance for a given {@link Display} object with
	 * {@link Display#getHdrCapabilities getHdrCapabilities()}.
	 */
	public static final class HdrCapabilities implements Parcelable {
		/** Invalid luminance value. */
		public static final float INVALID_LUMINANCE = -1;
		/** Invalid HDR type value. */
		public static final int HDR_TYPE_INVALID = -1;
		/** Dolby Vision high dynamic range (HDR) display. */
		public static final int HDR_TYPE_DOLBY_VISION = 1;
		/** HDR10 display. */
		public static final int HDR_TYPE_HDR10 = 2;
		/** Hybrid Log-Gamma HDR display. */
		public static final int HDR_TYPE_HLG = 3;
		/** HDR10+ display. */
		public static final int HDR_TYPE_HDR10_PLUS = 4;

		private int[] mSupportedHdrTypes = new int[0];
		private float mMaxLuminance = INVALID_LUMINANCE;
		private float mMaxAverageLuminance = INVALID_LUMINANCE;
		private float mMinLuminance = INVALID_LUMINANCE;

		/**
		 * @hide
		 */
		public HdrCapabilities() {
		}

		/**
		 * @hide
		 */
		public HdrCapabilities(int[] supportedHdrTypes, float maxLuminance, float maxAverageLuminance, float minLuminance) {
			mSupportedHdrTypes = supportedHdrTypes;
			Arrays.sort(mSupportedHdrTypes);
			mMaxLuminance = maxLuminance;
			mMaxAverageLuminance = maxAverageLuminance;
			mMinLuminance = minLuminance;
		}

		/**
		 * Gets the supported HDR types of this display.
		 * Returns empty array if HDR is not supported by the display.
		 *
		 * @deprecated use {@link Display#getMode()} and its supported HDR types instead
		 */
		@Deprecated
		public int[] getSupportedHdrTypes() {
			return Arrays.copyOf(mSupportedHdrTypes, mSupportedHdrTypes.length);
		}
		/**
		 * Returns the desired content max luminance data in cd/m2 for this display.
		 */
		public float getDesiredMaxLuminance() {
			return mMaxLuminance;
		}
		/**
		 * Returns the desired content max frame-average luminance data in cd/m2 for this display.
		 */
		public float getDesiredMaxAverageLuminance() {
			return mMaxAverageLuminance;
		}
		/**
		 * Returns the desired content min luminance data in cd/m2 for this display.
		 */
		public float getDesiredMinLuminance() {
			return mMinLuminance;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof HdrCapabilities)) {
				return false;
			}
			HdrCapabilities that = (HdrCapabilities) other;
			return Arrays.equals(mSupportedHdrTypes, that.mSupportedHdrTypes)
			     && mMaxLuminance == that.mMaxLuminance
			     && mMaxAverageLuminance == that.mMaxAverageLuminance
			     && mMinLuminance == that.mMinLuminance;
		}

		@Override
		public int hashCode() {
			int hash = 23;
			hash = hash * 17 + Arrays.hashCode(mSupportedHdrTypes);
			hash = hash * 17 + Float.floatToIntBits(mMaxLuminance);
			hash = hash * 17 + Float.floatToIntBits(mMaxAverageLuminance);
			hash = hash * 17 + Float.floatToIntBits(mMinLuminance);
			return hash;
		}

		public static final Parcelable.Creator<HdrCapabilities> CREATOR = new Parcelable.Creator<HdrCapabilities>() {
			//@Override
			public HdrCapabilities createFromParcel(Parcel source) {
				return new HdrCapabilities(source);
			}
			//@Override
			public HdrCapabilities[] newArray(int size) {
				return new HdrCapabilities[size];
			}
		};

		private HdrCapabilities(Parcel source) {
			readFromParcel(source);
		}

		/**
		 * @hide
		 */
		public void readFromParcel(Parcel source) {
			int types = source.readInt();
			mSupportedHdrTypes = new int[types];
			for (int i = 0; i < types; ++i) {
				mSupportedHdrTypes[i] = source.readInt();
			}
			mMaxLuminance = source.readFloat();
			mMaxAverageLuminance = source.readFloat();
			mMinLuminance = source.readFloat();
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(mSupportedHdrTypes.length);
			for (int i = 0; i < mSupportedHdrTypes.length; ++i) {
				dest.writeInt(mSupportedHdrTypes[i]);
			}
			dest.writeFloat(mMaxLuminance);
			dest.writeFloat(mMaxAverageLuminance);
			dest.writeFloat(mMinLuminance);
		}

		@Override
		public String toString() {
			return "HdrCapabilities{"
			     + "mSupportedHdrTypes=" + Arrays.toString(mSupportedHdrTypes)
			     + ", mMaxLuminance=" + mMaxLuminance
			     + ", mMaxAverageLuminance=" + mMaxAverageLuminance
			     + ", mMinLuminance=" + mMinLuminance + '}';
		}
	}
}
