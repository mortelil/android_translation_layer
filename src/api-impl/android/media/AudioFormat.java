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

public class AudioFormat {

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
	public static final int ENCODING_PCM_24BIT_PACKED = 21;
	public static final int ENCODING_PCM_32BIT = 22;
	public static final int CHANNEL_CONFIGURATION_DEFAULT = 1;
	public static final int CHANNEL_CONFIGURATION_MONO = 2;
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
	public static final int CHANNEL_OUT_MONO = CHANNEL_OUT_FRONT_LEFT;
	public static final int CHANNEL_OUT_STEREO = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT);
	public static final int CHANNEL_OUT_QUAD = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT);
	public static final int CHANNEL_OUT_SURROUND = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_BACK_CENTER);
	public static final int CHANNEL_OUT_5POINT1 = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY | CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT);
	public static final int CHANNEL_OUT_7POINT1 = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY | CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT |
            CHANNEL_OUT_FRONT_LEFT_OF_CENTER | CHANNEL_OUT_FRONT_RIGHT_OF_CENTER);
	public static final int CHANNEL_OUT_7POINT1_SURROUND = (
            CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT |
            CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT |
            CHANNEL_OUT_LOW_FREQUENCY);
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
	public static final int CHANNEL_IN_MONO = CHANNEL_IN_FRONT;
	public static final int CHANNEL_IN_STEREO = (CHANNEL_IN_LEFT | CHANNEL_IN_RIGHT);


	int sampleRate = 44100;
	int channelMask;
	int encoding;

	public static class Builder {

		private AudioFormat audioFormat = new AudioFormat();

		public Builder setSampleRate(int sampleRate) {
			audioFormat.sampleRate = sampleRate;
			return this;
		}

		public Builder setChannelMask(int channelMask) {
			audioFormat.channelMask = channelMask;
			return this;
		}

		public Builder setEncoding(int encoding) {
			audioFormat.encoding = encoding;
			return this;
		}

		public AudioFormat build() {
			return audioFormat;
		}
	}
}
