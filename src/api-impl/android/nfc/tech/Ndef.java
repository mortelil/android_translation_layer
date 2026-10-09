// SPDX-License-Identifier: GPL-3.0-only
package android.nfc.tech;

import android.nfc.Tag;
import java.io.IOException;

/** Optional NFC type. No instances are available without an ATL NFC backend. */
public final class Ndef implements TagTechnology {
	private Ndef() {}
	public static Ndef get(Tag tag) { return null; }
	@Override public Tag getTag() { return null; }
	@Override public boolean isConnected() { return false; }
	@Override public void connect() throws IOException { throw new IOException("ATL has no NFC backend"); }
	@Override public void close() throws IOException { throw new IOException("ATL has no NFC backend"); }
}
