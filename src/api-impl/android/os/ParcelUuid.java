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

import java.util.UUID;

/**
 * This class is a Parcelable wrapper around {@link UUID} which is an
 * immutable representation of a 128-bit universally unique identifier.
 */
public final class ParcelUuid implements Parcelable {

	private final UUID mUuid;

	/**
	 * Constructor creates a new ParcelUuid from a string representation of UUID.
	 */
	public ParcelUuid(UUID uuid) {
		mUuid = uuid;
	}

	public static ParcelUuid fromString(String uuid) {
		return new ParcelUuid(UUID.fromString(uuid));
	}

	/**
	 * Gets the UUID.
	 */
	public UUID getUuid() {
		return mUuid;
	}

	@Override
	public String toString() {
		return mUuid.toString();
	}

	@Override
	public int hashCode() {
		return mUuid.hashCode();
	}

	@Override
	public boolean equals(Object object) {
		if (object == null) {
			return false;
		} else if (this == object) {
			return true;
		} else if (object instanceof ParcelUuid) {
			ParcelUuid uuid = (ParcelUuid)object;
			return (this.mUuid.equals(uuid.mUuid));
		} else {
			return false;
		}
	}

	@Override
	public int describeContents() {
		return 0;
	}

	/** {@hide} */
	public static final @android.annotation.NonNull Parcelable.Creator<ParcelUuid> CREATOR =
	    new Parcelable.Creator<ParcelUuid>() {
		    public ParcelUuid createFromParcel(Parcel source) {
			    long mostSigBits = source.readLong();
			    long leastSigBits = source.readLong();
			    UUID uuid = new UUID(mostSigBits, leastSigBits);
			    return new ParcelUuid(uuid);
		    }

		    public ParcelUuid[] newArray(int size) {
			    return new ParcelUuid[size];
		    }
	    };

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeLong(mUuid.getMostSignificantBits());
		dest.writeLong(mUuid.getLeastSignificantBits());
	}
}
