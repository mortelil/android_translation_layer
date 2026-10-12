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
		return new VibrationEffect();
	}

	public static VibrationEffect createWaveform(long[] pattern, int repeat) {
		return new VibrationEffect();
	}

	/**
	 * Create a predefined vibration effect.
	 *
	 * <p>The ID of the effect to perform may be one of {@link #EFFECT_CLICK},
	 * {@link #EFFECT_DOUBLE_CLICK}, {@link #EFFECT_TICK} or {@link #EFFECT_HEAVY_CLICK}.
	 *
	 * <p>Like {@link #createOneShot} and {@link #createWaveform}, ATL has no haptic backend to
	 * describe, so this returns a no-op effect handle for the device to consume rather than
	 * inventing a pattern.
	 *
	 * @param effectId The ID of the effect to perform.
	 *
	 * @return The desired effect.
	 */
	public static VibrationEffect createPredefined(int effectId) {
		return new VibrationEffect();
	}
}