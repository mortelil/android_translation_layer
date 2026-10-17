/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.media;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Port of AOSP {@code media/java/android/media/AudioFormat.java}. The pinned
 * partial reference tree (/home/morten/ATL/reference/aosp/android-17.0.0_r1/
 * frameworks-base) carries no {@code media/}, so the port is cited against the
 * SDK sources at
 * /home/morten/Android/Sdk/sources/android-37.0/android/media/AudioFormat.java
 * (same public and package API as android-17.0.0_r1).
 *
 * Previously this class only carried the constants plus a Builder that stored
 * the three values, so native code looking up the accessors (e.g.
 * {@code xeno.effect.RemoteAssetManager}'s JNI does GetMethodID on
 * {@code getChannelCount()I}, {@code getChannelMask()I}, {@code getEncoding()I}
 * and {@code getSampleRate()I}) aborted the process with a
 * NoSuchMethodError. The accessors now return the stored / derived values.
 *
 * Not ported: {@code ChannelMasksArray} (the surround-sound device-enumeration
 * helper) and {@code toDisplayName}/{@code SURROUND_SOUND_ENCODING}, which
 * belong to the AudioDeviceInfo/AudioPort surround API that ATL does not have.
 */
public class AudioFormat implements Parcelable {

	// Values from AOSP frameworks/base android-15.0.0_r1 AudioFormat.java.
	public static final int ENCODING_INVALID = 0;
	public static final int ENCODING_DEFAULT = 1;
	public static final int ENCODING_PCM_16BIT = 2;
	public static final int ENCODING_PCM_8BIT = 3;
	public static final int ENCODING_PCM_FLOAT = 4;
	public static final int ENCODING_AC3 = 5;
	public static final int ENCODING_E_AC3 = 6;
	public static final int ENCODING_DTS = 7;
	public static final int ENCODING_DTS_HD = 8;
	public static final int ENCODING_MP3 = 9;
	public static final int ENCODING_AAC_LC = 10;
	public static final int ENCODING_AAC_HE_V1 = 11;
	public static final int ENCODING_AAC_HE_V2 = 12;
	public static final int ENCODING_IEC61937 = 13;
	public static final int ENCODING_DOLBY_TRUEHD = 14;
	public static final int ENCODING_AAC_ELD = 15;
	public static final int ENCODING_AAC_XHE = 16;
	public static final int ENCODING_AC4 = 17;
	public static final int ENCODING_E_AC3_JOC = 18;
	public static final int ENCODING_DOLBY_MAT = 19;
	public static final int ENCODING_OPUS = 20;
	public static final int ENCODING_LEGACY_SHORT_ARRAY_THRESHOLD = ENCODING_OPUS;
	public static final int ENCODING_PCM_24BIT_PACKED = 21;
	public static final int ENCODING_PCM_32BIT = 22;
	public static final int ENCODING_MPEGH_BL_L3 = 23;
	public static final int ENCODING_MPEGH_BL_L4 = 24;
	public static final int ENCODING_MPEGH_LC_L3 = 25;
	public static final int ENCODING_MPEGH_LC_L4 = 26;
	/** @deprecated Use {@link #ENCODING_DTS_UHD_P1} instead. */
	public static final int ENCODING_DTS_UHD = 27;
	public static final int ENCODING_DRA = 28;
	public static final int ENCODING_DTS_HD_MA = 29;
	public static final int ENCODING_DTS_UHD_P1 = 27;
	public static final int ENCODING_DTS_UHD_P2 = 30;
	public static final int ENCODING_DSD = 31;
	public static final int ENCODING_AC4_L4 = 32;
	public static final int ENCODING_IAMF_SIMPLE_PROFILE_OPUS = 33;
	public static final int ENCODING_IAMF_SIMPLE_PROFILE_AAC = 34;
	public static final int ENCODING_IAMF_SIMPLE_PROFILE_FLAC = 35;
	public static final int ENCODING_IAMF_SIMPLE_PROFILE_PCM = 36;
	public static final int ENCODING_IAMF_BASE_PROFILE_OPUS = 37;
	public static final int ENCODING_IAMF_BASE_PROFILE_AAC = 38;
	public static final int ENCODING_IAMF_BASE_PROFILE_FLAC = 39;
	public static final int ENCODING_IAMF_BASE_PROFILE_PCM = 40;
	public static final int ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS = 41;
	public static final int ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC = 42;
	public static final int ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC = 43;
	public static final int ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM = 44;

	public static String toLogFriendlyEncoding(int enc) {
		switch (enc) {
			case ENCODING_INVALID: return "ENCODING_INVALID";
			case ENCODING_PCM_16BIT: return "ENCODING_PCM_16BIT";
			case ENCODING_PCM_8BIT: return "ENCODING_PCM_8BIT";
			case ENCODING_PCM_FLOAT: return "ENCODING_PCM_FLOAT";
			case ENCODING_AC3: return "ENCODING_AC3";
			case ENCODING_E_AC3: return "ENCODING_E_AC3";
			case ENCODING_DTS: return "ENCODING_DTS";
			case ENCODING_DTS_HD: return "ENCODING_DTS_HD";
			case ENCODING_MP3: return "ENCODING_MP3";
			case ENCODING_AAC_LC: return "ENCODING_AAC_LC";
			case ENCODING_AAC_HE_V1: return "ENCODING_AAC_HE_V1";
			case ENCODING_AAC_HE_V2: return "ENCODING_AAC_HE_V2";
			case ENCODING_IEC61937: return "ENCODING_IEC61937";
			case ENCODING_DOLBY_TRUEHD: return "ENCODING_DOLBY_TRUEHD";
			case ENCODING_AAC_ELD: return "ENCODING_AAC_ELD";
			case ENCODING_AAC_XHE: return "ENCODING_AAC_XHE";
			case ENCODING_AC4: return "ENCODING_AC4";
			case ENCODING_AC4_L4: return "ENCODING_AC4_L4";
			case ENCODING_E_AC3_JOC: return "ENCODING_E_AC3_JOC";
			case ENCODING_DOLBY_MAT: return "ENCODING_DOLBY_MAT";
			case ENCODING_OPUS: return "ENCODING_OPUS";
			case ENCODING_PCM_24BIT_PACKED: return "ENCODING_PCM_24BIT_PACKED";
			case ENCODING_PCM_32BIT: return "ENCODING_PCM_32BIT";
			case ENCODING_MPEGH_BL_L3: return "ENCODING_MPEGH_BL_L3";
			case ENCODING_MPEGH_BL_L4: return "ENCODING_MPEGH_BL_L4";
			case ENCODING_MPEGH_LC_L3: return "ENCODING_MPEGH_LC_L3";
			case ENCODING_MPEGH_LC_L4: return "ENCODING_MPEGH_LC_L4";
			case ENCODING_DTS_UHD_P1: return "ENCODING_DTS_UHD_P1";
			case ENCODING_DRA: return "ENCODING_DRA";
			case ENCODING_DTS_HD_MA: return "ENCODING_DTS_HD_MA";
			case ENCODING_DTS_UHD_P2: return "ENCODING_DTS_UHD_P2";
			case ENCODING_DSD: return "ENCODING_DSD";
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC: return "ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC";
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC: return "ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC";
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS: return "ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS";
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM: return "ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM";
			case ENCODING_IAMF_BASE_PROFILE_AAC: return "ENCODING_IAMF_BASE_PROFILE_AAC";
			case ENCODING_IAMF_BASE_PROFILE_FLAC: return "ENCODING_IAMF_BASE_PROFILE_FLAC";
			case ENCODING_IAMF_BASE_PROFILE_OPUS: return "ENCODING_IAMF_BASE_PROFILE_OPUS";
			case ENCODING_IAMF_BASE_PROFILE_PCM: return "ENCODING_IAMF_BASE_PROFILE_PCM";
			case ENCODING_IAMF_SIMPLE_PROFILE_AAC: return "ENCODING_IAMF_SIMPLE_PROFILE_AAC";
			case ENCODING_IAMF_SIMPLE_PROFILE_FLAC: return "ENCODING_IAMF_SIMPLE_PROFILE_FLAC";
			case ENCODING_IAMF_SIMPLE_PROFILE_OPUS: return "ENCODING_IAMF_SIMPLE_PROFILE_OPUS";
			case ENCODING_IAMF_SIMPLE_PROFILE_PCM: return "ENCODING_IAMF_SIMPLE_PROFILE_PCM";
			default: return "invalid encoding " + enc;
		}
	}

	/** @deprecated Use {@link #CHANNEL_INVALID} instead. */
	public static final int CHANNEL_CONFIGURATION_INVALID = 0;
	/** @deprecated Use {@link #CHANNEL_OUT_MONO} or {@link #CHANNEL_IN_MONO} instead. */
	public static final int CHANNEL_CONFIGURATION_DEFAULT = 1;
	/** @deprecated Use {@link #CHANNEL_OUT_MONO} or {@link #CHANNEL_IN_MONO} instead. */
	public static final int CHANNEL_CONFIGURATION_MONO = 2;
	/** @deprecated Use {@link #CHANNEL_OUT_STEREO} or {@link #CHANNEL_IN_STEREO} instead. */
	public static final int CHANNEL_CONFIGURATION_STEREO = 3;
	public static final int CHANNEL_INVALID = 0;
	public static final int CHANNEL_OUT_DEFAULT = 1;
	public static final int CHANNEL_OUT_FRONT_LEFT = 0x4;
	public static final int CHANNEL_OUT_FRONT_RIGHT = 0x8;
	public static final int CHANNEL_OUT_FRONT_CENTER = 0x10;
	public static final int CHANNEL_OUT_LOW_FREQUENCY = 0x20;
	public static final int CHANNEL_OUT_BACK_LEFT = 0x40;
	public static final int CHANNEL_OUT_BACK_RIGHT = 0x80;
	public static final int CHANNEL_OUT_FRONT_LEFT_OF_CENTER = 0x100;
	public static final int CHANNEL_OUT_FRONT_RIGHT_OF_CENTER = 0x200;
	public static final int CHANNEL_OUT_BACK_CENTER = 0x400;
	public static final int CHANNEL_OUT_SIDE_LEFT = 0x800;
	public static final int CHANNEL_OUT_SIDE_RIGHT = 0x1000;
	public static final int CHANNEL_OUT_TOP_CENTER = 0x2000;
	public static final int CHANNEL_OUT_TOP_FRONT_LEFT = 0x4000;
	public static final int CHANNEL_OUT_TOP_FRONT_CENTER = 0x8000;
	public static final int CHANNEL_OUT_TOP_FRONT_RIGHT = 0x10000;
	public static final int CHANNEL_OUT_TOP_BACK_LEFT = 0x20000;
	public static final int CHANNEL_OUT_TOP_BACK_CENTER = 0x40000;
	public static final int CHANNEL_OUT_TOP_BACK_RIGHT = 0x80000;
	public static final int CHANNEL_OUT_TOP_SIDE_LEFT = 0x100000;
	public static final int CHANNEL_OUT_TOP_SIDE_RIGHT = 0x200000;
	public static final int CHANNEL_OUT_BOTTOM_FRONT_LEFT = 0x400000;
	public static final int CHANNEL_OUT_BOTTOM_FRONT_CENTER = 0x800000;
	public static final int CHANNEL_OUT_BOTTOM_FRONT_RIGHT = 0x1000000;
	public static final int CHANNEL_OUT_LOW_FREQUENCY_2 = 0x2000000;
	public static final int CHANNEL_OUT_FRONT_WIDE_LEFT = 0x4000000;
	public static final int CHANNEL_OUT_FRONT_WIDE_RIGHT = 0x8000000;
	public static final int CHANNEL_OUT_HAPTIC_B = 0x10000000;
	public static final int CHANNEL_OUT_HAPTIC_A = 0x20000000;
	public static final int CHANNEL_OUT_MONO = CHANNEL_OUT_FRONT_LEFT;
	public static final int CHANNEL_OUT_STEREO = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT);
	public static final int CHANNEL_OUT_QUAD = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT);
	public static final int CHANNEL_OUT_QUAD_SIDE = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT);
	public static final int CHANNEL_OUT_SURROUND = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_BACK_CENTER);
	public static final int CHANNEL_OUT_5POINT1 = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY | CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT);
	public static final int CHANNEL_OUT_6POINT1 = (CHANNEL_OUT_5POINT1 | CHANNEL_OUT_BACK_CENTER);
	public static final int CHANNEL_OUT_5POINT1_SIDE = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT);
	/** @deprecated Not the typical 7.1 surround configuration. Use {@link #CHANNEL_OUT_7POINT1_SURROUND} instead. */
	public static final int CHANNEL_OUT_7POINT1 = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY | CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT |
            CHANNEL_OUT_FRONT_LEFT_OF_CENTER | CHANNEL_OUT_FRONT_RIGHT_OF_CENTER);
	public static final int CHANNEL_OUT_7POINT1_SURROUND = (
            CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT |
            CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT |
            CHANNEL_OUT_LOW_FREQUENCY);
	public static final int CHANNEL_OUT_5POINT1POINT2 = (CHANNEL_OUT_5POINT1 |
            CHANNEL_OUT_TOP_SIDE_LEFT | CHANNEL_OUT_TOP_SIDE_RIGHT);
	public static final int CHANNEL_OUT_5POINT1POINT4 = (CHANNEL_OUT_5POINT1 |
            CHANNEL_OUT_TOP_FRONT_LEFT | CHANNEL_OUT_TOP_FRONT_RIGHT |
            CHANNEL_OUT_TOP_BACK_LEFT | CHANNEL_OUT_TOP_BACK_RIGHT);
	public static final int CHANNEL_OUT_7POINT1POINT2 = (CHANNEL_OUT_7POINT1_SURROUND |
            CHANNEL_OUT_TOP_SIDE_LEFT | CHANNEL_OUT_TOP_SIDE_RIGHT);
	public static final int CHANNEL_OUT_7POINT1POINT4 = (CHANNEL_OUT_7POINT1_SURROUND |
            CHANNEL_OUT_TOP_FRONT_LEFT | CHANNEL_OUT_TOP_FRONT_RIGHT |
            CHANNEL_OUT_TOP_BACK_LEFT | CHANNEL_OUT_TOP_BACK_RIGHT);
	public static final int CHANNEL_OUT_9POINT1POINT4 = (CHANNEL_OUT_7POINT1POINT4
            | CHANNEL_OUT_FRONT_WIDE_LEFT | CHANNEL_OUT_FRONT_WIDE_RIGHT);
	public static final int CHANNEL_OUT_9POINT1POINT6 = (CHANNEL_OUT_9POINT1POINT4
            | CHANNEL_OUT_TOP_SIDE_LEFT | CHANNEL_OUT_TOP_SIDE_RIGHT);
	public static final int CHANNEL_OUT_13POINT0 = (
            CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT |
            CHANNEL_OUT_TOP_FRONT_LEFT | CHANNEL_OUT_TOP_FRONT_CENTER |
            CHANNEL_OUT_TOP_FRONT_RIGHT |
            CHANNEL_OUT_TOP_BACK_LEFT | CHANNEL_OUT_TOP_BACK_RIGHT |
            CHANNEL_OUT_BOTTOM_FRONT_LEFT | CHANNEL_OUT_BOTTOM_FRONT_CENTER |
            CHANNEL_OUT_BOTTOM_FRONT_RIGHT);
	public static final int CHANNEL_OUT_22POINT2 = (CHANNEL_OUT_7POINT1POINT4 |
            CHANNEL_OUT_FRONT_LEFT_OF_CENTER | CHANNEL_OUT_FRONT_RIGHT_OF_CENTER |
            CHANNEL_OUT_BACK_CENTER | CHANNEL_OUT_TOP_CENTER |
            CHANNEL_OUT_TOP_FRONT_CENTER | CHANNEL_OUT_TOP_BACK_CENTER |
            CHANNEL_OUT_TOP_SIDE_LEFT | CHANNEL_OUT_TOP_SIDE_RIGHT |
            CHANNEL_OUT_BOTTOM_FRONT_LEFT | CHANNEL_OUT_BOTTOM_FRONT_RIGHT |
            CHANNEL_OUT_BOTTOM_FRONT_CENTER |
            CHANNEL_OUT_LOW_FREQUENCY_2);

	public static final int CHANNEL_ACN_ORDER_0 = 1;
	public static final int CHANNEL_ACN_ORDER_1 = 4;
	public static final int CHANNEL_ACN_ORDER_2 = 9;
	public static final int CHANNEL_ACN_ORDER_3 = 16;
	public static final int CHANNEL_ACN_ORDER_4 = 25;
	public static final int CHANNEL_ACN_ORDER_5 = 36;
	public static final int CHANNEL_ACN_ORDER_6 = 49;
	public static final int CHANNEL_ACN_ORDER_7 = 64;
	public static final int CHANNEL_ACN_ORDER_8 = 81;
	public static final int CHANNEL_ACN_ORDER_9 = 100;
	public static final int CHANNEL_ACN_ORDER_10 = 121;
	public static final int CHANNEL_ACN_ORDER_11 = 144;
	public static final int CHANNEL_ACN_ORDER_12 = 169;
	public static final int CHANNEL_ACN_ORDER_13 = 196;
	public static final int CHANNEL_ACN_ORDER_14 = 225;
	public static final int CHANNEL_ACN_HORIZONTAL = 1 << 9;
	public static final int CHANNEL_ACN_ORDER_0_HRZ = CHANNEL_ACN_HORIZONTAL | 1;
	public static final int CHANNEL_ACN_ORDER_1_HRZ = CHANNEL_ACN_HORIZONTAL | 3;
	public static final int CHANNEL_ACN_ORDER_2_HRZ = CHANNEL_ACN_HORIZONTAL | 5;
	public static final int CHANNEL_ACN_ORDER_3_HRZ = CHANNEL_ACN_HORIZONTAL | 7;
	public static final int CHANNEL_ACN_ORDER_4_HRZ = CHANNEL_ACN_HORIZONTAL | 9;
	public static final int CHANNEL_ACN_ORDER_5_HRZ = CHANNEL_ACN_HORIZONTAL | 11;
	public static final int CHANNEL_ACN_ORDER_6_HRZ = CHANNEL_ACN_HORIZONTAL | 13;
	public static final int CHANNEL_ACN_ORDER_7_HRZ = CHANNEL_ACN_HORIZONTAL | 15;
	public static final int CHANNEL_ACN_ORDER_8_HRZ = CHANNEL_ACN_HORIZONTAL | 17;
	public static final int CHANNEL_ACN_ORDER_9_HRZ = CHANNEL_ACN_HORIZONTAL | 19;
	public static final int CHANNEL_ACN_ORDER_10_HRZ = CHANNEL_ACN_HORIZONTAL | 21;
	public static final int CHANNEL_ACN_ORDER_11_HRZ = CHANNEL_ACN_HORIZONTAL | 23;
	public static final int CHANNEL_ACN_ORDER_12_HRZ = CHANNEL_ACN_HORIZONTAL | 25;
	public static final int CHANNEL_ACN_ORDER_13_HRZ = CHANNEL_ACN_HORIZONTAL | 27;
	public static final int CHANNEL_ACN_ORDER_14_HRZ = CHANNEL_ACN_HORIZONTAL | 29;

	/* AOSP reads these from AudioSystem. ATL has no AudioSystem class; the
	 * values are the audio HAL range the native sink in
	 * src/api-impl-jni/audio/audio.h validates against
	 * (ATL_SAMPLE_RATE_HZ_MIN/MAX). */
	public static final int SAMPLE_RATE_HZ_MIN = 4000;
	public static final int SAMPLE_RATE_HZ_MAX = 192000;
	public static final int SAMPLE_RATE_UNSPECIFIED = 0;

	public static int inChannelMaskFromOutChannelMask(int outMask) throws IllegalArgumentException {
		if (outMask == CHANNEL_OUT_DEFAULT) {
			throw new IllegalArgumentException(
				"Illegal CHANNEL_OUT_DEFAULT channel mask for input.");
		}
		switch (channelCountFromOutChannelMask(outMask)) {
			case 1:
				return CHANNEL_IN_MONO;
			case 2:
				return CHANNEL_IN_STEREO;
			default:
				throw new IllegalArgumentException("Unsupported channel configuration for input.");
		}
	}

	public static int channelCountFromInChannelMask(int mask) {
		return Integer.bitCount(mask);
	}

	public static int channelCountFromOutChannelMask(int mask) {
		return Integer.bitCount(mask);
	}

	public static int channelCountFromAcnChannelMask(int mask) {
		return mask & ((1 << 8) - 1);
	}

	public static int convertChannelOutMaskToNativeMask(int javaMask) {
		return (javaMask >> 2);
	}

	public static int convertNativeChannelMaskToOutMask(int nativeMask) {
		return (nativeMask << 2);
	}

	public static String javaChannelOutMaskToString(int javaMask) {
		// save haptics info for end of string
		int haptics = javaMask & (CHANNEL_OUT_HAPTIC_A | CHANNEL_OUT_HAPTIC_B);
		// continue without looking at haptic channels
		javaMask &= ~(CHANNEL_OUT_HAPTIC_A | CHANNEL_OUT_HAPTIC_B);
		StringBuilder result = new StringBuilder("");
		switch (javaMask) {
			case CHANNEL_OUT_MONO: result.append("mono"); break;
			case CHANNEL_OUT_STEREO: result.append("stereo"); break;
			case CHANNEL_OUT_QUAD: result.append("quad"); break;
			case CHANNEL_OUT_QUAD_SIDE: result.append("quad side"); break;
			case CHANNEL_OUT_SURROUND: result.append("4.0"); break;
			case CHANNEL_OUT_5POINT1: result.append("5.1"); break;
			case CHANNEL_OUT_6POINT1: result.append("6.1"); break;
			case CHANNEL_OUT_5POINT1_SIDE: result.append("5.1 side"); break;
			case CHANNEL_OUT_7POINT1: result.append("7.1 (5 fronts)"); break;
			case CHANNEL_OUT_7POINT1_SURROUND: result.append("7.1"); break;
			case CHANNEL_OUT_5POINT1POINT2: result.append("5.1.2"); break;
			case CHANNEL_OUT_5POINT1POINT4: result.append("5.1.4"); break;
			case CHANNEL_OUT_7POINT1POINT2: result.append("7.1.2"); break;
			case CHANNEL_OUT_7POINT1POINT4: result.append("7.1.4"); break;
			case CHANNEL_OUT_9POINT1POINT4: result.append("9.1.4"); break;
			case CHANNEL_OUT_9POINT1POINT6: result.append("9.1.6"); break;
			case CHANNEL_OUT_13POINT0: result.append("360RA 13ch"); break;
			case CHANNEL_OUT_22POINT2: result.append("22.2"); break;
			default:
				result.append("0x").append(Integer.toHexString(javaMask));
				break;
		}
		if ((haptics & (CHANNEL_OUT_HAPTIC_A | CHANNEL_OUT_HAPTIC_B)) != 0) {
			result.append("(+haptic ");
			if ((haptics & CHANNEL_OUT_HAPTIC_A) == CHANNEL_OUT_HAPTIC_A) {
				result.append("A");
			}
			if ((haptics & CHANNEL_OUT_HAPTIC_B) == CHANNEL_OUT_HAPTIC_B) {
				result.append("B");
			}
			result.append(")");
		}
		return result.toString();
	}

	public static final int CHANNEL_IN_DEFAULT = 1;
	public static final int CHANNEL_IN_LEFT = 0x4;
	public static final int CHANNEL_IN_RIGHT = 0x8;
	public static final int CHANNEL_IN_FRONT = 0x10;
	public static final int CHANNEL_IN_BACK = 0x20;
	public static final int CHANNEL_IN_LEFT_PROCESSED = 0x40;
	public static final int CHANNEL_IN_RIGHT_PROCESSED = 0x80;
	public static final int CHANNEL_IN_FRONT_PROCESSED = 0x100;
	public static final int CHANNEL_IN_BACK_PROCESSED = 0x200;
	public static final int CHANNEL_IN_PRESSURE = 0x400;
	public static final int CHANNEL_IN_X_AXIS = 0x800;
	public static final int CHANNEL_IN_Y_AXIS = 0x1000;
	public static final int CHANNEL_IN_Z_AXIS = 0x2000;
	public static final int CHANNEL_IN_VOICE_UPLINK = 0x4000;
	public static final int CHANNEL_IN_VOICE_DNLINK = 0x8000;
	public static final int CHANNEL_IN_BACK_LEFT = 0x10000;
	public static final int CHANNEL_IN_BACK_RIGHT = 0x20000;
	public static final int CHANNEL_IN_CENTER = 0x40000;
	public static final int CHANNEL_IN_LOW_FREQUENCY = 0x100000;
	public static final int CHANNEL_IN_TOP_LEFT = 0x200000;
	public static final int CHANNEL_IN_TOP_RIGHT = 0x400000;
	public static final int CHANNEL_IN_MONO = CHANNEL_IN_FRONT;
	public static final int CHANNEL_IN_STEREO = (CHANNEL_IN_LEFT | CHANNEL_IN_RIGHT);
	public static final int CHANNEL_IN_2POINT0POINT2 = (
            CHANNEL_IN_LEFT | CHANNEL_IN_RIGHT | CHANNEL_IN_TOP_LEFT | CHANNEL_IN_TOP_RIGHT);
	public static final int CHANNEL_IN_2POINT1POINT2 = (
            CHANNEL_IN_LEFT | CHANNEL_IN_RIGHT | CHANNEL_IN_TOP_LEFT | CHANNEL_IN_TOP_RIGHT
            | CHANNEL_IN_LOW_FREQUENCY);
	public static final int CHANNEL_IN_3POINT0POINT2 = (
            CHANNEL_IN_LEFT | CHANNEL_IN_CENTER | CHANNEL_IN_RIGHT | CHANNEL_IN_TOP_LEFT
            | CHANNEL_IN_TOP_RIGHT);
	public static final int CHANNEL_IN_3POINT1POINT2 = (
            CHANNEL_IN_LEFT | CHANNEL_IN_CENTER | CHANNEL_IN_RIGHT | CHANNEL_IN_TOP_LEFT
            | CHANNEL_IN_TOP_RIGHT | CHANNEL_IN_LOW_FREQUENCY);
	public static final int CHANNEL_IN_5POINT1 = (
            CHANNEL_IN_LEFT | CHANNEL_IN_CENTER | CHANNEL_IN_RIGHT | CHANNEL_IN_BACK_LEFT
            | CHANNEL_IN_BACK_RIGHT | CHANNEL_IN_LOW_FREQUENCY);
	public static final int CHANNEL_IN_FRONT_BACK = CHANNEL_IN_FRONT | CHANNEL_IN_BACK;

	public static int getBytesPerSample(int audioFormat) {
		switch (audioFormat) {
			case ENCODING_PCM_8BIT:
				return 1;
			case ENCODING_PCM_16BIT:
			case ENCODING_IEC61937:
			case ENCODING_DEFAULT:
				return 2;
			case ENCODING_PCM_24BIT_PACKED:
				return 3;
			case ENCODING_PCM_FLOAT:
			case ENCODING_PCM_32BIT:
				return 4;
			case ENCODING_INVALID:
			default:
				throw new IllegalArgumentException("Bad audio format " + audioFormat);
		}
	}

	public static boolean isValidEncoding(int audioFormat) {
		switch (audioFormat) {
			case ENCODING_PCM_16BIT:
			case ENCODING_PCM_8BIT:
			case ENCODING_PCM_FLOAT:
			case ENCODING_AC3:
			case ENCODING_E_AC3:
			case ENCODING_DTS:
			case ENCODING_DTS_HD:
			case ENCODING_MP3:
			case ENCODING_AAC_LC:
			case ENCODING_AAC_HE_V1:
			case ENCODING_AAC_HE_V2:
			case ENCODING_IEC61937:
			case ENCODING_DOLBY_TRUEHD:
			case ENCODING_AAC_ELD:
			case ENCODING_AAC_XHE:
			case ENCODING_AC4:
			case ENCODING_AC4_L4:
			case ENCODING_E_AC3_JOC:
			case ENCODING_DOLBY_MAT:
			case ENCODING_OPUS:
			case ENCODING_PCM_24BIT_PACKED:
			case ENCODING_PCM_32BIT:
			case ENCODING_MPEGH_BL_L3:
			case ENCODING_MPEGH_BL_L4:
			case ENCODING_MPEGH_LC_L3:
			case ENCODING_MPEGH_LC_L4:
			case ENCODING_DTS_UHD_P1:
			case ENCODING_DRA:
			case ENCODING_DTS_HD_MA:
			case ENCODING_DTS_UHD_P2:
			case ENCODING_DSD:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM:
			case ENCODING_IAMF_BASE_PROFILE_AAC:
			case ENCODING_IAMF_BASE_PROFILE_FLAC:
			case ENCODING_IAMF_BASE_PROFILE_OPUS:
			case ENCODING_IAMF_BASE_PROFILE_PCM:
			case ENCODING_IAMF_SIMPLE_PROFILE_AAC:
			case ENCODING_IAMF_SIMPLE_PROFILE_FLAC:
			case ENCODING_IAMF_SIMPLE_PROFILE_OPUS:
			case ENCODING_IAMF_SIMPLE_PROFILE_PCM:
				return true;
			default:
				return false;
		}
	}

	public static boolean isPublicEncoding(int audioFormat) {
		return isValidEncoding(audioFormat);
	}

	public static boolean isEncodingLinearPcm(int audioFormat) {
		switch (audioFormat) {
			case ENCODING_PCM_16BIT:
			case ENCODING_PCM_8BIT:
			case ENCODING_PCM_FLOAT:
			case ENCODING_PCM_24BIT_PACKED:
			case ENCODING_PCM_32BIT:
			case ENCODING_DEFAULT:
				return true;
			case ENCODING_AC3:
			case ENCODING_E_AC3:
			case ENCODING_DTS:
			case ENCODING_DTS_HD:
			case ENCODING_MP3:
			case ENCODING_AAC_LC:
			case ENCODING_AAC_HE_V1:
			case ENCODING_AAC_HE_V2:
			case ENCODING_IEC61937: // wrapped in PCM but compressed
			case ENCODING_DOLBY_TRUEHD:
			case ENCODING_AAC_ELD:
			case ENCODING_AAC_XHE:
			case ENCODING_AC4:
			case ENCODING_AC4_L4:
			case ENCODING_E_AC3_JOC:
			case ENCODING_DOLBY_MAT:
			case ENCODING_OPUS:
			case ENCODING_MPEGH_BL_L3:
			case ENCODING_MPEGH_BL_L4:
			case ENCODING_MPEGH_LC_L3:
			case ENCODING_MPEGH_LC_L4:
			case ENCODING_DTS_UHD_P1:
			case ENCODING_DRA:
			case ENCODING_DTS_HD_MA:
			case ENCODING_DTS_UHD_P2:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM: // PCM but inside compressed stream
			case ENCODING_IAMF_BASE_PROFILE_AAC:
			case ENCODING_IAMF_BASE_PROFILE_FLAC:
			case ENCODING_IAMF_BASE_PROFILE_OPUS:
			case ENCODING_IAMF_BASE_PROFILE_PCM: // PCM but inside compressed stream
			case ENCODING_IAMF_SIMPLE_PROFILE_AAC:
			case ENCODING_IAMF_SIMPLE_PROFILE_FLAC:
			case ENCODING_IAMF_SIMPLE_PROFILE_OPUS:
			case ENCODING_IAMF_SIMPLE_PROFILE_PCM: // PCM but inside compressed stream
				return false;
			case ENCODING_INVALID:
			default:
				throw new IllegalArgumentException("Bad audio format " + audioFormat);
		}
	}

	public static boolean isEncodingLinearFrames(int audioFormat) {
		switch (audioFormat) {
			case ENCODING_PCM_16BIT:
			case ENCODING_PCM_8BIT:
			case ENCODING_PCM_FLOAT:
			case ENCODING_IEC61937: // same size as stereo PCM
			case ENCODING_PCM_24BIT_PACKED:
			case ENCODING_PCM_32BIT:
			case ENCODING_DEFAULT:
				return true;
			case ENCODING_AC3:
			case ENCODING_E_AC3:
			case ENCODING_DTS:
			case ENCODING_DTS_HD:
			case ENCODING_MP3:
			case ENCODING_AAC_LC:
			case ENCODING_AAC_HE_V1:
			case ENCODING_AAC_HE_V2:
			case ENCODING_DOLBY_TRUEHD:
			case ENCODING_AAC_ELD:
			case ENCODING_AAC_XHE:
			case ENCODING_AC4:
			case ENCODING_AC4_L4:
			case ENCODING_E_AC3_JOC:
			case ENCODING_DOLBY_MAT:
			case ENCODING_OPUS:
			case ENCODING_MPEGH_BL_L3:
			case ENCODING_MPEGH_BL_L4:
			case ENCODING_MPEGH_LC_L3:
			case ENCODING_MPEGH_LC_L4:
			case ENCODING_DTS_UHD_P1:
			case ENCODING_DRA:
			case ENCODING_DTS_HD_MA:
			case ENCODING_DTS_UHD_P2:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS:
			case ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM:
			case ENCODING_IAMF_BASE_PROFILE_AAC:
			case ENCODING_IAMF_BASE_PROFILE_FLAC:
			case ENCODING_IAMF_BASE_PROFILE_OPUS:
			case ENCODING_IAMF_BASE_PROFILE_PCM:
			case ENCODING_IAMF_SIMPLE_PROFILE_AAC:
			case ENCODING_IAMF_SIMPLE_PROFILE_FLAC:
			case ENCODING_IAMF_SIMPLE_PROFILE_OPUS:
			case ENCODING_IAMF_SIMPLE_PROFILE_PCM:
				return false;
			case ENCODING_INVALID:
			default:
				throw new IllegalArgumentException("Bad audio format " + audioFormat);
		}
	}

	public static int[] filterPublicFormats(int[] formats) {
		if (formats == null) {
			return null;
		}
		int[] myCopy = Arrays.copyOf(formats, formats.length);
		int size = 0;
		for (int i = 0; i < myCopy.length; i++) {
			if (isPublicEncoding(myCopy[i])) {
				if (size != i) {
					myCopy[size] = myCopy[i];
				}
				size++;
			}
		}
		return Arrays.copyOf(myCopy, size);
	}

	/** The channel position / index / Ambisonics masks of an AudioFormat (AOSP ChannelMasks). */
	public static class ChannelMasks {
		private final int mPositionMask;
		private final int mIndexMask;
		private final int mAcnMask;
		private final int mChannelCount;

		public ChannelMasks() {
			this(AudioFormat.CHANNEL_INVALID, AudioFormat.CHANNEL_INVALID, AudioFormat.CHANNEL_INVALID);
		}

		public ChannelMasks(int positionMask) {
			this(positionMask, AudioFormat.CHANNEL_INVALID, AudioFormat.CHANNEL_INVALID);
		}

		public ChannelMasks(int positionMask, int indexMask) {
			this(positionMask, indexMask, AudioFormat.CHANNEL_INVALID);
		}

		public ChannelMasks(int positionMask, int indexMask, int acnMask) {
			mChannelCount = getChannelCount(positionMask, indexMask, acnMask);
			mPositionMask = positionMask;
			mIndexMask = indexMask;
			mAcnMask = acnMask;
		}

		public int getPositionMask() {
			return mPositionMask;
		}

		public int getIndexMask() {
			return mIndexMask;
		}

		public int getAcnMask() {
			return mAcnMask;
		}

		public int getChannelCount() {
			return mChannelCount;
		}

		@Override
		public int hashCode() {
			return Objects.hash(mPositionMask, mIndexMask, mAcnMask);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ChannelMasks)) return false;
			ChannelMasks that = (ChannelMasks) o;
			// mChannelCount is derived from masks, so strictly checking masks is sufficient
			return mPositionMask == that.mPositionMask
				&& mIndexMask == that.mIndexMask
				&& mAcnMask == that.mAcnMask;
		}

		@Override
		public String toString() {
			return "ChannelMasks:"
				+ " chan=0x" + Integer.toHexString(mPositionMask).toUpperCase(Locale.ROOT)
				+ " chan_index=0x" + Integer.toHexString(mIndexMask).toUpperCase(Locale.ROOT)
				+ " chan_acn=0x" + Integer.toHexString(mAcnMask).toUpperCase(Locale.ROOT);
		}

		private static int getChannelCount(int positionMask, int indexMask, int acnMask) {
			final int indexChannelCount = Integer.bitCount(indexMask);
			final int acnChannelCount = channelCountFromAcnChannelMask(acnMask);
			int channelCount = channelCountFromOutChannelMask(positionMask);
			if (positionMask == 0 && indexMask == 0) {
				return acnChannelCount;
			}
			if (positionMask != 0) {
				if (indexMask != 0 && channelCount != indexChannelCount) {
					return 0; // Mismatch between position and index channel counts
				}
			} else {
				channelCount = indexChannelCount;
			}
			if (acnMask != 0 && indexMask != 0 && channelCount != acnChannelCount) {
				return 0; // Mismatch with ACN channel count
			}
			return channelCount;
		}
	}

	/**
	 * There is no valid usage of this constructor directly: use
	 * {@link AudioFormat.Builder} (AOSP marks this @removed and throws).
	 */
	public AudioFormat() {
		this(AUDIO_FORMAT_HAS_PROPERTY_NONE, ENCODING_INVALID, SAMPLE_RATE_UNSPECIFIED,
			CHANNEL_INVALID, CHANNEL_INVALID, CHANNEL_INVALID);
	}

	/** Constructor used by the JNI. Parameters are not checked for validity. */
	public AudioFormat(int encoding, int sampleRate, int channelMask, int channelIndexMask) {
		this(AUDIO_FORMAT_HAS_PROPERTY_ENCODING
			| AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE
			| AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK
			| AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK,
			encoding, sampleRate, channelMask, channelIndexMask, CHANNEL_INVALID);
	}

	private AudioFormat(int propertySetMask,
			int encoding, int sampleRate,
			int channelMask, int channelIndexMask, int channelAcnMask) {
		mPropertySetMask = propertySetMask;
		mEncoding = (propertySetMask & AUDIO_FORMAT_HAS_PROPERTY_ENCODING) != 0
			? encoding : ENCODING_INVALID;
		mSampleRate = (propertySetMask & AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE) != 0
			? sampleRate : SAMPLE_RATE_UNSPECIFIED;
		mChannelMasks = new ChannelMasks(
			(propertySetMask & AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK) != 0
				? channelMask : CHANNEL_INVALID,
			(propertySetMask & AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK) != 0
				? channelIndexMask : CHANNEL_INVALID,
			(propertySetMask & AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_ACN_MASK) != 0
				? channelAcnMask : CHANNEL_INVALID);

		int frameSizeInBytes = 1;
		try {
			frameSizeInBytes = getBytesPerSample(mEncoding) * mChannelMasks.mChannelCount;
		} catch (IllegalArgumentException iae) {
			// ignored
		}
		// it is possible that channel count is 0, so ensure we return 1 for
		// mFrameSizeInBytes for consistency.
		mFrameSizeInBytes = frameSizeInBytes != 0 ? frameSizeInBytes : 1;
	}

	public final static int AUDIO_FORMAT_HAS_PROPERTY_NONE = 0x0;
	public final static int AUDIO_FORMAT_HAS_PROPERTY_ENCODING = 0x1 << 0;
	public final static int AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE = 0x1 << 1;
	public final static int AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK = 0x1 << 2;
	public final static int AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK = 0x1 << 3;
	public final static int AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_ACN_MASK = 0x1 << 4;

	// This is an immutable class, all member variables are final.
	private final int mEncoding;
	private final int mSampleRate;
	private final ChannelMasks mChannelMasks;
	private final int mPropertySetMask;
	// Derived value computed in the constructor, cached here.
	private final int mFrameSizeInBytes;

	public int getEncoding() {
		return mEncoding;
	}

	public int getSampleRate() {
		return mSampleRate;
	}

	public int getChannelMask() {
		return mChannelMasks.mPositionMask;
	}

	public int getChannelIndexMask() {
		return mChannelMasks.mIndexMask;
	}

	public int getChannelAcnMask() {
		return mChannelMasks.mAcnMask;
	}

	public ChannelMasks getChannelMasks() {
		return mChannelMasks;
	}

	public int getChannelCount() {
		return mChannelMasks.mChannelCount;
	}

	public int getFrameSizeInBytes() {
		return mFrameSizeInBytes;
	}

	public int getPropertySetMask() {
		return mPropertySetMask;
	}

	public String toLogFriendlyString() {
		return String.format("%dch %dHz %s",
			mChannelMasks.mChannelCount, mSampleRate, toLogFriendlyEncoding(mEncoding));
	}

	public static class Builder {
		private int mEncoding = ENCODING_INVALID;
		private int mSampleRate = SAMPLE_RATE_UNSPECIFIED;
		private int mChannelMask = CHANNEL_INVALID;
		private int mChannelIndexMask = 0;
		private int mChannelAcnMask = CHANNEL_INVALID;
		private int mPropertySetMask = AUDIO_FORMAT_HAS_PROPERTY_NONE;

		public Builder() {
		}

		public Builder(AudioFormat af) {
			mEncoding = af.mEncoding;
			mSampleRate = af.mSampleRate;
			mChannelMask = af.mChannelMasks.mPositionMask;
			mChannelIndexMask = af.mChannelMasks.mIndexMask;
			mChannelAcnMask = af.mChannelMasks.mAcnMask;
			mPropertySetMask = af.mPropertySetMask;
		}

		public AudioFormat build() {
			return new AudioFormat(mPropertySetMask, mEncoding, mSampleRate, mChannelMask,
				mChannelIndexMask, mChannelAcnMask);
		}

		public Builder setEncoding(int encoding) throws IllegalArgumentException {
			switch (encoding) {
				case ENCODING_DEFAULT:
					mEncoding = ENCODING_PCM_16BIT;
					break;
				case ENCODING_PCM_16BIT:
				case ENCODING_PCM_8BIT:
				case ENCODING_PCM_FLOAT:
				case ENCODING_AC3:
				case ENCODING_E_AC3:
				case ENCODING_DTS:
				case ENCODING_DTS_HD:
				case ENCODING_MP3:
				case ENCODING_AAC_LC:
				case ENCODING_AAC_HE_V1:
				case ENCODING_AAC_HE_V2:
				case ENCODING_IEC61937:
				case ENCODING_DOLBY_TRUEHD:
				case ENCODING_AAC_ELD:
				case ENCODING_AAC_XHE:
				case ENCODING_AC4:
				case ENCODING_AC4_L4:
				case ENCODING_E_AC3_JOC:
				case ENCODING_DOLBY_MAT:
				case ENCODING_OPUS:
				case ENCODING_PCM_24BIT_PACKED:
				case ENCODING_PCM_32BIT:
				case ENCODING_MPEGH_BL_L3:
				case ENCODING_MPEGH_BL_L4:
				case ENCODING_MPEGH_LC_L3:
				case ENCODING_MPEGH_LC_L4:
				case ENCODING_DTS_UHD_P1:
				case ENCODING_DRA:
				case ENCODING_DTS_HD_MA:
				case ENCODING_DTS_UHD_P2:
				case ENCODING_DSD:
				case ENCODING_IAMF_BASE_ENHANCED_PROFILE_AAC:
				case ENCODING_IAMF_BASE_ENHANCED_PROFILE_FLAC:
				case ENCODING_IAMF_BASE_ENHANCED_PROFILE_OPUS:
				case ENCODING_IAMF_BASE_ENHANCED_PROFILE_PCM:
				case ENCODING_IAMF_BASE_PROFILE_AAC:
				case ENCODING_IAMF_BASE_PROFILE_FLAC:
				case ENCODING_IAMF_BASE_PROFILE_OPUS:
				case ENCODING_IAMF_BASE_PROFILE_PCM:
				case ENCODING_IAMF_SIMPLE_PROFILE_AAC:
				case ENCODING_IAMF_SIMPLE_PROFILE_FLAC:
				case ENCODING_IAMF_SIMPLE_PROFILE_OPUS:
				case ENCODING_IAMF_SIMPLE_PROFILE_PCM:
					mEncoding = encoding;
					break;
				case ENCODING_INVALID:
				default:
					throw new IllegalArgumentException("Invalid encoding " + encoding);
			}
			mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_ENCODING;
			return this;
		}

		public Builder setChannelMask(int channelMask) {
			if (channelMask == CHANNEL_INVALID) {
				throw new IllegalArgumentException("Invalid zero channel mask");
			} else if (mChannelIndexMask != 0 &&
					Integer.bitCount(channelMask) != Integer.bitCount(mChannelIndexMask)) {
				throw new IllegalArgumentException("Mismatched channel count for mask " +
					Integer.toHexString(channelMask).toUpperCase(Locale.ROOT));
			}
			mChannelMask = channelMask;
			mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK;
			return this;
		}

		public Builder setChannelIndexMask(int channelIndexMask) {
			if (channelIndexMask == 0) {
				throw new IllegalArgumentException("Invalid zero channel index mask");
			} else if ((mChannelMask != 0
					&& Integer.bitCount(channelIndexMask) != Integer.bitCount(mChannelMask))
					|| (mChannelAcnMask != 0 && Integer.bitCount(channelIndexMask)
					!= channelCountFromAcnChannelMask(mChannelAcnMask))) {
				throw new IllegalArgumentException("Mismatched channel count for index mask " +
					Integer.toHexString(channelIndexMask).toUpperCase(Locale.ROOT));
			}
			mChannelIndexMask = channelIndexMask;
			mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK;
			return this;
		}

		public Builder setChannelAcnMask(int channelAcnMask) {
			if (channelAcnMask == 0) {
				throw new IllegalArgumentException("Invalid zero Ambisonics channel mask");
			} else if (mChannelIndexMask != 0
					&& channelCountFromAcnChannelMask(channelAcnMask)
					!= Integer.bitCount(mChannelIndexMask)) {
				throw new IllegalArgumentException("Mismatched channel count for Ambisonics mask "
					+ Integer.toHexString(channelAcnMask).toUpperCase(Locale.ROOT));
			}
			mChannelAcnMask = channelAcnMask;
			mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_ACN_MASK;
			return this;
		}

		public Builder setSampleRate(int sampleRate) throws IllegalArgumentException {
			if (((sampleRate < SAMPLE_RATE_HZ_MIN) || (sampleRate > SAMPLE_RATE_HZ_MAX)) &&
					sampleRate != SAMPLE_RATE_UNSPECIFIED) {
				throw new IllegalArgumentException("Invalid sample rate " + sampleRate);
			}
			mSampleRate = sampleRate;
			mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE;
			return this;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		AudioFormat that = (AudioFormat) o;

		if (mPropertySetMask != that.mPropertySetMask) return false;

		// return false if any of the properties is set and the values differ
		return !((((mPropertySetMask & AUDIO_FORMAT_HAS_PROPERTY_ENCODING) != 0)
					&& (mEncoding != that.mEncoding))
				|| (((mPropertySetMask & AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE) != 0)
					&& (mSampleRate != that.mSampleRate))
				|| (((mPropertySetMask & AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK) != 0)
					&& (mChannelMasks.mPositionMask != that.mChannelMasks.mPositionMask))
				|| (((mPropertySetMask & AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK) != 0)
					&& (mChannelMasks.mIndexMask != that.mChannelMasks.mIndexMask)));
	}

	@Override
	public int hashCode() {
		return Objects.hash(mPropertySetMask, mSampleRate, mEncoding, mChannelMasks.hashCode());
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(mPropertySetMask);
		dest.writeInt(mEncoding);
		dest.writeInt(mSampleRate);
		dest.writeInt(mChannelMasks.mPositionMask);
		dest.writeInt(mChannelMasks.mIndexMask);
		dest.writeInt(mChannelMasks.mAcnMask);
	}

	private AudioFormat(Parcel in) {
		this(in.readInt(), // propertySetMask
			in.readInt(), // encoding
			in.readInt(), // sampleRate
			in.readInt(), // channelMask
			in.readInt(), // channelIndexMask
			in.readInt()); // channelAcnMask
	}

	public static final Parcelable.Creator<AudioFormat> CREATOR =
			new Parcelable.Creator<AudioFormat>() {
		public AudioFormat createFromParcel(Parcel p) {
			return new AudioFormat(p);
		}
		public AudioFormat[] newArray(int size) {
			return new AudioFormat[size];
		}
	};

	@Override
	public String toString() {
		return new String("AudioFormat:"
			+ " props=" + mPropertySetMask
			+ " enc=" + mEncoding
			+ mChannelMasks
			+ " rate=" + mSampleRate);
	}
}
