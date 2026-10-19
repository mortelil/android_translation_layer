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

package android.app.usage;

/**
 * Enumeration over buckets of network usage statistics.
 *
 * Port of AOSP core/java/android/app/usage/NetworkStats.java (android-17.0.0_r1, commit
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d; the pinned partial reference tree only carries
 * core/java/android/{app,content,os,util}, so this file is cited from the SDK sources of the same
 * release train, /home/morten/Android/Sdk/sources/android-37.0/android/app/usage/NetworkStats.java).
 *
 * ATL has no NetworkStatsService (there is no system server and the host kernel keeps no per-UID
 * accounting), so {@link NetworkStatsManager} can only hand out empty enumerations; the bucket
 * identities and the enumeration contract below are AOSP's, unchanged.
 */
public final class NetworkStats implements AutoCloseable {
	private final long mStartTimeStamp;
	private final long mEndTimeStamp;
	private final Bucket[] mBuckets;
	private int mEnumerationIndex;

	/** @hide */
	NetworkStats(long startTimeStamp, long endTimeStamp, Bucket[] buckets) {
		mStartTimeStamp = startTimeStamp;
		mEndTimeStamp = endTimeStamp;
		mBuckets = buckets;
	}

	/** @return Start of interval, see System.currentTimeMillis(). */
	public long getStartTimeStamp() {
		return mStartTimeStamp;
	}

	/** @return End of interval, see System.currentTimeMillis(). */
	public long getEndTimeStamp() {
		return mEndTimeStamp;
	}

	/**
	 * Buckets are the smallest elements of a query result.
	 * AOSP NetworkStats.Bucket:48-270 (the STATE_/UID_/METERED_/ROAMING_/DEFAULT_NETWORK_ and
	 * TAG_NONE constants).
	 */
	public static class Bucket {
		public static final int STATE_ALL = -1;
		public static final int STATE_DEFAULT = 0x1;
		public static final int STATE_FOREGROUND = 0x2;

		public static final int UID_ALL = -1; /* android.net.NetworkStats.UID_ALL */
		public static final int UID_REMOVED = -4; /* android.net.TrafficStats.UID_REMOVED */
		public static final int UID_TETHERING = -5; /* android.net.TrafficStats.UID_TETHERING */

		public static final int METERED_ALL = -1;
		public static final int METERED_NO = 0x0;
		public static final int METERED_YES = 0x1;

		public static final int ROAMING_ALL = -1;
		public static final int ROAMING_NO = 0x0;
		public static final int ROAMING_YES = 0x1;

		public static final int DEFAULT_NETWORK_ALL = -1;
		public static final int DEFAULT_NETWORK_NO = 0x0;
		public static final int DEFAULT_NETWORK_YES = 0x1;

		public static final int TAG_NONE = 0x0; /* android.net.NetworkStats.TAG_NONE */

		private int mUid;
		private int mTag;
		private int mState;
		private int mDefaultNetworkStatus;
		private int mMetered;
		private int mRoaming;
		private long mBeginTimeStamp;
		private long mEndTimeStamp;
		private long mRxBytes;
		private long mRxPackets;
		private long mTxBytes;
		private long mTxPackets;

		public Bucket() {
		}

		public int getUid() { return mUid; }

		public int getTag() { return mTag; }

		public int getState() { return mState; }

		public int getMetered() { return mMetered; }

		public int getRoaming() { return mRoaming; }

		public int getDefaultNetworkStatus() { return mDefaultNetworkStatus; }

		public long getStartTimeStamp() { return mBeginTimeStamp; }

		public long getEndTimeStamp() { return mEndTimeStamp; }

		public long getRxBytes() { return mRxBytes; }

		public long getTxBytes() { return mTxBytes; }

		public long getRxPackets() { return mRxPackets; }

		public long getTxPackets() { return mTxPackets; }
	}

	/**
	 * Fills the recycled bucket with data of the next bin in the enumeration.
	 * AOSP NetworkStats.java:482-490.
	 *
	 * @param bucketOut Bucket to be filled with data. If null, the method does nothing and
	 *                  returns false.
	 * @return true if successfully filled the bucket, false otherwise.
	 */
	public boolean getNextBucket(Bucket bucketOut) {
		if (bucketOut == null || mEnumerationIndex >= mBuckets.length)
			return false;
		Bucket next = mBuckets[mEnumerationIndex++];
		bucketOut.mUid = next.mUid;
		bucketOut.mTag = next.mTag;
		bucketOut.mState = next.mState;
		bucketOut.mDefaultNetworkStatus = next.mDefaultNetworkStatus;
		bucketOut.mMetered = next.mMetered;
		bucketOut.mRoaming = next.mRoaming;
		bucketOut.mBeginTimeStamp = next.mBeginTimeStamp;
		bucketOut.mEndTimeStamp = next.mEndTimeStamp;
		bucketOut.mRxBytes = next.mRxBytes;
		bucketOut.mRxPackets = next.mRxPackets;
		bucketOut.mTxBytes = next.mTxBytes;
		bucketOut.mTxPackets = next.mTxPackets;
		return true;
	}

	/** @return true if there is at least one more bucket. AOSP NetworkStats.java:494-505. */
	public boolean hasNextBucket() {
		return mEnumerationIndex < mBuckets.length;
	}

	/** Closes the enumeration. AOSP NetworkStats.java:508-522. */
	@Override
	public void close() {
		mEnumerationIndex = mBuckets.length;
	}
}
