// SPDX-License-Identifier: GPL-3.0-only
package android.nfc;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * NFC tag type for apps inspecting optional NFC modules through reflection.
 * ATL has no NFC backend: NfcAdapter.getDefaultAdapter() returns null, and no
 * Tag instances are created or accepted from parcels.
 */
public final class Tag implements Parcelable {
	private Tag() {}

	@Override public int describeContents() { return 0; }
	@Override public void writeToParcel(Parcel dest, int flags) {
		throw new UnsupportedOperationException("ATL has no NFC backend");
	}

	public static final Parcelable.Creator<Tag> CREATOR = new Parcelable.Creator<Tag>() {
		@Override public Tag createFromParcel(Parcel source) {
			throw new UnsupportedOperationException("ATL has no NFC backend");
		}
		@Override public Tag[] newArray(int size) { return new Tag[size]; }
	};
}
