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

package android.os;

public class VibrationEffect {
	// Restricted legacy-motor representation; no amplitude-control capability.
	final long duration;
	final long[] timings;
	final int repeat;
	private VibrationEffect(long duration, long[] timings, int repeat) {
		this.duration = duration; this.timings = timings; this.repeat = repeat;
	}


	/**
	 * The default vibration strength of the device.
	 */
	public static final int DEFAULT_AMPLITUDE = -1;

	/**
	 * A click effect. Use this effect as a baseline, as it's the most common type of click effect.
	 */
	public static final int EFFECT_CLICK = 0;

	/**
	 * A double click effect.
	 */
	public static final int EFFECT_DOUBLE_CLICK = 1;

	/**
	 * A tick effect. This effect is less strong compared to {@link #EFFECT_CLICK}.
	 */
	public static final int EFFECT_TICK = 2;

	/**
	 * A heavy click effect. This effect is stronger than {@link #EFFECT_CLICK}.
	 */
	public static final int EFFECT_HEAVY_CLICK = 5;

	public static VibrationEffect createOneShot(long milliseconds, int amplitude) {
		if (milliseconds <= 0 || (amplitude != DEFAULT_AMPLITUDE && (amplitude < 1 || amplitude > 255)))
			throw new IllegalArgumentException("Invalid one-shot vibration");
		return new VibrationEffect(milliseconds, null, -1);
	}

	public static VibrationEffect createWaveform(long[] pattern, int repeat) {
		if (pattern == null) throw new NullPointerException("pattern");
		boolean nonzero = false;
		for (long timing : pattern) {
			if (timing < 0) throw new IllegalArgumentException("Negative timing");
			nonzero |= timing > 0;
		}
		if (!nonzero || repeat < -1 || repeat >= pattern.length) throw new IllegalArgumentException("Invalid waveform");
		return new VibrationEffect(0, pattern.clone(), repeat);
	}

	/**
	 * Create a predefined vibration effect.
	 *
	 * <p>The ID of the effect to perform may be one of {@link #EFFECT_CLICK},
	 * {@link #EFFECT_DOUBLE_CLICK}, {@link #EFFECT_TICK} or {@link #EFFECT_HEAVY_CLICK}.
	 *
	 * <p>ATL's legacy motor backend has no predefined-effect support. These effects
	 * are accepted but skipped, rather than inventing a device-specific pattern.
	 *
	 * @param effectId The ID of the effect to perform.
	 *
	 * @return The desired effect.
	 */
	public static VibrationEffect createPredefined(int effectId) {
		if (effectId != EFFECT_CLICK && effectId != EFFECT_DOUBLE_CLICK && effectId != EFFECT_TICK && effectId != EFFECT_HEAVY_CLICK)
			throw new IllegalArgumentException("Unknown predefined effect");
		return new VibrationEffect(0, null, -1);
	}
}