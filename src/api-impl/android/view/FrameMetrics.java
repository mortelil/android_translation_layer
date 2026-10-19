/*
 * Copyright (C) 2016 The Android Open Source Project
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

/**
 * Data holder for the timing data of a single frame rendered by a window.
 *
 * Mirrors AOSP frameworks/base/core/java/android/view/FrameMetrics.java
 * (android-17.0.0_r1, commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d; the
 * same source ships in the SDK sources/android-37.0 tree used for this
 * checkout): the metric identifiers, the FrameInfo timestamp indices and the
 * DURATIONS bucket table are the AOSP ones, and getMetric() maps a metric id
 * to the difference of two timestamps exactly like AOSP does.
 */
public final class FrameMetrics {
	// Metric identifiers (AOSP FrameMetrics.java:48-190).
	public static final int UNKNOWN_DELAY_DURATION = 0;
	public static final int INPUT_HANDLING_DURATION = 1;
	public static final int ANIMATION_DURATION = 2;
	public static final int LAYOUT_MEASURE_DURATION = 3;
	public static final int DRAW_DURATION = 4;
	public static final int SYNC_DURATION = 5;
	public static final int COMMAND_ISSUE_DURATION = 6;
	public static final int SWAP_BUFFERS_DURATION = 7;
	public static final int TOTAL_DURATION = 8;
	public static final int FIRST_DRAW_FRAME = 9;
	public static final int INTENDED_VSYNC_TIMESTAMP = 10;
	public static final int VSYNC_TIMESTAMP = 11;
	public static final int GPU_DURATION = 12;
	public static final int DEADLINE = 13;
	public static final int FRAME_TIMELINE_VSYNC_ID = 14;

	/* Timestamp indices for frame milestones. Kept in sync with
	 * frameworks/base/libs/hwui/FrameInfo.h (AOSP FrameMetrics.Index). */
	private static final int FLAGS = 0;
	private static final int FRAME_TIMELINE_VSYNC_ID_INDEX = 1;
	private static final int INTENDED_VSYNC = 2;
	private static final int VSYNC = 3;
	private static final int HANDLE_INPUT_START = 5;
	private static final int ANIMATION_START = 6;
	private static final int PERFORM_TRAVERSALS_START = 7;
	private static final int DRAW_START = 8;
	private static final int FRAME_DEADLINE = 9;
	private static final int SYNC_QUEUED = 14;
	private static final int SYNC_START = 15;
	private static final int ISSUE_DRAW_COMMANDS_START = 16;
	private static final int SWAP_BUFFERS = 17;
	private static final int FRAME_COMPLETED = 18;
	private static final int GPU_COMPLETED = 21;
	private static final int SWAP_BUFFERS_COMPLETED = 22;
	private static final int COMMAND_SUBMISSION_COMPLETED = 24;
	static final int FRAME_STATS_COUNT = 25;

	/* Bucket endpoints for each metric; each metric *must* have an entry
	 * (AOSP FrameMetrics.DURATIONS). */
	private static final int[] DURATIONS = new int[] {
		// UNKNOWN_DELAY
		INTENDED_VSYNC, HANDLE_INPUT_START,
		// INPUT_HANDLING
		HANDLE_INPUT_START, ANIMATION_START,
		// ANIMATION
		ANIMATION_START, PERFORM_TRAVERSALS_START,
		// LAYOUT_MEASURE
		PERFORM_TRAVERSALS_START, DRAW_START,
		// DRAW
		DRAW_START, SYNC_QUEUED,
		// SYNC
		SYNC_START, ISSUE_DRAW_COMMANDS_START,
		// COMMAND_ISSUE
		ISSUE_DRAW_COMMANDS_START, SWAP_BUFFERS,
		// SWAP_BUFFERS
		SWAP_BUFFERS, SWAP_BUFFERS_COMPLETED,
		// TOTAL_DURATION
		INTENDED_VSYNC, FRAME_COMPLETED,
		// RESERVED for FIRST_DRAW_FRAME
		0, 0,
		// RESERVED for INTENDED_VSYNC_TIMESTAMP
		0, 0,
		// RESERVED VSYNC_TIMESTAMP
		0, 0,
		// GPU_DURATION
		COMMAND_SUBMISSION_COMPLETED, GPU_COMPLETED,
		// DEADLINE
		INTENDED_VSYNC, FRAME_DEADLINE,
	};

	/** @hide */
	public final long[] mTimingData;

	/**
	 * Constructs a FrameMetrics object as a copy.
	 */
	public FrameMetrics(FrameMetrics other) {
		mTimingData = new long[FRAME_STATS_COUNT];
		System.arraycopy(other.mTimingData, 0, mTimingData, 0, mTimingData.length);
	}

	/** @hide */
	public FrameMetrics() {
		mTimingData = new long[FRAME_STATS_COUNT];
	}

	/**
	 * Retrieves the value associated with metric identifier {@code id} for
	 * this frame, or -1 if it is not available.
	 */
	public long getMetric(int id) {
		if (id < UNKNOWN_DELAY_DURATION || id > FRAME_TIMELINE_VSYNC_ID)
			return -1;

		if (mTimingData == null)
			return -1;

		if (id == FIRST_DRAW_FRAME)
			return (mTimingData[FLAGS] & 1) != 0 ? 1 : 0;
		else if (id == INTENDED_VSYNC_TIMESTAMP)
			return mTimingData[INTENDED_VSYNC];
		else if (id == VSYNC_TIMESTAMP)
			return mTimingData[VSYNC];
		else if (id == FRAME_TIMELINE_VSYNC_ID)
			return mTimingData[FRAME_TIMELINE_VSYNC_ID_INDEX];

		int durationsIdx = 2 * id;
		return mTimingData[DURATIONS[durationsIdx + 1]] - mTimingData[DURATIONS[durationsIdx]];
	}
}
